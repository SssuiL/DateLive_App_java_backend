package com.manliao.backend.realtime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import com.manliao.backend.common.*;
import com.manliao.backend.identity.*;
import com.manliao.backend.chat.ChatService;
@Service
public class RealtimeStore {
 private final JdbcTemplate db;private final TransactionTemplate tx;private final ObjectMapper json;
 private final AuthService auth;private final ChatService chat;
 public RealtimeStore(JdbcTemplate db,TransactionTemplate tx,ObjectMapper json,AuthService auth,ChatService chat){
  this.db=db;this.tx=tx;this.json=json;this.auth=auth;this.chat=chat;
 }
 public AuthDtos.Principal authenticate(String token){
  var user=auth.authenticate(token);
  if(!active(user.userId()))throw new ApiError(401,"AUTH_INVALID_TOKEN","账号当前不可连接");
  return user;
 }
 private boolean active(String user){return db.queryForObject("SELECT count(*) FROM users WHERE id=? AND status='active'",Integer.class,user)>0;}
 private void userLocks(Collection<String> users){
  for(String user:new TreeSet<>(users))db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",user);
 }
 private void serial(){db.queryForList("SELECT pg_advisory_xact_lock(78452911)");}
 public long connect(String token,String connection,String channel,Long cursor){
  return tx.execute(status->{
   var user=authenticate(token);userLocks(List.of(user.userId()));authenticate(token);
   db.update("DELETE FROM realtime_connections WHERE user_id=? AND expires_at<=clock_timestamp()",user.userId());
   if(db.queryForObject("SELECT count(*) FROM realtime_connections WHERE user_id=?",Integer.class,user.userId())>=8)
    throw new ApiError(429,"AUTH_RATE_LIMITED","实时连接过多");
   long max=db.queryForObject("SELECT COALESCE(max(id),0) FROM realtime_events WHERE recipient_user_id=? AND channel=?",Long.class,user.userId(),channel);
   max=Math.max(max,db.queryForObject("SELECT COALESCE(max(last_ack),0) FROM realtime_checkpoints WHERE user_id=? AND channel=?",Long.class,user.userId(),channel));
   if(cursor!=null&&(cursor<0||cursor>max))throw new ApiError(400,"COMMON_VALIDATION_ERROR","恢复游标无效");
   db.update("INSERT INTO realtime_checkpoints(user_id,session_id,channel) VALUES(?,?,?) ON CONFLICT DO NOTHING",user.userId(),user.sessionId(),channel);
   db.update("INSERT INTO realtime_connections(id,user_id,session_id,channel,expires_at) VALUES(?,?,?,?,clock_timestamp()+interval '45 seconds')",connection,user.userId(),user.sessionId(),channel);
   if(channel.equals("messages"))db.update("UPDATE users SET last_seen_at=clock_timestamp() WHERE id=?",user.userId());
   return cursor!=null?cursor:db.queryForObject("SELECT last_ack FROM realtime_checkpoints WHERE session_id=? AND channel=?",Long.class,user.sessionId(),channel);
  });
 }
 public void heartbeat(String token,String connection){
  tx.executeWithoutResult(status->{
   var user=authenticate(token);userLocks(List.of(user.userId()));authenticate(token);
   db.update("UPDATE realtime_connections SET expires_at=clock_timestamp()+interval '45 seconds' WHERE id=? AND user_id=?",connection,user.userId());
   db.update("UPDATE users SET last_seen_at=clock_timestamp() WHERE id=? AND EXISTS(SELECT 1 FROM realtime_connections WHERE id=? AND channel='messages')",user.userId(),connection);
  });
 }
 public void disconnect(String connection){
  db.update("DELETE FROM realtime_connections WHERE id=?",connection);
 }
 public int publish(){
  int count=0;
  for(String table:List.of("chat_change_outbox","notification_change_outbox")){
   List<Map<String,Object>> candidates;
   try{candidates=db.queryForList("SELECT id FROM "+table+" WHERE delivered_at IS NULL AND next_retry_at<=clock_timestamp() ORDER BY id LIMIT 32");}
   catch(RuntimeException failure){return count;}
   for(var candidate:candidates){
    long id=((Number)candidate.get("id")).longValue();
    try{if(Boolean.TRUE.equals(tx.execute(status->{serial();return publishOne(table,id);})))count++;}
    catch(RuntimeException failure){
     try{db.update("UPDATE "+table+" SET attempts=attempts+1,last_error_code='REALTIME_PUBLICATION_FAILED',next_retry_at=clock_timestamp()+interval '5 seconds'*LEAST(attempts+1,12) WHERE id=? AND delivered_at IS NULL",id);}catch(RuntimeException ignored){}
    }
   }
  }
  return count;
 }
 private boolean publishOne(String table,long id){
  boolean chatEvent=table.equals("chat_change_outbox");
  var snapshot=db.queryForList("SELECT * FROM "+table+" WHERE id=? AND delivered_at IS NULL",id);
  if(snapshot.isEmpty())return false;
  var row=snapshot.getFirst();var users=new TreeSet<String>();
  String conversation=chatEvent?(String)row.get("conversation_id"):null;
  String actor=chatEvent?(String)row.get("actor_user_id"):null;
  if(chatEvent){
   users.add(actor);
   users.addAll(db.queryForList("SELECT user_id FROM conversation_member_states WHERE active AND conversation_id=?",String.class,conversation));
  }else users.add((String)row.get("user_id"));
  userLocks(users);
  var claimed=db.queryForList("SELECT * FROM "+table+" WHERE id=? AND delivered_at IS NULL AND next_retry_at<=clock_timestamp() FOR UPDATE",id);
  if(claimed.isEmpty())return false;
  row=claimed.getFirst();
  String type=chatEvent?(String)row.get("event_type"):"notification.unread_count";
  boolean personal=type.equals("message.hidden")||type.equals("conversation.cleared");
  for(String recipient:users){
   if(!active(recipient)||(chatEvent&&!active(actor))||(personal&&!recipient.equals(actor)))continue;
   if(chatEvent){
    try{chat.get(recipient,conversation);}catch(ApiError inaccessible){continue;}
   }
   append(recipient,actor,chatEvent?"messages":"notifications",type,(chatEvent?"chat:":"notification:")+id,conversation,
     chatEvent?(String)row.get("message_id"):null,chatEvent?row.get("payload").toString():"{}",null);
  }
  db.update("UPDATE "+table+" SET delivered_at=clock_timestamp(),attempts=attempts+1,last_error_code=NULL WHERE id=?",id);
  return true;
 }
 private void append(String recipient,String actor,String channel,String type,String source,String conversation,String message,String payload,java.sql.Timestamp expires){
  db.update("""
   INSERT INTO realtime_events(recipient_user_id,actor_user_id,channel,event_type,source_key,conversation_id,message_id,payload,expires_at)
   VALUES(?,?,?,?,?,?,?,?::jsonb,?) ON CONFLICT(recipient_user_id,channel,source_key) DO NOTHING
   """,recipient,actor,channel,type,source,conversation,message,payload,expires);
 }
 public Map<String,Object> event(String user,String channel,long id){
  var found=db.queryForList("SELECT * FROM realtime_events WHERE id=? AND recipient_user_id=? AND channel=?",id,user,channel);
  return found.isEmpty()?null:found.getFirst();
 }
 public List<Map<String,Object>> events(String user,String channel,long cursor){
  return db.queryForList("SELECT * FROM realtime_events WHERE recipient_user_id=? AND channel=? AND id>? ORDER BY id LIMIT 32",user,channel,cursor);
 }
 public Map<String,Object> frame(String user,Map<String,Object> event){
  if(event.get("expires_at") instanceof java.sql.Timestamp expiry&&expiry.toInstant().isBefore(java.time.Instant.now()))return null;
  String type=(String)event.get("event_type"),conversation=(String)event.get("conversation_id"),message=(String)event.get("message_id");
  var out=new LinkedHashMap<String,Object>();out.put("event_id",event.get("id").toString());out.put("type",type);
  if(type.equals("notification.unread_count")){out.put("unread_count",unread(user));return out;}
  try{
   chat.get(user,conversation);
   if(type.equals("message.hidden")||type.equals("conversation.cleared")){
    if(!user.equals(event.get("actor_user_id")))return null;
    out.put("conversation_id",conversation);if(message!=null)out.put("message_id",message);return out;
   }
   if(type.startsWith("typing.")){
    if(user.equals(event.get("actor_user_id"))||!available(conversation))return null;
    out.put("conversation_id",conversation);out.put("user_id",event.get("actor_user_id"));return out;
   }
   out.put("conversation_id",conversation);
   if(type.equals("message.created")||type.equals("message.recalled")||type.equals("message.delivered")){
    if(type.equals("message.created")&&!available(conversation))return null;
    var current=chat.realtimeMessage(user,conversation,message);
    if(type.equals("message.created")&&Boolean.TRUE.equals(current.get("is_recalled")))return null;
    if(type.equals("message.delivered")){
     out.put("message_id",message);out.put("delivered_to_user_ids",current.get("delivered_to_user_ids"));
    }else out.put("message",current);
   }else if(type.equals("message.read")){
    var payload=json.readTree(event.get("payload").toString());
    var visible=new ArrayList<String>();
    for(var mid:payload.path("message_ids")){
     if(db.queryForObject("SELECT count(*) FROM message_receipts WHERE message_id=? AND user_id=? AND hidden_at IS NULL",Integer.class,mid.asString(),user)>0)visible.add(mid.asString());
    }
    if(visible.isEmpty())return null;
    out.put("message_ids",visible);out.put("reader_user_id",event.get("actor_user_id"));
   }else return null;
   return out;
  }catch(ApiError inaccessible){return null;}
 }
 private boolean available(String conversation){return db.queryForObject("SELECT count(*) FROM conversations c WHERE c.id=? AND (EXISTS(SELECT 1 FROM friendships f WHERE f.conversation_id=c.id) OR EXISTS(SELECT 1 FROM groups g WHERE g.id=c.group_id AND g.dissolved_at IS NULL))",Integer.class,conversation)>0;}
 public long unread(String user){return db.queryForObject("SELECT count(*) FROM notification_events WHERE recipient_user_id=? AND read_at IS NULL",Long.class,user);}
 public void ack(String token,String channel,long eventId){
  var principal=authenticate(token);
  tx.executeWithoutResult(status->{
   var found=db.queryForList("SELECT * FROM realtime_events WHERE id=? AND recipient_user_id=? AND channel=?",eventId,principal.userId(),channel);
   if(found.isEmpty())throw new ApiError(400,"COMMON_BAD_REQUEST","事件确认无效");
   var event=found.getFirst();
   if(event.get("event_type").equals("message.created"))chat.realtimeDelivered(principal,(String)event.get("conversation_id"),(String)event.get("message_id"));
   userLocks(List.of(principal.userId()));authenticate(token);
   db.update("""
    INSERT INTO realtime_checkpoints(user_id,session_id,channel,last_ack) VALUES(?,?,?,?)
    ON CONFLICT(session_id,channel) DO UPDATE SET last_ack=GREATEST(realtime_checkpoints.last_ack,excluded.last_ack),updated_at=clock_timestamp()
    """,principal.userId(),principal.sessionId(),channel,eventId);
  });
 }
 public void typing(String token,String conversation,String type){
  tx.executeWithoutResult(status->{
   serial();var actor=authenticate(token);
   var users=db.queryForList("SELECT user_id FROM conversation_member_states WHERE active AND conversation_id=? ORDER BY user_id",String.class,conversation);
   userLocks(users);authenticate(token);chat.get(actor.userId(),conversation);
   if(!available(conversation))throw new ApiError(403,"CONVERSATION_ACCESS_FORBIDDEN","已不是好友");
   String source="typing:"+UUID.randomUUID();
   for(String user:users)if(!user.equals(actor.userId()))append(user,actor.userId(),"messages",type,source,conversation,null,"{}",java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(5)));
  });
 }
 public Map<String,Boolean> presence(String viewer){
  var out=new LinkedHashMap<String,Boolean>();
  for(var conversation:chat.list(viewer)){
   String id=(String)conversation.get("id");
   for(var row:db.queryForList("""
    SELECT u.id,EXISTS(SELECT 1 FROM realtime_connections r JOIN refresh_tokens t ON t.id=r.session_id
      WHERE r.user_id=u.id AND r.channel='messages' AND r.expires_at>clock_timestamp()
       AND t.revoked_at IS NULL AND t.expires_at>clock_timestamp()) AS online
    FROM users u JOIN conversation_member_states m ON m.user_id=u.id WHERE m.active AND m.conversation_id=? AND u.id<>? AND u.status='active'
    """,id,viewer))out.put((String)row.get("id"),(Boolean)row.get("online"));
  }
  return out;
 }
 public Object lastSeen(String user){
  var value=db.queryForObject("SELECT last_seen_at FROM users WHERE id=?",java.sql.Timestamp.class,user);
  return value==null?null:value.toInstant().toString();
 }
}
