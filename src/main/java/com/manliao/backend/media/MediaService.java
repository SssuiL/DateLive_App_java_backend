package com.manliao.backend.media;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import com.manliao.backend.common.*;
import com.manliao.backend.identity.AuthDtos.Principal;
@Service
public class MediaService {
 private final JdbcTemplate db;private final TransactionTemplate tx;
 @org.springframework.beans.factory.annotation.Value("${app.media.derivatives-worker-enabled:true}") private boolean derivativesWorkerEnabled;
 private final MediaStorage storage;private final MediaTokens tokens;private final DatabaseRows rows;
 private final com.manliao.backend.chat.ChatService chat;private final VoiceProcessor voice;private final VideoProcessor video;
 public MediaService(JdbcTemplate db,TransactionTemplate tx,MediaStorage storage,MediaTokens tokens,DatabaseRows rows,com.manliao.backend.chat.ChatService chat,VoiceProcessor voice,VideoProcessor video) {
   this.db=db;this.tx=tx;this.storage=storage;this.tokens=tokens;this.rows=rows;this.chat=chat;this.voice=voice;this.video=video;
 }
 void validateUpload(Principal user,String type,String source,String conversation,MultipartFile file) {
   if(!Set.of("image","voice","video","file").contains(type) || (!type.equals("image")&&!source.equals("chat")) || !Set.of("profile","chat").contains(source) ||
     (source.equals("profile")&&conversation!=null)||(source.equals("chat")&&(conversation==null||conversation.isBlank())))
     throw new ApiError(400,"MEDIA_TYPE_INVALID","支持资料图片或绑定会话的聊天图片/语音/视频/文件");
   if(source.equals("profile")&&"image/gif".equals(file.getContentType()))throw new ApiError(400,"MEDIA_TYPE_INVALID","GIF 目前仅支持聊天附件");
   if(source.equals("chat"))chat.get(user.userId(),conversation);
 }
 void lockUpload(Principal user,String source,String conversation){if(source.equals("chat"))chat.lockMediaUpload(user,conversation);else lockUser(user.userId());}
 MediaStorage.Stored store(String id,String type,MultipartFile file)throws IOException{
   return type.equals("voice")?storage.saveVoice(id,file,voice):type.equals("video")?storage.saveVideo(id,file,video):type.equals("file")?storage.saveFile(id,file):storage.save(id,file);
 }
 public Map<String,Object> upload(Principal user,String type,String source,String conversation,MultipartFile file) throws IOException {
   validateUpload(user,type,source,conversation,file);
   String id="media_"+UUID.randomUUID().toString().replace("-","");
   var stored=store(id,type,file);
   try {
     tx.executeWithoutResult(status->{
       if(source.equals("chat"))chat.lockMediaUpload(user,conversation);else lockUser(user.userId());
       db.update("""
         INSERT INTO media_assets(id,owner_user_id,url,media_type,status,storage_key,original_filename,
           content_type,file_size,sha256,width,height,moderation_reason,source,conversation_id,duration_ms,image_derivatives_ready,processed_at)
         VALUES(?,?,?,?,'review_pending',?,?,?,?,?,?,?,?,?,?,?,?,now())
         """,id,user.userId(),"media:"+id,type,stored.key(),safeName(file.getOriginalFilename()),stored.contentType(),
         stored.size(),stored.sha256(),stored.width(),stored.height(),type.equals("file")?"原始文件等待人工审核，未执行病毒扫描":"媒体已重新编码，内容等待审核",source,conversation,stored.durationMs(),type.equals("image"));
     });
   } catch(RuntimeException failure) {
     try{storage.delete(stored.key());}catch(IOException cleanup){failure.addSuppressed(cleanup);}
     throw failure;
   }
   return output(user,asset(id));
 }
 String safeName(String name) {
   String clean=Optional.ofNullable(name).orElse("upload").replace('\\','/');
   clean=clean.substring(clean.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]","");
   return clean.substring(0,Math.min(255,clean.length()));
 }
 public List<Map<String,Object>> list(Principal user) {
   return db.queryForList("SELECT * FROM media_assets WHERE owner_user_id=? AND status<>'deleted' ORDER BY created_at DESC,id DESC",user.userId())
     .stream().map(row->output(user,row)).toList();
 }
 public Map<String,Object> owned(Principal user,String id){
   var asset=asset(id);if(!user.userId().equals(asset.get("owner_user_id"))||"deleted".equals(asset.get("status")))throw missing();
   return output(user,asset);
 }
 public Map<String,Object> metadata(String id){return output(null,asset(id));}
 private Map<String,Object> output(Principal user,Map<String,Object> asset) {
   var out=rows.output(asset);out.remove("storage_key");out.remove("width");out.remove("height");out.remove("image_derivatives_ready");out.remove("image_derivatives_retry_at");
   Object preview=null;
   if(user!=null && Set.of("approved","review_pending").contains(asset.get("status")))try{preview=access(user,(String)asset.get("id"),null).get("url");}catch(ApiError inaccessible){}
   out.put("preview_url",preview);
   out.put("visibility","restricted");
   if(!"ready".equals(asset.get("processing_status"))){
     out.put("preview_url",null);out.put("media_metadata",Map.of());out.put("derivatives",Map.of());out.put("pipeline_version",null);
     out.put("detected_content_type",null);out.put("malware_status","skipped");return out;
   }
   out.put("processing_error",null);
   out.put("detected_content_type",asset.get("content_type"));
   boolean audio="voice".equals(asset.get("media_type"));
   out.put("media_metadata",audio?Map.of("duration_ms",asset.get("duration_ms"),"duration_seconds",Math.min(60,(((Number)asset.get("duration_ms")).intValue()+999)/1000),"sample_rate",16000,"channels",1,"codec","pcm_s16le"):Map.of("width",asset.get("width"),"height",asset.get("height")));
   boolean movie="video".equals(asset.get("media_type"));
   if(movie)out.put("media_metadata",Map.of("width",asset.get("width"),"height",asset.get("height"),"duration_ms",asset.get("duration_ms"),"duration_seconds",Math.min(60,(((Number)asset.get("duration_ms")).intValue()+999)/1000),"codec","h264"));
   out.put("derivatives",movie?Map.of("thumbnail",Map.of("variant","thumbnail","content_type","image/png"),"cover",Map.of("variant","cover","content_type","image/png")):Map.of());
   boolean generic="file".equals(asset.get("media_type")),gif="image/gif".equals(asset.get("content_type"));
   boolean derivativesReady=Boolean.TRUE.equals(asset.get("image_derivatives_ready"));
   if(derivativesReady&&"image".equals(asset.get("media_type")))out.put("derivatives",Map.of(
     "thumbnail",Map.of("variant","thumbnail","content_type","image/png","max_edge",320),
     "display",Map.of("variant","display","content_type",gif?"image/gif":"image/png","max_edge",gif?Math.max(((Number)asset.get("width")).intValue(),((Number)asset.get("height")).intValue()):1280)));
   if(generic)out.put("media_metadata",Map.of("download_only",true));
   if(gif)out.put("media_metadata",Map.of("width",asset.get("width"),"height",asset.get("height"),"duration_ms",asset.get("duration_ms"),"format","gif"));
   out.put("pipeline_version",generic?"java-file-v1":gif?(derivativesReady?"java-gif-v2":"java-gif-v1"):movie?"java-video-v1":audio?"java-voice-v1":derivativesReady?"java-image-v2":"java-image-v1");
   out.put("malware_status","skipped");out.put("latest_security_scan_id",null);out.put("quarantined_at",null);out.put("released_at",null);
   out.put("post_id",null);
   out.put("moderation_provider",asset.get("reviewed_by")==null?"manual_pending":"manual");out.put("moderation_labels",Boolean.TRUE.equals(asset.get("portrait_manual_approved"))?List.of("portrait_manual_approved"):List.of());
   return out;
 }
 public Map<String,Object> access(Principal user,String id,String variant) {
   MediaStorage.variantKey(authorize(user,id),variant);
   String selected=variant==null?"original":variant;
   Instant expires=Instant.now().plusSeconds(300);
   return Map.of("media_id",id,"url","/media/access/"+tokens.sign(user,id,expires,selected),"variant",selected,"expires_at",expires);
 }
 public MediaStorage.Opened read(String token) throws IOException {return read(token,null);}
 public MediaStorage.Opened read(String token,String variant) throws IOException {
   var grant=tokens.decode(token);
   var asset=authorize(grant.principal(),grant.mediaId());
   return storage.open(MediaStorage.variantKey(asset,MediaStorage.signedVariant(grant.variant(),variant)),(String)asset.get("original_filename"));
 }
 private Map<String,Object> authorize(Principal user,String id) {
   if(db.queryForObject("""
     SELECT count(*) FROM refresh_tokens r JOIN users u ON u.id=r.user_id
     WHERE r.id=? AND r.user_id=? AND r.revoked_at IS NULL AND r.expires_at>now()
     AND u.status IN ('active','deactivation_pending')
     """,Integer.class,user.sessionId(),user.userId())==0) throw missing();
   var asset=asset(id);
   if(asset.get("storage_key")==null || !Set.of("approved","review_pending").contains(asset.get("status"))) throw missing();
   String owner=(String)asset.get("owner_user_id");
   if("chat".equals(asset.get("source"))){
     try{chat.get(user.userId(),(String)asset.get("conversation_id"));}catch(ApiError unavailable){throw missing();}
     if(asset.get("message_id")==null){if(owner.equals(user.userId()))return asset;throw missing();}
     if(!"approved".equals(asset.get("status"))||db.queryForObject("""
       SELECT count(*) FROM messages m JOIN message_receipts r ON r.message_id=m.id
       WHERE m.id=? AND m.media_asset_id=? AND m.conversation_id=? AND m.recalled_at IS NULL
        AND r.user_id=? AND r.hidden_at IS NULL
       """,Integer.class,asset.get("message_id"),id,asset.get("conversation_id"),user.userId())==0)throw missing();
     return asset;
   }
   if(owner.equals(user.userId())) return asset;
   if(!asset.get("status").equals("approved")) throw missing();
   if(db.queryForObject("""
     SELECT count(*) FROM users u JOIN user_profiles p ON p.user_id=u.id
     WHERE u.id=? AND u.status='active'
       AND (p.avatar_url=? OR p.photo_urls @> jsonb_build_array(?::text))
       AND NOT EXISTS(SELECT 1 FROM blocks WHERE (actor_user_id=? AND target_user_id=u.id)
         OR(target_user_id=? AND actor_user_id=u.id))
     """,Integer.class,owner,asset.get("url"),asset.get("url"),user.userId(),user.userId())==0) throw missing();
   return asset;
 }
 public String canonicalOwned(String user,String url) {
   if(url==null || url.isEmpty()) return url;
   if(url.startsWith("/media/access/")) {
     var grant=tokens.decode(url.substring("/media/access/".length()));
     var asset=asset(grant.mediaId());
     if(!user.equals(asset.get("owner_user_id"))) throw missing();
     return (String)asset.get("url");
   }
   return url;
 }
 public String profileUrl(String url) {
   if(url==null || !url.startsWith("media:")) return url;
   var authentication=SecurityContextHolder.getContext().getAuthentication();
   if(authentication==null || !(authentication.getPrincipal() instanceof Principal user)) return null;
   try{return (String)access(user,url.substring(6),null).get("url");}catch(ApiError unavailable){return null;}
 }
 public void delete(Principal user,String id) {
   tx.executeWithoutResult(status->{
     lockUser(user.userId());
     var asset=asset(id);
     if(!user.userId().equals(asset.get("owner_user_id"))) throw missing();
     db.queryForList("SELECT user_id FROM user_profiles WHERE user_id=? FOR UPDATE",user.userId());
     db.queryForList("SELECT id FROM media_assets WHERE id=? FOR UPDATE",id);
     db.update("UPDATE media_assets SET status='deleted' WHERE id=?",id);
     db.update("DELETE FROM media_processing_jobs WHERE media_id=?",id);
     detach(user.userId(),(String)asset.get("url"));
   });
   cleanupDeleted();
 }
 private void detach(String user,String url) {
   db.update("""
     UPDATE user_profiles SET avatar_url=CASE WHEN avatar_url=? THEN NULL ELSE avatar_url END,
       pending_avatar_url=CASE WHEN pending_avatar_url=? THEN NULL ELSE pending_avatar_url END,
       photo_urls=photo_urls-?,pending_photo_urls=pending_photo_urls-? WHERE user_id=?
     """,url,url,url,url,user);
 }
 /** Internal application operation: deliberately not exposed to ordinary HTTP users. */
 public void resolveReview(String id,boolean approved,String reviewer,String reason) {
   if(reviewer==null || reviewer.isBlank() || reviewer.length()>64 || reason==null || reason.length()>240)
     throw new IllegalArgumentException("Review identity and reason required");
   tx.executeWithoutResult(status->{
     var first=asset(id);String owner=(String)first.get("owner_user_id"),url=(String)first.get("url");
     lockUser(owner);
     db.queryForList("SELECT user_id FROM user_profiles WHERE user_id=? FOR UPDATE",owner);
     var found=db.queryForList("SELECT * FROM media_assets WHERE id=? FOR UPDATE",id);
     if(found.isEmpty() || found.getFirst().get("status").equals("deleted")) throw missing();
     if(!"ready".equals(found.getFirst().get("processing_status")))throw new ApiError(409,"MEDIA_NOT_READY","媒体尚未处理完成");
     db.update("UPDATE media_assets SET status=?,reviewed_by=?,reviewed_at=now(),moderation_reason=? WHERE id=?",
       approved?"approved":"rejected",reviewer,reason,id);
     db.update("INSERT INTO media_reviews(media_id,reviewer_id,action,reason) VALUES(?,?,?,?)",id,reviewer,approved?"approved":"rejected",reason);
     if(approved && "profile".equals(first.get("source"))) {
       db.update("""
         UPDATE user_profiles SET
           avatar_url=CASE WHEN pending_avatar_url=? THEN ? ELSE avatar_url END,
           pending_avatar_url=CASE WHEN pending_avatar_url=? THEN NULL ELSE pending_avatar_url END,
           photo_urls=CASE WHEN pending_photo_urls @> jsonb_build_array(?::text)
             AND NOT photo_urls @> jsonb_build_array(?::text) THEN photo_urls||jsonb_build_array(?::text) ELSE photo_urls END,
           pending_photo_urls=pending_photo_urls-? WHERE user_id=?
         """,url,url,url,url,url,url,url,owner);
     } else detach(owner,url);
   });
 }
 private void lockUser(String user) {
   var list=db.queryForList("SELECT status FROM users WHERE id=? FOR UPDATE",user);
   if(list.isEmpty() || !Set.of("active","deactivation_pending").contains(list.getFirst().get("status"))) throw missing();
 }
 private Map<String,Object> asset(String id) {
   var list=db.queryForList("SELECT * FROM media_assets WHERE id=?",id);
   if(list.isEmpty()) throw missing();return list.getFirst();
 }
 @Scheduled(initialDelay=10000,fixedDelay=60000,scheduler="mediaProcessingScheduler")
 public void scheduledImageDerivatives(){if(derivativesWorkerEnabled)backfillImageDerivatives();}
 public int backfillImageDerivatives(){
  int completed=0;
  for(var candidate:db.queryForList("""
    SELECT id,owner_user_id FROM media_assets WHERE media_type='image' AND NOT image_derivatives_ready
      AND storage_key IS NOT NULL AND status<>'deleted' AND processing_status='ready' AND image_derivatives_retry_at<=now()
    ORDER BY image_derivatives_retry_at,id LIMIT 10
    """)){
   String id=(String)candidate.get("id");
   try{
    boolean done=Boolean.TRUE.equals(tx.execute(status->{
     lockUser((String)candidate.get("owner_user_id"));
     var found=db.queryForList("SELECT * FROM media_assets WHERE id=? FOR UPDATE",id);
     if(found.isEmpty())return false;var asset=found.getFirst();
     if(Boolean.TRUE.equals(asset.get("image_derivatives_ready"))||asset.get("storage_key")==null||"deleted".equals(asset.get("status")))return false;
     try{storage.rebuildImageDerivatives((String)asset.get("storage_key"));}catch(IOException failure){throw new java.io.UncheckedIOException(failure);}
     db.update("UPDATE media_assets SET image_derivatives_ready=true WHERE id=?",id);return true;
    }));
    if(done)completed++;
   }catch(RuntimeException failure){
    db.update("UPDATE media_assets SET image_derivatives_retry_at=now()+interval '5 minutes' WHERE id=? AND NOT image_derivatives_ready",id);
   }
  }return completed;
 }
 @Scheduled(initialDelay=60000,fixedDelay=60000)
 public void cleanupDeleted() {
   for(var asset:db.queryForList("SELECT id,storage_key FROM media_assets WHERE (status='deleted' OR processing_status='failed') AND storage_key IS NOT NULL LIMIT 100")) {
     try {
       storage.delete((String)asset.get("storage_key"));
       db.update("UPDATE media_assets SET storage_key=NULL WHERE id=? AND (status='deleted' OR processing_status='failed')",asset.get("id"));
     }catch(IOException failure){ /* Tombstone retained; next scheduled run retries. */ }
   }
 }
 private ApiError missing(){return new ApiError(404,"MEDIA_NOT_FOUND","媒体不存在或无权访问");}
}
