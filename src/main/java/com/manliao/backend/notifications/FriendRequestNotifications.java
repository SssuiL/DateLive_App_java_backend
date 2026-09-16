package com.manliao.backend.notifications;
import java.time.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
@Service
public class FriendRequestNotifications {
 private final JdbcTemplate db;
 public FriendRequestNotifications(JdbcTemplate db){this.db=db;}
 // Caller holds both user rows in sorted order. Event, preferences and outbox share the relationship transaction.
 public void created(String request,String actor,String recipient){
  if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Notification requires relationship transaction");
  db.update("INSERT INTO notification_preferences(user_id) VALUES(?) ON CONFLICT DO NOTHING",recipient);
  var pref=db.queryForMap("SELECT * FROM notification_preferences WHERE user_id=?",recipient);
  String reason=null;
  if(!Boolean.TRUE.equals(pref.get("friend_requests_enabled")))reason="notification_preference_disabled";
  else if(Boolean.TRUE.equals(pref.get("night_quiet_enabled")) && quiet(LocalTime.now(ZoneOffset.UTC),
    LocalTime.parse((String)pref.get("night_quiet_start")),LocalTime.parse((String)pref.get("night_quiet_end"))))reason="night_quiet";
  else if(db.queryForObject("SELECT count(*) FROM push_devices WHERE user_id=? AND enabled=true",Integer.class,recipient)==0)reason="no_enabled_device";
  int inserted=db.update("""
    INSERT INTO notification_events(id,deduplication_key,recipient_user_id,actor_user_id,event_type,category,
      source_type,source_id,title,body,status,suppress_reason,delivery_channel)
    VALUES(?,?,?,?,'friend_request','friend_request','friend_request',?,'你收到一条好友申请','打开漫聊查看详情',?,?,?)
    ON CONFLICT(deduplication_key) DO NOTHING
    ""","notif_"+request,"friend_request:"+request,recipient,actor,request,
    reason==null?"pending":"suppressed",reason,reason==null?"offline_push":"none");
  // Pending means queued; no provider was invoked and no push is claimed delivered.
  if(inserted>0)db.update("INSERT INTO notification_change_outbox(user_id,event_type) VALUES(?,'unread_count_changed')",recipient);
 }
 public static boolean quiet(LocalTime now,LocalTime start,LocalTime end){
  if(start.equals(end))return false;
  return start.isBefore(end)?!now.isBefore(start)&&now.isBefore(end):!now.isBefore(start)||now.isBefore(end);
 }
}
