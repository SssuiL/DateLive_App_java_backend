package com.manliao.backend;
import java.net.URI;
import java.net.http.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.*;
import com.manliao.backend.admin.*;
import com.manliao.backend.media.*;
import com.manliao.backend.identity.*;
import com.manliao.backend.notifications.NotificationService;
import static org.assertj.core.api.Assertions.*;
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.realtime.worker-enabled=false",
 "app.auth.jwt-secret=isolated-erasure-test-secret-at-least-thirty-two-bytes","app.auth.legacy-registration-enabled=true",
 "app.accounts.worker-enabled=false","app.sms.provider=development","app.sms.return-dev-code=true",
 "app.sms.code-secret=isolated-erasure-sms-code-secret-more-than-thirty-two"})
class AccountLifecycleTests {
 static final Path ROOT=createRoot();
 static Path createRoot(){try{return Files.createTempDirectory(Path.of("target"),"erasure-it-").toAbsolutePath();}catch(IOException e){throw new UncheckedIOException(e);}}
 @DynamicPropertySource static void storage(DynamicPropertyRegistry p){p.add("app.media.storage-root",()->ROOT.toString());}
 @Value("${local.server.port}") int port;
 @Autowired JdbcTemplate db;@Autowired ObjectMapper mapper;@Autowired AccountLifecycleService accounts;
 @Autowired MediaService media;@Autowired AdminService admins;@Autowired NotificationService notifications;
 @Autowired ApplicationContext context;
 final HttpClient http=HttpClient.newHttpClient();
 record Reply(int status,JsonNode json){String text(String key){return json.get(key).asString();}}
 @BeforeEach void clean(){db.execute("TRUNCATE admin_users,users,auth_rate_windows,auth_verification_codes RESTART IDENTITY CASCADE");}
 Reply call(String method,String path,Object body,String token)throws Exception {
   var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20));
   if(token!=null)b.header("Authorization","Bearer "+token);
   if(body!=null)b.header("Content-Type","application/json");
   var r=http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
   return new Reply(r.statusCode(),r.body().isBlank()?null:mapper.readTree(r.body()));
 }
 Reply register(String phone)throws Exception {
   var r=call("POST","/auth/register",Map.of("phone",phone,"password","Account-test-password","nickname","待注销用户"),null);
   assertThat(r.status()).isEqualTo(200);return r;
 }
 Reply deactivate(Reply u)throws Exception{return call("POST","/auth/account/deactivate",null,u.text("access_token"));}
 void due(Reply u)throws Exception {
   assertThat(deactivate(u).status()).isEqualTo(200);
   db.update("UPDATE users SET deactivation_due_at=now()-interval '1 second' WHERE id=?",u.text("user_id"));
   db.update("UPDATE account_erasure_records SET scheduled_at=now()-interval '1 second' WHERE user_id=?",u.text("user_id"));
 }
 Map<String,Object> image(Reply u)throws Exception {
   var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(3,2,BufferedImage.TYPE_INT_RGB),"png",out);
   return media.upload(new AuthDtos.Principal(u.text("user_id"),u.text("session_id")),"image","profile",null,
      new MockMultipartFile("file","fixture.png","image/png",out.toByteArray()));
 }
 @Test void repeatedAndConcurrentRequestsKeepSameFortyFiveDayDeadline()throws Exception {
   Reply user=register("13960000001");var replies=new ArrayList<Reply>();
   try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
     for(var f:pool.invokeAll(List.<Callable<Reply>>of(()->deactivate(user),()->deactivate(user))))replies.add(f.get());
   }
   for(var reply:replies){
     assertThat(reply.status()).isEqualTo(200);assertThat(reply.json().get("cooling_off_days").asInt()).isEqualTo(45);
     assertThat(Duration.between(Instant.parse(reply.text("deactivation_requested_at")),Instant.parse(reply.text("deactivation_due_at")))).isEqualTo(Duration.ofDays(45));
   }
   assertThat(replies.get(0).text("deactivation_due_at")).isEqualTo(replies.get(1).text("deactivation_due_at"));
   assertThat(db.queryForObject("SELECT count(*) FROM account_erasure_records",Integer.class)).isEqualTo(1);
   assertThat(db.queryForObject("SELECT count(*) FROM auth_security_events WHERE event_type='account_deactivate'",Integer.class)).isEqualTo(1);
   assertThat(accounts.eraseDue(user.text("user_id"))).isFalse();
 }
 @Test void restoreKeepsDataAndCancelsJob()throws Exception {
   Reply user=register("13960000002");deactivate(user);
   Reply restored=call("POST","/auth/account/restore",null,user.text("access_token"));
   assertThat(restored.status()).isEqualTo(200);assertThat(restored.text("status")).isEqualTo("active");
   assertThat(restored.json().get("deactivation_due_at").isNull()).isTrue();
   assertThat(db.queryForObject("SELECT count(*) FROM account_erasure_records",Integer.class)).isZero();
   assertThat(accounts.eraseDue(user.text("user_id"))).isFalse();
   assertThat(call("POST","/auth/account/restore",null,user.text("access_token")).status()).isEqualTo(409);
   assertThat(db.queryForObject("SELECT count(*) FROM user_profiles",Integer.class)).isEqualTo(1);
 }
 @Test void deadlineCannotBeRestoredAndAutomaticWorkerIsDisabled()throws Exception {
   Reply user=register("13960000003");due(user);
   assertThat(context.getBeansOfType(AccountErasureWorker.class)).isEmpty();
   assertThat(call("POST","/auth/account/restore",null,user.text("access_token")).status()).isEqualTo(403);
   assertThat(accounts.preview(user.text("user_id")).get("eligible")).isEqualTo(true);
   assertThat(db.queryForObject("SELECT status FROM users",String.class)).isEqualTo("deactivation_pending");
 }
 @Test void erasureScrubsOnlyTargetAndQueuesFilesBeforeCompletion()throws Exception {
   Reply user=register("13960000004"),other=register("13960000005");
   String id=user.text("user_id"),otherId=other.text("user_id");var asset=image(user);
   String key=db.queryForObject("SELECT storage_key FROM media_assets",String.class);
   media.resolveReview((String)asset.get("id"),true,"fixture-reviewer","private fixture text");
   db.update("INSERT INTO blocks VALUES(?,?)",id,otherId);
   db.update("INSERT INTO push_devices(id,user_id,device_id,platform,push_token,provider) VALUES('push_fixture',?,'fixture_device','android','private_token','development')",id);
   notifications.preferences(id);
   db.update("INSERT INTO notification_events(id,recipient_user_id,actor_user_id,event_type,category,title,body) VALUES('notification_fixture',?,?,'test','social','private','private')",otherId,id);
   call("POST","/auth/sms/send",Map.of("phone","13960000004","purpose","login"),null);
   due(user);
   assertThat(accounts.eraseDue(id)).isTrue();
   assertThat(accounts.record(id).get("status")).isEqualTo("storage_pending");
   assertThat(Files.exists(ROOT.resolve(key))).isTrue();
   assertThat(db.queryForObject("SELECT status FROM users WHERE id=?",String.class,id)).isEqualTo("deactivated");
   assertThat(db.queryForObject("SELECT phone FROM users WHERE id=?",String.class,id)).startsWith("deleted_").isNotEqualTo("13960000004");
   for(String table:List.of("media_assets","media_reviews","push_devices","notification_events","notification_preferences","blocks","auth_verification_codes"))
     assertThat(db.queryForObject("SELECT count(*) FROM "+table,Integer.class)).as(table).isZero();
   assertThat(db.queryForObject("SELECT count(*) FROM user_profiles WHERE user_id=?",Integer.class,id)).isZero();
   assertThat(db.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id=?",Integer.class,id)).isZero();
   assertThat(db.queryForObject("SELECT count(*) FROM auth_security_events WHERE user_id=? AND session_id IS NOT NULL",Integer.class,id)).isZero();
   assertThat(call("GET","/auth/me",null,user.text("access_token")).status()).isEqualTo(401);
   assertThat(call("POST","/auth/refresh",Map.of("refresh_token",user.text("refresh_token")),null).status()).isEqualTo(401);
   assertThat(call("GET","/auth/me",null,other.text("access_token")).status()).isEqualTo(200);
   assertThat(accounts.cleanupStorage()).isEqualTo(1);
   assertThat(accounts.record(id).get("status")).isEqualTo("completed");
   assertThat(Files.exists(ROOT.resolve(key))).isFalse();
   assertThat(accounts.eraseDue(id)).isFalse();assertThat(accounts.cleanupStorage()).isZero();
 }
 @Test void storageFailuresStayPendingAndRetryWithoutExposingPath()throws Exception {
   Reply user=register("13960000006");image(user);due(user);accounts.eraseDue(user.text("user_id"));
   String key=db.queryForObject("SELECT storage_key FROM storage_deletion_jobs",String.class);
   // A deliberately invalid generated test key is rejected before any filesystem access.
   db.update("UPDATE storage_deletion_jobs SET storage_key='invalid-test-key'");
   assertThat(accounts.cleanupStorage()).isZero();
   assertThat(accounts.record(user.text("user_id")).get("status")).isEqualTo("storage_pending");
   assertThat(db.queryForObject("SELECT attempts FROM storage_deletion_jobs",Integer.class)).isEqualTo(1);
   assertThat(accounts.storageJobs().getFirst()).doesNotContainKey("storage_key");
   db.update("UPDATE storage_deletion_jobs SET storage_key=?,next_retry_at=now()",key);
   assertThat(accounts.cleanupStorage()).isEqualTo(1);
   assertThat(accounts.record(user.text("user_id")).get("status")).isEqualTo("completed");
 }
 @Test void databaseFailureRollsBackAndBatchRecordsRetryableFailure()throws Exception {
   Reply user=register("13960000007");image(user);due(user);
   db.execute("ALTER TABLE users ADD CONSTRAINT test_erasure_failure CHECK(status<>'deactivated')");
   try{
     assertThat(accounts.processDue()).isZero();
     assertThat(accounts.record(user.text("user_id")).get("status")).isEqualTo("failed");
     assertThat(accounts.record(user.text("user_id")).get("error_message")).isEqualTo("DATABASE_ERASURE_FAILED");
     assertThat(db.queryForObject("SELECT count(*) FROM media_assets",Integer.class)).isEqualTo(1);
     assertThat(db.queryForObject("SELECT count(*) FROM storage_deletion_jobs",Integer.class)).isZero();
     assertThat(db.queryForObject("SELECT count(*) FROM user_profiles",Integer.class)).isEqualTo(1);
     assertThat(call("GET","/auth/me",null,user.text("access_token")).status()).isEqualTo(200);
   }finally{db.execute("ALTER TABLE users DROP CONSTRAINT test_erasure_failure");}
   db.update("UPDATE account_erasure_records SET next_retry_at=now()");
   assertThat(accounts.processDue()).isEqualTo(1);accounts.cleanupStorage();
   assertThat(accounts.record(user.text("user_id")).get("status")).isEqualTo("completed");
 }
 @Test void concurrentExecutorsEraseOnlyOnce()throws Exception {
   Reply user=register("13960000008");due(user);var results=new ArrayList<Boolean>();
   try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
     for(var f:pool.invokeAll(List.<Callable<Boolean>>of(()->accounts.eraseDue(user.text("user_id")),()->accounts.eraseDue(user.text("user_id")))))results.add(f.get());
   }
   assertThat(results).containsExactlyInAnyOrder(true,false);
   assertThat(db.queryForObject("SELECT count(*) FROM account_erasure_records",Integer.class)).isEqualTo(1);
 }
 @Test void restoreAndEarlyCleanupRaceDoesNotDeleteData()throws Exception {
   Reply user=register("13960000009");deactivate(user);
   try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
     var erase=pool.submit(()->accounts.eraseDue(user.text("user_id")));
     var restore=pool.submit(()->call("POST","/auth/account/restore",null,user.text("access_token")));
     assertThat(erase.get(10,TimeUnit.SECONDS)).isFalse();
     assertThat(restore.get(10,TimeUnit.SECONDS).status()).isEqualTo(200);
   }
   assertThat(db.queryForObject("SELECT count(*) FROM user_profiles",Integer.class)).isEqualTo(1);
 }
 @Test void latePushAndPreferenceWritesCannotRecreateErasedData()throws Exception {
   Reply user=register("13960000010");due(user);
   try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
     var erase=pool.submit(()->accounts.eraseDue(user.text("user_id")));
     var push=pool.submit(()->call("POST","/notifications/devices",Map.of("device_id","fixture-device","push_token","fixture-token"),user.text("access_token")));
     assertThat(erase.get(10,TimeUnit.SECONDS)).isTrue();
     assertThat(push.get(10,TimeUnit.SECONDS).status()).isIn(200,401,403);
   }
   assertThat(db.queryForObject("SELECT count(*) FROM push_devices",Integer.class)).isZero();
   assertThatThrownBy(()->notifications.preferences(user.text("user_id"))).isInstanceOf(com.manliao.backend.common.ApiError.class);
   assertThat(db.queryForObject("SELECT count(*) FROM notification_preferences",Integer.class)).isZero();
 }
 @Test void originalPhoneMayRegisterNewIdentityWithoutRevivingOldTokens()throws Exception {
   Reply user=register("13960000011");due(user);accounts.eraseDue(user.text("user_id"));
   Reply replacement=register("13960000011");
   assertThat(replacement.text("user_id")).isNotEqualTo(user.text("user_id"));
   assertThat(call("GET","/auth/me",null,user.text("access_token")).status()).isEqualTo(401);
   assertThat(call("GET","/auth/me",null,replacement.text("access_token")).status()).isEqualTo(200);
 }
 @Test void ownerOnlyPreviewAndNoUnauthenticatedLifecycleOperations()throws Exception {
   admins.bootstrap(new AdminDtos.Create("erase_owner","Owner-test-password","Owner","owner"));
   Reply owner=call("POST","/admin/auth/login",Map.of("username","erase_owner","password","Owner-test-password"),null);
   String token=owner.text("access_token");
   call("POST","/admin/admin-users",Map.of("username","erase_auditor","password","Auditor-test-password","display_name","Auditor","role","auditor"),token);
   Reply auditor=call("POST","/admin/auth/login",Map.of("username","erase_auditor","password","Auditor-test-password"),null);
   Reply user=register("13960000012");deactivate(user);
   String path="/admin/users/"+user.text("user_id");
   assertThat(call("GET",path+"/erasure-preview",null,token).status()).isEqualTo(200);
   assertThat(call("GET",path+"/erasure",null,token).text("status")).isEqualTo("pending");
   assertThat(call("GET","/admin/moderation/storage-deletions",null,token).status()).isEqualTo(200);
   assertThat(call("GET",path+"/erasure-preview",null,auditor.text("access_token")).status()).isEqualTo(403);
   assertThat(call("POST","/auth/account/deactivate",null,null).status()).isEqualTo(401);
   assertThat(call("POST","/auth/account/restore",null,null).status()).isEqualTo(401);
 }
 @Test void everyUserForeignKeyHasAnErasurePolicy(){
   var actual=db.queryForList("""
     SELECT c.relname||'.'||a.attname AS reference
     FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid
     JOIN pg_attribute a ON a.attrelid=k.conrelid AND a.attnum=ANY(k.conkey)
     WHERE k.contype='f' AND k.confrelid='users'::regclass
     """,String.class);
   assertThat(new HashSet<>(actual)).isEqualTo(AccountLifecycleService.USER_FK_POLICY);
 }
}
