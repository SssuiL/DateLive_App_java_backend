package com.manliao.backend;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.*;
import com.manliao.backend.chat.*;
import com.manliao.backend.identity.*;
import static org.assertj.core.api.Assertions.*;
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.realtime.worker-enabled=false",
 "app.auth.jwt-secret=isolated-chat-tests-secret-at-least-thirty-two-bytes",
 "app.auth.legacy-registration-enabled=true","app.accounts.worker-enabled=false"})
class ChatIntegrationTests {
 @Value("${local.server.port}") int port;
 @Autowired JdbcTemplate db;@Autowired ObjectMapper json;@Autowired AccountLifecycleService accounts;@Autowired ChatService chat;
 final HttpClient http=HttpClient.newHttpClient();int sequence;
 record Reply(int status,JsonNode body){String text(String key){return body.get(key).asString();}}
 record User(String id,String token,String session){}
 record Pair(User a,User b,String id){}
 @BeforeEach void clean(){db.execute("TRUNCATE users,conversations,auth_rate_windows RESTART IDENTITY CASCADE");sequence=1;}
 Reply call(String method,String path,Object body,User user)throws Exception{
  var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(java.time.Duration.ofSeconds(15));
  if(user!=null)b.header("Authorization","Bearer "+user.token());
  if(body!=null)b.header("Content-Type","application/json");
  var response=http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
  return new Reply(response.statusCode(),response.body().isBlank()?null:json.readTree(response.body()));
 }
 User user()throws Exception{
  var r=call("POST","/auth/register",Map.of("phone","1398000"+String.format("%04d",sequence++),"nickname","聊天测试","password","Chat-test-password"),null);
  assertThat(r.status()).isEqualTo(200);return new User(r.text("user_id"),r.text("access_token"),r.text("session_id"));
 }
 String friend(User a,User b)throws Exception{
  var r=call("POST","/friends/requests",Map.of("target_user_id",b.id()),a);assertThat(r.status()).isEqualTo(200);
  assertThat(call("POST","/friends/requests/"+r.text("id")+"/accept",null,b).status()).isEqualTo(200);
  return db.queryForObject("SELECT conversation_id FROM friendships WHERE (user_a_id=? AND user_b_id=?) OR (user_a_id=? AND user_b_id=?)",String.class,a.id(),b.id(),b.id(),a.id());
 }
 Pair pair()throws Exception{User a=user(),b=user();return new Pair(a,b,friend(a,b));}
 String path(String id){return "/conversations/"+id;}
 Reply send(User u,String id,String key,String text)throws Exception{return call("POST",path(id)+"/messages",Map.of("client_message_id",key,"content",text),u);}
 Reply read(User u,String id)throws Exception{return call("POST",path(id)+"/read",null,u);}
 int count(String table){return db.queryForObject("SELECT count(*) FROM "+table,Integer.class);}
 int unread(User u,String id){return db.queryForObject("SELECT unread_count FROM conversation_member_states WHERE user_id=? AND conversation_id=?",Integer.class,u.id(),id);}
 List<String> ids(JsonNode items){var out=new ArrayList<String>();for(var item:items)out.add(item.get("id").asString());return out;}
 void device(User u){db.update("INSERT INTO push_devices(id,user_id,device_id,platform,push_token,provider) VALUES(?,?,?,'android','fixture','development')","dev_"+u.id(),u.id(),u.id());}
 @Test void sendHistoryAndReadExposeCompatibleFieldsWithoutFakeDelivery()throws Exception{
  Pair p=pair();Reply r=send(p.a(),p.id(),"message-0001","你好");
  assertThat(r.status()).isEqualTo(200);assertThat(r.text("type")).isEqualTo("text");
  assertThat(r.body().get("read_by").get(0).asString()).isEqualTo(p.a().id());
  assertThat(r.body().get("delivered_to_user_ids").size()).isZero();
  assertThat(r.body().get("reply_preview").isNull()).isTrue();assertThat(r.body().get("is_recalled").asBoolean()).isFalse();
  assertThat(unread(p.b(),p.id())).isEqualTo(1);assertThat(unread(p.a(),p.id())).isZero();
  Reply detail=call("GET",path(p.id()),null,p.b());assertThat(detail.text("last_message")).isEqualTo("你好");
  assertThat(detail.body().get("participant_ids").size()).isEqualTo(2);
  assertThat(detail.body().get("participant_online").size()).isEqualTo(2); assertThat(detail.body().get("participant_online").get(p.a().id()).asBoolean()).isFalse();
  assertThat(call("GET",path(p.id())+"/messages",null,p.b()).body().get(0).get("id").asString()).isEqualTo(r.text("id"));
  assertThat(read(p.b(),p.id()).body().get("unread_count").asInt()).isZero();
  var after=call("GET",path(p.id())+"/messages",null,p.a()).body().get(0);
  assertThat(after.get("read_by").size()).isEqualTo(2);
  assertThat(after.get("delivered_to_user_ids").get(0).asString()).isEqualTo(p.b().id());
  assertThat(count("chat_change_outbox")).isEqualTo(2);
  read(p.b(),p.id());assertThat(count("chat_change_outbox")).isEqualTo(2);
 }
 @Test void duplicateConcurrentSendDoesNotDoubleCountOrDoubleNotify()throws Exception{
  Pair p=pair();List<Reply> result=new ArrayList<>();
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   for(var f:pool.invokeAll(List.<Callable<Reply>>of(()->send(p.a(),p.id(),"message-same","same"),()->send(p.a(),p.id(),"message-same","same"))))result.add(f.get());
  }
  assertThat(result).allMatch(r->r.status()==200);assertThat(result.get(0).text("id")).isEqualTo(result.get(1).text("id"));
  assertThat(count("messages")).isEqualTo(1);assertThat(unread(p.b(),p.id())).isEqualTo(1);assertThat(count("chat_change_outbox")).isEqualTo(1);
  assertThat(db.queryForObject("SELECT count(*) FROM notification_events WHERE category='chat'",Integer.class)).isEqualTo(1);
  assertThat(send(p.a(),p.id(),"message-same","changed").status()).isEqualTo(409);
  assertThat(send(p.b(),p.id(),"message-same","independent sender").status()).isEqualTo(200);
 }
 @Test void replyMustBeInSameConversationAndIdempotentPayloadMustMatch()throws Exception{
  Pair p=pair();User c=user();String other=friend(p.a(),c);
  Reply first=send(p.a(),p.id(),"message-origin","原文");
  Reply reply=call("POST",path(p.id())+"/messages",Map.of("client_message_id","message-reply","content","回复","reply_to_message_id",first.text("id")),p.b());
  assertThat(reply.status()).isEqualTo(200);assertThat(reply.body().get("reply_preview").get("content").asString()).isEqualTo("原文");
  assertThat(call("POST",path(other)+"/messages",Map.of("content","cross","reply_to_message_id",first.text("id")),p.a()).status()).isEqualTo(400);
  assertThat(call("POST",path(p.id())+"/messages",Map.of("client_message_id","message-reply","content","回复"),p.b()).status()).isEqualTo(409);
 }
 @Test void membershipAndBlockProtectAllChatRoutes()throws Exception{
  Pair p=pair();User stranger=user();send(p.a(),p.id(),"message-private","private");
  for(String suffix:List.of("","/messages","/messages/page"))assertThat(call("GET",path(p.id())+suffix,null,stranger).status()).isEqualTo(404);
  assertThat(send(stranger,p.id(),"message-attack","x").status()).isEqualTo(404);
  assertThat(read(stranger,p.id()).status()).isEqualTo(404);
  assertThat(call("PATCH",path(p.id())+"/settings",Map.of("muted",true),stranger).status()).isEqualTo(404);
  call("POST","/safety/blocks",Map.of("target_user_id",p.b().id()),p.a());
  assertThat(call("GET","/conversations/",null,p.b()).body().size()).isZero();
  for(String suffix:List.of("","/messages","/messages/page"))assertThat(call("GET",path(p.id())+suffix,null,p.b()).status()).isEqualTo(404);
  assertThat(send(p.b(),p.id(),"message-block","x").status()).isEqualTo(404);
 }
 @Test void deletingFriendHidesListButKeepsHistoryAndRejectsNewMessages()throws Exception{
  Pair p=pair();send(p.a(),p.id(),"message-history","history");
  call("DELETE","/friends/"+p.b().id(),null,p.a());
  assertThat(call("GET","/conversations/",null,p.a()).body().size()).isZero();
  assertThat(call("GET",path(p.id())+"/messages",null,p.a()).body().size()).isEqualTo(1);
  assertThat(send(p.a(),p.id(),"message-newer","new").status()).isEqualTo(403);
  assertThat(friend(p.a(),p.b())).isEqualTo(p.id());
  assertThat(call("GET","/conversations",null,p.a()).body().size()).isEqualTo(1);
 }
 @Test void settingsArePerUserAndMuteSuppressesNotificationNotUnread()throws Exception{
  Pair p=pair();device(p.b());
  var settings=call("PATCH",path(p.id())+"/settings",Map.of("pinned",true,"muted",true),p.b());
  assertThat(settings.body().get("muted").asBoolean()).isTrue();
  var own=call("GET",path(p.id()),null,p.a());
  assertThat(own.body().get("pinned").asBoolean()).isFalse();assertThat(own.body().get("muted").asBoolean()).isFalse();
  send(p.a(),p.id(),"message-muted","quiet");
  assertThat(unread(p.b(),p.id())).isEqualTo(1);
  assertThat(db.queryForObject("SELECT suppress_reason FROM notification_events WHERE category='chat'",String.class)).isEqualTo("conversation_muted");
  call("PATCH",path(p.id())+"/settings",new HashMap<>(Map.of("pinned",false)),p.b());
  assertThat(call("GET",path(p.id()),null,p.b()).body().get("muted").asBoolean()).isTrue();
  var nulls=new HashMap<String,Object>();nulls.put("muted",null);
  assertThat(call("PATCH",path(p.id())+"/settings",nulls,p.b()).body().get("muted").asBoolean()).isTrue();
 }
 @Test void messagePaginationHandlesTimestampTiesAndConcurrentNewMessage()throws Exception{
  Pair p=pair();
  for(int i=0;i<5;i++)send(p.a(),p.id(),"message-page-"+i,"message"+i);
  db.update("UPDATE messages SET created_at='2026-01-01T00:00:00Z'");
  var expected=db.queryForList("SELECT id FROM messages ORDER BY created_at,id",String.class);
  var first=call("GET",path(p.id())+"/messages/page?limit=2",null,p.b()).body();
  assertThat(ids(first.get("items"))).containsExactlyElementsOf(expected.subList(3,5));
  send(p.a(),p.id(),"message-newest","new after page");
  var second=call("GET",path(p.id())+"/messages/page?limit=2&cursor="+first.get("next_cursor").asString(),null,p.b()).body();
  assertThat(ids(second.get("items"))).containsExactlyElementsOf(expected.subList(1,3));
  var third=call("GET",path(p.id())+"/messages/page?limit=2&cursor="+second.get("next_cursor").asString(),null,p.b()).body();
  assertThat(ids(third.get("items"))).containsExactly(expected.getFirst());
  assertThat(third.get("has_more").asBoolean()).isFalse();assertThat(third.get("next_cursor").isNull()).isTrue();
 }
 @Test void conversationPagesRespectOwnPinAndCursor()throws Exception{
  Pair p=pair();User c=user(),d=user();String second=friend(p.a(),c),third=friend(p.a(),d);
  db.update("UPDATE conversations SET last_active_at='2026-01-01T00:00:00Z'");
  call("PATCH",path(second)+"/settings",Map.of("pinned",true),p.a());
  List<String> collected=new ArrayList<>();String cursor=null;
  for(int i=0;i<3;i++){
   var page=call("GET","/conversations/page?limit=1"+(cursor==null?"":"&cursor="+cursor),null,p.a()).body();
   collected.addAll(ids(page.get("items")));
   cursor=page.get("next_cursor").isNull()?null:page.get("next_cursor").asString();
  }
  assertThat(collected.getFirst()).isEqualTo(second);assertThat(new HashSet<>(collected)).containsExactlyInAnyOrder(p.id(),second,third);
  assertThat(cursor).isNull();
 }
 @Test void unreadReadAndConcurrentSendRemainConsistent()throws Exception{
  Pair p=pair();send(p.a(),p.id(),"message-before","before");
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var sending=pool.submit(()->send(p.a(),p.id(),"message-during","during"));
   var reading=pool.submit(()->read(p.b(),p.id()));
   assertThat(sending.get(15,TimeUnit.SECONDS).status()).isEqualTo(200);assertThat(reading.get(15,TimeUnit.SECONDS).status()).isEqualTo(200);
  }
  int unread=db.queryForObject("SELECT count(*) FROM message_receipts WHERE user_id=? AND read_at IS NULL",Integer.class,p.b().id());
  assertThat(unread(p.b(),p.id())).isEqualTo(unread);assertThat(unread).isBetween(0,1);
  read(p.b(),p.id());assertThat(unread(p.b(),p.id())).isZero();
 }
 @Test void simultaneousOppositeSendDoesNotDeadlock()throws Exception{
  Pair p=pair();
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   for(var f:pool.invokeAll(List.<Callable<Reply>>of(()->send(p.a(),p.id(),"message-side-a","a"),()->send(p.b(),p.id(),"message-side-b","b"))))
    assertThat(f.get(15,TimeUnit.SECONDS).status()).isEqualTo(200);
  }
  assertThat(count("messages")).isEqualTo(2);assertThat(unread(p.a(),p.id())).isEqualTo(1);assertThat(unread(p.b(),p.id())).isEqualTo(1);
 }
 @Test void notificationFailureRollsBackMessageReceiptsUnreadAndDispatch()throws Exception{
  Pair p=pair();db.execute("ALTER TABLE notification_events ADD CONSTRAINT chat_test_failure CHECK(category<>'chat')");
  try{
   assertThat(send(p.a(),p.id(),"message-failed","failure").status()).isEqualTo(500);
   assertThat(count("messages")).isZero();assertThat(count("message_receipts")).isZero();assertThat(count("chat_change_outbox")).isZero();
   assertThat(unread(p.b(),p.id())).isZero();
   assertThat(db.queryForObject("SELECT last_message FROM conversations",String.class)).isNull();
  }finally{db.execute("ALTER TABLE notification_events DROP CONSTRAINT chat_test_failure");}
 }
 @Test void readOutboxFailureRollsBackReceiptAndCounter()throws Exception{
  Pair p=pair();send(p.a(),p.id(),"message-unread","keep unread");
  db.execute("ALTER TABLE chat_change_outbox ADD CONSTRAINT chat_test_failure CHECK(event_type<>'message.read')");
  try{
   assertThat(read(p.b(),p.id()).status()).isEqualTo(500);assertThat(unread(p.b(),p.id())).isEqualTo(1);
   assertThat(db.queryForObject("SELECT read_at FROM message_receipts WHERE user_id=?",java.sql.Timestamp.class,p.b().id())).isNull();
  }finally{db.execute("ALTER TABLE chat_change_outbox DROP CONSTRAINT chat_test_failure");}
 }
 @Test void longTextKeepsFullContentAndBoundsConversationPreview()throws Exception{
  Pair p=pair();String text="文".repeat(2000);
  assertThat(send(p.a(),p.id(),"message-long",text).text("content")).isEqualTo(text);
  assertThat(call("GET",path(p.id()),null,p.b()).text("last_message")).hasSize(500);
  assertThat(send(p.a(),p.id(),"message-too-long",text+"文").status()).isEqualTo(422);
 }
 @Test void inputCursorAndRateValidationAreBounded()throws Exception{
  Pair p=pair();
  assertThat(call("GET","/conversations",null,null).status()).isEqualTo(401);
  assertThat(call("POST",path(p.id())+"/messages",Map.of("content","x","type","image"),p.a()).status()).isEqualTo(422);
  assertThat(call("POST",path(p.id())+"/messages",Map.of("content","x","media_asset_id","fake"),p.a()).status()).isEqualTo(422);
  assertThat(send(p.a(),p.id(),"        ","x").status()).isEqualTo(400);
  assertThat(send(p.a(),p.id(),"message-empty","").status()).isEqualTo(422);
  for(String bad:List.of("0","51","abc"))assertThat(call("GET",path(p.id())+"/messages/page?limit="+bad,null,p.a()).status()).isEqualTo(422);
  assertThat(call("GET","/conversations/page?limit=101",null,p.a()).status()).isEqualTo(422);
  for(String route:List.of("/conversations/page",path(p.id())+"/messages/page"))
   assertThat(call("GET",route+"?cursor=invalid",null,p.a()).status()).isEqualTo(400);
  send(p.a(),p.id(),"message-rate","original");
  db.update("UPDATE auth_rate_windows SET hits=120 WHERE bucket_key=?","chat-send:"+p.a().id());
  assertThat(send(p.a(),p.id(),"message-rate","original").status()).isEqualTo(200);
  assertThat(send(p.a(),p.id(),"message-new-rate","new").status()).isEqualTo(429);assertThat(count("messages")).isEqualTo(1);
 }
 @Test void cleanupErasesRepliesReceiptsAndEventsButPreservesUnrelatedConversation()throws Exception{
  Pair p=pair();User c=user();String other=friend(p.b(),c);
  Reply first=send(p.a(),p.id(),"message-erase-a","secret-a");
  call("POST",path(p.id())+"/messages",Map.of("content","secret-b","reply_to_message_id",first.text("id")),p.b());
  send(p.b(),other,"message-retain","retained");
  read(p.b(),p.id());
  call("POST","/auth/account/deactivate",null,p.a());
  db.update("UPDATE users SET deactivation_due_at=now()-interval '1 second' WHERE id=?",p.a().id());
  assertThat(((Map<?,?>)accounts.preview(p.a().id()).get("counts")).get("messages")).isEqualTo(2);
  assertThat(accounts.eraseDue(p.a().id())).isTrue();
  assertThat(count("messages")).isEqualTo(1);assertThat(count("message_receipts")).isEqualTo(2);
  assertThat(count("chat_change_outbox")).isEqualTo(1);
  assertThat(call("GET",path(other)+"/messages",null,p.b()).body().get(0).get("content").asString()).isEqualTo("retained");
  assertThat(call("GET",path(p.id()),null,p.b()).status()).isEqualTo(404);
  assertThat(db.queryForObject("SELECT count(*) FROM auth_rate_windows WHERE bucket_key=?",Integer.class,"chat-send:"+p.a().id())).isZero();
 }
 @Test void cleanupAndStaleSendCannotResurrectMessage()throws Exception{
  Pair p=pair();call("POST","/auth/account/deactivate",null,p.a());
  db.update("UPDATE users SET deactivation_due_at=now()-interval '1 second' WHERE id=?",p.a().id());
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var erased=pool.submit(()->accounts.eraseDue(p.a().id()));
   var sent=pool.submit(()->send(p.b(),p.id(),"message-race","new"));
   assertThat(erased.get(15,TimeUnit.SECONDS)).isTrue();assertThat(sent.get(15,TimeUnit.SECONDS).status()).isEqualTo(404);
  }
  assertThat(count("messages")).isZero();assertThat(count("chat_change_outbox")).isZero();
  assertThatThrownBy(()->chat.send(new AuthDtos.Principal(p.a().id(),p.a().session()),p.id(),new ChatDtos.Message("message-stale",null,"text","stale",null,null,0))).isInstanceOf(com.manliao.backend.common.ApiError.class);
 }
 @Test void blockAndConcurrentSendAlwaysEndHiddenAndPendingPushSuppressed()throws Exception{
  Pair p=pair();device(p.b());
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var sending=pool.submit(()->send(p.a(),p.id(),"message-block-race","race"));
   var blocking=pool.submit(()->call("POST","/safety/blocks",Map.of("target_user_id",p.b().id()),p.a()));
   assertThat(blocking.get(15,TimeUnit.SECONDS).status()).isEqualTo(200);
   assertThat(sending.get(15,TimeUnit.SECONDS).status()).isIn(200,404);
  }
  assertThat(call("GET",path(p.id()),null,p.b()).status()).isEqualTo(404);
  assertThat(db.queryForObject("SELECT count(*) FROM notification_events WHERE category='chat' AND status='pending'",Integer.class)).isZero();
 }
 @Test void enabledDeviceQueuesNotificationWithoutFakeRealtimeAcknowledgement()throws Exception{
  Pair p=pair();device(p.b());send(p.a(),p.id(),"message-queue","hello");
  assertThat(db.queryForObject("SELECT status FROM notification_events WHERE category='chat'",String.class)).isEqualTo("pending");
  assertThat(db.queryForObject("SELECT count(*) FROM chat_change_outbox WHERE delivered_at IS NOT NULL",Integer.class)).isZero();
  assertThat(call("GET",path(p.id())+"/messages",null,p.b()).body().get(0).get("delivered_to_user_ids").size()).isZero();
 }
}
