package com.manliao.backend.admin;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import com.manliao.backend.common.*;
import com.manliao.backend.identity.PasswordHasher;
@Service
public class AdminService {
 private final JdbcTemplate db;private final TransactionTemplate tx;private final PasswordHasher passwords;
 private final AdminTokens tokens;private final DatabaseRows rows;private final ObjectMapper json;private final String dummy;
 public AdminService(JdbcTemplate db,TransactionTemplate tx,PasswordHasher passwords,AdminTokens tokens,DatabaseRows rows,ObjectMapper json){
   this.db=db;this.tx=tx;this.passwords=passwords;this.tokens=tokens;this.rows=rows;this.json=json;dummy=passwords.hash(UUID.randomUUID().toString());
 }
 public Set<String> permissions(String role) {
   return switch(role){
     case "owner"->Set.of("admin.manage","moderation.read","moderation.write","operation_logs.read","users.erase","billing.read","payments.read","live_preflight.read","host_verification.read","host_verification.write","gifts.read","gifts.write","gift_risk.read","gift_risk.write");
     case "auditor"->Set.of("moderation.read","moderation.write","billing.read","payments.read","live_preflight.read","host_verification.read","host_verification.write","gifts.read","gift_risk.read");
     case "operator"->Set.of("billing.read","payments.read","live_preflight.read","host_verification.read","host_verification.write","gifts.read","gifts.write","gift_risk.read","gift_risk.write");
     default->Set.of();
   };
 }
 public AdminDtos.Principal principal(Map<String,Object> row) {
   return new AdminDtos.Principal((String)row.get("id"),((Number)row.get("token_version")).longValue(),tokens.fingerprint((String)row.get("password_hash")));
 }
 public AdminDtos.Principal authenticate(String token) {
   var principal=tokens.decode(token,"admin_access").principal();require(principal,null,false);return principal;
 }
 public Map<String,Object> require(AdminDtos.Principal actor,String permission,boolean lock) {
   if(actor==null)throw new ApiError(401,"AUTH_MISSING_TOKEN","缺少管理员凭证");
   var row=one("SELECT * FROM admin_users WHERE id=?"+(lock?" FOR UPDATE":""),actor.id());
   if(row==null || !row.get("status").equals("active"))
     throw new ApiError(403,"MODERATION_ADMIN_FORBIDDEN","管理员账号不可用");
   if(((Number)row.get("token_version")).longValue()!=actor.version() ||
       !java.security.MessageDigest.isEqual(tokens.fingerprint((String)row.get("password_hash")).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
          actor.fingerprint().getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
     throw new ApiError(401,"AUTH_INVALID_TOKEN","管理员凭证已失效");
   if(permission!=null && !permissions((String)row.get("role")).contains(permission))
     throw new ApiError(403,"MODERATION_ADMIN_FORBIDDEN","当前管理员没有操作权限",Map.of("permission",permission));
   return row;
 }
 public Map<String,Object> login(AdminDtos.Login input,String request) {
   var row=one("SELECT * FROM admin_users WHERE username=?",input.username().strip());
   boolean valid=passwords.matches(input.password(),row==null?dummy:(String)row.get("password_hash"));
   if(row==null || !valid || !row.get("status").equals("active")){
     audit(null,"admin.login.failed",null,null,Map.of("code","AUTH_INVALID_CREDENTIALS"),request);
     throw new ApiError(401,"AUTH_INVALID_CREDENTIALS","管理员账号或密码错误");
   }
   return tx.execute(status->{
     var current=one("SELECT * FROM admin_users WHERE id=? FOR UPDATE",row.get("id"));
     if(current==null || !current.get("status").equals("active") || !Objects.equals(current.get("password_hash"),row.get("password_hash")))
       throw new ApiError(401,"AUTH_INVALID_CREDENTIALS","管理员账号或密码已变更");
     db.update("UPDATE admin_users SET last_login_at=now() WHERE id=?",row.get("id"));
     audit((String)row.get("id"),"admin.login","admin_user",(String)row.get("id"),Map.of(),request);
     current=one("SELECT * FROM admin_users WHERE id=?",row.get("id"));
     return Map.of("access_token",tokens.sign(principal(current),"admin_access",null,Instant.now().plusSeconds(1800)),
       "token_type","bearer","admin",output(current));
   });
 }
 public Map<String,Object> me(AdminDtos.Principal actor){return output(require(actor,null,false));}
 public void logout(AdminDtos.Principal actor,String request){
   tx.executeWithoutResult(status->{
     require(actor,null,true);
     db.update("UPDATE admin_users SET token_version=token_version+1,updated_at=now() WHERE id=?",actor.id());
     audit(actor.id(),"admin.logout","admin_user",actor.id(),Map.of("scope","all_admin_tokens"),request);
   });
 }
 public Map<String,Object> ownPassword(AdminDtos.Principal actor,AdminDtos.SelfPassword input,String request){
   var current=require(actor,null,false);
   if(!passwords.matches(input.current_password(),(String)current.get("password_hash")))
     throw new ApiError(401,"AUTH_INVALID_CREDENTIALS","当前密码错误");
   if(input.current_password().equals(input.new_password()))throw new ApiError(400,"COMMON_VALIDATION_ERROR","新密码不能与当前密码相同");
   String hash=passwords.hash(input.new_password());
   return tx.execute(status->{
     serializeManagement();require(actor,null,true);
     updatePassword(actor.id(),hash);
     audit(actor.id(),"admin.password.self_update","admin_user",actor.id(),Map.of(),request);
     return output(one("SELECT * FROM admin_users WHERE id=?",actor.id()));
   });
 }
 public Map<String,Object> create(AdminDtos.Principal actor,AdminDtos.Create input,String request){
   require(actor,"admin.manage",false);
   String hash=passwords.hash(input.password());
   return tx.execute(status->{
     serializeManagement();require(actor,"admin.manage",true);
     return insert(input,hash,actor.id(),request);
   });
 }
 public void bootstrap(AdminDtos.Create input){
   String hash=passwords.hash(input.password());
   tx.executeWithoutResult(status->{
     serializeManagement();
     if(db.queryForObject("SELECT count(*) FROM admin_users",Integer.class)==0)insert(input,hash,null,"local_bootstrap");
   });
 }
 private Map<String,Object> insert(AdminDtos.Create input,String hash,String actor,String request){
   String id="admin_"+UUID.randomUUID().toString().replace("-","");
   if(db.update("""
     INSERT INTO admin_users(id,username,display_name,password_hash,role) VALUES(?,?,?,?,?)
     ON CONFLICT(username) DO NOTHING
     """,id,input.username().strip(),input.display_name(),hash,input.role())==0)
     throw new ApiError(409,"COMMON_CONFLICT","管理员用户名已存在");
   audit(actor==null?id:actor,actor==null?"admin.bootstrap":"admin.create","admin_user",id,Map.of("role",input.role()),request);
   return output(one("SELECT * FROM admin_users WHERE id=?",id));
 }
 public List<Map<String,Object>> accounts(AdminDtos.Principal actor,String keyword,String status,String role){
   require(actor,"admin.manage",false);
   return db.queryForList("""
     SELECT * FROM admin_users WHERE (?::text IS NULL OR position(? in username)>0 OR position(? in display_name)>0)
     AND (?::text IS NULL OR status=?) AND (?::text IS NULL OR role=?)
     ORDER BY created_at DESC,id DESC LIMIT 100
     """,keyword,keyword,keyword,status,status,role,role).stream().map(this::output).toList();
 }
 public Map<String,Object> changeStatus(AdminDtos.Principal actor,String id,AdminDtos.Status input,String request){
   if(id.equals(actor.id()) && input.status().equals("disabled"))throw new ApiError(400,"COMMON_BAD_REQUEST","不能禁用当前管理员");
   if(input.status().equals("disabled") && (input.reason()==null || input.reason().isBlank()))
     throw new ApiError(400,"COMMON_VALIDATION_ERROR","禁用管理员必须填写原因");
   return tx.execute(status->{
     serializeManagement();require(actor,"admin.manage",true);
     var target=one("SELECT * FROM admin_users WHERE id=? FOR UPDATE",id);
     if(target==null)throw new ApiError(404,"COMMON_NOT_FOUND","管理员不存在");
     db.update("UPDATE admin_users SET status=?,token_version=token_version+1,updated_at=now() WHERE id=?",input.status(),id);
     audit(actor.id(),"admin.status.update","admin_user",id,Map.of("status",input.status(),"reason",Objects.toString(input.reason(),"")),request);
     return output(one("SELECT * FROM admin_users WHERE id=?",id));
   });
 }
 public Map<String,Object> resetPassword(AdminDtos.Principal actor,String id,AdminDtos.Password input,String request){
   require(actor,"admin.manage",false);String hash=passwords.hash(input.new_password());
   return tx.execute(status->{
     serializeManagement();require(actor,"admin.manage",true);
     if(one("SELECT id FROM admin_users WHERE id=? FOR UPDATE",id)==null)throw new ApiError(404,"COMMON_NOT_FOUND","管理员不存在");
     updatePassword(id,hash);audit(actor.id(),"admin.password.update","admin_user",id,Map.of(),request);
     return output(one("SELECT * FROM admin_users WHERE id=?",id));
   });
 }
 private void updatePassword(String id,String hash){db.update("UPDATE admin_users SET password_hash=?,token_version=token_version+1,updated_at=now() WHERE id=?",hash,id);}
 private void serializeManagement(){db.queryForList("SELECT pg_advisory_xact_lock(78452910)");}
 public List<Map<String,Object>> logs(AdminDtos.Principal actor,String admin,String action,String type,String target){
   require(actor,"operation_logs.read",false);
   return db.queryForList("""
     SELECT id,admin_user_id,action,target_type,target_id,details,created_at FROM admin_operation_logs
     WHERE (?::text IS NULL OR admin_user_id=?) AND (?::text IS NULL OR action=?)
       AND (?::text IS NULL OR target_type=?) AND (?::text IS NULL OR target_id=?)
     ORDER BY created_at DESC,id DESC LIMIT 100
     """,admin,admin,action,action,type,type,target,target).stream().map(row->rows.output(row,"details")).toList();
 }
 public void audit(String actor,String action,String type,String target,Map<String,Object> details,String request){
   db.update("INSERT INTO admin_operation_logs(id,admin_user_id,action,target_type,target_id,details,request_id) VALUES(?,?,?,?,?,?::jsonb,?)",
     "op_"+UUID.randomUUID().toString().replace("-",""),actor,action,type,target,json.writeValueAsString(details),request);
 }
 private Map<String,Object> output(Map<String,Object> row){
   var out=rows.output(row);out.remove("password_hash");out.remove("token_version");out.remove("updated_at");
   out.put("permissions",permissions((String)row.get("role")).stream().sorted().toList());return out;
 }
 private Map<String,Object> one(String sql,Object...args){var list=db.queryForList(sql,args);return list.isEmpty()?null:list.getFirst();}
}
