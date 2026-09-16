package com.manliao.backend.notifications;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import com.manliao.backend.common.*;
@Service
public class NotificationService {
 private static final Set<String> PREFERENCES=Set.of("chat_messages_enabled","friend_requests_enabled",
   "matches_enabled","social_notifications_enabled","group_messages_enabled","live_notifications_enabled",
   "official_notifications_enabled","payment_notifications_enabled","night_quiet_enabled","night_quiet_start","night_quiet_end");
 private static final String EVENT_COLUMNS="id,recipient_user_id,actor_user_id,event_type,category,source_type,source_id,conversation_id,title,body,status,suppress_reason,delivery_channel,payload,read_at,created_at,updated_at";
 private final com.manliao.backend.identity.UserWriteGuard guard;
 private final JdbcTemplate db;
 private final TransactionTemplate tx;
 private final ObjectMapper json;
 private final DatabaseRows rows;
 public NotificationService(JdbcTemplate db,TransactionTemplate tx,ObjectMapper json,DatabaseRows rows,com.manliao.backend.identity.UserWriteGuard guard) {
   this.db=db;this.tx=tx;this.json=json;this.rows=rows;this.guard=guard;
 }
 public Map<String,Object> preferences(String user) {
   return tx.execute(status->{
     guard.lock(user);
     db.update("INSERT INTO notification_preferences(user_id) VALUES(?) ON CONFLICT DO NOTHING",user);
     return rows.output(db.queryForMap("SELECT * FROM notification_preferences WHERE user_id=?",user));
   });
 }
 public Map<String,Object> updatePreferences(String user,NotificationPreferencesUpdate input) {
   for(String value:new String[]{input.night_quiet_start(),input.night_quiet_end()})
     if(value!=null && !value.matches("([01][0-9]|2[0-3]):[0-5][0-9]"))
       throw new ApiError(400,"COMMON_BAD_REQUEST","免打扰时间超出有效范围");
   Map<?,?> inputMap=json.convertValue(input,Map.class);
   return tx.execute(status->{
     guard.lock(user);
     db.update("INSERT INTO notification_preferences(user_id) VALUES(?) ON CONFLICT DO NOTHING",user);
     var assignments=new ArrayList<String>();var values=new ArrayList<Object>();
     for(String key:PREFERENCES) if(inputMap.get(key)!=null) {
       assignments.add(key+"=?");values.add(inputMap.get(key));
     }
     if(!assignments.isEmpty()) {
       values.add(user);
       db.update("UPDATE notification_preferences SET "+String.join(",",assignments)+",updated_at=now() WHERE user_id=?",values.toArray());
     }
     return rows.output(db.queryForMap("SELECT * FROM notification_preferences WHERE user_id=?",user));
   });
 }
 public List<Map<String,Object>> events(String user) {
   return db.queryForList("SELECT "+EVENT_COLUMNS+" FROM notification_events WHERE recipient_user_id=? ORDER BY created_at DESC,id DESC",user)
       .stream().map(row->rows.output(row,"payload")).toList();
 }
 public long unread(String user) {
   return db.queryForObject("SELECT count(*) FROM notification_events WHERE recipient_user_id=? AND read_at IS NULL",Long.class,user);
 }
 public Map<String,Object> read(String user,String event) {
   return tx.execute(status->{
     guard.lock(user);
     var changed=db.queryForList("UPDATE notification_events SET read_at=now(),updated_at=now() WHERE id=? AND recipient_user_id=? AND read_at IS NULL RETURNING "+EVENT_COLUMNS,event,user);
     if(!changed.isEmpty()) {
       signal(user);
       return rows.output(changed.getFirst(),"payload");
     }
     var existing=db.queryForList("SELECT "+EVENT_COLUMNS+" FROM notification_events WHERE id=? AND recipient_user_id=?",event,user);
     if(existing.isEmpty()) throw new ApiError(404,"COMMON_NOT_FOUND","通知不存在");
     return rows.output(existing.getFirst(),"payload");
   });
 }
 public int readAll(String user) {
   return tx.execute(status->{
     guard.lock(user);
     int count=db.update("UPDATE notification_events SET read_at=now(),updated_at=now() WHERE recipient_user_id=? AND read_at IS NULL",user);
     if(count>0) signal(user);
     return count;
   });
 }
 private void signal(String user) {
   // Durable handoff only. A later realtime worker must deliver and acknowledge it.
   db.update("INSERT INTO notification_change_outbox(user_id,event_type) VALUES(?,'unread_count_changed')",user);
 }
}
