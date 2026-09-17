package com.manliao.backend.social;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.manliao.backend.common.*;
import com.manliao.backend.identity.AuthDtos.Principal;
import com.manliao.backend.profiles.ProfileService;
import com.manliao.backend.notifications.FriendRequestNotifications;
@Service
public class SocialService {
 private static final String VISIBLE="""
   u.status='active' AND NOT EXISTS(SELECT 1 FROM blocks b WHERE
    (b.actor_user_id=? AND b.target_user_id=u.id) OR (b.target_user_id=? AND b.actor_user_id=u.id))
   """;
 private final JdbcTemplate db;private final TransactionTemplate tx;private final DatabaseRows rows;
 private final ProfileService profiles;private final FriendRequestNotifications notifications;
 public SocialService(JdbcTemplate db,TransactionTemplate tx,DatabaseRows rows,ProfileService profiles,FriendRequestNotifications notifications){
  this.db=db;this.tx=tx;this.rows=rows;this.profiles=profiles;this.notifications=notifications;
 }
 public List<Map<String,Object>> search(String viewer,String keyword,String rawLimit){
  int limit;
  try{limit=Integer.parseInt(rawLimit);}catch(NumberFormatException e){throw invalid();}
  if(limit<1||limit>50||(keyword!=null&&keyword.codePointCount(0,keyword.length())>100))throw invalid();
  String q=keyword==null?"":keyword.strip();if(q.isEmpty())return List.of();
  // strpos treats %, _ and backslashes literally, matching the legacy escaped substring search.
  return db.queryForList("SELECT u.id,u.nickname FROM users u WHERE "+VISIBLE+
    " AND (u.id=? OR strpos(u.nickname,?)>0) ORDER BY u.created_at DESC,u.id DESC LIMIT ?",viewer,viewer,q,q,limit);
 }
 public Map<String,Object> user(String viewer,String id){
  var found=db.queryForList("SELECT u.id,u.nickname FROM users u WHERE "+VISIBLE+" AND u.id=?",viewer,viewer,id);
  if(found.isEmpty())throw new ApiError(404,"USER_NOT_FOUND","用户不存在或不可访问");return found.getFirst();
 }
 public Map<String,Object> request(Principal user,SocialDtos.FriendRequest input){
  return tx.execute(status->{
   pair(user,input.target_user_id(),true);notBlocked(user.userId(),input.target_user_id());
   if(friendship(user.userId(),input.target_user_id())!=null)throw new ApiError(409,"FRIEND_ALREADY_EXISTS","已经是好友");
   var existing=db.queryForList("SELECT * FROM friend_requests WHERE requester_id=? AND receiver_id=? AND status='pending'",user.userId(),input.target_user_id());
   if(!existing.isEmpty())return rows.output(existing.getFirst());
   admit(user.userId());
   String id=id("fr");
   db.update("INSERT INTO friend_requests(id,requester_id,receiver_id,message) VALUES(?,?,?,?)",id,user.userId(),input.target_user_id(),input.message());
   notifications.created(id,user.userId(),input.target_user_id());
   return requestRow(id,false);
  });
 }
 public List<Map<String,Object>> requests(String viewer){
  return db.queryForList("""
   SELECT r.* FROM friend_requests r JOIN users u ON u.id=CASE WHEN r.requester_id=? THEN r.receiver_id ELSE r.requester_id END
   WHERE (r.requester_id=? OR r.receiver_id=?) AND
   """+VISIBLE+" ORDER BY r.created_at DESC,r.id DESC",viewer,viewer,viewer,viewer,viewer).stream().map(rows::output).toList();
 }
 public Map<String,Object> resolve(Principal user,String requestId,boolean accepted){
  return tx.execute(status->{
   var snapshot=requestRow(requestId,false);
   if(!user.userId().equals(snapshot.get("receiver_id")))throw new ApiError(403,"FRIEND_REQUEST_RESOLVE_FORBIDDEN","只有接收方可以处理好友申请");
   String actor=(String)snapshot.get("requester_id");
   pair(user,actor,true);notBlocked(user.userId(),actor);
   var request=requestRow(requestId,true);
   String next=accepted?"accepted":"rejected";
   if(request.get("status").equals(next))return request;
   if(!request.get("status").equals("pending"))throw new ApiError(409,"FRIEND_REQUEST_ALREADY_RESOLVED","好友申请已处理");
   if(accepted){
    String[] ids=sorted(user.userId(),actor);
    if(friendship(ids[0],ids[1])==null){
     String conversation=conversation(ids[0],ids[1]);
     db.update("INSERT INTO friendships(id,user_a_id,user_b_id,conversation_id) VALUES(?,?,?,?)",id("friendship"),ids[0],ids[1],conversation);
    }
    // Opposite pending requests express the same mutual consent; settle them in the same transaction.
    db.update("UPDATE friend_requests SET status='accepted' WHERE requester_id=? AND receiver_id=? AND status='pending'",user.userId(),actor);
   }
   db.update("UPDATE friend_requests SET status=? WHERE id=?",next,requestId);
   return requestRow(requestId,false);
  });
 }
 public List<Map<String,Object>> friends(String viewer){
  var found=db.queryForList("""
   SELECT u.id FROM friendships f JOIN users u ON u.id=CASE WHEN f.user_a_id=? THEN f.user_b_id ELSE f.user_a_id END
   WHERE (f.user_a_id=? OR f.user_b_id=?) AND
   """+VISIBLE+" ORDER BY f.created_at DESC,f.id DESC",viewer,viewer,viewer,viewer,viewer);
  var result=new ArrayList<Map<String,Object>>();
  for(var row:found){
   try{result.add(profiles.get(viewer,(String)row.get("id")));}
   catch(ApiError changed){if(changed.status()!=404)throw changed;} // A concurrent block/erasure may hide this profile.
  }
  return result;
 }
 public void deleteFriend(Principal user,String target){
  tx.executeWithoutResult(status->{
   pair(user,target,false);String[] ids=sorted(user.userId(),target);
   if(db.update("DELETE FROM friendships WHERE user_a_id=? AND user_b_id=?",ids[0],ids[1])==0)
    throw new ApiError(404,"COMMON_NOT_FOUND","好友关系不存在");
   // Keep conversation history, matching the legacy delete-friend contract.
  });
 }
 public Map<String,Object> block(Principal user,SocialDtos.Block input){
  return tx.execute(status->{
   pair(user,input.target_user_id(),true);
   String type=input.block_type()==null?"block":input.block_type();
   db.update("""
    INSERT INTO blocks(actor_user_id,target_user_id,block_type) VALUES(?,?,?)
    ON CONFLICT(actor_user_id,target_user_id) DO UPDATE SET
      block_type=CASE WHEN blocks.block_type='block' THEN 'block' ELSE excluded.block_type END
    """,user.userId(),input.target_user_id(),type);
   db.update("""
    UPDATE friend_requests SET status='rejected' WHERE status='pending' AND
      ((requester_id=? AND receiver_id=?) OR (requester_id=? AND receiver_id=?))
    """,user.userId(),input.target_user_id(),input.target_user_id(),user.userId());
   db.update("""
    UPDATE notification_events SET status='suppressed',suppress_reason='blocked',delivery_channel='none',updated_at=now()
    WHERE status='pending' AND category IN ('friend_request','chat') AND
      ((actor_user_id=? AND recipient_user_id=?) OR (actor_user_id=? AND recipient_user_id=?))
    """,user.userId(),input.target_user_id(),input.target_user_id(),user.userId());
   return rows.output(db.queryForMap("SELECT id,actor_user_id,target_user_id,block_type,created_at FROM blocks WHERE actor_user_id=? AND target_user_id=?",user.userId(),input.target_user_id()));
  });
 }
 public List<Map<String,Object>> blocks(String viewer){
  return db.queryForList("SELECT id,actor_user_id,target_user_id,block_type,created_at FROM blocks WHERE actor_user_id=? ORDER BY created_at DESC,id DESC",viewer)
   .stream().map(rows::output).toList();
 }
 public void unblock(Principal user,String id){
  tx.executeWithoutResult(status->{
   var found=db.queryForList("SELECT target_user_id FROM blocks WHERE id=? AND actor_user_id=?",id,user.userId());
   if(found.isEmpty())throw new ApiError(404,"BLOCK_NOT_FOUND","拉黑记录不存在");
   pair(user,(String)found.getFirst().get("target_user_id"),false);
   if(db.update("DELETE FROM blocks WHERE id=? AND actor_user_id=?",id,user.userId())==0)throw new ApiError(404,"BLOCK_NOT_FOUND","拉黑记录不存在");
  });
 }
 private void pair(Principal caller,String target,boolean active){
  if(caller.userId().equals(target))throw new ApiError(400,"COMMON_BAD_REQUEST","不能对自己执行此操作");
  // All two-user writes lock users first, in the same order, before relation rows; cleanup locks its user first too.
  for(String id:sorted(caller.userId(),target)){
   var found=db.queryForList("SELECT status FROM users WHERE id=? FOR UPDATE",id);
   if(found.isEmpty())throw new ApiError(404,"USER_NOT_FOUND","用户不存在或不可访问");
   String state=(String)found.getFirst().get("status");
   if(active&&!state.equals("active"))throw new ApiError(404,"USER_NOT_FOUND","用户不存在或不可访问");
   if(id.equals(caller.userId())&&!Set.of("active","deactivation_pending").contains(state))
    throw new ApiError(403,"ACCOUNT_DEACTIVATED","账号当前不可写入");
  }
  if(db.queryForObject("SELECT count(*) FROM refresh_tokens WHERE id=? AND user_id=? AND revoked_at IS NULL AND expires_at>now()",Integer.class,caller.sessionId(),caller.userId())==0)
   throw new ApiError(401,"AUTH_INVALID_TOKEN","登录会话已失效");
 }
 private void notBlocked(String a,String b){
  if(db.queryForObject("SELECT count(*) FROM blocks WHERE (actor_user_id=? AND target_user_id=?) OR (actor_user_id=? AND target_user_id=?)",Integer.class,a,b,b,a)>0)
   throw new ApiError(404,"USER_NOT_FOUND","用户不存在或不可访问");
 }
 private Object friendship(String a,String b){
  String[] ids=sorted(a,b);var found=db.queryForList("SELECT id FROM friendships WHERE user_a_id=? AND user_b_id=?",ids[0],ids[1]);
  return found.isEmpty()?null:found.getFirst().get("id");
 }
 private Map<String,Object> requestRow(String id,boolean lock){
  var found=db.queryForList("SELECT * FROM friend_requests WHERE id=?"+(lock?" FOR UPDATE":""),id);
  if(found.isEmpty())throw new ApiError(404,"FRIEND_REQUEST_NOT_FOUND","好友申请不存在");return rows.output(found.getFirst());
 }
 private String conversation(String a,String b){
  var found=db.queryForList("""
   SELECT c.id FROM conversations c WHERE c.type='friend'
    AND EXISTS(SELECT 1 FROM conversation_member_states m WHERE m.conversation_id=c.id AND m.user_id=?)
    AND EXISTS(SELECT 1 FROM conversation_member_states m WHERE m.conversation_id=c.id AND m.user_id=?)
    AND (SELECT count(*) FROM conversation_member_states m WHERE m.conversation_id=c.id)=2
   ORDER BY c.created_at,c.id LIMIT 1
   """,a,b);
  if(!found.isEmpty())return (String)found.getFirst().get("id");
  String id=id("conv");db.update("INSERT INTO conversations(id,type,title) VALUES(?,'friend','好友会话')",id);
  for(String user:List.of(a,b))db.update("INSERT INTO conversation_member_states(id,conversation_id,user_id) VALUES(?,?,?)",id("member"),id,user);
  return id;
 }
 /** Internal entry point after mutual ordinary likes have been confirmed in the caller's transaction. */
 public String ensureMatchedFriendship(Principal actor,String target){
  if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Match requires transaction");
  pair(actor,target,true);notBlocked(actor.userId(),target);String[] ids=sorted(actor.userId(),target);
  var found=db.queryForList("SELECT conversation_id FROM friendships WHERE user_a_id=? AND user_b_id=?",ids[0],ids[1]);
  if(!found.isEmpty())return (String)found.getFirst().get("conversation_id");
  String conversation=conversation(ids[0],ids[1]);
  db.update("INSERT INTO friendships(id,user_a_id,user_b_id,conversation_id) VALUES(?,?,?,?)",id("friendship"),ids[0],ids[1],conversation);
  db.update("UPDATE friend_requests SET status='accepted' WHERE status='pending' AND ((requester_id=? AND receiver_id=?) OR (requester_id=? AND receiver_id=?))",ids[0],ids[1],ids[1],ids[0]);
  return conversation;
 }
 private void admit(String user){
  int hits=db.queryForObject("""
   INSERT INTO auth_rate_windows(bucket_key,hits,expires_at) VALUES(?,1,now()+interval '60 seconds')
   ON CONFLICT(bucket_key) DO UPDATE SET
    hits=CASE WHEN auth_rate_windows.expires_at<=now() THEN 1 ELSE auth_rate_windows.hits+1 END,
    expires_at=CASE WHEN auth_rate_windows.expires_at<=now() THEN now()+interval '60 seconds' ELSE auth_rate_windows.expires_at END RETURNING hits
   """,Integer.class,"social-request:"+user);
  if(hits>30)throw new ApiError(429,"AUTH_RATE_LIMITED","好友申请过于频繁，请稍后再试");
 }
 private static String[] sorted(String a,String b){return a.compareTo(b)<0?new String[]{a,b}:new String[]{b,a};}
 private static String id(String prefix){return prefix+"_"+UUID.randomUUID().toString().replace("-","");}
 private static ApiError invalid(){return new ApiError(422,"COMMON_VALIDATION_ERROR","搜索参数超出有效范围");}
}
