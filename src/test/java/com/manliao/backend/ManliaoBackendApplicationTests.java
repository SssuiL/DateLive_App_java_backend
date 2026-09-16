package com.manliao.backend;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"app.realtime.worker-enabled=false",
 "app.auth.jwt-secret=isolated-java-test-secret-at-least-thirty-two-bytes",
 "app.auth.legacy-registration-enabled=true",
 "app.sms.provider=development","app.sms.return-dev-code=true",
 "app.sms.code-secret=isolated-sms-test-secret-more-than-thirty-two-bytes"})
class ManliaoBackendApplicationTests {
 @Value("${local.server.port}") int port;
 @Autowired JdbcTemplate db;
 @Autowired ObjectMapper mapper;
 final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
 static final String PASSWORD="Test-password-中文123";
 record Reply(int status,JsonNode json,HttpHeaders headers,String raw) {
   String text(String key) { return json.get(key).asString(); }
 }
 @BeforeEach void clean() { db.execute("TRUNCATE users,auth_rate_windows,auth_verification_codes RESTART IDENTITY CASCADE"); }

 @Test void registrationLoginAndCurrentUserContract() throws Exception {
   Reply r=register("13910000001");
   assertThat(r.status()).isEqualTo(200);
   assertThat(r.text("token_type")).isEqualTo("bearer");
   Reply me=call("GET","/auth/me",null,r.text("access_token"));
   assertThat(me.status()).isEqualTo(200);
   assertThat(me.text("phone")).isEqualTo("13910000001");
   assertThat(me.text("nickname")).isEqualTo("Java测试");
   assertThat(me.json().has("password_hash")).isFalse();
   assertThat(me.text("created_at")).contains("T");
   assertThat(me.headers().firstValue("X-Request-ID")).contains("test_request");
   assertThat(count("user_profiles")).isEqualTo(1);
   assertThat(db.queryForObject("SELECT password_hash FROM users",String.class)).startsWith("pbkdf2_sha256$600000$");
   assertThat(db.queryForObject("SELECT token_hash FROM refresh_tokens",String.class)).isNotEqualTo(r.text("refresh_token"));
   Reply login=call("POST","/auth/login",Map.of("phone","13910000001","password",PASSWORD),null);
   assertThat(login.status()).isEqualTo(200);
   Reply sessions=call("GET","/auth/sessions",null,login.text("access_token"));
   assertThat(sessions.status()).isEqualTo(200);
   assertThat(sessions.json().size()).isEqualTo(2);
   assertThat(sessions.raw()).doesNotContain("token_hash","refresh_token");
 }
 @Test void duplicateRegistrationIsAtomicUnderConcurrency() throws Exception {
   try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
     List<Callable<Reply>> tasks=new ArrayList<>();
     for(int i=0;i<4;i++) tasks.add(()->register("13910000002"));
     List<Integer> statuses=new ArrayList<>();
     for(var future:pool.invokeAll(tasks)) statuses.add(future.get().status());
     assertThat(statuses).containsExactlyInAnyOrder(200,409,409,409);
   }
   assertThat(count("users")).isEqualTo(1);
   assertThat(count("user_profiles")).isEqualTo(1);
   assertThat(count("refresh_tokens")).isEqualTo(1);
 }
 @Test void onlyOneConcurrentRefreshWins() throws Exception {
   Reply r=register("13910000003");
   var body=Map.of("refresh_token",r.text("refresh_token"));
   List<Reply> replies=new ArrayList<>();
   try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
     List<Callable<Reply>> tasks=List.of(
       ()->call("POST","/auth/refresh",body,null),
       ()->call("POST","/auth/refresh",body,null));
     for(var future:pool.invokeAll(tasks)) replies.add(future.get());
   }
   assertThat(replies.stream().map(Reply::status).toList()).containsExactlyInAnyOrder(200,401);
   Reply winner=replies.stream().filter(v->v.status()==200).findFirst().orElseThrow();
   assertThat(call("GET","/auth/me",null,r.text("access_token")).status()).isEqualTo(401);
   assertThat(call("GET","/auth/me",null,winner.text("access_token")).status()).isEqualTo(200);
   assertThat(db.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL",Integer.class)).isEqualTo(1);
 }
 @Test void logoutIsIdempotentAndInvalidatesBothTokens() throws Exception {
   Reply r=register("13910000004");
   var body=Map.of("refresh_token",r.text("refresh_token"));
   assertThat(call("POST","/auth/logout",body,null).status()).isEqualTo(200);
   assertThat(call("POST","/auth/logout",body,null).status()).isEqualTo(200);
   assertThat(call("POST","/auth/refresh",body,null).status()).isEqualTo(401);
   assertThat(call("GET","/auth/me",null,r.text("access_token")).status()).isEqualTo(401);
 }
 @Test void bansApplyToAccessLoginAndRefresh() throws Exception {
   Reply r=register("13910000005");
   db.update("UPDATE users SET status='banned'");
   assertThat(call("GET","/auth/me",null,r.text("access_token")).status()).isEqualTo(403);
   assertThat(call("POST","/auth/login",Map.of("phone","13910000005","password",PASSWORD),null).status()).isEqualTo(403);
   assertThat(call("POST","/auth/refresh",Map.of("refresh_token",r.text("refresh_token")),null).status()).isEqualTo(403);
 }
 @Test void sessionsCannotBeRevokedByOtherUsers() throws Exception {
   Reply first=register("13910000006");
   Reply other=register("13910000007");
   assertThat(call("DELETE","/auth/sessions/"+other.text("session_id"),null,first.text("access_token")).status()).isEqualTo(404);
   assertThat(call("GET","/auth/me",null,other.text("access_token")).status()).isEqualTo(200);
   assertThat(call("DELETE","/auth/sessions/"+first.text("session_id"),null,first.text("access_token")).status()).isEqualTo(200);
   assertThat(call("GET","/auth/me",null,first.text("access_token")).status()).isEqualTo(401);
 }
 @Test void expiredRefreshMalformedBearerAndMissingBearer() throws Exception {
   Reply r=register("13910000008");
   db.update("UPDATE refresh_tokens SET expires_at=now()-interval '1 second'");
   Reply expired=call("POST","/auth/refresh",Map.of("refresh_token",r.text("refresh_token")),null);
   assertThat(expired.status()).isEqualTo(401);
   assertThat(expired.text("code")).isEqualTo("AUTH_REFRESH_TOKEN_EXPIRED");
   assertThat(call("GET","/auth/me",null,"malformed.token").status()).isEqualTo(401);
   Reply missing=call("GET","/auth/me",null,null);
   assertThat(missing.status()).isEqualTo(401);
   assertThat(missing.text("code")).isEqualTo("AUTH_MISSING_TOKEN");
 }
 @Test void validationNeverEchoesPasswords() throws Exception {
   String secret="sensitive-"+"x".repeat(130);
   Reply bad=call("POST","/auth/register",Map.of("phone","13910000009","nickname","昵称","password",secret),null);
   assertThat(bad.status()).isEqualTo(422);
   assertThat(bad.raw()).doesNotContain(secret);
   register("13910000009");
   Reply wrong=call("POST","/auth/login",Map.of("phone","13910000009","password","incorrect"),null);
   assertThat(wrong.status()).isEqualTo(401);
   assertThat(wrong.text("code")).isEqualTo("AUTH_INVALID_CREDENTIALS");
   assertThat(db.queryForObject("SELECT count(*) FROM auth_security_events WHERE outcome='failure'",Integer.class)).isEqualTo(1);
 }
 @Test void failedAuditRollsBackEntireRegistration() throws Exception {
   db.execute("ALTER TABLE auth_security_events ADD CONSTRAINT test_reject_registration CHECK(event_type <> 'registration')");
   try {
     assertThat(register("13910000010").status()).isEqualTo(500);
     assertThat(count("users")).isZero();
     assertThat(count("user_profiles")).isZero();
     assertThat(count("refresh_tokens")).isZero();
   } finally {
     db.execute("ALTER TABLE auth_security_events DROP CONSTRAINT test_reject_registration");
   }
 }
 @Test void rateLimitingRejectsBeforeAccountCreation() throws Exception {
   register("13910000011");
   db.update("UPDATE auth_rate_windows SET hits=1000");
   Reply rejected=register("13910000012");
   assertThat(rejected.status()).isEqualTo(429);
   assertThat(rejected.headers().firstValue("Retry-After")).contains("60");
   assertThat(count("users")).isEqualTo(1);
   assertThat(call("GET","/health",null,null).status()).isEqualTo(200);
   assertThat(call("GET","/health/ready",null,null).status()).isEqualTo(200);
 }
 @Test void encodedRouteCannotBypassRateLimiting() throws Exception {
   register("13910000013");
   db.update("UPDATE auth_rate_windows SET hits=1000");
   Reply reply=call("POST","/%61uth/register",Map.of("phone","13910000014","password",PASSWORD,"nickname","test"),null);
   assertThat(reply.status()).isEqualTo(429);
   assertThat(count("users")).isEqualTo(1);
 }

 @Test void verifiedRegistrationAndSmsLoginContracts() throws Exception {
   String phone="13920000001";
   Reply otp=sendCode(phone,"register");
   assertThat(otp.status()).isEqualTo(200);
   assertThat(otp.text("dev_code")).matches("[0-9]{6}");
   assertThat(otp.json().get("expires_in_seconds").asInt()).isEqualTo(300);
   assertThat(db.queryForObject("SELECT code_hash FROM auth_verification_codes",String.class))
      .hasSize(64).isNotEqualTo(otp.text("dev_code"));
   Reply registered=verifyRegister(phone,otp);
   assertThat(registered.status()).isEqualTo(200);
   assertThat(call("GET","/auth/me",null,registered.text("access_token")).text("phone")).isEqualTo(phone);
   assertThat(count("user_profiles")).isEqualTo(1);
   assertThat(verifyRegister(phone,otp).status()).isEqualTo(400);
   Reply loginCode=sendCode(phone,"login");
   Reply login=call("POST","/auth/sms/login",codeBody(phone,loginCode),null);
   assertThat(login.status()).isEqualTo(200);
   assertThat(login.text("user_id")).isEqualTo(registered.text("user_id"));
 }
 @Test void smsCooldownHourlyQuotaAndReplacement() throws Exception {
   String phone="13920000002";
   Reply old=sendCode(phone,"register");
   assertThat(sendCode(phone,"register").status()).isEqualTo(429);
   db.update("UPDATE auth_verification_codes SET created_at=now()-interval '61 seconds'");
   Reply latest=sendCode(phone,"register");
   assertThat(latest.status()).isEqualTo(200);
   assertThat(verifyRegister(phone,old).status()).isEqualTo(400);
   assertThat(verifyRegister(phone,latest).status()).isEqualTo(200);
   for(int i=0;i<10;i++) {
     db.update("UPDATE auth_verification_codes SET created_at=now()-interval '61 seconds'");
     assertThat(sendCode(phone,"login").status()).isEqualTo(200);
   }
   db.update("UPDATE auth_verification_codes SET created_at=now()-interval '61 seconds'");
   assertThat(sendCode(phone,"login").status()).isEqualTo(429);
 }
 @Test void wrongSmsAttemptsPersistAndCannotBeBypassedWithCorrectCode() throws Exception {
   String phone="13920000003";
   register(phone);
   Reply otp=sendCode(phone,"login");
   Map<String,Object> wrong=new HashMap<>(codeBody(phone,otp));
   wrong.put("code",otp.text("dev_code").equals("000000")?"000001":"000000");
   for(int i=1;i<=5;i++) {
     Reply r=call("POST","/auth/sms/login",wrong,null);
     assertThat(r.status()).isEqualTo(i<5?400:429);
     assertThat(db.queryForObject("SELECT attempts FROM auth_verification_codes",Integer.class)).isEqualTo(i);
   }
   assertThat(call("POST","/auth/sms/login",codeBody(phone,otp),null).status()).isEqualTo(429);
   assertThat(count("refresh_tokens")).isEqualTo(1);
 }
 @Test void smsCodesAreBoundToPhonePurposeAndExpiry() throws Exception {
   String phone="13920000004";
   Reply otp=sendCode(phone,"register");
   assertThat(verifyRegister("13920000005",otp).status()).isEqualTo(400);
   assertThat(call("POST","/auth/sms/login",codeBody(phone,otp),null).status()).isEqualTo(400);
   db.update("UPDATE auth_verification_codes SET expires_at=now()-interval '1 second'");
   Reply expired=verifyRegister(phone,otp);
   assertThat(expired.status()).isEqualTo(400);
   assertThat(expired.text("code")).isEqualTo("AUTH_CODE_EXPIRED");
   assertThat(count("users")).isZero();
 }
 @Test void concurrentSmsSendAndConsumptionOnlySucceedOnce() throws Exception {
   String phone="13920000006";
   List<Reply> replies=new ArrayList<>();
   try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
     List<Callable<Reply>> tasks=List.of(()->sendCode(phone,"register"),()->sendCode(phone,"register"));
     for(var future:pool.invokeAll(tasks)) replies.add(future.get());
   }
   assertThat(replies.stream().map(Reply::status).toList()).containsExactlyInAnyOrder(200,429);
   Reply otp=replies.stream().filter(r->r.status()==200).findFirst().orElseThrow();
   List<Integer> statuses=new ArrayList<>();
   try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
     List<Callable<Reply>> tasks=List.of(()->verifyRegister(phone,otp),()->verifyRegister(phone,otp));
     for(var future:pool.invokeAll(tasks)) statuses.add(future.get().status());
   }
   assertThat(statuses).containsExactlyInAnyOrder(200,400);
   assertThat(count("users")).isEqualTo(1);
   assertThat(count("refresh_tokens")).isEqualTo(1);
 }
 @Test void verifiedRegistrationFailureRollsBackCodeAndBusinessTogether() throws Exception {
   String phone="13920000007";
   Reply otp=sendCode(phone,"register");
   db.execute("ALTER TABLE auth_security_events ADD CONSTRAINT test_sms_failure CHECK(event_type <> 'verified_registration')");
   try {
     assertThat(verifyRegister(phone,otp).status()).isEqualTo(500);
     assertThat(count("users")).isZero();
     assertThat(db.queryForObject("SELECT consumed_at IS NULL FROM auth_verification_codes",Boolean.class)).isTrue();
   } finally { db.execute("ALTER TABLE auth_security_events DROP CONSTRAINT test_sms_failure"); }
   assertThat(verifyRegister(phone,otp).status()).isEqualTo(200);
 }
 @Test void passwordResetInvalidatesEverySessionAndOldPassword() throws Exception {
   String phone="13920000008";
   Reply first=register(phone);
   Reply second=call("POST","/auth/login",Map.of("phone",phone,"password",PASSWORD),null);
   Reply otp=sendCode(phone,"password_reset");
   var body=new HashMap<String,Object>(codeBody(phone,otp));
   body.put("new_password","New-test-password-123");
   assertThat(call("POST","/auth/password/reset",body,null).status()).isEqualTo(200);
   assertThat(call("GET","/auth/me",null,first.text("access_token")).status()).isEqualTo(401);
   assertThat(call("GET","/auth/me",null,second.text("access_token")).status()).isEqualTo(401);
   assertThat(call("POST","/auth/refresh",Map.of("refresh_token",second.text("refresh_token")),null).status()).isEqualTo(401);
   assertThat(call("POST","/auth/login",Map.of("phone",phone,"password",PASSWORD),null).status()).isEqualTo(401);
   assertThat(call("POST","/auth/login",Map.of("phone",phone,"password","New-test-password-123"),null).status()).isEqualTo(200);
   assertThat(call("POST","/auth/password/reset",body,null).status()).isEqualTo(400);
 }
 @Test void revokeOthersPreservesCurrentSessionAndOtherUsers() throws Exception {
   String phone="13920000009";
   Reply first=register(phone), other=register("13920000010");
   Reply current=call("POST","/auth/login",Map.of("phone",phone,"password",PASSWORD),null);
   Reply result=call("POST","/auth/sessions/revoke-others",null,current.text("access_token"));
   assertThat(result.status()).isEqualTo(200);
   assertThat(result.json().get("revoked_count").asInt()).isEqualTo(1);
   assertThat(call("GET","/auth/me",null,first.text("access_token")).status()).isEqualTo(401);
   assertThat(call("GET","/auth/me",null,current.text("access_token")).status()).isEqualTo(200);
   assertThat(call("GET","/auth/me",null,other.text("access_token")).status()).isEqualTo(200);
   assertThat(call("POST","/auth/sessions/revoke-others",null,current.text("access_token")).json().get("revoked_count").asInt()).isZero();
 }
 @Test void pushRegistrationIsAtomicPrivateAndScopedByOwner() throws Exception {
   Reply user=register("13920000011"),other=register("13920000012");
   var body=Map.of("device_id","test-device-0001","push_token","private-push-token-1","platform","android");
   Reply created=call("POST","/notifications/devices",body,user.text("access_token"));
   assertThat(created.status()).isEqualTo(200);
   assertThat(created.raw()).doesNotContain("push_token","private-push-token");
   assertThat(created.text("provider")).isEqualTo("development");
   assertThat(call("POST","/notifications/devices",body,user.text("access_token")).text("id")).isEqualTo(created.text("id"));
   try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
     List<Callable<Reply>> tasks=List.of(
       ()->call("POST","/notifications/devices",body,user.text("access_token")),
       ()->call("POST","/notifications/devices",body,user.text("access_token")));
     for(var future:pool.invokeAll(tasks)) assertThat(future.get().status()).isEqualTo(200);
   }
   assertThat(count("push_devices")).isEqualTo(1);
   assertThat(call("GET","/notifications/devices",null,other.text("access_token")).json().size()).isZero();
   assertThat(call("DELETE","/notifications/devices/test-device-0001",null,other.text("access_token")).status()).isEqualTo(404);
   assertThat(call("DELETE","/notifications/devices/test-device-0001",null,user.text("access_token")).text("status")).isEqualTo("disabled");
   assertThat(db.queryForObject("SELECT enabled FROM push_devices",Boolean.class)).isFalse();
   assertThat(call("POST","/notifications/devices",body,null).status()).isEqualTo(401);
 }
 Reply sendCode(String phone,String purpose) throws Exception {
   return call("POST","/auth/sms/send",Map.of("phone",phone,"purpose",purpose),null);
 }
 Map<String,Object> codeBody(String phone,Reply otp) {
   return Map.of("phone",phone,"request_id",otp.text("request_id"),"code",otp.text("dev_code"));
 }
 Reply verifyRegister(String phone,Reply otp) throws Exception {
   var body=new HashMap<String,Object>(codeBody(phone,otp));
   body.put("password",PASSWORD); body.put("nickname","短信测试");
   return call("POST","/auth/register/verify",body,null);
 }


 @Test void passwordResetAndConcurrentRefreshCannotLeaveAnActiveOldSession() throws Exception {
   String phone="13920000013";
   Reply user=register(phone),otp=sendCode(phone,"password_reset");
   var reset=new HashMap<String,Object>(codeBody(phone,otp));
   reset.put("new_password","Concurrent-reset-password");
   try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
     var resetFuture=pool.submit(()->call("POST","/auth/password/reset",reset,null));
     var refreshFuture=pool.submit(()->call("POST","/auth/refresh",Map.of("refresh_token",user.text("refresh_token")),null));
     assertThat(resetFuture.get(10,TimeUnit.SECONDS).status()).isEqualTo(200);
     Reply refresh=refreshFuture.get(10,TimeUnit.SECONDS);
     assertThat(refresh.status()).isIn(200,401);
     if(refresh.status()==200)
       assertThat(call("GET","/auth/me",null,refresh.text("access_token")).status()).isEqualTo(401);
   }
   assertThat(db.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL",Integer.class)).isZero();
 }
 @Test void passwordResetFailureRollsBackPasswordSessionsAndCode() throws Exception {
   String phone="13920000014";
   Reply user=register(phone),otp=sendCode(phone,"password_reset");
   String before=db.queryForObject("SELECT password_hash FROM users",String.class);
   var body=new HashMap<String,Object>(codeBody(phone,otp)); body.put("new_password","Another-password-123");
   db.execute("ALTER TABLE auth_security_events ADD CONSTRAINT test_reset_failure CHECK(event_type <> 'password_reset')");
   try {
     assertThat(call("POST","/auth/password/reset",body,null).status()).isEqualTo(500);
     assertThat(db.queryForObject("SELECT password_hash FROM users",String.class)).isEqualTo(before);
     assertThat(db.queryForObject("SELECT consumed_at IS NULL FROM auth_verification_codes",Boolean.class)).isTrue();
     assertThat(call("GET","/auth/me",null,user.text("access_token")).status()).isEqualTo(200);
   } finally { db.execute("ALTER TABLE auth_security_events DROP CONSTRAINT test_reset_failure"); }
   assertThat(call("POST","/auth/password/reset",body,null).status()).isEqualTo(200);
 }
 @Test void smsValidationAndFailureDetailsMatchContract() throws Exception {
   String phone="13920000015";
   assertThat(sendCode(phone,"reset_password").status()).isEqualTo(422);
   Reply otp=sendCode(phone,"login");
   Reply quota=sendCode(phone,"login");
   assertThat(quota.status()).isEqualTo(429);
   assertThat(quota.json().get("details").get("retry_after_seconds").asInt()).isBetween(1,60);
   assertThat(quota.headers().firstValue("Retry-After")).isPresent();
   var body=new HashMap<String,Object>(codeBody(phone,otp));
   body.put("code","abc123");
   assertThat(call("POST","/auth/sms/login",body,null).status()).isEqualTo(422);
   body.put("code",otp.text("dev_code").equals("000000")?"000001":"000000");
   Reply wrong=call("POST","/auth/sms/login",body,null);
   assertThat(wrong.status()).isEqualTo(400);
   assertThat(wrong.json().get("details").get("remaining_attempts").asInt()).isEqualTo(4);
 }

 int count(String table) { return db.queryForObject("SELECT count(*) FROM "+table,Integer.class); }
 Reply register(String phone) throws Exception {
   return call("POST","/auth/register",Map.of("phone",phone,"password",PASSWORD,"nickname","Java测试"),null);
 }
 Reply call(String method,String path,Object body,String token) throws Exception {
   var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
     .timeout(Duration.ofSeconds(20)).header("X-Request-ID","test_request");
   if(token!=null) b.header("Authorization","Bearer "+token);
   if(body!=null) b.header("Content-Type","application/json");
   var request=b.method(method,body==null?HttpRequest.BodyPublishers.noBody():
     HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
   var response=http.send(request,HttpResponse.BodyHandlers.ofString());
   return new Reply(response.statusCode(),mapper.readTree(response.body()),response.headers(),response.body());
 }
}
