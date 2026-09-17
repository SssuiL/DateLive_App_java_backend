package com.manliao.backend.groups;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import com.manliao.backend.common.*;
import com.manliao.backend.identity.*;
@Service
public class GroupService {
 private final JdbcTemplate db;private final TransactionTemplate tx;private final DatabaseRows rows;private final ObjectMapper json;private final PasswordHasher hashes;
 public GroupService(JdbcTemplate db,TransactionTemplate tx,DatabaseRows rows,ObjectMapper json,PasswordHasher hashes){this.db=db;this.tx=tx;this.rows=rows;this.json=json;this.hashes=hashes;}
 public Map<String,Object> create(AuthDtos.Principal user,GroupDtos.Create input){
  String rule=input.join_rule_type()==null?"manual":input.join_rule_type();String secret=rule.equals("password")?input.join_password():rule.equals("question")?input.join_answer():null;
  if((Set.of("password","question").contains(rule)&&(secret==null||secret.isBlank()))||(rule.equals("question")&&(input.join_question()==null||input.join_question().isBlank())))throw new ApiError(422,"COMMON_VALIDATION_ERROR","入群规则需要密码或问题答案");
  String encoded=secret==null?null:hashes.hash(secret);
  return tx.execute(status->{
   lockUsers(user,List.of(user.userId()));admit(user.userId());String id=id("group"),conv=id("conv");
   db.update("INSERT INTO groups(id,name,tags,owner_id,link_code,join_rule_type,join_question,join_secret_hash,max_members) VALUES(?,?,?::jsonb,?,?,?,?,?,?)",
    id,input.name().strip(),json.writeValueAsString(input.tags()),user.userId(),UUID.randomUUID().toString().replace("-",""),rule,input.join_question(),encoded,input.max_members()==null?500:input.max_members());
   db.update("INSERT INTO conversations(id,type,title,group_id) VALUES(?,'group',?,?)",conv,input.name().strip(),id);
   addMember(id,conv,user.userId(),"create","owner");return output(user.userId(),id);
  });
 }
 public List<Map<String,Object>> list(String user){return db.queryForList("SELECT g.id FROM groups g JOIN group_members m ON m.group_id=g.id WHERE m.user_id=? AND g.dissolved_at IS NULL ORDER BY g.created_at DESC,g.id",user).stream().map(r->output(user,(String)r.get("id"))).toList();}
 public List<Map<String,Object>> search(String user,String query){
  if(query!=null&&query.length()>120)throw new ApiError(422,"COMMON_VALIDATION_ERROR","群组关键词过长");String q=query==null?"":query.strip();
  return db.queryForList("""
   SELECT g.id FROM groups g WHERE g.dissolved_at IS NULL AND (?='' OR strpos(lower(g.name),lower(?))>0 OR lower(g.id)=lower(?) OR lower(g.link_code)=lower(?)
    OR EXISTS(SELECT 1 FROM jsonb_array_elements_text(g.tags) tag WHERE strpos(lower(tag),lower(?))>0)) ORDER BY g.created_at DESC,g.id LIMIT 50
   """,q,q,q,q,q).stream().map(r->output(user,(String)r.get("id"))).toList();
 }
 public Map<String,Object> get(String user,String id){activeGroup(id,false);return output(user,id);}
 private Map<String,Object> output(String viewer,String id){
  return rows.output(db.queryForMap("""
   SELECT g.id,g.name,g.tags,g.owner_id,g.link_code,g.join_rule_type,g.max_members,g.created_at,g.dissolved_at,c.id AS conversation_id,
    (SELECT count(*) FROM group_members m WHERE m.group_id=g.id) AS member_count,
    (SELECT role FROM group_members m WHERE m.group_id=g.id AND m.user_id=?) AS current_user_role
   FROM groups g JOIN conversations c ON c.group_id=g.id WHERE g.id=?
   """,viewer,id),"tags");
 }
 private Map<String,Object> activeGroup(String id,boolean lock){
  var found=db.queryForList("SELECT g.* FROM groups g WHERE id=?"+(lock?" FOR UPDATE":""),id);
  if(found.isEmpty())throw error(404,"GROUP_NOT_FOUND","群组不存在");var group=found.getFirst();if(group.get("dissolved_at")!=null)throw error(410,"GROUP_DISSOLVED","群组已解散");
  if(lock)db.queryForList("SELECT id FROM conversations WHERE group_id=? FOR UPDATE",id);return group;
 }
 private String member(String group,String user){
  var found=db.queryForList("SELECT role FROM group_members WHERE group_id=? AND user_id=?",group,user);
  if(found.isEmpty())throw error(403,"GROUP_MANAGE_FORBIDDEN","你不是该群成员");return (String)found.getFirst().get("role");
 }
 private String manager(String group,String user){String role=member(group,user);if(!Set.of("owner","admin").contains(role))throw error(403,"GROUP_MANAGE_FORBIDDEN","只有群主或管理员可以操作");return role;}
 private void owner(String group,String user){if(!member(group,user).equals("owner"))throw error(403,"GROUP_OWNER_ACTION_REQUIRED","只有群主可以操作");}
 private void lockUsers(AuthDtos.Principal actor,Collection<String> targets){
  var users=new TreeSet<String>(targets);users.add(actor.userId());
  for(String user:users){var found=db.queryForList("SELECT status FROM users WHERE id=? FOR UPDATE",user);if(found.isEmpty()||!"active".equals(found.getFirst().get("status")))throw error(404,"USER_NOT_FOUND","用户不存在或不可操作");}
  if(db.queryForObject("SELECT count(*) FROM refresh_tokens WHERE id=? AND user_id=? AND revoked_at IS NULL AND expires_at>now()",Integer.class,actor.sessionId(),actor.userId())==0)throw error(401,"AUTH_INVALID_TOKEN","登录会话已失效");
 }
 private String conversation(String group){return db.queryForObject("SELECT id FROM conversations WHERE group_id=?",String.class,group);}
 private void capacity(Map<String,Object> group){if(db.queryForObject("SELECT count(*) FROM group_members WHERE group_id=?",Integer.class,group.get("id"))>=((Number)group.get("max_members")).intValue())throw error(409,"GROUP_FULL","群成员人数已达到上限");}
 private void notMember(String group,String user){if(db.queryForObject("SELECT count(*) FROM group_members WHERE group_id=? AND user_id=?",Integer.class,group,user)>0)throw error(409,"GROUP_ALREADY_JOINED","已经在该群组中");}
 private void addMember(String group,String conversation,String user,String source,String role){
  db.update("INSERT INTO group_members(group_id,user_id,role,join_source) VALUES(?,?,?,?)",group,user,role,source);
  db.update("""
   INSERT INTO conversation_member_states(id,conversation_id,user_id) VALUES(?,?,?)
   ON CONFLICT(conversation_id,user_id) DO UPDATE SET active=true,unread_count=0,updated_at=clock_timestamp()
   """,id("cms"),conversation,user);
  // Rejoining does not recover receipts from a prior membership or disclose pre-join history.
  db.update("DELETE FROM message_receipts WHERE conversation_id=? AND user_id=?",conversation,user);
 }
 private Map<String,Object> request(String group,String user,String source,String status){String id=id("gjr");db.update("INSERT INTO group_join_requests(id,group_id,user_id,source,status) VALUES(?,?,?,?,?)",id,group,user,source,status);return request(id);}
 private Map<String,Object> request(String id){return rows.output(db.queryForMap("SELECT * FROM group_join_requests WHERE id=?",id));}
 public Map<String,Object> invite(AuthDtos.Principal actor,String id,String target){return tx.execute(status->{lockUsers(actor,List.of(target));var group=activeGroup(id,true);manager(id,actor.userId());notMember(id,target);capacity(group);
  db.update("UPDATE group_join_requests SET status='approved' WHERE group_id=? AND user_id=? AND status='pending'",id,target);
  addMember(id,conversation(id),target,"invite","member");return request(id,target,"invite","approved");});}
 public Map<String,Object> join(AuthDtos.Principal actor,String id,GroupDtos.Join input){return tx.execute(status->{
  lockUsers(actor,List.of());admit(actor.userId());var group=activeGroup(id,true);notMember(id,actor.userId());
  if(db.queryForObject("SELECT count(*) FROM group_join_requests WHERE group_id=? AND user_id=? AND status='pending'",Integer.class,id,actor.userId())>0)throw error(409,"GROUP_JOIN_REQUEST_DUPLICATE","已有待处理的入群申请");
  String rule=(String)group.get("join_rule_type"),state="pending";
  if(rule.equals("invite_only"))throw error(403,"GROUP_INVITE_ONLY","该群仅允许邀请加入");
  if(rule.equals("open"))state="approved";
  if(Set.of("password","question").contains(rule)){String guess=rule.equals("password")?input.password():input.answer();state=guess!=null&&hashes.matches(guess,(String)group.get("join_secret_hash"))?"approved":"rejected";}
  String source=input.source()==null?"search":input.source();
  if(state.equals("approved")){capacity(group);addMember(id,conversation(id),actor.userId(),source,"member");}return request(id,actor.userId(),source,state);
 });}
 public Map<String,Object> joinByCode(AuthDtos.Principal actor,String code,GroupDtos.Join input){
  if(code.length()>32)throw error(404,"GROUP_NOT_FOUND","群组不存在");var found=db.queryForList("SELECT id FROM groups WHERE lower(link_code)=lower(?) AND dissolved_at IS NULL",code.strip());
  if(found.isEmpty())throw error(404,"GROUP_NOT_FOUND","群组不存在");return join(actor,(String)found.getFirst().get("id"),new GroupDtos.Join(input.answer(),input.password(),"link_code"));
 }
 public List<Map<String,Object>> members(String actor,String id){activeGroup(id,false);member(id,actor);return db.queryForList("SELECT m.user_id,u.nickname,m.role,m.join_source,m.created_at AS joined_at FROM group_members m JOIN users u ON u.id=m.user_id WHERE m.group_id=? ORDER BY m.created_at,m.user_id",id).stream().map(rows::output).toList();}
 public List<Map<String,Object>> requests(String actor,String id){activeGroup(id,false);manager(id,actor);return db.queryForList("SELECT * FROM group_join_requests WHERE group_id=? ORDER BY created_at DESC,id DESC",id).stream().map(rows::output).toList();}
 public Map<String,Object> resolve(AuthDtos.Principal actor,String id,String request,boolean approved){
  var snapshot=db.queryForList("SELECT user_id FROM group_join_requests WHERE id=? AND group_id=?",request,id);if(snapshot.isEmpty())throw error(404,"GROUP_JOIN_REQUEST_NOT_FOUND","申请不存在");
  return tx.execute(status->{lockUsers(actor,List.of((String)snapshot.getFirst().get("user_id")));var group=activeGroup(id,true);manager(id,actor.userId());
   var found=db.queryForList("SELECT * FROM group_join_requests WHERE id=? AND group_id=? AND status='pending' FOR UPDATE",request,id);
   if(found.isEmpty())throw error(404,"GROUP_JOIN_REQUEST_NOT_FOUND","待处理申请不存在");var row=found.getFirst();
   if(approved){notMember(id,(String)row.get("user_id"));capacity(group);addMember(id,conversation(id),(String)row.get("user_id"),(String)row.get("source"),"member");}
   db.update("UPDATE group_join_requests SET status=? WHERE id=?",approved?"approved":"rejected",request);return request(request);
  });
 }
 public Map<String,Object> role(AuthDtos.Principal actor,String id,String target,String role){return tx.execute(status->{lockUsers(actor,List.of(target));activeGroup(id,true);owner(id,actor.userId());
  if(db.update("UPDATE group_members SET role=? WHERE group_id=? AND user_id=? AND role<>'owner'",role,id,target)==0)throw error(404,"GROUP_MEMBER_NOT_FOUND","目标成员不存在或不可修改");return members(actor.userId(),id).stream().filter(r->target.equals(r.get("user_id"))).findFirst().orElseThrow();});}
 public Map<String,String> remove(AuthDtos.Principal actor,String id,String target,boolean leaving){return tx.execute(status->{lockUsers(actor,List.of(target));activeGroup(id,true);String role=leaving?member(id,actor.userId()):manager(id,actor.userId());
  var found=db.queryForList("SELECT role FROM group_members WHERE group_id=? AND user_id=?",id,target);if(found.isEmpty())throw error(404,"GROUP_MEMBER_NOT_FOUND","群成员不存在");String other=(String)found.getFirst().get("role");
  if(other.equals("owner"))throw error(leaving?409:403,leaving?"GROUP_OWNER_ACTION_REQUIRED":"GROUP_MANAGE_FORBIDDEN","群主需要先转让或解散群组");
  if(!leaving&&role.equals("admin")&&other.equals("admin"))throw error(403,"GROUP_MANAGE_FORBIDDEN","管理员不能移除管理员");removeMembership(id,target);return Map.of("status",leaving?"left":"removed");});}
 private void removeMembership(String id,String user){
  String conversation=conversation(id);db.update("DELETE FROM group_members WHERE group_id=? AND user_id=?",id,user);
  db.update("UPDATE conversation_member_states SET active=false,unread_count=0 WHERE conversation_id=? AND user_id=?",conversation,user);
  db.update("DELETE FROM message_receipts WHERE conversation_id=? AND user_id=?",conversation,user);
  db.update("UPDATE notification_events SET status='suppressed',delivery_channel='none',suppress_reason='group_left' WHERE conversation_id=? AND recipient_user_id=? AND status='pending'",conversation,user);
 }
 public Map<String,Object> transfer(AuthDtos.Principal actor,String id,String target){return tx.execute(status->{lockUsers(actor,List.of(target));activeGroup(id,true);owner(id,actor.userId());
  if(target.equals(actor.userId())||db.queryForObject("SELECT count(*) FROM group_members WHERE group_id=? AND user_id=?",Integer.class,id,target)==0)throw error(404,"GROUP_MEMBER_NOT_FOUND","新群主必须是其他群成员");
  db.update("UPDATE group_members SET role='admin' WHERE group_id=? AND user_id=?",id,actor.userId());db.update("UPDATE group_members SET role='owner' WHERE group_id=? AND user_id=?",id,target);db.update("UPDATE groups SET owner_id=? WHERE id=?",target,id);return output(actor.userId(),id);});}
 public Map<String,String> dissolve(AuthDtos.Principal actor,String id){return tx.execute(status->{lockUsers(actor,List.of());activeGroup(id,true);owner(id,actor.userId());close(id);return Map.of("status","dissolved");});}
 private void close(String id){db.update("UPDATE groups SET dissolved_at=clock_timestamp(),join_secret_hash=NULL,join_question=NULL WHERE id=?",id);db.update("UPDATE conversation_member_states SET active=false,unread_count=0 WHERE conversation_id=?",conversation(id));db.update("UPDATE notification_events SET status='suppressed',delivery_channel='none',suppress_reason='group_dissolved' WHERE conversation_id=? AND status='pending'",conversation(id));}
 /** Called inside account erasure after locking the erased user. Preserve other members' conversation. */
 public void eraseMemberships(String user){
  for(var item:db.queryForList("SELECT group_id FROM group_members WHERE user_id=? ORDER BY group_id",user)){
   String id=(String)item.get("group_id");var group=db.queryForMap("SELECT * FROM groups WHERE id=? FOR UPDATE",id);db.queryForList("SELECT id FROM conversations WHERE group_id=? FOR UPDATE",id);
   if(user.equals(group.get("owner_id"))&&group.get("dissolved_at")==null){
    var next=db.queryForList("SELECT m.user_id FROM group_members m JOIN users u ON u.id=m.user_id WHERE m.group_id=? AND m.user_id<>? AND u.status='active' ORDER BY m.created_at,m.user_id LIMIT 1",id,user);
    if(next.isEmpty())close(id);else{String successor=(String)next.getFirst().get("user_id");db.update("UPDATE group_members SET role='member' WHERE group_id=? AND user_id=?",id,user);db.update("UPDATE group_members SET role='owner' WHERE group_id=? AND user_id=?",id,successor);db.update("UPDATE groups SET owner_id=? WHERE id=?",successor,id);}
   }removeMembership(id,user);
  }
  db.update("DELETE FROM group_join_requests WHERE user_id=?",user);
 }
 public Map<String,Object> share(String actor,String id){activeGroup(id,false);member(id,actor);return publicLink(db.queryForObject("SELECT link_code FROM groups WHERE id=?",String.class,id));}
 public Map<String,Object> publicLink(String code){
  if(!code.matches("[a-zA-Z0-9]{8,32}"))throw error(404,"GROUP_NOT_FOUND","群组不存在");
  var found=db.queryForList("SELECT id AS group_id,name,link_code,join_rule_type,CASE WHEN join_rule_type='question' THEN join_question ELSE NULL END AS join_question,(SELECT count(*) FROM group_members m WHERE m.group_id=g.id) AS member_count FROM groups g WHERE lower(link_code)=lower(?) AND dissolved_at IS NULL",code);
  if(found.isEmpty())throw error(404,"GROUP_NOT_FOUND","群组不存在或已解散");
  return rows.output(found.getFirst());
 }
 private void admit(String user){int hits=db.queryForObject("""
  INSERT INTO auth_rate_windows(bucket_key,hits,expires_at) VALUES(?,1,now()+interval '1 minute')
  ON CONFLICT(bucket_key) DO UPDATE SET hits=CASE WHEN auth_rate_windows.expires_at<=now() THEN 1 ELSE auth_rate_windows.hits+1 END,
  expires_at=CASE WHEN auth_rate_windows.expires_at<=now() THEN now()+interval '1 minute' ELSE auth_rate_windows.expires_at END RETURNING hits
  """,Integer.class,"group-write:"+user);if(hits>30)throw error(429,"AUTH_RATE_LIMITED","群操作过于频繁");}
 private static ApiError error(int status,String code,String message){return new ApiError(status,code,message);}
 private static String id(String prefix){return prefix+"_"+UUID.randomUUID().toString().replace("-","");}
}
