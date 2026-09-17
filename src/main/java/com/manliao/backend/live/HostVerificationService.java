package com.manliao.backend.live;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import com.manliao.backend.admin.*;
import com.manliao.backend.common.*;
import com.manliao.backend.identity.AuthDtos.Principal;
import com.manliao.backend.billing.*;
import com.manliao.backend.notifications.OfficialNotifications;
@Service
public class HostVerificationService {
 private final JdbcTemplate db;private final TransactionTemplate tx;private final AdminService admin;private final WalletService users;private final LedgerService ledger;private final DatabaseRows rows;private final OfficialNotifications notifications;private final byte[] secret;
 @Value("${app.live.host-verification-required:true}") private boolean required;
 @Value("${app.live.host-agreement-version:2026-07}") private String agreement;
 public HostVerificationService(JdbcTemplate db,TransactionTemplate tx,AdminService admin,WalletService users,LedgerService ledger,DatabaseRows rows,OfficialNotifications notifications,@Value("${app.auth.jwt-secret}") String secret){this.db=db;this.tx=tx;this.admin=admin;this.users=users;this.ledger=ledger;this.rows=rows;this.notifications=notifications;this.secret=secret.getBytes(StandardCharsets.UTF_8);}
 private static ApiError state(String text){return new ApiError(409,"LIVE_HOST_VERIFICATION_STATE_INVALID",text);}
 private void init(String user){db.update("INSERT INTO live_host_qualifications(user_id) VALUES(?) ON CONFLICT DO NOTHING",user);}
 private Map<String,Object> application(Map<String,Object> row){var out=rows.output(row);out.remove("id_number_hash");out.remove("agreement_accepted_at");out.put("date_of_birth",((java.sql.Date)row.get("date_of_birth")).toLocalDate());return out;}
 private Map<String,Object> overview(String user){init(user);var q=rows.output(db.queryForMap("SELECT * FROM live_host_qualifications WHERE user_id=?",user));q.put("enforcement_required",required);q.put("agreement_version",agreement);return Map.of("qualification",q,"applications",db.queryForList("SELECT * FROM live_host_verification_applications WHERE user_id=? ORDER BY submitted_at DESC,id DESC",user).stream().map(this::application).toList());}
 public Map<String,Object> me(Principal actor){return tx.execute(s->{users.lock(actor);return overview(actor.userId());});}
 private String hash(String id){try{var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(secret,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(id.getBytes(StandardCharsets.UTF_8)));}catch(java.security.GeneralSecurityException e){throw new IllegalStateException(e);}}
 public Map<String,Object> submit(Principal actor,HostVerificationController.Submit input){
  if(!input.agreement_accepted())throw new ApiError(400,"LIVE_HOST_VERIFICATION_STATE_INVALID","请先同意主播协议");LocalDate now=LocalDate.now(ZoneOffset.UTC);int age=Period.between(input.date_of_birth(),now).getYears();if(input.date_of_birth().isAfter(now))throw new ApiError(400,"LIVE_HOST_AGE_NOT_ELIGIBLE","出生日期不能晚于今天");if(age<16)throw new ApiError(403,"LIVE_HOST_AGE_NOT_ELIGIBLE","未满16周岁不能申请成为主播");if(age<18&&!input.guardian_consent())throw new ApiError(400,"LIVE_HOST_GUARDIAN_CONSENT_REQUIRED","需要监护人同意");
  String name=input.legal_name().strip(),id=input.id_number().replaceAll("\\s","").toUpperCase(Locale.ROOT);if(name.codePointCount(0,name.length())<2||id.length()<8)throw new ApiError(422,"COMMON_VALIDATION_ERROR","姓名或证件格式无效");
  String masked=name.substring(0,name.offsetByCodePoints(0,1))+"*".repeat(name.codePointCount(0,name.length())-1),maskedId=id.substring(0,3)+"*".repeat(id.length()-7)+id.substring(id.length()-4);
  return tx.execute(s->{users.lock(actor);init(actor.userId());var qualification=db.queryForMap("SELECT * FROM live_host_qualifications WHERE user_id=?",actor.userId());
   if("suspended".equals(qualification.get("host_permission_status"))){var until=(Timestamp)qualification.get("restriction_until");if(until==null||until.toInstant().isAfter(Instant.now()))throw state("开播权限暂停期间不能重新申请");}
   if(db.queryForObject("SELECT count(*) FROM live_host_verification_applications WHERE user_id=? AND status='pending_review'",Integer.class,actor.userId())>0)throw state("已有申请正在审核");
   String app="hostverify_"+UUID.randomUUID().toString().replace("-","");db.update("INSERT INTO live_host_verification_applications(id,user_id,legal_name_masked,id_number_hash,id_number_masked,date_of_birth,age_at_submission,guardian_consent,agreement_version) VALUES(?,?,?,?,?,?,?,?,?)",app,actor.userId(),masked,hash(id),maskedId,java.sql.Date.valueOf(input.date_of_birth()),age,input.guardian_consent(),agreement);
   db.update("UPDATE live_host_qualifications SET identity_status='pending',host_permission_status='pending_review',can_receive_gifts=false,current_application_id=?,restriction_until=NULL,restriction_reason=NULL,updated_at=clock_timestamp() WHERE user_id=?",app,actor.userId());return overview(actor.userId());
  });
 }
 public List<Map<String,Object>> list(AdminDtos.Principal actor,String status,String user){admin.require(actor,"host_verification.read",false);return db.queryForList("SELECT * FROM live_host_verification_applications WHERE (?::text IS NULL OR status=?) AND (?::text IS NULL OR user_id=?) ORDER BY submitted_at DESC,id DESC",status,status,user,user).stream().map(this::application).toList();}
 public Map<String,Object> resolve(AdminDtos.Principal actor,String id,HostVerificationController.Resolve input,String request){
  String reason=input.reason()==null?null:input.reason().strip();if(input.decision().equals("rejected")&&(reason==null||reason.isBlank()))throw new ApiError(400,"LIVE_HOST_VERIFICATION_STATE_INVALID","拒绝申请必须填写原因");
  return tx.execute(s->{admin.require(actor,"host_verification.write",true);var found=db.queryForList("SELECT user_id FROM live_host_verification_applications WHERE id=?",id);if(found.isEmpty())throw new ApiError(404,"LIVE_HOST_VERIFICATION_NOT_FOUND","实名认证申请不存在");String user=(String)found.getFirst().get("user_id");ledger.lockUsers(List.of(user));var app=db.queryForMap("SELECT * FROM live_host_verification_applications WHERE id=? FOR UPDATE",id);init(user);var q=db.queryForMap("SELECT * FROM live_host_qualifications WHERE user_id=?",user);
   if(!"pending_review".equals(app.get("status"))||!id.equals(q.get("current_application_id")))throw state("申请已经审核或失效");boolean suspended="suspended".equals(q.get("host_permission_status"));boolean approved=input.decision().equals("approved");
   db.update("UPDATE live_host_verification_applications SET status=?,rejection_reason=?,reviewer_id=?,reviewed_at=clock_timestamp() WHERE id=?",input.decision(),reason,actor.id(),id);
   db.update("UPDATE live_host_qualifications SET identity_status=?,host_permission_status=?,can_receive_gifts=?,last_verified_at=CASE WHEN ? THEN clock_timestamp() ELSE last_verified_at END,restriction_until=CASE WHEN ? THEN restriction_until ELSE NULL END,restriction_reason=CASE WHEN ? THEN restriction_reason ELSE NULL END,updated_at=clock_timestamp() WHERE user_id=?",approved?"verified":"rejected",suspended?"suspended":approved?"enabled":"not_applied",!suspended&&approved&&input.allow_gifts(),approved,suspended,suspended,user);
   notifications.event(user,"host_verification_reviewed","host_verification",id,approved?"主播认证已通过":"主播认证未通过",approved?(suspended?"认证审核通过，开播权限仍处于暂停状态":"你已取得开播资格"):reason,Map.of("decision",input.decision()));var details=new LinkedHashMap<String,Object>();details.put("decision",input.decision());details.put("allow_gifts",input.allow_gifts());details.put("reason",reason);admin.audit(actor.id(),"live_host_verification.resolve","host_verification",id,details,request);return overview(user);
  });
 }
 public Map<String,Object> suspend(AdminDtos.Principal actor,String user,HostVerificationController.Suspend input,String request){return tx.execute(s->{admin.require(actor,"host_verification.write",true);ledger.lockUsers(List.of(user));init(user);if(input.restriction_until()!=null&&!input.restriction_until().isAfter(Instant.now()))throw new ApiError(422,"COMMON_VALIDATION_ERROR","暂停截止时间必须在未来");db.update("UPDATE live_host_qualifications SET host_permission_status='suspended',can_receive_gifts=false,restriction_until=?,restriction_reason=?,updated_at=clock_timestamp() WHERE user_id=?",input.restriction_until()==null?null:Timestamp.from(input.restriction_until()),input.reason().strip(),user);notifications.event(user,"host_permission_suspended","user",user,"开播权限已暂停",input.reason().strip(),Map.of());var details=new LinkedHashMap<String,Object>();details.put("reason",input.reason().strip());details.put("restriction_until",input.restriction_until());admin.audit(actor.id(),"live_host_permission.suspend","user",user,details,request);return overview(user);});}
 public Map<String,Object> restore(AdminDtos.Principal actor,String user,String request){return tx.execute(s->{admin.require(actor,"host_verification.write",true);ledger.lockUsers(List.of(user));init(user);var q=db.queryForMap("SELECT * FROM live_host_qualifications WHERE user_id=?",user);boolean verified="verified".equals(q.get("identity_status"));if(!verified&&!"suspended".equals(q.get("host_permission_status")))throw state("用户尚未通过实名认证");String permission=verified?"enabled":"pending".equals(q.get("identity_status"))?"pending_review":"not_applied";db.update("UPDATE live_host_qualifications SET host_permission_status=?,restriction_until=NULL,restriction_reason=NULL,updated_at=clock_timestamp() WHERE user_id=?",permission,user);admin.audit(actor.id(),"live_host_permission.restore","user",user,Map.of(),request);return overview(user);});}
 /** Called while the host user is locked by a room/gift transaction. */
 public void assertCanStart(String user){
  if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Host check requires transaction");ledger.lockUsers(List.of(user));init(user);var q=db.queryForMap("SELECT * FROM live_host_qualifications WHERE user_id=?",user);
  if("suspended".equals(q.get("host_permission_status"))){var until=(Timestamp)q.get("restriction_until");if(until==null||until.toInstant().isAfter(Instant.now()))throw new ApiError(403,"LIVE_HOST_PERMISSION_SUSPENDED","开播权限已暂停");if("verified".equals(q.get("identity_status"))){db.update("UPDATE live_host_qualifications SET host_permission_status='enabled',restriction_until=NULL,restriction_reason=NULL,updated_at=clock_timestamp() WHERE user_id=?",user);q.put("host_permission_status","enabled");}}
  if(required&&(!"verified".equals(q.get("identity_status"))||!"enabled".equals(q.get("host_permission_status"))))throw new ApiError(403,"LIVE_HOST_VERIFICATION_REQUIRED","开始直播前需要完成主播审核");
 }
 public void assertCanReceive(String user){assertCanStart(user);var q=db.queryForMap("SELECT * FROM live_host_qualifications WHERE user_id=?",user);if(!"verified".equals(q.get("identity_status"))||!"enabled".equals(q.get("host_permission_status"))||!Boolean.TRUE.equals(q.get("can_receive_gifts")))throw new ApiError(403,"LIVE_HOST_GIFT_PERMISSION_REQUIRED","主播尚未取得收礼资格");}
}
