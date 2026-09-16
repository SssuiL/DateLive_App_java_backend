package com.manliao.backend.notifications;
import java.time.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
@Service
public class ChatNotifications {
 private final JdbcTemplate db;
 public ChatNotifications(JdbcTemplate db){this.db=db;}
 // ChatService holds both user rows before this producer runs; all writes share its transaction.
 public void created(String message,String conversation,String sender,String receiver){
  if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Chat notification requires transaction");
  db.update("INSERT INTO notification_preferences(user_id) VALUES(?) ON CONFLICT DO NOTHING",receiver);
  var p=db.queryForMap("SELECT * FROM notification_preferences WHERE user_id=?",receiver);
  String reason=null;
  if(!Boolean.TRUE.equals(p.get("chat_messages_enabled")))reason="notification_preference_disabled";
  else if(Boolean.TRUE.equals(p.get("night_quiet_enabled"))&&FriendRequestNotifications.quiet(LocalTime.now(ZoneOffset.UTC),
    LocalTime.parse((String)p.get("night_quiet_start")),LocalTime.parse((String)p.get("night_quiet_end"))))reason="night_quiet";
  else if(Boolean.TRUE.equals(db.queryForObject("SELECT muted FROM conversation_member_states WHERE conversation_id=? AND user_id=?",Boolean.class,conversation,receiver)))reason="conversation_muted";
  else if(db.queryForObject("SELECT count(*) FROM push_devices WHERE user_id=? AND enabled=true",Integer.class,receiver)==0)reason="no_enabled_device";
  int inserted=db.update("""
   INSERT INTO notification_events(id,deduplication_key,recipient_user_id,actor_user_id,event_type,category,
    source_type,source_id,conversation_id,title,body,status,suppress_reason,delivery_channel)
   VALUES(?,?,?,?,'chat_message','chat','message',?,?,'你收到一条新消息','打开漫聊查看详情',?,?,?)
   ON CONFLICT(deduplication_key) DO NOTHING
   ""","notif_"+message,"chat_message:"+message,receiver,sender,message,conversation,
    reason==null?"pending":"suppressed",reason,reason==null?"offline_push":"none");
  if(inserted>0)db.update("INSERT INTO notification_change_outbox(user_id,event_type) VALUES(?,'unread_count_changed')",receiver);
 }
}
