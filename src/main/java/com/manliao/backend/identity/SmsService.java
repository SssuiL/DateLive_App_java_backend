package com.manliao.backend.identity;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.manliao.backend.common.ApiError;

@Service
public class SmsService {
 private final JdbcTemplate db;
 private final TransactionTemplate tx;
 private final AuthService auth;
 private final PasswordHasher passwords;
 private final SmsGateway gateway;
 private final byte[] secret;
 private final SecureRandom random=new SecureRandom();
 public SmsService(JdbcTemplate db,TransactionTemplate tx,AuthService auth,PasswordHasher passwords,
       SmsGateway gateway,@Value("${app.sms.code-secret:}") String secret) {
   this.db=db; this.tx=tx; this.auth=auth; this.passwords=passwords; this.gateway=gateway;
   this.secret=secret.getBytes(StandardCharsets.UTF_8);
   if (!gateway.provider().equals("disabled") && this.secret.length<32)
     throw new IllegalArgumentException("SMS code secret must contain at least 32 bytes");
 }
 public SmsDtos.Dispatch send(SmsDtos.Send input) {
   gateway.requireAvailable();
   String phone=auth.normalizePhone(input.phone()), purpose=input.purpose();
   String id=auth.id("otp"), code=String.format(Locale.ROOT,"%06d",random.nextInt(1000000));
   tx.executeWithoutResult(status -> {
     db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","account-phone:"+phone);
     // Serialize requests for this phone and purpose, without holding a connection during delivery.
     db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",phone+":"+purpose);
     if (purpose.equals("register") && auth.one("SELECT id FROM users WHERE phone=?",phone)!=null)
       throw new ApiError(409,"PHONE_ALREADY_REGISTERED","手机号已注册");
     var quota=auth.one("""
       SELECT count(*) AS total, count(*) FILTER(WHERE created_at>now()-interval '60 seconds') AS recent,
       COALESCE(ceil(extract(epoch FROM max(created_at)+interval '60 seconds'-now())),0)::int AS retry,
       COALESCE(ceil(extract(epoch FROM min(created_at)+interval '1 hour'-now())),0)::int AS hourly_retry
       FROM auth_verification_codes WHERE phone=? AND purpose=? AND created_at>now()-interval '1 hour'
       """,phone,purpose);
     if (((Number)quota.get("recent")).longValue()>0 || ((Number)quota.get("total")).longValue()>=10)
       throw new ApiError(429,"AUTH_CODE_RATE_LIMITED","验证码请求过于频繁，请稍后重试",
         Map.of("retry_after_seconds",Math.max(1,((Number)quota.get(((Number)quota.get("total")).longValue()>=10 ? "hourly_retry" : "retry")).intValue())));
     db.update("""
       INSERT INTO auth_verification_codes(id,phone,purpose,code_hash,delivery_state,expires_at)
       VALUES (?,?,?,?,'pending',now()+interval '5 minutes')
       """,id,phone,purpose,digest(id,phone,purpose,code));
   });
   String messageId;
   try { messageId=gateway.send(phone,code,purpose); }
   catch (RuntimeException failure) {
     db.update("UPDATE auth_verification_codes SET delivery_state='failed' WHERE id=?",id);
     throw new ApiError(503,"AUTH_SMS_PROVIDER_UNAVAILABLE","短信暂时无法发送");
   }
   tx.executeWithoutResult(status -> {
     db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","account-phone:"+phone);
     db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",phone+":"+purpose);
     // A slower, older delivery must never supersede a newer reserved request.
     if (auth.one("""
       SELECT id FROM auth_verification_codes WHERE phone=? AND purpose=?
       AND created_at>(SELECT created_at FROM auth_verification_codes WHERE id=?) LIMIT 1
       """,phone,purpose,id)!=null) {
       db.update("UPDATE auth_verification_codes SET delivery_state='failed' WHERE id=?",id);
       return;
     }
     db.update("""
       UPDATE auth_verification_codes SET consumed_at=now()
       WHERE phone=? AND purpose=? AND id<>? AND consumed_at IS NULL
       """,phone,purpose,id);
     db.update("""
       UPDATE auth_verification_codes SET delivery_state='sent',provider=?,provider_message_id=? WHERE id=?
       """,gateway.provider(),messageId,id);
   });
   var sent=auth.one("SELECT id FROM auth_verification_codes WHERE id=? AND delivery_state='sent'",id);
   if (sent==null) throw new ApiError(400,"AUTH_CODE_INVALID","请使用最新验证码");
   return new SmsDtos.Dispatch(id,300,60,gateway.visibleCode(code));
 }
 public AuthDtos.Tokens register(SmsDtos.Register input,String requestId) {
   String phone=auth.normalizePhone(input.phone());
   String hash=passwords.hash(input.password());
   return consume(input.requestId(),phone,"register",input.code(),()->{
     String userId=auth.id("user");
     if (db.update("INSERT INTO users(id,phone,nickname,password_hash) VALUES(?,?,?,?) ON CONFLICT(phone) DO NOTHING",
            userId,phone,input.nickname().strip(),hash)==0)
       throw new ApiError(409,"PHONE_ALREADY_REGISTERED","手机号已注册");
     db.update("INSERT INTO user_profiles(user_id,nickname) VALUES(?,?)",userId,input.nickname().strip());
     var result=auth.issue(userId,input.deviceId(),input.deviceName(),input.platform());
     auth.audit(userId,result.sessionId(),"verified_registration","success",null,requestId);
     return result;
   },requestId);
 }
 public AuthDtos.Tokens login(SmsDtos.Login input,String requestId) {
   String phone=auth.normalizePhone(input.phone());
   return consume(input.requestId(),phone,"login",input.code(),()->{
     var user=activeUser(phone);
     var result=auth.issue((String)user.get("id"),input.deviceId(),input.deviceName(),input.platform());
     auth.audit(result.userId(),result.sessionId(),"sms_login","success",null,requestId);
     return result;
   },requestId);
 }
 public Map<String,String> reset(SmsDtos.Reset input,String requestId) {
   String phone=auth.normalizePhone(input.phone());
   String hash=passwords.hash(input.newPassword());
   return consume(input.requestId(),phone,"password_reset",input.code(),()->{
     var user=activeUser(phone);
     db.update("UPDATE users SET password_hash=?,updated_at=now() WHERE id=?",hash,user.get("id"));
     db.update("""
       UPDATE refresh_tokens SET revoked_at=now(),revoked_reason='password_reset'
       WHERE user_id=? AND revoked_at IS NULL
       """,user.get("id"));
     auth.audit((String)user.get("id"),null,"password_reset","success",null,requestId);
     return Map.of("status","ok","message","密码已重置，请重新登录");
   },requestId);
 }
 private Map<String,Object> activeUser(String phone) {
   var user=auth.one("SELECT id,status FROM users WHERE phone=? FOR UPDATE",phone);
   if(user==null) throw new ApiError(404,"PHONE_NOT_REGISTERED","手机号尚未注册");
   auth.requireUser(user);
   return user;
 }
 private record Result<T>(T value,ApiError error) {}
 private <T> T consume(String id,String phone,String purpose,String code,Supplier<T> operation,String requestId) {
   gateway.requireAvailable();
   Result<T> result=tx.execute(status->{
     db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","account-phone:"+phone);
     db.queryForList("SELECT id FROM users WHERE phone=? FOR UPDATE",phone);
     var row=auth.one("SELECT *,expires_at<=now() AS expired FROM auth_verification_codes WHERE id=? FOR UPDATE",id);
     ApiError failure=null;
     if(row==null || !phone.equals(row.get("phone")) || !purpose.equals(row.get("purpose"))
          || row.get("consumed_at")!=null || !"sent".equals(row.get("delivery_state")))
       failure=new ApiError(400,"AUTH_CODE_INVALID","验证码无效");
     else if(Boolean.TRUE.equals(row.get("expired"))) {
       db.update("UPDATE auth_verification_codes SET consumed_at=now() WHERE id=?",id);
       failure=new ApiError(400,"AUTH_CODE_EXPIRED","验证码已过期");
     } else if(((Number)row.get("attempts")).intValue()>=5)
       failure=new ApiError(429,"AUTH_CODE_ATTEMPTS_EXCEEDED","验证码错误次数过多");
     else if(!MessageDigest.isEqual(((String)row.get("code_hash")).getBytes(StandardCharsets.US_ASCII),
           digest(id,phone,purpose,code).getBytes(StandardCharsets.US_ASCII))) {
       int attempts=((Number)row.get("attempts")).intValue()+1;
       db.update("UPDATE auth_verification_codes SET attempts=? WHERE id=?",attempts,id);
       failure=attempts>=5 ? new ApiError(429,"AUTH_CODE_ATTEMPTS_EXCEEDED","验证码错误次数过多")
          : new ApiError(400,"AUTH_CODE_INVALID","验证码错误",Map.of("remaining_attempts",5-attempts));
     }
     if(failure!=null) {
       auth.audit(null,null,"sms_"+purpose,"failure",failure.code(),requestId);
       // Return errors out of the transaction so failed attempts are committed.
       return new Result<T>(null,failure);
     }
     db.update("UPDATE auth_verification_codes SET consumed_at=now() WHERE id=?",id);
     return new Result<T>(operation.get(),null);
   });
   if(result.error()!=null) throw result.error();
   return result.value();
 }
 private String digest(String id,String phone,String purpose,String code) {
   try {
     Mac mac=Mac.getInstance("HmacSHA256");
     mac.init(new SecretKeySpec(secret,"HmacSHA256"));
     return HexFormat.of().formatHex(mac.doFinal((id+":"+phone+":"+purpose+":"+code).getBytes(StandardCharsets.UTF_8)));
   } catch(GeneralSecurityException error) { throw new IllegalStateException("SMS digest unavailable"); }
 }
}
