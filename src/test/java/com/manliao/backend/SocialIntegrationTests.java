package com.manliao.backend;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.*;
import com.manliao.backend.identity.*;
import com.manliao.backend.social.*;
import com.manliao.backend.notifications.FriendRequestNotifications;
import static org.assertj.core.api.Assertions.*;
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.realtime.worker-enabled=false",
 "app.auth.jwt-secret=isolated-social-tests-secret-at-least-thirty-two-bytes",
 "app.auth.legacy-registration-enabled=true","app.accounts.worker-enabled=false"})
class SocialIntegrationTests {
 @Value("${local.server.port}") int port;
 @Autowired JdbcTemplate db;@Autowired ObjectMapper json;@Autowired AccountLifecycleService accounts;@Autowired SocialService social;
 final HttpClient http=HttpClient.newHttpClient();
 record Reply(int status,JsonNode body){String text(String field){return body.get(field).asString();}}
 record User(String id,String token,String session){}
 @BeforeEach void clean(){db.execute("TRUNCATE users,conversations,auth_rate_windows RESTART IDENTITY CASCADE");}
 Reply call(String method,String path,Object body,User user)throws Exception{
  var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
  if(user!=null)builder.header("Authorization","Bearer "+user.token());
  if(body!=null)builder.header("Content-Type","application/json");
  var response=http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
  return new Reply(response.statusCode(),response.body().isBlank()?null:json.readTree(response.body()));
 }
 User user(int suffix)throws Exception{
  var reply=call("POST","/auth/register",Map.of("phone","1397000"+String.format("%04d",suffix),"password","Social-test-password","nickname","社交测试"+suffix),null);
  assertThat(reply.status()).isEqualTo(200);return new User(reply.text("user_id"),reply.text("access_token"),reply.text("session_id"));
 }
 Reply request(User a,User b)throws Exception{return call("POST","/friends/requests",Map.of("target_user_id",b.id(),"message","你好"),a);}
 Reply accept(User receiver,Reply request)throws Exception{return call("POST","/friends/requests/"+request.text("id")+"/accept",null,receiver);}
 Reply block(User a,User b)throws Exception{return call("POST","/safety/blocks",Map.of("target_user_id",b.id()),a);}
 void friends(User a,User b)throws Exception{var r=request(a,b);assertThat(r.status()).isEqualTo(200);assertThat(accept(b,r).status()).isEqualTo(200);}
 int count(String table){return db.queryForObject("SELECT count(*) FROM "+table,Integer.class);}
 void due(User u)throws Exception{
  assertThat(call("POST","/auth/account/deactivate",null,u).status()).isEqualTo(200);
  db.update("UPDATE users SET deactivation_due_at=now()-interval '1 second' WHERE id=?",u.id());
 }
 void device(User u){
  db.update("INSERT INTO push_devices(id,user_id,device_id,platform,push_token,provider) VALUES(?,?,?,'android','fixture-token','development')","push_"+u.id(),u.id(),"dev_"+u.id());
 }
 @Test void directoryHasOnlyPublicFieldsAndLiteralSearch()throws Exception{
  User a=user(1),b=user(2);
  db.update("UPDATE users SET nickname='literal%_name' WHERE id=?",b.id());
  assertThat(call("GET","/users/?q=%25_",null,a).body().size()).isEqualTo(1);
  assertThat(call("GET","/users/",null,a).body().size()).isZero();
  var detail=call("GET","/users/"+b.id(),null,a);
  assertThat(detail.status()).isEqualTo(200);
  assertThat(json.convertValue(detail.body(),Map.class).keySet()).containsExactlyInAnyOrder("id","nickname");
  assertThat(call("GET","/users?q="+b.id()+"&limit=1",null,a).body().get(0).get("id").asString()).isEqualTo(b.id());
  db.update("UPDATE users SET status='deactivation_pending' WHERE id=?",b.id());
  assertThat(call("GET","/users/"+b.id(),null,a).status()).isEqualTo(404);
  assertThat(call("GET","/users?q="+b.id(),null,a).body().size()).isZero();
 }
 @Test void fullWorkflowCreatesAtomicNotificationAndConversation()throws Exception{
  User a=user(1),b=user(2);
  Reply r=request(a,b);assertThat(r.status()).isEqualTo(200);assertThat(r.text("status")).isEqualTo("pending");
  assertThat(json.convertValue(r.body(),Map.class).keySet()).containsExactlyInAnyOrder("id","requester_id","receiver_id","message","status","created_at");
  assertThat(call("GET","/friends/requests",null,b).body().size()).isEqualTo(1);
  assertThat(call("GET","/friends/requests",null,a).body().size()).isEqualTo(1);
  assertThat(count("notification_events")).isEqualTo(1);assertThat(count("notification_change_outbox")).isEqualTo(1);
  var event=db.queryForMap("SELECT * FROM notification_events");
  assertThat(event.get("source_id")).isEqualTo(r.text("id"));assertThat(event.get("suppress_reason")).isEqualTo("no_enabled_device");
  assertThat(accept(b,r).text("status")).isEqualTo("accepted");
  assertThat(count("friendships")).isEqualTo(1);assertThat(count("conversations")).isEqualTo(1);assertThat(count("conversation_member_states")).isEqualTo(2);
  var profiles=call("GET","/friends/",null,a);assertThat(profiles.body().size()).isEqualTo(1);
  assertThat(profiles.body().get(0).get("user_id").asString()).isEqualTo(b.id());
  assertThat(profiles.body().get(0).get("pending_photo_urls").size()).isZero();
  assertThat(accept(b,r).status()).isEqualTo(200);assertThat(count("conversations")).isEqualTo(1);
 }
 @Test void concurrentDuplicateRequestsAndAcceptsAreIdempotent()throws Exception{
  User a=user(1),b=user(2);
  List<Reply> replies=new ArrayList<>();
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   for(var f:pool.invokeAll(List.<Callable<Reply>>of(()->request(a,b),()->request(a,b))))replies.add(f.get());
   assertThat(replies).allMatch(r->r.status()==200);
   assertThat(replies.get(0).text("id")).isEqualTo(replies.get(1).text("id"));
   Reply request=replies.getFirst();
   for(var f:pool.invokeAll(List.<Callable<Reply>>of(()->accept(b,request),()->accept(b,request))))assertThat(f.get().status()).isEqualTo(200);
  }
  assertThat(count("friend_requests")).isEqualTo(1);assertThat(count("notification_events")).isEqualTo(1);
  assertThat(count("notification_change_outbox")).isEqualTo(1);assertThat(count("friendships")).isEqualTo(1);
 }
 @Test void oppositeAcceptsShareOneConversation()throws Exception{
  User a=user(1),b=user(2);Reply ab=request(a,b),ba=request(b,a);
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   for(var f:pool.invokeAll(List.<Callable<Reply>>of(()->accept(b,ab),()->accept(a,ba))))assertThat(f.get().status()).isEqualTo(200);
  }
  assertThat(count("friendships")).isEqualTo(1);assertThat(count("conversations")).isEqualTo(1);
  assertThat(db.queryForObject("SELECT count(*) FROM friend_requests WHERE status='accepted'",Integer.class)).isEqualTo(2);
 }
 @Test void resolutionOwnershipAndTerminalStatesAreEnforced()throws Exception{
  User a=user(1),b=user(2),c=user(3);Reply r=request(a,b);
  assertThat(accept(a,r).status()).isEqualTo(403);assertThat(accept(c,r).status()).isEqualTo(403);
  assertThat(call("POST","/friends/requests/"+r.text("id")+"/reject",null,b).status()).isEqualTo(200);
  assertThat(accept(b,r).status()).isEqualTo(409);assertThat(count("friendships")).isZero();
  Reply again=request(a,b);assertThat(again.text("id")).isNotEqualTo(r.text("id"));accept(b,again);
  assertThat(call("DELETE","/friends/"+b.id(),null,a).status()).isEqualTo(200);
  assertThat(accept(b,again).status()).isEqualTo(200);assertThat(count("friendships")).isZero();
  friends(a,b);assertThat(count("conversations")).isEqualTo(1);
  assertThat(call("DELETE","/friends/"+a.id(),null,c).status()).isEqualTo(404);
 }
 @Test void blockIsPrivateIdempotentAndBidirectional()throws Exception{
  User a=user(1),b=user(2),c=user(3);friends(a,b);
  Reply blocked=call("POST","/safety/blocks",Map.of("target_user_id",b.id(),"block_type","hide_from_explore"),a);
  assertThat(blocked.status()).isEqualTo(200);assertThat(block(a,b).text("id")).isEqualTo(blocked.text("id"));
  assertThat(count("blocks")).isEqualTo(1);assertThat(block(a,b).text("block_type")).isEqualTo("block");
  assertThat(call("GET","/users/"+a.id(),null,b).status()).isEqualTo(404);
  assertThat(call("GET","/profiles/"+b.id(),null,a).status()).isEqualTo(404);
  assertThat(call("GET","/friends/",null,a).body().size()).isZero();
  assertThat(call("GET","/friends/",null,b).body().size()).isZero();
  assertThat(request(b,a).status()).isEqualTo(404);
  assertThat(call("GET","/safety/blocks",null,b).body().size()).isZero();
  assertThat(call("DELETE","/safety/blocks/"+blocked.text("id"),null,c).status()).isEqualTo(404);
  assertThat(call("DELETE","/safety/blocks/"+blocked.text("id"),null,a).status()).isEqualTo(204);
  assertThat(call("GET","/friends/",null,a).body().size()).isEqualTo(1);
  assertThat(call("DELETE","/safety/blocks/"+blocked.text("id"),null,a).status()).isEqualTo(404);
 }
 @Test void blockVersusAcceptCannotExposeBlockedFriendOrLeavePendingRequest()throws Exception{
  User a=user(1),b=user(2);device(b);Reply r=request(a,b);
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var futures=pool.invokeAll(List.<Callable<Reply>>of(()->block(a,b),()->accept(b,r)));
   assertThat(futures.get(0).get().status()).isEqualTo(200);
   assertThat(futures.get(1).get().status()).isIn(200,404);
  }
  assertThat(call("GET","/friends",null,b).body().size()).isZero();
  assertThat(db.queryForObject("SELECT count(*) FROM friend_requests WHERE status='pending'",Integer.class)).isZero();
  assertThat(db.queryForObject("SELECT suppress_reason FROM notification_events",String.class)).isEqualTo("blocked");
 }
 @Test void notificationPreferencesSuppressPushButKeepInboxEvent()throws Exception{
  User a=user(1),b=user(2);device(b);
  db.update("INSERT INTO notification_preferences(user_id,friend_requests_enabled) VALUES(?,false)",b.id());
  request(a,b);
  assertThat(db.queryForObject("SELECT suppress_reason FROM notification_events",String.class)).isEqualTo("notification_preference_disabled");
  assertThat(call("GET","/notifications/events",null,b).status()).isEqualTo(200);
  assertThat(db.queryForObject("SELECT count(*) FROM notification_events WHERE read_at IS NULL",Integer.class)).isEqualTo(1);
 }
 @Test void enabledDeviceQueuesPushWithoutClaimingDelivery()throws Exception{
  User a=user(1),b=user(2);device(b);request(a,b);
  var event=db.queryForMap("SELECT status,delivery_channel FROM notification_events");
  assertThat(event.get("status")).isEqualTo("pending");assertThat(event.get("delivery_channel")).isEqualTo("offline_push");
 }
 @Test void notificationFailureRollsBackRequestPreferencesAndOutbox()throws Exception{
  User a=user(1),b=user(2);
  db.execute("ALTER TABLE notification_events ADD CONSTRAINT test_social_failure CHECK(event_type<>'friend_request')");
  try{
   assertThat(request(a,b).status()).isEqualTo(500);
   assertThat(count("friend_requests")).isZero();assertThat(count("notification_preferences")).isZero();assertThat(count("notification_change_outbox")).isZero();
  }finally{db.execute("ALTER TABLE notification_events DROP CONSTRAINT test_social_failure");}
 }
 @Test void conversationFailureRollsBackAcceptance()throws Exception{
  User a=user(1),b=user(2);Reply r=request(a,b);
  db.execute("ALTER TABLE conversations ADD CONSTRAINT test_social_failure CHECK(type<>'friend')");
  try{
   assertThat(accept(b,r).status()).isEqualTo(500);
   assertThat(count("friendships")).isZero();assertThat(count("conversations")).isZero();
   assertThat(db.queryForObject("SELECT status FROM friend_requests",String.class)).isEqualTo("pending");
  }finally{db.execute("ALTER TABLE conversations DROP CONSTRAINT test_social_failure");}
 }
 @Test void erasureCleansBothDirectionsAndConversationsWithoutTouchingUnrelatedFriends()throws Exception{
  User a=user(1),b=user(2),c=user(3),d=user(4);
  friends(a,b);friends(b,c);request(c,a);request(a,d);
  due(a);
  assertThat(((Map<?,?>)accounts.preview(a.id()).get("counts")).get("conversations")).isEqualTo(1);
  assertThat(accounts.eraseDue(a.id())).isTrue();
  assertThat(count("friendships")).isEqualTo(1);assertThat(count("conversations")).isEqualTo(1);assertThat(count("conversation_member_states")).isEqualTo(2);
  assertThat(db.queryForObject("SELECT count(*) FROM friend_requests WHERE requester_id=? OR receiver_id=?",Integer.class,a.id(),a.id())).isZero();
  assertThat(call("GET","/friends",null,b).body().get(0).get("user_id").asString()).isEqualTo(c.id());
  assertThat(call("GET","/friends",null,a).status()).isEqualTo(401);
  assertThatThrownBy(()->social.request(new AuthDtos.Principal(a.id(),a.session()),new SocialDtos.FriendRequest(d.id(),"stale"))).isInstanceOf(com.manliao.backend.common.ApiError.class);
  assertThat(db.queryForObject("SELECT count(*) FROM auth_rate_windows WHERE bucket_key=?",Integer.class,"social-request:"+a.id())).isZero();
 }
 @Test void erasureAndSocialWriteDoNotDeadlockOrResurrectData()throws Exception{
  User a=user(1),b=user(2);Reply r=request(a,b);due(a);
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var erase=pool.submit(()->accounts.eraseDue(a.id()));
   var accept=pool.submit(()->accept(b,r));
   assertThat(erase.get(15,TimeUnit.SECONDS)).isTrue();assertThat(accept.get(15,TimeUnit.SECONDS).status()).isEqualTo(404);
  }
  assertThat(count("friendships")).isZero();assertThat(count("conversations")).isZero();assertThat(count("friend_requests")).isZero();
 }
 @Test void validationAuthAndPerUserRateLimit()throws Exception{
  User a=user(1),b=user(2),c=user(3);
  assertThat(call("GET","/users/",null,null).status()).isEqualTo(401);
  assertThat(request(a,a).status()).isEqualTo(400);assertThat(block(a,a).status()).isEqualTo(400);
  assertThat(call("POST","/friends/requests",Map.of("target_user_id",b.id(),"message","x".repeat(121)),a).status()).isEqualTo(422);
  assertThat(call("POST","/safety/blocks",Map.of("target_user_id",b.id(),"block_type","invalid"),a).status()).isEqualTo(422);
  for(String limit:List.of("0","51","abc"))assertThat(call("GET","/users/?limit="+limit,null,a).status()).isEqualTo(422);
  assertThat(call("GET","/users/?q="+"x".repeat(101),null,a).status()).isEqualTo(422);
  Reply r=request(a,b);
  db.update("UPDATE auth_rate_windows SET hits=30 WHERE bucket_key=?","social-request:"+a.id());
  assertThat(request(a,b).text("id")).isEqualTo(r.text("id"));
  assertThat(request(a,c).status()).isEqualTo(429);assertThat(count("friend_requests")).isEqualTo(1);
 }
 @Test void quietWindowUsesLegacyUtcAndHandlesMidnightAndEqualEndpoints(){
  assertThat(FriendRequestNotifications.quiet(LocalTime.of(23,0),LocalTime.of(22,0),LocalTime.of(8,0))).isTrue();
  assertThat(FriendRequestNotifications.quiet(LocalTime.of(7,59),LocalTime.of(22,0),LocalTime.of(8,0))).isTrue();
  assertThat(FriendRequestNotifications.quiet(LocalTime.of(8,0),LocalTime.of(22,0),LocalTime.of(8,0))).isFalse();
  assertThat(FriendRequestNotifications.quiet(LocalTime.NOON,LocalTime.of(8,0),LocalTime.of(13,0))).isTrue();
  assertThat(FriendRequestNotifications.quiet(LocalTime.NOON,LocalTime.of(8,0),LocalTime.of(8,0))).isFalse();
 }
}
