package com.manliao.backend.profiles;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import com.manliao.backend.common.*;
@Service
public class ProfileService {
 private final JdbcTemplate db;
 private final TransactionTemplate tx;
 private final ObjectMapper json;
 private final DatabaseRows rows;
 private final ProfileTextReview review;
 private final com.manliao.backend.media.MediaService media;
 public ProfileService(JdbcTemplate db,TransactionTemplate tx,ObjectMapper json,DatabaseRows rows,ProfileTextReview review,com.manliao.backend.media.MediaService media) {
   this.db=db;this.tx=tx;this.json=json;this.rows=rows;this.review=review;this.media=media;
 }
 public Map<String,Object> get(String viewer,String owner) {
   boolean own=viewer.equals(owner);
   if(!own && db.queryForObject("""
     SELECT count(*) FROM users u WHERE u.id=? AND u.status='active' AND NOT EXISTS(
       SELECT 1 FROM blocks WHERE (actor_user_id=? AND target_user_id=u.id)
       OR (target_user_id=? AND actor_user_id=u.id))
     """,Integer.class,owner,viewer,viewer)==0)
     throw new ApiError(404,"USER_NOT_FOUND","用户不存在或不可访问");
   var found=db.queryForList("SELECT * FROM user_profiles WHERE user_id=?",owner);
   if(found.isEmpty()) throw new ApiError(404,"PROFILE_NOT_FOUND","用户资料不存在");
   var original=found.getFirst();
   var output=rows.output(original,"tags","hobbies");
   output.remove("reviewed_at");
   output.put("avatar_url",visibleUrl(owner,(String)original.get("avatar_url"),false));
   output.put("photo_urls",visiblePhotos(owner,original.get("photo_urls"),false));
   output.put("pending_avatar_url",own?visibleUrl(owner,(String)original.get("pending_avatar_url"),true):null);
   output.put("pending_photo_urls",own?visiblePhotos(owner,original.get("pending_photo_urls"),true):List.of());
   if(!own) {
     output.put("moderation_reason",null);
     if(!Boolean.TRUE.equals(original.get("distance_visible"))) output.put("distance_km",null);
   }
   return output;
 }
 private List<String> visiblePhotos(String owner,Object stored,boolean pending) {
   return Arrays.stream(json.readValue(stored.toString(),String[].class))
      .map(url->visibleUrl(owner,url,pending)).filter(Objects::nonNull).toList();
 }
 private String visibleUrl(String owner,String url,boolean pending) {
   if(url==null || url.isBlank()) return null;
   var assets=db.queryForList("SELECT status FROM media_assets WHERE owner_user_id=? AND url=? AND source='profile' AND media_type='image'",owner,url);
   if(assets.isEmpty()) return null;
   String status=(String)assets.getFirst().get("status");
   return status.equals("approved") || (pending && status.equals("review_pending")) ? media.profileUrl(url):null;
 }
 public Map<String,Object> update(String user,ProfileUpdate original) {
   ProfileUpdate input=new ProfileUpdate(original.nickname(),media.canonicalOwned(user,original.avatarUrl()),original.age(),original.zodiac(),original.bio(),original.occupation(),original.tags(),original.hobbies(),original.photoUrls()==null?null:original.photoUrls().stream().map(url->media.canonicalOwned(user,url)).toList(),original.distanceKm(),original.distanceVisible());
   if(input.nickname()!=null && input.nickname().isBlank())
     throw new ApiError(422,"COMMON_VALIDATION_ERROR","昵称不能为空");
   if(input.distanceKm()!=null && !Double.isFinite(input.distanceKm()))
     throw new ApiError(422,"COMMON_VALIDATION_ERROR","距离必须是有限数值");
   var text=new ArrayList<String>();
   for(String value:new String[]{input.nickname(),input.bio(),input.occupation(),input.zodiac()})
     if(value!=null) text.add(value);
   if(input.tags()!=null) text.addAll(input.tags());
   if(input.hobbies()!=null) text.addAll(input.hobbies());
   var labels=review.labels(String.join(" ",text));
   ApiError failure=tx.execute(status->{
     var owners=db.queryForList("SELECT status FROM users WHERE id=? FOR UPDATE",user);
     if(owners.isEmpty()) throw new ApiError(404,"USER_NOT_FOUND","用户不存在");
     String account=(String)owners.getFirst().get("status");
     if(!account.equals("active") && !account.equals("deactivation_pending"))
       throw new ApiError(403,"COMMON_FORBIDDEN","账号状态不可用");
     if(db.queryForList("SELECT user_id FROM user_profiles WHERE user_id=? FOR UPDATE",user).isEmpty())
       throw new ApiError(404,"PROFILE_NOT_FOUND","用户资料不存在");
     if(!text.isEmpty()) {
       String reason=labels.isEmpty()?null:"资料包含联系方式或不允许的内容";
       db.update("INSERT INTO profile_reviews(user_id,action,reason,labels,provider) VALUES(?,?,?,?::jsonb,'local_rules')",
         user,labels.isEmpty()?"approved":"rejected",reason,json.writeValueAsString(labels));
       db.update("UPDATE user_profiles SET moderation_status=?,moderation_reason=?,reviewed_at=now() WHERE user_id=?",
         labels.isEmpty()?"approved":"rejected",reason,user);
       if(!labels.isEmpty()) return new ApiError(400,"MODERATION_TEXT_REJECTED",reason,Map.of("labels",labels));
     }
     var changes=new LinkedHashMap<String,Object>();
     put(changes,"nickname",input.nickname());put(changes,"age",input.age());put(changes,"zodiac",input.zodiac());
     put(changes,"bio",input.bio());put(changes,"occupation",input.occupation());
     put(changes,"distance_km",input.distanceKm());put(changes,"distance_visible",input.distanceVisible());
     if(input.tags()!=null) changes.put("tags",json.writeValueAsString(new LinkedHashSet<>(input.tags())));
     if(input.hobbies()!=null) changes.put("hobbies",json.writeValueAsString(new LinkedHashSet<>(input.hobbies())));
     if(input.avatarUrl()!=null) {
       if(input.avatarUrl().isEmpty()) { changes.put("avatar_url",null);changes.put("pending_avatar_url",null); }
       else if(assetStatus(user,input.avatarUrl()).equals("approved")) {
         changes.put("avatar_url",input.avatarUrl());changes.put("pending_avatar_url",null);
       } else changes.put("pending_avatar_url",input.avatarUrl());
     }
     if(input.photoUrls()!=null) {
       var urls=new LinkedHashSet<>(input.photoUrls());
       if(urls.size()>9) throw new ApiError(400,"COMMON_VALIDATION_ERROR","探索照片最多只能上传 9 张",Map.of("max_photo_count",9));
       var approved=new ArrayList<String>();var pending=new ArrayList<String>();
       for(String url:urls) (assetStatus(user,url).equals("approved")?approved:pending).add(url);
       changes.put("photo_urls",json.writeValueAsString(approved));
       changes.put("pending_photo_urls",json.writeValueAsString(pending));
     }
     if(!changes.isEmpty()) {
       var assignments=new ArrayList<String>();
       // Keys originate exclusively from the explicit DTO mapping above.
       for(String key:changes.keySet()) assignments.add(key+"=?"+(Set.of("tags","hobbies","photo_urls","pending_photo_urls").contains(key)?"::jsonb":""));
       var values=new ArrayList<Object>(changes.values());values.add(user);
       db.update("UPDATE user_profiles SET "+String.join(",",assignments)+" WHERE user_id=?",values.toArray());
       if(input.nickname()!=null) db.update("UPDATE users SET nickname=?,updated_at=now() WHERE id=?",input.nickname(),user);
     }
     return null;
   });
   if(failure!=null) throw failure;
   return get(user,user);
 }
 private void put(Map<String,Object> changes,String key,Object value) { if(value!=null) changes.put(key,value); }
 private String assetStatus(String owner,String url) {
   var assets=db.queryForList("SELECT status,media_type FROM media_assets WHERE owner_user_id=? AND url=? AND source='profile' FOR SHARE",owner,url);
   if(assets.isEmpty()) throw new ApiError(404,"MEDIA_NOT_FOUND","媒体不存在或不属于当前账号");
   var asset=assets.getFirst();
   if(!asset.get("media_type").equals("image") || !Set.of("approved","review_pending").contains(asset.get("status")))
     throw new ApiError(409,"MEDIA_NOT_APPROVED","媒体未通过审核，不能用于资料展示");
   return (String)asset.get("status");
 }
}
