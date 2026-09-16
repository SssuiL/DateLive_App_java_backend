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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.*;
import com.manliao.backend.admin.*;
import com.manliao.backend.media.*;
import com.manliao.backend.identity.AuthDtos.Principal;
import static org.assertj.core.api.Assertions.*;
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.realtime.worker-enabled=false",
 "app.auth.jwt-secret=isolated-admin-test-secret-at-least-thirty-two-bytes","app.auth.legacy-registration-enabled=true"})
class AdminIntegrationTests {
 static final String PASSWORD="Admin-test-password-123";
 static final Path ROOT=createRoot();
 static Path createRoot(){try{return Files.createTempDirectory(Path.of("target"),"admin-media-it-").toAbsolutePath();}catch(IOException e){throw new UncheckedIOException(e);}}
 @DynamicPropertySource static void storage(DynamicPropertyRegistry p){p.add("app.media.storage-root",()->ROOT.toString());}
 @Value("${local.server.port}") int port;
 @Autowired JdbcTemplate db;@Autowired ObjectMapper mapper;@Autowired AdminService admins;@Autowired MediaService media;
 final HttpClient http=HttpClient.newHttpClient();
 record Reply(int status,JsonNode json,String raw){String text(String key){return json.get(key).asString();}}
 @BeforeEach void clean(){
   db.execute("TRUNCATE admin_users,users,auth_rate_windows,auth_verification_codes RESTART IDENTITY CASCADE");
   admins.bootstrap(new AdminDtos.Create("test_owner",PASSWORD,"测试所有者","owner"));
 }
 Reply call(String method,String path,Object body,String token)throws Exception {
   var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20));
   if(token!=null)b.header("Authorization","Bearer "+token);
   if(body!=null)b.header("Content-Type","application/json");
   var r=http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
   return new Reply(r.statusCode(),r.body().isBlank()?null:mapper.readTree(r.body()),r.body());
 }
 Reply login(String name,String password)throws Exception{return call("POST","/admin/auth/login",Map.of("username",name,"password",password),null);}
 Reply owner()throws Exception {var r=login("test_owner",PASSWORD);assertThat(r.status()).isEqualTo(200);return r;}
 Reply create(String token,String name,String role)throws Exception {
   return call("POST","/admin/admin-users",Map.of("username",name,"password",PASSWORD,"display_name","测试管理员","role",role),token);
 }
 Reply user()throws Exception{return call("POST","/auth/register",Map.of("phone","13950000001","password","User-test-password","nickname","图片用户"),null);}
 Map<String,Object> asset(Reply user)throws Exception {
   var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(3,2,BufferedImage.TYPE_INT_RGB),"png",out);
   var asset=media.upload(new Principal(user.text("user_id"),user.text("session_id")),"image","profile",null,
      new MockMultipartFile("file","test.png","image/png",out.toByteArray()));
   assertThat(call("PATCH","/profiles/me",Map.of("avatar_url",asset.get("url")),user.text("access_token")).status()).isEqualTo(200);
   return asset;
 }
 int previewStatus(String path)throws Exception {
   return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode();
 }

 @Test void concurrentBootstrapCreatesExactlyOneAdministrator()throws Exception {
   db.execute("TRUNCATE admin_users CASCADE");
   try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
     List<Callable<Void>> tasks=List.of(
       ()->{admins.bootstrap(new AdminDtos.Create("first_owner",PASSWORD,"First","owner"));return null;},
       ()->{admins.bootstrap(new AdminDtos.Create("second_owner",PASSWORD,"Second","owner"));return null;});
     for(var f:pool.invokeAll(tasks))f.get();
   }
   assertThat(db.queryForObject("SELECT count(*) FROM admin_users",Integer.class)).isEqualTo(1);
   assertThat(db.queryForObject("SELECT count(*) FROM admin_operation_logs WHERE action='admin.bootstrap'",Integer.class)).isEqualTo(1);
 }

 @Test void adminContractAndBootstrapDoesNotOverwriteAccounts()throws Exception {
   Reply owner=owner();String token=owner.text("access_token");
   assertThat(owner.text("token_type")).isEqualTo("bearer");
   assertThat(owner.raw()).doesNotContain("password_hash","token_version","fingerprint",PASSWORD);
   assertThat(call("GET","/admin/auth/me",null,token).text("role")).isEqualTo("owner");
   assertThat(create(token,"test_auditor","auditor").status()).isEqualTo(200);
   assertThat(create(token,"test_auditor","auditor").status()).isEqualTo(409);
   assertThat(call("GET","/admin/admin-users?role=auditor&keyword=auditor",null,token).json().size()).isEqualTo(1);
   admins.bootstrap(new AdminDtos.Create("another_owner",PASSWORD,"不应创建","owner"));
   assertThat(db.queryForObject("SELECT count(*) FROM admin_users",Integer.class)).isEqualTo(2);
   assertThat(call("POST","/admin/auth/bootstrap",Map.of(),token).status()).isEqualTo(404);
 }
 @Test void ordinaryAndAdminTokensCannotCrossBoundaries()throws Exception {
   Reply user=user(),owner=owner();
   assertThat(call("GET","/admin/auth/me",null,user.text("access_token")).status()).isEqualTo(401);
   assertThat(call("GET","/auth/me",null,owner.text("access_token")).status()).isEqualTo(401);
   assertThat(call("GET","/admin/moderation/media",null,null).status()).isEqualTo(401);
   var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/admin/moderation/media"))
      .header("X-Admin-Token","legacy_admin").GET().build();
   assertThat(http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
   assertThat(call("GET","/admin/auth/me",null,"bad-token").status()).isEqualTo(401);
 }
 @Test void operatorCannotReviewAndAuditorCannotManageAccounts()throws Exception {
   String token=owner().text("access_token");
   create(token,"test_operator","operator");create(token,"test_auditor","auditor");
   String operator=login("test_operator",PASSWORD).text("access_token"),auditor=login("test_auditor",PASSWORD).text("access_token");
   assertThat(call("GET","/admin/moderation/media/pending",null,operator).status()).isEqualTo(403);
   assertThat(call("POST","/admin/moderation/media/missing/approve",Map.of(),operator).status()).isEqualTo(403);
   assertThat(call("GET","/admin/moderation/media/pending",null,auditor).status()).isEqualTo(200);
   assertThat(create(auditor,"forbidden_owner","owner").status()).isEqualTo(403);
   assertThat(call("GET","/admin/operation-logs",null,auditor).status()).isEqualTo(403);
 }
 @Test void passwordChangeInvalidatesAllTokensAndChecksCurrentPassword()throws Exception {
   Reply first=owner(),second=owner();String token=first.text("access_token");
   assertThat(call("PATCH","/admin/auth/password",Map.of("current_password","incorrect-password","new_password","New-admin-password-123"),token).status()).isEqualTo(401);
   assertThat(call("PATCH","/admin/auth/password",Map.of("current_password",PASSWORD,"new_password",PASSWORD),token).status()).isEqualTo(400);
   assertThat(call("PATCH","/admin/auth/password",Map.of("current_password",PASSWORD,"new_password","New-admin-password-123"),token).status()).isEqualTo(200);
   assertThat(call("GET","/admin/auth/me",null,first.text("access_token")).status()).isEqualTo(401);
   assertThat(call("GET","/admin/auth/me",null,second.text("access_token")).status()).isEqualTo(401);
   assertThat(login("test_owner",PASSWORD).status()).isEqualTo(401);
   assertThat(login("test_owner","New-admin-password-123").status()).isEqualTo(200);
 }
 @Test void disableReenableAndResetNeverReviveOldTokens()throws Exception {
   Reply owner=owner();String token=owner.text("access_token");
   Reply account=create(token,"test_auditor","auditor");String id=account.text("id");
   String old=login("test_auditor",PASSWORD).text("access_token");
   assertThat(call("PATCH","/admin/admin-users/"+id+"/status",Map.of("status","disabled"),token).status()).isEqualTo(400);
   assertThat(call("PATCH","/admin/admin-users/"+id+"/status",Map.of("status","disabled","reason","测试停用"),token).status()).isEqualTo(200);
   assertThat(call("GET","/admin/auth/me",null,old).status()).isEqualTo(403);
   assertThat(login("test_auditor",PASSWORD).status()).isEqualTo(401);
   call("PATCH","/admin/admin-users/"+id+"/status",Map.of("status","active"),token);
   assertThat(call("GET","/admin/auth/me",null,old).status()).isEqualTo(401);
   String current=login("test_auditor",PASSWORD).text("access_token");
   assertThat(call("PATCH","/admin/admin-users/"+id+"/password",Map.of("new_password","Reset-admin-password-123"),token).status()).isEqualTo(200);
   assertThat(call("GET","/admin/auth/me",null,current).status()).isEqualTo(401);
   assertThat(login("test_auditor","Reset-admin-password-123").status()).isEqualTo(200);
   String ownerId=owner.json().get("admin").get("id").asString();
   assertThat(call("PATCH","/admin/admin-users/"+ownerId+"/status",Map.of("status","disabled","reason","测试"),token).status()).isEqualTo(400);
 }
 @Test void reviewUsesAuthenticatedIdentityAndCommitsProfileAndLogs()throws Exception {
   String owner=owner().text("access_token");create(owner,"test_auditor","auditor");
   Reply login=login("test_auditor",PASSWORD);String token=login.text("access_token");
   Reply user=user();var asset=asset(user);String id=(String)asset.get("id");
   assertThat(call("GET","/admin/moderation/media/pending",null,token).json().size()).isEqualTo(1);
   assertThat(call("GET","/admin/moderation/media?owner_user_id="+user.text("user_id"),null,token).json().size()).isEqualTo(1);
   assertThat(call("GET","/admin/moderation/media?source=chat",null,token).json().size()).isZero();
   Reply detail=call("GET","/admin/moderation/media/"+id,null,token);
   assertThat(detail.status()).isEqualTo(200);assertThat(detail.raw()).doesNotContain("storage_key",ROOT.toString());
   Reply approved=call("POST","/admin/moderation/media/"+id+"/approve",Map.of("reviewer_id","forged_owner","reason","测试审核通过"),token);
   assertThat(approved.status()).isEqualTo(200);assertThat(approved.text("status")).isEqualTo("approved");
   assertThat(db.queryForObject("SELECT reviewed_by FROM media_assets",String.class)).isEqualTo(login.json().get("admin").get("id").asString());
   assertThat(db.queryForObject("SELECT avatar_url FROM user_profiles",String.class)).isEqualTo(asset.get("url"));
   assertThat(call("GET","/admin/moderation/reviews?target_type=media&target_id="+id,null,token).json().size()).isEqualTo(1);
   assertThat(call("POST","/admin/moderation/media/"+id+"/reject",Map.of("reason","测试拒绝"),token).status()).isEqualTo(200);
   assertThat(db.queryForObject("SELECT avatar_url FROM user_profiles",String.class)).isNull();
   Reply logs=call("GET","/admin/operation-logs?action=media.approve",null,owner);
   assertThat(logs.json().size()).isEqualTo(1);assertThat(logs.raw()).doesNotContain("forged_owner",PASSWORD,"password_hash");
 }
 @Test void previewsRequireLiveAdminPermissionAndCannotBeUsedAsLogin()throws Exception {
   String owner=owner().text("access_token");create(owner,"test_auditor","auditor");
   String auditor=login("test_auditor",PASSWORD).text("access_token");var asset=asset(user());String id=(String)asset.get("id");
   Reply grant=call("POST","/admin/moderation/media/"+id+"/preview-url",null,auditor);
   assertThat(grant.status()).isEqualTo(200);
   assertThat(previewStatus(grant.text("url"))).isEqualTo(200);
   String previewToken=grant.text("url").substring(grant.text("url").lastIndexOf('/')+1);
   assertThat(call("GET","/admin/auth/me",null,previewToken).status()).isEqualTo(401);
   assertThat(previewStatus("/media/access/"+previewToken)).isEqualTo(404);
   db.update("UPDATE admin_users SET role='operator' WHERE username='test_auditor'");
   assertThat(previewStatus(grant.text("url"))).isEqualTo(403);
   db.update("UPDATE admin_users SET role='auditor' WHERE username='test_auditor'");
   assertThat(call("POST","/admin/auth/logout",null,auditor).status()).isEqualTo(200);
   assertThat(previewStatus(grant.text("url"))).isEqualTo(401);
 }
 @Test void operationLogFailureRollsBackReviewAndProfile()throws Exception {
   String token=owner().text("access_token");var asset=asset(user());String id=(String)asset.get("id");
   db.execute("ALTER TABLE admin_operation_logs ADD CONSTRAINT test_admin_audit_failure CHECK(action<>'media.approve')");
   try{
     assertThat(call("POST","/admin/moderation/media/"+id+"/approve",Map.of("reason","事务测试"),token).status()).isEqualTo(500);
     assertThat(db.queryForObject("SELECT status FROM media_assets",String.class)).isEqualTo("review_pending");
     assertThat(db.queryForObject("SELECT count(*) FROM media_reviews",Integer.class)).isZero();
     assertThat(db.queryForObject("SELECT avatar_url FROM user_profiles",String.class)).isNull();
   }finally{db.execute("ALTER TABLE admin_operation_logs DROP CONSTRAINT test_admin_audit_failure");}
 }
 @Test void createFailureRollsBackAccountAndLoginRateLimitPrecedesHashing()throws Exception {
   String token=owner().text("access_token");
   db.execute("ALTER TABLE admin_operation_logs ADD CONSTRAINT test_create_failure CHECK(action<>'admin.create')");
   try{assertThat(create(token,"rolled_back","auditor").status()).isEqualTo(500);
     assertThat(db.queryForObject("SELECT count(*) FROM admin_users",Integer.class)).isEqualTo(1);
   }finally{db.execute("ALTER TABLE admin_operation_logs DROP CONSTRAINT test_create_failure");}
   db.update("UPDATE auth_rate_windows SET hits=999 WHERE bucket_key LIKE '/admin/auth/login:%'");
   assertThat(login("test_owner",PASSWORD).status()).isEqualTo(429);
 }
 @Test void concurrentOwnerDisablesLeaveOneActiveOwner()throws Exception {
   Reply first=owner();String token=first.text("access_token");
   Reply other=create(token,"other_owner","owner");String otherToken=login("other_owner",PASSWORD).text("access_token");
   String firstId=first.json().get("admin").get("id").asString();
   var body=Map.of("status","disabled","reason","并发权限测试");
   var statuses=new ArrayList<Integer>();
   try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
     List<Callable<Reply>> tasks=List.of(
       ()->call("PATCH","/admin/admin-users/"+other.text("id")+"/status",body,token),
       ()->call("PATCH","/admin/admin-users/"+firstId+"/status",body,otherToken));
     for(var f:pool.invokeAll(tasks))statuses.add(f.get().status());
   }
   assertThat(statuses).containsExactlyInAnyOrder(200,403);
   assertThat(db.queryForObject("SELECT count(*) FROM admin_users WHERE role='owner' AND status='active'",Integer.class)).isEqualTo(1);
 }
}
