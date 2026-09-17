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
 "app.auth.jwt-secret=isolated-explore-tests-secret-at-least-thirty-two-bytes",
 "app.auth.legacy-registration-enabled=true","app.accounts.worker-enabled=false"})
class ExploreIntegrationTests {
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
 Reply like(User a,User b)throws Exception{return call("POST","/explore/likes/"+b.id(),null,a);}
 @Test void freeUserCanLikeMoreThanAnyFormerDailyQuotaWithoutCreatingUnilateralChats()throws Exception{
  var a=user(1);assertThat(db.queryForObject("SELECT is_paid_member FROM users WHERE id=?",Boolean.class,a.id())).isFalse();
  for(int i=0;i<150;i++){
   String id="candidate_"+i;db.update("INSERT INTO users(id,phone,nickname,password_hash) VALUES(?,?,?,'fixture')",id,"fixture-phone-"+i,"candidate");
   assertThat(like(a,new User(id,"","")).status()).as("like %s",i).isEqualTo(200);
  }
  assertThat(count("explore_actions")).isEqualTo(150);assertThat(count("matches")).isZero();assertThat(count("conversations")).isZero();
  assertThat(db.queryForObject("SELECT count(*) FROM auth_rate_windows WHERE bucket_key LIKE 'social-request:%'",Integer.class)).isZero();
 }
 @Test void mutualLikesCreateOneFriendshipAndWorkingChatAndTwoNotifications()throws Exception{
  var a=user(1);var b=user(2);device(a);device(b);
  var first=like(a,b);assertThat(first.status()).isEqualTo(200);assertThat(first.body().get("match").isNull()).isTrue();assertThat(count("conversations")).isZero();
  var second=like(b,a);assertThat(second.status()).isEqualTo(200);String conversation=second.text("conversation_id");
  assertThat(second.body().get("match").get("user_ids").size()).isEqualTo(2);
  assertThat(count("matches")).isEqualTo(1);assertThat(count("friendships")).isEqualTo(1);assertThat(count("notification_events")).isEqualTo(2);
  for(int i=0;i<5;i++){assertThat(like(a,b).text("conversation_id")).isEqualTo(conversation);assertThat(like(b,a).status()).isEqualTo(200);}
  assertThat(count("explore_actions")).isEqualTo(2);assertThat(count("notification_events")).isEqualTo(2);
  assertThat(db.queryForObject("SELECT count(*) FROM notification_events WHERE event_type='match_created' AND status='pending'",Integer.class)).isEqualTo(2);
  assertThat(call("POST","/conversations/"+conversation+"/messages",Map.of("content","matched chat","client_message_id","match-chat-123"),a).status()).isEqualTo(200);
  assertThat(call("GET","/conversations/"+conversation+"/messages",null,b).body().size()).isEqualTo(1);
  assertThat(call("GET","/explore/liked-profiles",null,a).body().size()).isZero();
 }
 @Test void concurrentReciprocalLikesAreIdempotent()throws Exception{
  var a=user(1);var b=user(2);
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var gate=new CountDownLatch(1);var work=new ArrayList<Future<Reply>>();
   for(int i=0;i<12;i++){boolean forward=i%2==0;work.add(pool.submit(()->{gate.await();return forward?like(a,b):like(b,a);}));}
   gate.countDown();for(var result:work)assertThat(result.get(15,TimeUnit.SECONDS).status()).isEqualTo(200);
  }
  assertThat(count("matches")).isEqualTo(1);assertThat(count("friendships")).isEqualTo(1);assertThat(count("conversations")).isEqualTo(1);assertThat(count("explore_actions")).isEqualTo(2);assertThat(count("notification_events")).isEqualTo(2);
 }
 @Test void retiredSuperLikeHasNoSideEffects()throws Exception{
  var a=user(1);var b=user(2);
  var reply=call("POST","/explore/super-likes/"+b.id(),null,a);
  assertThat(reply.status()).isEqualTo(410);assertThat(reply.body().toString()).contains("EXPLORE_SUPER_LIKE_REMOVED");
  assertThat(count("explore_actions")).isZero();assertThat(count("conversations")).isZero();assertThat(count("notification_events")).isZero();
 }
 @Test void unlimitedUndoRetractsUnmatchedLikesAndSkipsOnce()throws Exception{
  var a=user(1);var b=user(2);
  for(int i=0;i<8;i++){
   assertThat(like(a,b).status()).isEqualTo(200);
   var undo=call("POST","/explore/undo",null,a);assertThat(undo.status()).isEqualTo(200);assertThat(undo.body().get("undo_unlimited").asBoolean()).isTrue();
   assertThat(call("POST","/explore/undo",null,a).status()).isEqualTo(409);
  }
  assertThat(like(b,a).body().get("match").isNull()).isTrue();
  assertThat(call("GET","/explore/liked-profiles",null,a).body().size()).isZero();
  assertThat(call("POST","/explore/skips/"+b.id(),null,a).status()).isEqualTo(200);
  assertThat(call("POST","/explore/undo",null,a).status()).isEqualTo(200);
  assertThat(count("matches")).isZero();
 }
 @Test void blockSelfInactiveAndExpiredSessionCannotLike()throws Exception{
  var a=user(1);var b=user(2);
  assertThat(like(a,a).status()).isEqualTo(422);
  assertThat(like(a,new User("missing","","")).status()).isEqualTo(404);
  db.update("UPDATE users SET status='deactivation_pending' WHERE id=?",b.id());assertThat(like(a,b).status()).isEqualTo(404);
  db.update("UPDATE users SET status='active' WHERE id=?",b.id());assertThat(block(b,a).status()).isEqualTo(200);assertThat(like(a,b).status()).isEqualTo(404);
  db.update("DELETE FROM blocks");db.update("UPDATE refresh_tokens SET revoked_at=now() WHERE id=?",a.session());assertThat(like(a,b).status()).isEqualTo(401);
  assertThat(count("explore_actions")).isZero();
 }
 @Test void matchesArePrivateAndHiddenAfterBlock()throws Exception{
  var a=user(1);var b=user(2);var c=user(3);like(a,b);var matched=like(b,a);String id=matched.body().get("match").get("id").asString();
  assertThat(call("GET","/matches/"+id,null,a).status()).isEqualTo(200);assertThat(call("GET","/matches/"+id,null,c).status()).isEqualTo(404);
  assertThat(call("GET","/matches/",null,c).body().size()).isZero();
  block(a,b);assertThat(call("GET","/matches/",null,b).body().size()).isZero();assertThat(call("GET","/matches/"+id,null,a).status()).isEqualTo(404);
 }
 @Test void existingFriendChatIsReusedAndNotificationPreferenceHonored()throws Exception{
  var a=user(1);var b=user(2);friends(a,b);String before=db.queryForObject("SELECT conversation_id FROM friendships",String.class);
  db.update("INSERT INTO notification_preferences(user_id,matches_enabled) VALUES(?,false) ON CONFLICT(user_id) DO UPDATE SET matches_enabled=false",a.id());
  like(a,b);var match=like(b,a);assertThat(match.text("conversation_id")).isEqualTo(before);assertThat(count("conversations")).isEqualTo(1);
  assertThat(db.queryForObject("SELECT suppress_reason FROM notification_events WHERE recipient_user_id=? AND event_type='match_created'",String.class,a.id())).isEqualTo("notification_preference_disabled");
 }
 @Test void transactionFailureDoesNotLeaveLikeOrHalfAMatch()throws Exception{
  var a=user(1);var b=user(2);like(a,b);db.execute("ALTER TABLE matches ADD CONSTRAINT fixture_reject CHECK (false) NOT VALID");
  try{assertThat(like(b,a).status()).isEqualTo(500);assertThat(count("matches")).isZero();assertThat(count("friendships")).isZero();assertThat(count("conversations")).isZero();assertThat(count("explore_actions")).isEqualTo(1);}
  finally{db.execute("ALTER TABLE matches DROP CONSTRAINT fixture_reject");}
  assertThat(like(b,a).status()).isEqualTo(200);assertThat(count("matches")).isEqualTo(1);
 }
 @Test void erasureDeletesLikesMatchesAndPrivateChat()throws Exception{
  var a=user(1);var b=user(2);like(a,b);like(b,a);due(a);
  assertThat(accounts.eraseDue(a.id())).isTrue();assertThat(count("matches")).isZero();assertThat(count("explore_actions")).isZero();assertThat(count("friendships")).isZero();assertThat(count("conversations")).isZero();
 }
 void portrait(User u,String status,boolean portrait){
  String id="portrait_"+u.id(),url="media:"+id;
  db.update("INSERT INTO media_assets(id,owner_user_id,url,media_type,status,source,portrait_manual_approved) VALUES(?,?,?,'image',?,'profile',?)",id,u.id(),url,status,portrait);
  db.update("UPDATE user_profiles SET photo_urls=jsonb_build_array(?::text) WHERE user_id=?",url,u.id());
 }
 @Test void candidatesRequireApprovedPortraitAndRespectBlocksAndCounts()throws Exception{
  var a=user(1);var b=user(2);var c=user(3);var d=user(4);portrait(b,"approved",true);portrait(c,"review_pending",true);portrait(d,"approved",false);
  like(b,a);assertThat(call("POST","/explore/exposures/"+b.id(),null,a).status()).isEqualTo(200);
  var candidates=call("GET","/explore/candidates",null,a);assertThat(candidates.status()).isEqualTo(200);assertThat(candidates.body().size()).isEqualTo(1);
  var card=candidates.body().get(0);assertThat(card.get("profile").get("user_id").asString()).isEqualTo(b.id());assertThat(card.get("exposure_count").asInt()).isEqualTo(1);assertThat(card.get("total_liked_me_count").asInt()).isEqualTo(1);
  assertThat(card.get("likes_unlimited").asBoolean()).isTrue();assertThat(card.get("undo_remaining_today").isNull()).isTrue();
  assertThat(call("GET","/explore/candidates?limit=0",null,a).status()).isEqualTo(422);
  assertThat(call("POST","/explore/blocks/"+b.id(),null,a).status()).isEqualTo(200);
  assertThat(call("GET","/explore/candidates",null,a).body().size()).isZero();
 }
 @Test void likedProfilesOnlyContainOrdinaryUnmatchedVisibleLikes()throws Exception{
  var a=user(1);var b=user(2);like(a,b);var list=call("GET","/explore/liked-profiles",null,a);
  assertThat(list.body().size()).isEqualTo(1);assertThat(list.body().get(0).get("liked_action_type").asString()).isEqualTo("like");
  assertThat(list.body().toString()).doesNotContain("super_like");block(b,a);assertThat(call("GET","/explore/liked-profiles",null,a).body().size()).isZero();
 }
 @Test void candidatePagesRotateWithoutRestrictingDailyDecisions()throws Exception{
  var a=user(1);var b=user(2);var c=user(3);portrait(b,"approved",true);portrait(c,"approved",true);
  var first=call("GET","/explore/candidates?limit=1",null,a);String target=first.body().get(0).get("profile").get("user_id").asString();
  assertThat(call("POST","/explore/skips/"+target,null,a).status()).isEqualTo(200);
  String next=call("GET","/explore/candidates?limit=1",null,a).body().get(0).get("profile").get("user_id").asString();assertThat(next).isNotEqualTo(target);
  call("POST","/explore/undo",null,a);assertThat(call("GET","/explore/candidates?limit=1",null,a).body().get(0).get("profile").get("user_id").asString()).isEqualTo(target);
 }}