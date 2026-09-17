package com.manliao.backend.posts;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.manliao.backend.identity.AuthDtos.Principal;
import com.manliao.backend.common.ApiError;
/** Shared authorization for post HTTP and already-issued media URLs. No media/service cycle. */
@Service
public class PostAccess {
 private final JdbcTemplate db;
 public PostAccess(JdbcTemplate db){this.db=db;}
 public Map<String,Object> visible(String viewer,String id){
  var found=db.queryForList("SELECT p.*,u.status AS author_status FROM posts p JOIN users u ON u.id=p.author_id WHERE p.id=? AND p.deleted_at IS NULL",id);
  if(found.isEmpty())throw missing();var post=found.getFirst();String author=(String)post.get("author_id");
  if(!"active".equals(post.get("author_status"))||blocked(viewer,author))throw missing();
  if(!viewer.equals(author)&&(!"approved".equals(post.get("moderation_status"))||"private".equals(post.get("visibility"))||("friends".equals(post.get("visibility"))&&!friends(viewer,author))))throw missing();
  return post;
 }
 public boolean friends(String a,String b){return db.queryForObject("SELECT count(*) FROM friendships WHERE (user_a_id=? AND user_b_id=?) OR (user_a_id=? AND user_b_id=?)",Integer.class,a,b,b,a)>0;}
 public boolean blocked(String a,String b){return db.queryForObject("SELECT count(*) FROM blocks WHERE block_type='block' AND ((actor_user_id=? AND target_user_id=?) OR (actor_user_id=? AND target_user_id=?))",Integer.class,a,b,b,a)>0;}
 public Map<String,Object> comment(String viewer,String postId,String id,boolean allowDeleted){
  visible(viewer,postId);
  var found=db.queryForList("SELECT * FROM post_comments WHERE id=? AND post_id=?",id,postId);if(found.isEmpty())throw missingComment();
  var row=found.getFirst();if((!allowDeleted&&row.get("deleted_at")!=null)||(!viewer.equals(row.get("author_id"))&&!"approved".equals(row.get("moderation_status"))))throw missingComment();return row;
 }
 public Map<String,Object> manageableComment(String viewer,String postId,String id){
  var post=visible(viewer,postId);
  if(!viewer.equals(post.get("author_id")))return comment(viewer,postId,id,false);
  var found=db.queryForList("SELECT * FROM post_comments WHERE id=? AND post_id=? AND deleted_at IS NULL",id,postId);
  if(found.isEmpty())throw missingComment();return found.getFirst();
 }
 public void lockUsers(Principal actor,Collection<String> others){
  var users=new TreeSet<String>();others.stream().filter(Objects::nonNull).forEach(users::add);users.add(actor.userId());
  for(String id:users){
   var row=db.queryForList("SELECT status FROM users WHERE id=? FOR UPDATE",id);
   if(row.isEmpty()||!Set.of("active","deactivation_pending").contains(row.getFirst().get("status")))throw missing();
   if(id.equals(actor.userId())&&!"active".equals(row.getFirst().get("status")))throw new ApiError(403,"COMMON_FORBIDDEN","当前账号不能发布或互动");
  }
  if(db.queryForObject("SELECT count(*) FROM refresh_tokens WHERE id=? AND user_id=? AND revoked_at IS NULL AND expires_at>now()",Integer.class,actor.sessionId(),actor.userId())==0)throw new ApiError(401,"AUTH_INVALID_TOKEN","登录会话已失效");
 }
 public Map<String,Object> lockPost(Principal actor,String id,Collection<String> additional){
  var first=visible(actor.userId(),id);var users=new HashSet<>(additional);users.add((String)first.get("author_id"));lockUsers(actor,users);
  db.queryForList("SELECT id FROM posts WHERE id=? FOR UPDATE",id);return visible(actor.userId(),id);
 }
 public void media(String viewer,Map<String,Object> asset){
  String source=(String)asset.get("source"),owner=(String)asset.get("owner_user_id");
  if(source.equals("post")){
   if(asset.get("post_id")==null){if(viewer.equals(owner))return;throw missing();}
   var post=visible(viewer,(String)asset.get("post_id"));
   if(!viewer.equals(owner)&&!"approved".equals(asset.get("status")))throw missing();
   if(!db.queryForObject("SELECT media_asset_ids @> jsonb_build_array(?::text) FROM posts WHERE id=?",Boolean.class,asset.get("id"),post.get("id")))throw missing();
  }else{
   var found=db.queryForList("SELECT id,post_id FROM post_comments WHERE media_asset_id=?",asset.get("id"));
   if(found.isEmpty()){if(viewer.equals(owner))return;throw missing();}
   var row=found.getFirst();comment(viewer,(String)row.get("post_id"),(String)row.get("id"),false);
   if(!viewer.equals(owner)&&!"approved".equals(asset.get("status")))throw missing();
  }
 }
 public static ApiError missing(){return new ApiError(404,"POST_NOT_FOUND","动态不存在或不可访问");}
 public static ApiError missingComment(){return new ApiError(404,"COMMON_NOT_FOUND","评论不存在或不可访问");}
}