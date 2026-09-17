package com.manliao.backend.notifications;
import java.time.*;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
@Service
public class MatchNotifications {
 private final JdbcTemplate db;
 public MatchNotifications(JdbcTemplate db){this.db=db;}
 public void created(String match,String actor,String recipient,String conversation){
  if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Match notification requires transaction");
  db.update("INSERT INTO notification_preferences(user_id) VALUES(?) ON CONFLICT DO NOTHING",recipient);
  var pref=db.queryForMap("SELECT * FROM notification_preferences WHERE user_id=?",recipient);String reason=null;
  if(!Boolean.TRUE.equals(pref.get("matches_enabled")))reason="notification_preference_disabled";
  else if(Boolean.TRUE.equals(pref.get("night_quiet_enabled"))&&FriendRequestNotifications.quiet(LocalTime.now(ZoneOffset.UTC),LocalTime.parse((String)pref.get("night_quiet_start")),LocalTime.parse((String)pref.get("night_quiet_end"))))reason="night_quiet";
  else if(db.queryForObject("SELECT count(*) FROM push_devices WHERE user_id=? AND enabled=true",Integer.class,recipient)==0)reason="no_enabled_device";
  int inserted=db.update("""
   INSERT INTO notification_events(id,deduplication_key,recipient_user_id,actor_user_id,event_type,category,source_type,source_id,conversation_id,title,body,status,suppress_reason,delivery_channel)
   VALUES(?,?,?,?,'match_created','match','match',?,?,'匹配成功','你们互相喜欢，已成为好友',?,?,?) ON CONFLICT(deduplication_key) DO NOTHING
   ""","notif_"+UUID.randomUUID().toString().replace("-",""),"match:"+match+":"+recipient,recipient,actor,match,conversation,reason==null?"pending":"suppressed",reason,reason==null?"offline_push":"none");
  if(inserted>0)db.update("INSERT INTO notification_change_outbox(user_id,event_type) VALUES(?,'unread_count_changed')",recipient);
 }
}