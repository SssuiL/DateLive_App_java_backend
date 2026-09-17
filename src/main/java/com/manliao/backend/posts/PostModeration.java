package com.manliao.backend.posts;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.manliao.backend.common.ApiError;
import com.manliao.backend.notifications.PostNotifications;
@Service
public class PostModeration {
 private final JdbcTemplate db;private final PostNotifications notifications;
 public PostModeration(JdbcTemplate db,PostNotifications notifications){this.db=db;this.notifications=notifications;}
 private Map<String,Object> binding(String asset){
  var found=db.queryForList("""
   SELECT a.owner_user_id,a.post_id,c.id AS comment_id,c.post_id AS comment_post_id,c.reply_to_user_id,p.author_id AS post_author_id
   FROM media_assets a LEFT JOIN post_comments c ON c.media_asset_id=a.id LEFT JOIN posts p ON p.id=coalesce(a.post_id,c.post_id) WHERE a.id=?
   """,asset);if(found.isEmpty())throw new ApiError(404,"MEDIA_NOT_FOUND","媒体不存在");return found.getFirst();
 }
 /** Must precede media/user locks. Publication uses the same user -> post -> asset order. */
 public void lockRelated(String asset){
  var before=binding(asset);var users=new TreeSet<String>();
  for(String key:List.of("owner_user_id","reply_to_user_id","post_author_id"))if(before.get(key)!=null)users.add((String)before.get(key));
  for(String user:users)db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",user);
  var after=binding(asset);
  if(!Objects.equals(before.get("post_id"),after.get("post_id"))||!Objects.equals(before.get("comment_id"),after.get("comment_id")))
   throw new ApiError(409,"COMMON_CONFLICT","媒体绑定状态已变化，请重试");
  Object post=after.get("post_id")==null?after.get("comment_post_id"):after.get("post_id");
  if(post!=null)db.queryForList("SELECT id FROM posts WHERE id=? FOR UPDATE",post);
 }
 public void sync(String asset){
  var binding=binding(asset);Object postId=binding.get("post_id");
  if(postId!=null){
   var post=db.queryForMap("SELECT * FROM posts WHERE id=?",postId);if(post.get("deleted_at")!=null)return;
   String next=db.queryForObject("""
    SELECT CASE WHEN bool_or(status IN ('rejected','deleted') OR processing_status='failed') THEN 'rejected'
      WHEN bool_and(status='approved' AND processing_status='ready') THEN 'approved' ELSE 'review_pending' END
    FROM media_assets WHERE post_id=?
    """,String.class,postId);
   if("rejected".equals(post.get("text_moderation_status")))next="rejected";
   if(next!=null&&!next.equals(post.get("moderation_status"))){
    db.update("UPDATE posts SET moderation_status=?,moderation_reason=? WHERE id=?",next,next.equals("rejected")?"动态媒体未通过审核":null,postId);
    if(Set.of("approved","rejected").contains(next))notifications.event((String)post.get("author_id"),null,"post_media_"+next,(String)postId,next.equals("approved")?"动态审核通过":"动态审核未通过","请打开动态查看审核结果",Map.of(),null,false);
   }
  }else if(binding.get("comment_id")!=null){
   var comment=db.queryForMap("SELECT * FROM post_comments WHERE id=?",binding.get("comment_id"));if(comment.get("deleted_at")!=null)return;
   var post=db.queryForMap("SELECT * FROM posts WHERE id=?",comment.get("post_id"));if(post.get("deleted_at")!=null)return;
   var media=db.queryForMap("SELECT status,processing_status FROM media_assets WHERE id=?",asset);
   String next=Set.of("rejected","deleted").contains(media.get("status"))||"failed".equals(media.get("processing_status"))||"rejected".equals(comment.get("text_moderation_status"))?"rejected":
    "approved".equals(media.get("status"))&&"approved".equals(comment.get("text_moderation_status"))?"approved":"review_pending";
   if(!next.equals(comment.get("moderation_status"))){
    db.update("UPDATE post_comments SET moderation_status=? WHERE id=?",next,comment.get("id"));
    if(next.equals("approved"))notifications.comment(post,comment);
    if(next.equals("rejected"))notifications.event((String)comment.get("author_id"),null,"post_comment_media_rejected",(String)post.get("id"),"评论图片审核未通过","请修改评论图片",Map.of("comment_id",comment.get("id")),null,false);
   }
  }
 }
}