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
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.realtime.worker-enabled=false",
 "app.auth.jwt-secret=isolated-java-test-secret-at-least-thirty-two-bytes",
 "app.auth.legacy-registration-enabled=true",
 "app.sms.provider=development","app.sms.return-dev-code=true",
 "app.sms.code-secret=isolated-sms-test-secret-more-than-thirty-two-bytes"})
class ProfileNotificationTests {
 @Value("${local.server.port}") int port;
 @Autowired JdbcTemplate db;
 @Autowired ObjectMapper mapper;
 final HttpClient http=HttpClient.newHttpClient();
 record Reply(int status,JsonNode json,String raw) { String text(String key){return json.get(key).asString();} }
 @BeforeEach void clean(){db.execute("TRUNCATE users,auth_rate_windows,auth_verification_codes RESTART IDENTITY CASCADE");}
 Reply call(String method,String path,Object body,String token) throws Exception {
   var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
   if(token!=null)b.header("Authorization","Bearer "+token);
   if(body!=null)b.header("Content-Type","application/json");
   var r=http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():
       HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
   return new Reply(r.statusCode(),mapper.readTree(r.body()),r.body());
 }
 Reply user(String suffix)throws Exception {
   Reply r=call("POST","/auth/register",Map.of("phone","1393000"+suffix,"password","Profile-test-password","nickname","原昵称"),null);
   assertThat(r.status()).isEqualTo(200);return r;
 }
 Reply patch(Reply user,Object body)throws Exception{return call("PATCH","/profiles/me",body,user.text("access_token"));}
 void asset(Reply owner,String id,String status){
   db.update("INSERT INTO media_assets(id,owner_user_id,url,media_type,status) VALUES(?,?,?,'image',?)",
     id,owner.text("user_id"),"https://media.example.test/"+id,status);
 }
 String url(String id){return "https://media.example.test/"+id;}
 void event(Reply user,String id){
   db.update("""
     INSERT INTO notification_events(id,recipient_user_id,event_type,category,title,body,payload,deduplication_key)
     VALUES(?,?,'friend_request','friend_request','测试标题','测试正文','{"route":"friends","count":2}',?)
     """,id,user.text("user_id"),"private-dedup-"+id);
 }
 @Test void profileDefaultsAndPartialPatchSynchronizeNickname()throws Exception {
   Reply user=user("0001");String token=user.text("access_token");
   Reply initial=call("GET","/profiles/me",null,token);
   assertThat(initial.status()).isEqualTo(200);
   assertThat(initial.json().get("tags").isArray()).isTrue();
   assertThat(initial.json().get("pending_photo_urls").size()).isZero();
   Reply result=patch(user,Map.of("nickname","新昵称","bio","喜欢读书","age",24,"tags",List.of("读书","读书","旅行"),"hobbies",List.of("摄影")));
   assertThat(result.status()).isEqualTo(200);
   assertThat(result.json().get("tags").size()).isEqualTo(2);
   assertThat(call("GET","/auth/me",null,token).text("nickname")).isEqualTo("新昵称");
   Map<String,Object> partial=new HashMap<>();partial.put("bio",null);partial.put("occupation","设计师");
   assertThat(patch(user,partial).text("bio")).isEqualTo("喜欢读书");
   assertThat(patch(user,Map.of("tags",List.of())).json().get("tags").size()).isZero();
 }
 @Test void rejectedTextKeepsOldContentAndPersistsReview()throws Exception {
   Reply user=user("0002");
   Reply result=patch(user,Map.of("nickname","加微信联系","bio","新简介"));
   assertThat(result.status()).isEqualTo(400);
   assertThat(result.text("code")).isEqualTo("MODERATION_TEXT_REJECTED");
   assertThat(db.queryForObject("SELECT nickname FROM users",String.class)).isEqualTo("原昵称");
   assertThat(db.queryForObject("SELECT bio FROM user_profiles",String.class)).isNull();
   assertThat(db.queryForObject("SELECT action FROM profile_reviews",String.class)).isEqualTo("rejected");
   assertThat(patch(user,Map.of("nickname","普通昵称")).status()).isEqualTo(200);
   assertThat(db.queryForObject("SELECT moderation_status FROM user_profiles",String.class)).isEqualTo("approved");
 }
 @Test void mediaOwnershipPendingAndRevokedVisibility()throws Exception {
   Reply owner=user("0003"),viewer=user("0004");
   asset(owner,"approved","approved");asset(owner,"pending","review_pending");asset(viewer,"foreign","approved");
   assertThat(patch(owner,Map.of("avatar_url",url("approved"))).status()).isEqualTo(200);
   Reply own=patch(owner,Map.of("avatar_url",url("pending"),"photo_urls",List.of(url("approved"),url("pending"),url("approved"))));
   assertThat(own.text("avatar_url")).isEqualTo(url("approved"));
   assertThat(own.text("pending_avatar_url")).isEqualTo(url("pending"));
   assertThat(own.json().get("photo_urls").size()).isEqualTo(1);
   Reply publicProfile=call("GET","/profiles/"+owner.text("user_id"),null,viewer.text("access_token"));
   assertThat(publicProfile.status()).isEqualTo(200);
   assertThat(publicProfile.raw()).doesNotContain(url("pending"));
   assertThat(publicProfile.json().get("pending_avatar_url").isNull()).isTrue();
   assertThat(patch(owner,Map.of("avatar_url",url("foreign"))).status()).isEqualTo(404);
   assertThat(patch(owner,Map.of("avatar_url","https://unregistered.example.test/a")).status()).isEqualTo(404);
   db.update("UPDATE media_assets SET status='rejected' WHERE id='approved'");
   Reply hidden=call("GET","/profiles/"+owner.text("user_id"),null,viewer.text("access_token"));
   assertThat(hidden.json().get("avatar_url").isNull()).isTrue();
   assertThat(hidden.json().get("photo_urls").size()).isZero();
   assertThat(patch(owner,Map.of("avatar_url",url("approved"))).status()).isEqualTo(409);
 }
 @Test void hiddenDistanceAndBothBlockDirectionsAreEnforced()throws Exception {
   Reply owner=user("0005"),viewer=user("0006");String path="/profiles/"+owner.text("user_id");
   assertThat(patch(owner,Map.of("distance_km",3.5,"distance_visible",false)).json().get("distance_km").asDouble()).isEqualTo(3.5);
   assertThat(call("GET",path,null,viewer.text("access_token")).json().get("distance_km").isNull()).isTrue();
   db.update("INSERT INTO blocks VALUES(?,?)",viewer.text("user_id"),owner.text("user_id"));
   assertThat(call("GET",path,null,viewer.text("access_token")).status()).isEqualTo(404);
   db.update("DELETE FROM blocks");
   db.update("INSERT INTO blocks VALUES(?,?)",owner.text("user_id"),viewer.text("user_id"));
   assertThat(call("GET",path,null,viewer.text("access_token")).status()).isEqualTo(404);
   db.update("DELETE FROM blocks");db.update("UPDATE users SET status='banned' WHERE id=?",owner.text("user_id"));
   assertThat(call("GET",path,null,viewer.text("access_token")).status()).isEqualTo(404);
 }
 @Test void invalidProfileUpdatesAreAtomic()throws Exception {
   Reply user=user("0007");
   assertThat(patch(user,Map.of("age",17)).status()).isEqualTo(422);
   assertThat(patch(user,Map.of("nickname","  ")).status()).isEqualTo(422);
   assertThat(patch(user,Map.of("distance_km",-1)).status()).isEqualTo(422);
   var urls=new ArrayList<String>();for(int i=0;i<10;i++)urls.add(url("image"+i));
   assertThat(patch(user,Map.of("photo_urls",urls)).status()).isEqualTo(400);
   assertThat(patch(user,Map.of("nickname","准备修改","avatar_url",url("missing"))).status()).isEqualTo(404);
   assertThat(db.queryForObject("SELECT nickname FROM users",String.class)).isEqualTo("原昵称");
   assertThat(db.queryForObject("SELECT count(*) FROM profile_reviews",Integer.class)).isZero();
 }
 @Test void profileDatabaseFailureRollsBackNicknameAndAudit()throws Exception {
   Reply user=user("0008");
   db.execute("ALTER TABLE users ADD CONSTRAINT test_nickname_failure CHECK(nickname<>'事务测试')");
   try {
     assertThat(patch(user,Map.of("nickname","事务测试")).status()).isEqualTo(500);
     assertThat(db.queryForObject("SELECT nickname FROM user_profiles",String.class)).isEqualTo("原昵称");
     assertThat(db.queryForObject("SELECT count(*) FROM profile_reviews",Integer.class)).isZero();
   }finally{db.execute("ALTER TABLE users DROP CONSTRAINT test_nickname_failure");}
 }

 @Test void notificationDefaultsPartialPatchesAndTimeValidation()throws Exception {
   Reply user=user("0010");String token=user.text("access_token");
   Reply defaults=call("GET","/notifications/preferences",null,token);
   assertThat(defaults.status()).isEqualTo(200);
   assertThat(defaults.json().get("chat_messages_enabled").asBoolean()).isTrue();
   assertThat(defaults.json().get("night_quiet_enabled").asBoolean()).isFalse();
   assertThat(defaults.text("night_quiet_start")).isEqualTo("22:00");
   assertThat(call("PATCH","/notifications/preferences",Map.of("chat_messages_enabled",false),token).json().get("chat_messages_enabled").asBoolean()).isFalse();
   var patch=new HashMap<String,Object>();patch.put("chat_messages_enabled",null);patch.put("night_quiet_start","23:30");
   Reply result=call("PATCH","/notifications/preferences",patch,token);
   assertThat(result.json().get("chat_messages_enabled").asBoolean()).isFalse();
   assertThat(result.text("night_quiet_start")).isEqualTo("23:30");
   assertThat(call("PATCH","/notifications/preferences",Map.of("night_quiet_start","24:00"),token).status()).isEqualTo(400);
   assertThat(call("PATCH","/notifications/preferences",Map.of("night_quiet_start","9:00"),token).status()).isEqualTo(422);
   assertThat(db.queryForObject("SELECT night_quiet_start FROM notification_preferences",String.class)).isEqualTo("23:30");
 }
 @Test void concurrentFirstPreferenceUpdatesPreserveBothFields()throws Exception {
   Reply user=user("0011");String token=user.text("access_token");
   try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
     List<Callable<Reply>> tasks=List.of(
       ()->call("PATCH","/notifications/preferences",Map.of("chat_messages_enabled",false),token),
       ()->call("PATCH","/notifications/preferences",Map.of("matches_enabled",false),token));
     for(var f:pool.invokeAll(tasks))assertThat(f.get().status()).isEqualTo(200);
   }
   Reply result=call("GET","/notifications/preferences",null,token);
   assertThat(result.json().get("chat_messages_enabled").asBoolean()).isFalse();
   assertThat(result.json().get("matches_enabled").asBoolean()).isFalse();
   assertThat(db.queryForObject("SELECT count(*) FROM notification_preferences",Integer.class)).isEqualTo(1);
 }
 @Test void notificationsAreRecipientScopedAndReadIsIdempotent()throws Exception {
   Reply owner=user("0012"),other=user("0013");String token=owner.text("access_token");
   event(owner,"evt_1");event(other,"evt_other");
   Reply list=call("GET","/notifications/events",null,token);
   assertThat(list.status()).isEqualTo(200);
   assertThat(list.json().size()).isEqualTo(1);
   assertThat(list.json().get(0).get("payload").get("count").asInt()).isEqualTo(2);
   assertThat(list.raw()).doesNotContain("deduplication_key","private-dedup","evt_other");
   assertThat(call("GET","/notifications/unread-count",null,token).json().get("unread_count").asInt()).isEqualTo(1);
   assertThat(call("POST","/notifications/events/evt_other/read",null,token).status()).isEqualTo(404);
   Reply read=call("POST","/notifications/events/evt_1/read",null,token);
   assertThat(read.status()).isEqualTo(200);
   assertThat(read.text("read_at")).contains("T");
   assertThat(call("POST","/notifications/events/evt_1/read",null,token).text("read_at")).isEqualTo(read.text("read_at"));
   assertThat(call("GET","/notifications/unread-count",null,token).json().get("unread_count").asInt()).isZero();
   assertThat(db.queryForObject("SELECT count(*) FROM notification_change_outbox",Integer.class)).isEqualTo(1);
   assertThat(call("GET","/notifications/unread-count",null,other.text("access_token")).json().get("unread_count").asInt()).isEqualTo(1);
 }
 @Test void concurrentReadAllCountsEachEventOnce()throws Exception {
   Reply owner=user("0014"),other=user("0015");String token=owner.text("access_token");
   for(int i=0;i<4;i++)event(owner,"evt_"+i);event(other,"evt_other");
   int marked=0;
   try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
     List<Callable<Reply>> tasks=List.of(
       ()->call("POST","/notifications/events/read-all",null,token),
       ()->call("POST","/notifications/events/read-all",null,token));
     for(var f:pool.invokeAll(tasks)) {Reply result=f.get();assertThat(result.status()).isEqualTo(200);marked+=result.json().get("marked_count").asInt();}
   }
   assertThat(marked).isEqualTo(4);
   assertThat(db.queryForObject("SELECT count(*) FROM notification_change_outbox",Integer.class)).isEqualTo(1);
   assertThat(call("POST","/notifications/events/read-all",null,token).json().get("marked_count").asInt()).isZero();
   assertThat(db.queryForObject("SELECT read_at IS NULL FROM notification_events WHERE id='evt_other'",Boolean.class)).isTrue();
 }
 @Test void failedUnreadSignalRollsBackReadOperation()throws Exception {
   Reply user=user("0016");String token=user.text("access_token");event(user,"evt_rollback");
   db.execute("ALTER TABLE notification_change_outbox ADD CONSTRAINT test_signal_failure CHECK(event_type<>'unread_count_changed')");
   try {
     assertThat(call("POST","/notifications/events/evt_rollback/read",null,token).status()).isEqualTo(500);
     assertThat(db.queryForObject("SELECT read_at IS NULL FROM notification_events",Boolean.class)).isTrue();
     assertThat(call("POST","/notifications/events/read-all",null,token).status()).isEqualTo(500);
     assertThat(db.queryForObject("SELECT read_at IS NULL FROM notification_events",Boolean.class)).isTrue();
   }finally{db.execute("ALTER TABLE notification_change_outbox DROP CONSTRAINT test_signal_failure");}
   assertThat(call("POST","/notifications/events/read-all",null,token).json().get("marked_count").asInt()).isEqualTo(1);
 }
 @Test void allNewRoutesRequireAuthentication()throws Exception {
   for(String path:List.of("/profiles/me","/profiles/unknown","/notifications/preferences","/notifications/events","/notifications/unread-count"))
     assertThat(call("GET",path,null,null).status()).isEqualTo(401);
   for(String path:List.of("/profiles/me","/notifications/preferences"))
     assertThat(call("PATCH",path,Map.of(),null).status()).isEqualTo(401);
   for(String path:List.of("/notifications/events/unknown/read","/notifications/events/read-all"))
     assertThat(call("POST",path,null,null).status()).isEqualTo(401);
 }
}
