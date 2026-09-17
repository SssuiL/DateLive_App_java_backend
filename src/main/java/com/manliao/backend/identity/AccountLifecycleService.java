package com.manliao.backend.identity;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import com.manliao.backend.common.*;
import com.manliao.backend.media.MediaStorage;
@Service
public class AccountLifecycleService {
 private final JdbcTemplate db;private final TransactionTemplate tx;private final AuthService auth;
 private final DatabaseRows rows;private final ObjectMapper json;private final MediaStorage storage;private final com.manliao.backend.groups.GroupService groups;
 // Every users.id FK must have an explicit policy; integration tests compare this with the real schema.
 public static final Set<String> USER_FK_POLICY=Set.of(
   "explore_actions.actor_user_id","explore_actions.target_user_id","matches.user_a_id","matches.user_b_id","groups.owner_id","group_members.user_id","group_join_requests.user_id","account_erasure_records.user_id","auth_security_events.user_id","blocks.actor_user_id","blocks.target_user_id",
   "media_assets.owner_user_id","notification_events.recipient_user_id","notification_events.actor_user_id",
   "notification_change_outbox.user_id","notification_preferences.user_id","profile_reviews.user_id",
   "push_devices.user_id","refresh_tokens.user_id","user_profiles.user_id",
   "friend_requests.requester_id","friend_requests.receiver_id","friendships.user_a_id","friendships.user_b_id",
   "conversation_member_states.user_id","messages.recalled_by_user_id","messages.sender_id","message_receipts.user_id","chat_change_outbox.actor_user_id","realtime_events.recipient_user_id","realtime_events.actor_user_id","realtime_connections.user_id","realtime_checkpoints.user_id");
 public AccountLifecycleService(JdbcTemplate db,TransactionTemplate tx,AuthService auth,DatabaseRows rows,ObjectMapper json,MediaStorage storage,com.manliao.backend.groups.GroupService groups){
   this.db=db;this.tx=tx;this.auth=auth;this.rows=rows;this.json=json;this.storage=storage;this.groups=groups;
 }
 public Map<String,Object> deactivate(AuthDtos.Principal user,String request){
   return tx.execute(status->{
     var row=user(user.userId(),true);auth.requireUser(row);session(user);
     if(!row.get("status").equals("deactivation_pending")){
       db.update("UPDATE users SET status='deactivation_pending',deactivation_requested_at=now(),deactivation_due_at=now()+interval '45 days',updated_at=now() WHERE id=?",user.userId());
       auth.audit(user.userId(),user.sessionId(),"account_deactivate","success",null,request);
     }
     row=user(user.userId(),false);
     db.update("""
       INSERT INTO account_erasure_records(id,user_id,status,scheduled_at)
       VALUES(?,?,'pending',?) ON CONFLICT(user_id) DO NOTHING
       """,id("erase"),user.userId(),row.get("deactivation_due_at"));
     var out=new LinkedHashMap<String,Object>();
     out.put("user_id",user.userId());out.put("status",row.get("status"));
     out.put("deactivation_requested_at",row.get("deactivation_requested_at"));
     out.put("deactivation_due_at",row.get("deactivation_due_at"));out.put("cooling_off_days",45);
     return rows.output(out);
   });
 }
 public Map<String,Object> restore(AuthDtos.Principal user,String request){
   return tx.execute(status->{
     var row=user(user.userId(),true);auth.requireUser(row);session(user);
     if(!row.get("status").equals("deactivation_pending"))
       throw new ApiError(409,"ACCOUNT_NOT_PENDING_DEACTIVATION","账号不在注销冷静期");
     if(!Boolean.TRUE.equals(row.get("restorable")))throw new ApiError(403,"ACCOUNT_DEACTIVATED","注销冷静期已结束，无法恢复");
     db.update("UPDATE users SET status='active',deactivation_requested_at=NULL,deactivation_due_at=NULL,updated_at=now() WHERE id=?",user.userId());
     db.update("DELETE FROM account_erasure_records WHERE user_id=? AND status IN ('pending','failed')",user.userId());
     auth.audit(user.userId(),user.sessionId(),"account_restore","success",null,request);
     return auth.me(user);
   });
 }
 private void session(AuthDtos.Principal user){
   if(db.queryForObject("SELECT count(*) FROM refresh_tokens WHERE id=? AND user_id=? AND revoked_at IS NULL AND expires_at>now()",Integer.class,user.sessionId(),user.userId())==0)
     throw new ApiError(401,"AUTH_INVALID_TOKEN","登录会话已失效");
 }
 private Map<String,Object> user(String id,boolean lock){
   var result=db.queryForList("SELECT *,deactivation_due_at>now() AS restorable,deactivation_due_at<=now() AS due FROM users WHERE id=?"+(lock?" FOR UPDATE":""),id);
   if(result.isEmpty())throw new ApiError(404,"USER_NOT_FOUND","用户不存在");return result.getFirst();
 }
 public Map<String,Object> preview(String id){
   return tx.execute(status->{
     var user=user(id,true);
     var counts=new LinkedHashMap<String,Object>();
     counts.put("profiles",count("user_profiles","user_id=?",id));
     counts.put("media_assets",count("media_assets","owner_user_id=? OR conversation_id IN (SELECT m.conversation_id FROM conversation_member_states m JOIN conversations c ON c.id=m.conversation_id WHERE m.user_id=? AND c.type<>'group')",id,id));
     counts.put("sessions",count("refresh_tokens","user_id=?",id));
     counts.put("push_devices",count("push_devices","user_id=?",id));
     counts.put("social_relations",count("blocks","actor_user_id=? OR target_user_id=?",id,id)
       +count("friendships","user_a_id=? OR user_b_id=?",id,id)
       +count("friend_requests","requester_id=? OR receiver_id=?",id,id));
     counts.put("conversations",count("conversation_member_states","user_id=?",id));
     counts.put("messages",count("messages","conversation_id IN (SELECT m.conversation_id FROM conversation_member_states m JOIN conversations c ON c.id=m.conversation_id WHERE m.user_id=? AND c.type<>'group')",id));
     counts.put("notifications",count("notification_events","recipient_user_id=? OR actor_user_id=?",id,id));
     var out=new LinkedHashMap<String,Object>();
     out.put("user_id",id);out.put("status",user.get("status"));out.put("due_at",user.get("deactivation_due_at"));
     out.put("eligible",user.get("status").equals("deactivation_pending") && Boolean.TRUE.equals(user.get("due")));
     out.put("counts",counts);return rows.output(out);
   });
 }
 public Map<String,Object> record(String user){
   var list=db.queryForList("SELECT id,user_id,status,scheduled_at,started_at,completed_at,summary,error_message FROM account_erasure_records WHERE user_id=?",user);
   if(list.isEmpty())throw new ApiError(404,"COMMON_NOT_FOUND","注销记录不存在");
   return rows.output(list.getFirst(),"summary");
 }
 public List<Map<String,Object>> storageJobs(){
   return db.queryForList("SELECT id,erasure_record_id,status,attempts,last_error_code,next_retry_at FROM storage_deletion_jobs WHERE status<>'completed' ORDER BY next_retry_at,id LIMIT 100")
     .stream().map(row->rows.output(row)).toList();
 }
 public int processDue(){
   int completed=0;
   var candidates=db.queryForList("""
     SELECT u.id FROM users u LEFT JOIN account_erasure_records r ON r.user_id=u.id
     WHERE u.status='deactivation_pending' AND u.deactivation_due_at<=now()
       AND (r.id IS NULL OR r.next_retry_at<=now()) ORDER BY u.deactivation_due_at LIMIT 20
     """);
   for(var candidate:candidates){
     String id=(String)candidate.get("id");
     try{if(eraseDue(id))completed++;}
     catch(RuntimeException failure){
       db.update("""
         UPDATE account_erasure_records SET status='failed',attempts=attempts+1,
           error_message='DATABASE_ERASURE_FAILED',next_retry_at=now()+interval '60 seconds'
         WHERE user_id=? AND status NOT IN ('storage_pending','completed')
         """,id);
     }
   }
   return completed;
 }
 public boolean eraseDue(String userId){
   return Boolean.TRUE.equals(tx.execute(status->{
     var snapshot=user(userId,false);
     // Same order as SMS: phone advisory lock, user row, then codes and dependent records.
     db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","account-phone:"+snapshot.get("phone"));
     var user=user(userId,true);
     if(!user.get("status").equals("deactivation_pending") || !Boolean.TRUE.equals(user.get("due")))return false;
     db.update("INSERT INTO account_erasure_records(id,user_id,status,scheduled_at) VALUES(?,?,'pending',?) ON CONFLICT(user_id) DO NOTHING",
       id("erase"),userId,user.get("deactivation_due_at"));
     String record=db.queryForObject("UPDATE account_erasure_records SET status='running',started_at=now(),error_message=NULL WHERE user_id=? RETURNING id",String.class,userId);
     var summary=new LinkedHashMap<String,Object>();
     groups.eraseMemberships(userId);
     summary.put("group_messages_anonymized",db.update("""
       UPDATE messages SET content='已注销用户的消息',type='text',media_asset_id=NULL,media_kind=NULL,duration_seconds=0,
        recalled_at=coalesce(recalled_at,clock_timestamp()),recalled_by_user_id=?
       WHERE sender_id=? AND conversation_id IN (SELECT id FROM conversations WHERE type='group')
       """,userId,userId));
     db.update("""
       UPDATE conversations c SET last_message=(SELECT left(m.content,500) FROM messages m WHERE m.conversation_id=c.id ORDER BY m.created_at DESC,m.id DESC LIMIT 1)
       WHERE c.type='group' AND EXISTS(SELECT 1 FROM messages m WHERE m.conversation_id=c.id AND m.sender_id=?)
       """,userId);
     db.update("DELETE FROM chat_change_outbox WHERE actor_user_id=? AND conversation_id IN (SELECT id FROM conversations WHERE type='group')",userId);
     db.queryForList("SELECT user_id FROM user_profiles WHERE user_id=? FOR UPDATE",userId);
     var assets=db.queryForList("SELECT id,storage_key FROM media_assets WHERE owner_user_id=? OR conversation_id IN (SELECT m.conversation_id FROM conversation_member_states m JOIN conversations c ON c.id=m.conversation_id WHERE m.user_id=? AND c.type<>'group') ORDER BY id FOR UPDATE",userId,userId);
     for(var asset:assets){
       if(asset.get("storage_key")!=null)db.update("""
         INSERT INTO storage_deletion_jobs(id,erasure_record_id,storage_key) VALUES(?,?,?)
         ON CONFLICT(erasure_record_id,storage_key) DO NOTHING
         """,id("delete"),record,asset.get("storage_key"));
       db.update("DELETE FROM media_reviews WHERE media_id=?",asset.get("id"));
       db.update("UPDATE admin_operation_logs SET details='{}'::jsonb WHERE target_type='media' AND target_id=?",asset.get("id"));
     }
     summary.put("media_assets_deleted",db.update("DELETE FROM media_assets WHERE owner_user_id=? OR conversation_id IN (SELECT m.conversation_id FROM conversation_member_states m JOIN conversations c ON c.id=m.conversation_id WHERE m.user_id=? AND c.type<>'group')",userId,userId));
     summary.put("realtime_events_deleted",db.update("DELETE FROM realtime_events WHERE recipient_user_id=? OR actor_user_id=?",userId,userId));
     summary.put("realtime_connections_deleted",db.update("DELETE FROM realtime_connections WHERE user_id=?",userId));
     summary.put("realtime_checkpoints_deleted",db.update("DELETE FROM realtime_checkpoints WHERE user_id=?",userId));
     summary.put("notifications_deleted",db.update("DELETE FROM notification_events WHERE recipient_user_id=? OR actor_user_id=?",userId,userId));
     for(String table:List.of("notification_change_outbox","notification_preferences","profile_reviews","push_devices","user_profiles"))
       summary.put(table+"_deleted",db.update("DELETE FROM "+table+" WHERE user_id=?",userId));
     db.update("UPDATE refresh_tokens SET replaced_by_token_id=NULL WHERE user_id=?",userId);
     summary.put("sessions_deleted",db.update("DELETE FROM refresh_tokens WHERE user_id=?",userId));
     summary.put("blocks_deleted",db.update("DELETE FROM blocks WHERE actor_user_id=? OR target_user_id=?",userId,userId));
     summary.put("friend_requests_deleted",db.update("DELETE FROM friend_requests WHERE requester_id=? OR receiver_id=?",userId,userId));
     summary.put("explore_actions_deleted",db.update("DELETE FROM explore_actions WHERE actor_user_id=? OR target_user_id=?",userId,userId));
     summary.put("matches_deleted",db.update("DELETE FROM matches WHERE user_a_id=? OR user_b_id=?",userId,userId));
     summary.put("friendships_deleted",db.update("DELETE FROM friendships WHERE user_a_id=? OR user_b_id=?",userId,userId));
     summary.put("chat_events_deleted",db.update("DELETE FROM chat_change_outbox WHERE conversation_id IN (SELECT m.conversation_id FROM conversation_member_states m JOIN conversations c ON c.id=m.conversation_id WHERE m.user_id=? AND c.type<>'group')",userId));
     summary.put("messages_deleted",db.update("DELETE FROM messages WHERE conversation_id IN (SELECT m.conversation_id FROM conversation_member_states m JOIN conversations c ON c.id=m.conversation_id WHERE m.user_id=? AND c.type<>'group')",userId));
     // Erase private conversations; group member state is retained inactive for sender referential integrity.
     summary.put("conversations_deleted",db.update("DELETE FROM conversations WHERE id IN (SELECT m.conversation_id FROM conversation_member_states m JOIN conversations c ON c.id=m.conversation_id WHERE m.user_id=? AND c.type<>'group')",userId));
     db.update("DELETE FROM auth_rate_windows WHERE bucket_key IN (?,?,?)","social-request:"+userId,"chat-send:"+userId,"group-write:"+userId);
     summary.put("verification_codes_deleted",db.update("DELETE FROM auth_verification_codes WHERE phone=?",user.get("phone")));
     db.update("UPDATE auth_security_events SET session_id=NULL,request_id='erased' WHERE user_id=?",userId);
     db.update("UPDATE admin_operation_logs SET details='{}'::jsonb WHERE target_type IN ('user','account') AND target_id=?",userId);
     db.update("""
       UPDATE users SET phone=?,nickname='已注销用户',password_hash=?,status='deactivated',is_paid_member=false,
         membership_status='free',deactivation_requested_at=NULL,deactivation_due_at=NULL,deactivated_at=now(),last_seen_at=NULL,updated_at=now() WHERE id=?
       ""","deleted_"+UUID.randomUUID().toString().replace("-","").substring(0,24),"!deleted:"+UUID.randomUUID(),userId);
     int jobs=count("storage_deletion_jobs","erasure_record_id=? AND status='pending'",record);
     summary.put("storage_objects_queued",jobs);
     db.update("""
       UPDATE account_erasure_records SET status=?,summary=?::jsonb,error_message=NULL,
         completed_at=CASE WHEN ?=0 THEN now() ELSE NULL END WHERE id=?
       """,jobs==0?"completed":"storage_pending",json.writeValueAsString(summary),jobs,record);
     return true;
   }));
 }
 public int cleanupStorage(){
   int cleaned=0;
   for(int i=0;i<100;i++){
     Boolean result=tx.execute(status->{
       var jobs=db.queryForList("SELECT * FROM storage_deletion_jobs WHERE status='pending' AND next_retry_at<=now() ORDER BY next_retry_at,id LIMIT 1 FOR UPDATE SKIP LOCKED");
       if(jobs.isEmpty())return null;
       var job=jobs.getFirst();
       try{
         storage.delete((String)job.get("storage_key"));
         db.update("UPDATE storage_deletion_jobs SET status='completed',storage_key=NULL,attempts=attempts+1,last_error_code=NULL,completed_at=now() WHERE id=?",job.get("id"));
         return true;
       }catch(java.io.IOException failure){
         db.update("UPDATE storage_deletion_jobs SET attempts=attempts+1,last_error_code='STORAGE_DELETE_FAILED',next_retry_at=now()+interval '60 seconds' WHERE id=?",job.get("id"));
         return false;
       }
     });
     if(result==null)break;if(result)cleaned++;
   }
   db.update("""
     UPDATE account_erasure_records r SET status='completed',completed_at=now()
     WHERE status='storage_pending' AND NOT EXISTS(
       SELECT 1 FROM storage_deletion_jobs j WHERE j.erasure_record_id=r.id AND j.status<>'completed')
     """);
   return cleaned;
 }
 private int count(String table,String condition,Object...args){return db.queryForObject("SELECT count(*) FROM "+table+" WHERE "+condition,Integer.class,args);}
 private String id(String prefix){return prefix+"_"+UUID.randomUUID().toString().replace("-","");}
}
