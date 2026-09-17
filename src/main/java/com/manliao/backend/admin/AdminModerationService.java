package com.manliao.backend.admin;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import com.manliao.backend.media.*;
import com.manliao.backend.common.*;
@Service
public class AdminModerationService {
 private final AdminService admins;private final AdminTokens tokens;private final MediaService media;
 private final MediaStorage storage;private final JdbcTemplate db;private final TransactionTemplate tx;private final DatabaseRows rows;
 public AdminModerationService(AdminService admins,AdminTokens tokens,MediaService media,MediaStorage storage,JdbcTemplate db,TransactionTemplate tx,DatabaseRows rows){
   this.admins=admins;this.tokens=tokens;this.media=media;this.storage=storage;this.db=db;this.tx=tx;this.rows=rows;
 }
 public List<Map<String,Object>> list(AdminDtos.Principal actor,String status,String type,String owner,String source,
   String conversation,String post,String message){
   admins.require(actor,"moderation.read",false);
   if((source!=null && !Set.of("profile","chat").contains(source)) || post!=null)return List.of();
   return db.queryForList("""
     SELECT id FROM media_assets WHERE status<>'deleted' AND processing_status='ready' AND storage_key IS NOT NULL
     AND (?::text IS NULL OR status=?) AND (?::text IS NULL OR media_type=?) AND (?::text IS NULL OR owner_user_id=?)
     AND (?::text IS NULL OR source=?) AND (?::text IS NULL OR conversation_id=?) AND (?::text IS NULL OR message_id=?)
     ORDER BY created_at DESC,id DESC LIMIT 100
     """,status,status,type,type,owner,owner,source,source,conversation,conversation,message,message).stream().map(row->media.metadata((String)row.get("id"))).toList();
 }
 public Map<String,Object> get(AdminDtos.Principal actor,String id){
   admins.require(actor,"moderation.read",false);asset(id);return media.metadata(id);
 }
 private Map<String,Object> asset(String id){
   var list=db.queryForList("SELECT * FROM media_assets WHERE id=? AND status<>'deleted' AND processing_status='ready' AND storage_key IS NOT NULL",id);
   if(list.isEmpty())throw new ApiError(404,"MEDIA_NOT_FOUND","媒体不存在");
   return list.getFirst();
 }
 public Map<String,Object> preview(AdminDtos.Principal actor,String id,String variant,String request){

   return tx.execute(status->{
     admins.require(actor,"moderation.read",true);MediaStorage.variantKey(asset(id),variant);
     Instant expires=Instant.now().plusSeconds(300);
     String url="/admin/moderation/access/"+tokens.sign(actor,"admin_preview",id,expires,variant==null?"original":variant);
     admins.audit(actor.id(),"media.preview","media",id,Map.of("expires_at",expires.toString()),request);
     return Map.of("media_id",id,"url",url,"variant",variant==null?"original":variant,"expires_at",expires);
   });
 }
 public MediaStorage.Opened read(String token)throws IOException {return read(token,null);}
 public MediaStorage.Opened read(String token,String variant)throws IOException {
   var grant=tokens.decode(token,"admin_preview");
   admins.require(grant.principal(),"moderation.read",false);
   var asset=asset(grant.mediaId());
   return storage.open(MediaStorage.variantKey(asset,MediaStorage.signedVariant(grant.variant(),variant)),(String)asset.get("original_filename"));
 }
 public Map<String,Object> review(AdminDtos.Principal actor,String id,boolean approved,AdminDtos.Review input,String request){
   return tx.execute(status->{
     admins.require(actor,"moderation.write",true);asset(id);
     // Never trust reviewer_id supplied by the client.
     String reason=input.reason()==null?(approved?"人工审核通过":"人工审核拒绝"):input.reason();
     media.resolveReview(id,approved,actor.id(),reason);
     boolean portrait=approved&&Boolean.TRUE.equals(input.portrait_manual_approved());
     db.update("UPDATE media_assets SET portrait_manual_approved=(? AND source='profile' AND media_type='image') WHERE id=?",portrait,id);
     admins.audit(actor.id(),approved?"media.approve":"media.reject","media",id,Map.of("reason",reason,"portrait_manual_approved",portrait),request);
     return media.metadata(id);
   });
 }
 public List<Map<String,Object>> reviews(AdminDtos.Principal actor,String type,String id){
   admins.require(actor,"moderation.read",false);
   return db.queryForList("""
     SELECT * FROM (
       SELECT 'media_'||id::text AS id,'media' AS target_type,media_id AS target_id,action,
         reviewer_id,reason,'[]'::jsonb AS labels,'manual' AS provider,created_at FROM media_reviews
       UNION ALL
       SELECT 'profile_'||id::text,'profile',user_id,action,NULL::varchar,reason,labels,provider,created_at FROM profile_reviews
     ) r WHERE (?::text IS NULL OR target_type=?) AND (?::text IS NULL OR target_id=?)
     ORDER BY created_at DESC,id DESC LIMIT 100
     """,type,type,id,id).stream().map(row->rows.output(row,"labels")).toList();
 }
}
