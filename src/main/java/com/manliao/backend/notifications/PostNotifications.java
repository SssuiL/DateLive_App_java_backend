package com.manliao.backend.notifications;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
@Service
public class PostNotifications {
 private final JdbcTemplate db;private final ObjectMapper json;
 public PostNotifications(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
 public void event(String recipient,String actor,String type,String post,String title,String body,Map<String,Object> payload,String dedup,boolean aggregate){
  if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Post notification requires transaction");
  if(recipient==null||recipient.equals(actor)||db.queryForObject("SELECT count(*) FROM users WHERE id=? AND status='active'",Integer.class,recipient)==0)return;
  if(actor!=null&&db.queryForObject("SELECT count(*) FROM blocks WHERE block_type='block' AND ((actor_user_id=? AND target_user_id=?) OR (actor_user_id=? AND target_user_id=?))",Integer.class,actor,recipient,recipient,actor)>0)return;
  var values=new LinkedHashMap<>(payload);String key=dedup;
  if(aggregate){
   var found=db.queryForList("SELECT id,payload FROM notification_events WHERE recipient_user_id=? AND event_type=? AND source_id=? AND read_at IS NULL AND (?::text IS NULL OR payload->>'comment_id'=?) ORDER BY created_at DESC,id DESC LIMIT 1 FOR UPDATE",recipient,type,post,values.get("comment_id"),values.get("comment_id"));
   if(!found.isEmpty()){
    var previous=found.getFirst();var actors=new LinkedHashSet<String>();
    var tree=json.readTree(previous.get("payload").toString());if(tree.has("actor_user_ids"))for(var item:tree.get("actor_user_ids"))actors.add(item.asString());actors.add(actor);
    values.put("actor_user_ids",actors);values.put("like_count",actors.size());
    db.update("UPDATE notification_events SET actor_user_id=?,payload=?::jsonb,body=?,updated_at=clock_timestamp() WHERE id=?",actor,json.writeValueAsString(values),actors.size()+" 人点赞",previous.get("id"));
    changed(recipient);return;
   }
   values.put("actor_user_ids",List.of(actor));values.put("like_count",1);key=null;
  }
  db.update("INSERT INTO notification_preferences(user_id) VALUES(?) ON CONFLICT DO NOTHING",recipient);
  var pref=db.queryForMap("SELECT * FROM notification_preferences WHERE user_id=?",recipient);
  boolean official=type.endsWith("_approved")||type.endsWith("_rejected");String reason=null;
  if(!Boolean.TRUE.equals(pref.get(official?"official_notifications_enabled":"social_notifications_enabled")))reason="notification_preference_disabled";
  else if(Boolean.TRUE.equals(pref.get("night_quiet_enabled"))&&FriendRequestNotifications.quiet(LocalTime.now(ZoneOffset.UTC),LocalTime.parse((String)pref.get("night_quiet_start")),LocalTime.parse((String)pref.get("night_quiet_end"))))reason="night_quiet";
  else if(db.queryForObject("SELECT count(*) FROM push_devices WHERE user_id=? AND enabled=true",Integer.class,recipient)==0)reason="no_enabled_device";
  int inserted=db.update("""
   INSERT INTO notification_events(id,deduplication_key,recipient_user_id,actor_user_id,event_type,category,source_type,source_id,title,body,payload,status,suppress_reason,delivery_channel)
   VALUES(?,?,?,?,?,?,'post',?,?,?,?::jsonb,?,?,?) ON CONFLICT(deduplication_key) DO NOTHING
   ""","notif_"+UUID.randomUUID().toString().replace("-",""),key,recipient,actor,type,official?"official":"social",post,title,body,json.writeValueAsString(values),reason==null?"pending":"suppressed",reason,reason==null?"offline_push":"none");
  if(inserted>0)changed(recipient);
 }
 public void comment(Map<String,Object> post,Map<String,Object> comment){
  String recipient=(String)(comment.get("reply_to_user_id")==null?post.get("author_id"):comment.get("reply_to_user_id"));
  var payload=new LinkedHashMap<String,Object>();payload.put("comment_id",comment.get("id"));payload.put("parent_comment_id",comment.get("parent_comment_id"));payload.put("root_comment_id",comment.get("root_comment_id"));
  String text=(String)comment.get("content");String preview=text==null?"发送了一条图片评论":text.substring(0,text.offsetByCodePoints(0,Math.min(60,text.codePointCount(0,text.length()))));
  event(recipient,(String)comment.get("author_id"),"post_commented",(String)post.get("id"),comment.get("reply_to_user_id")==null?"你的动态收到新评论":"有人回复了你的评论",preview,payload,"post-comment:"+comment.get("id"),false);
 }
 public void changed(String user){db.update("INSERT INTO notification_change_outbox(user_id,event_type) VALUES(?,'unread_count_changed')",user);}
}