package com.manliao.backend.notifications;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
@Service
public class OfficialNotifications {
 private final JdbcTemplate db;private final ObjectMapper json;
 public OfficialNotifications(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
 public void event(String user,String type,String source,String sourceId,String title,String body,Map<String,Object> payload){
  if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Notification requires transaction");
  db.update("INSERT INTO notification_preferences(user_id) VALUES(?) ON CONFLICT DO NOTHING",user);var pref=db.queryForMap("SELECT * FROM notification_preferences WHERE user_id=?",user);String reason=null;
  if(!Boolean.TRUE.equals(pref.get("official_notifications_enabled")))reason="notification_preference_disabled";
  else if(Boolean.TRUE.equals(pref.get("night_quiet_enabled"))&&FriendRequestNotifications.quiet(LocalTime.now(ZoneOffset.UTC),LocalTime.parse((String)pref.get("night_quiet_start")),LocalTime.parse((String)pref.get("night_quiet_end"))))reason="night_quiet";
  else if(db.queryForObject("SELECT count(*) FROM push_devices WHERE user_id=? AND enabled=true",Integer.class,user)==0)reason="no_enabled_device";
  db.update("INSERT INTO notification_events(id,recipient_user_id,event_type,category,source_type,source_id,title,body,payload,status,suppress_reason,delivery_channel) VALUES(?,?,?,'official',?,?,?,?,?::jsonb,?,?,?)","notif_"+UUID.randomUUID().toString().replace("-",""),user,type,source,sourceId,title,body,json.writeValueAsString(payload),reason==null?"pending":"suppressed",reason,reason==null?"offline_push":"none");
  db.update("INSERT INTO notification_change_outbox(user_id,event_type) VALUES(?,'unread_count_changed')",user);
 }
}
