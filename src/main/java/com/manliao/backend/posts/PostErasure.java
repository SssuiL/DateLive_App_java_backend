package com.manliao.backend.posts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
@Service
public class PostErasure {
 private final JdbcTemplate db;
 public PostErasure(JdbcTemplate db){this.db=db;}
 /** Called in erasure transaction after the user's row is locked. Retain thread tombstones. */
 public void erase(String user){
  db.queryForList("SELECT id FROM posts WHERE author_id=? OR id IN(SELECT post_id FROM post_comments WHERE author_id=?) ORDER BY id FOR UPDATE",user,user);
  db.update("""
   DELETE FROM notification_events WHERE source_type='post' AND
    (source_id IN(SELECT id FROM posts WHERE author_id=?)
    OR payload->>'comment_id' IN(SELECT id FROM post_comments WHERE author_id=?)
    OR payload->'actor_user_ids' @> jsonb_build_array(?::text))
   """,user,user,user);
  db.update("DELETE FROM post_likes WHERE user_id=?",user);db.update("DELETE FROM post_comment_likes WHERE user_id=?",user);
  db.update("DELETE FROM post_media_reactions WHERE user_id=?",user);
  db.update("UPDATE post_comments SET deleted_at=coalesce(deleted_at,clock_timestamp()),content=NULL,media_asset_id=NULL,media_kind=NULL WHERE author_id=?",user);
  db.update("UPDATE post_comments SET reply_to_user_id=NULL WHERE reply_to_user_id=?",user);
  db.update("UPDATE posts SET deleted_at=coalesce(deleted_at,clock_timestamp()),text=NULL,media_asset_ids='[]'::jsonb WHERE author_id=?",user);
  db.update("DELETE FROM auth_rate_windows WHERE bucket_key IN (?,?)","post-comment:"+user,"post-media-reaction:"+user);
 }
}