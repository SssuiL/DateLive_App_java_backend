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
 "app.auth.jwt-secret=isolated-interaction-tests-secret-at-least-thirty-two-bytes",
 "app.auth.legacy-registration-enabled=true","app.accounts.worker-enabled=false"})
class ChatInteractionTests {
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
 Reply recall(User user,String id,String message)throws Exception{return call("POST",path(id)+"/messages/"+message+"/recall",null,user);}
 Reply hide(User user,String id,String message)throws Exception{return call("DELETE",path(id)+"/messages/"+message,null,user);}
 Reply clear(User user,String id)throws Exception{return call("DELETE",path(id)+"/messages",null,user);}
 Reply search(User user,String id,String query)throws Exception{return call("GET",path(id)+"/messages/search?"+query,null,user);}
 @Test void recallChecksSenderWindowAndRepeatedResult()throws Exception{
  Pair p=pair();Reply m=send(p.a(),p.id(),"interaction-own","secret");
  assertThat(recall(p.b(),p.id(),m.text("id")).status()).isEqualTo(403);
  db.update("UPDATE messages SET created_at=clock_timestamp()-interval '121 seconds' WHERE id=?",m.text("id"));
  assertThat(recall(p.a(),p.id(),m.text("id")).status()).isEqualTo(409);
  db.update("UPDATE messages SET created_at=clock_timestamp()-interval '110 seconds' WHERE id=?",m.text("id"));
  assertThat(recall(p.a(),p.id(),m.text("id")).text("content")).isEqualTo("消息已撤回");
  db.update("UPDATE messages SET created_at=clock_timestamp()-interval '5 minutes' WHERE id=?",m.text("id"));
  assertThat(recall(p.a(),p.id(),m.text("id")).status()).isEqualTo(200);
  assertThat(db.queryForObject("SELECT count(*) FROM chat_change_outbox WHERE event_type='message.recalled'",Integer.class)).isEqualTo(1);
 }
 @Test void recallMasksHistoryReplyAndNotificationQueue()throws Exception{
  Pair p=pair();device(p.b());Reply m=send(p.a(),p.id(),"interaction-secret","sensitive-original");
  Reply reply=call("POST",path(p.id())+"/messages",Map.of("content","reply","reply_to_message_id",m.text("id")),p.b());
  recall(p.a(),p.id(),m.text("id"));
  var all=call("GET",path(p.id())+"/messages",null,p.b()).body();
  assertThat(all.toString()).doesNotContain("sensitive-original");
  assertThat(all.get(1).get("reply_preview").get("content").asString()).isEqualTo("原消息已撤回");
  assertThat(all.get(0).get("is_recalled").asBoolean()).isTrue();
  assertThat(call("GET",path(p.id()),null,p.a()).text("last_message")).isEqualTo("reply");
  recall(p.b(),p.id(),reply.text("id"));
  assertThat(call("GET",path(p.id()),null,p.a()).text("last_message")).isEqualTo("消息已撤回");
  assertThat(db.queryForObject("SELECT status FROM notification_events WHERE source_id=?",String.class,m.text("id"))).isEqualTo("suppressed");
  assertThat(db.queryForObject("SELECT count(*) FROM chat_change_outbox WHERE message_id=? AND event_type='message.created'",Integer.class,m.text("id"))).isZero();
  assertThat(send(p.a(),p.id(),"interaction-secret","sensitive-original").text("content")).isEqualTo("消息已撤回");
  assertThat(search(p.b(),p.id(),"q=sensitive").body().get("items").size()).isZero();
 }
 @Test void hiddenMessagesAndTheirReplyPreviewsArePrivate()throws Exception{
  Pair p=pair();Reply first=send(p.a(),p.id(),"interaction-hidden","hidden-source");
  call("POST",path(p.id())+"/messages",Map.of("content","visible-reply","reply_to_message_id",first.text("id")),p.a());
  assertThat(hide(p.b(),p.id(),first.text("id")).text("status")).isEqualTo("hidden");
  hide(p.b(),p.id(),first.text("id"));
  var own=call("GET",path(p.id())+"/messages",null,p.b()).body();
  assertThat(own.size()).isEqualTo(1);assertThat(own.get(0).get("reply_preview").isNull()).isTrue();
  assertThat(call("GET",path(p.id())+"/messages/page",null,p.b()).body().get("items").size()).isEqualTo(1);
  assertThat(search(p.b(),p.id(),"q=hidden").body().get("items").size()).isZero();
  assertThat(call("GET",path(p.id())+"/messages",null,p.a()).body().size()).isEqualTo(2);
  assertThat(unread(p.b(),p.id())).isEqualTo(1);
  assertThat(db.queryForObject("SELECT read_at FROM message_receipts WHERE message_id=? AND user_id=?",java.sql.Timestamp.class,first.text("id"),p.b().id())).isNull();
  read(p.b(),p.id());
  assertThat(db.queryForObject("SELECT read_at FROM message_receipts WHERE message_id=? AND user_id=?",java.sql.Timestamp.class,first.text("id"),p.b().id())).isNull();
  assertThat(db.queryForObject("SELECT count(*) FROM chat_change_outbox WHERE event_type='message.hidden'",Integer.class)).isEqualTo(1);
 }
 @Test void personalClearRetainsPeerHistoryAndNewMessages()throws Exception{
  Pair p=pair();send(p.a(),p.id(),"interaction-clear1","one");send(p.a(),p.id(),"interaction-clear2","two");
  Reply cleared=clear(p.b(),p.id());
  assertThat(cleared.body().get("last_message").isNull()).isTrue();assertThat(cleared.body().get("unread_count").asInt()).isZero();
  assertThat(call("GET",path(p.id())+"/messages",null,p.b()).body().size()).isZero();
  assertThat(call("GET","/conversations/",null,p.b()).body().get(0).get("last_message").isNull()).isTrue();
  assertThat(call("GET",path(p.id())+"/messages",null,p.a()).body().size()).isEqualTo(2);
  assertThat(call("GET",path(p.id()),null,p.a()).text("last_message")).isEqualTo("two");
  clear(p.b(),p.id());assertThat(db.queryForObject("SELECT count(*) FROM chat_change_outbox WHERE event_type='conversation.cleared'",Integer.class)).isEqualTo(1);
  send(p.a(),p.id(),"interaction-clear3","new");
  assertThat(call("GET",path(p.id())+"/messages",null,p.b()).body().size()).isEqualTo(1);assertThat(unread(p.b(),p.id())).isEqualTo(1);
 }
 @Test void hiddenSenderRetryDoesNotResurrectAndCannotBeQuoted()throws Exception{
  Pair p=pair();Reply m=send(p.a(),p.id(),"interaction-retry","old");
  hide(p.a(),p.id(),m.text("id"));
  assertThat(send(p.a(),p.id(),"interaction-retry","old").status()).isEqualTo(404);
  assertThat(call("POST",path(p.id())+"/messages",Map.of("content","quote","reply_to_message_id",m.text("id")),p.a()).status()).isEqualTo(400);
  assertThat(count("messages")).isEqualTo(1);
  assertThat(call("GET",path(p.id()),null,p.a()).body().get("last_message").isNull()).isTrue();
  assertThat(call("GET",path(p.id()),null,p.b()).text("last_message")).isEqualTo("old");
 }
 @Test void searchUsesLiteralCaseInsensitiveTextAndFiltersVisibility()throws Exception{
  Pair p=pair();
  Reply literal=send(p.a(),p.id(),"interaction-percent","AbC%_value");
  send(p.a(),p.id(),"interaction-other","abcOther");
  assertThat(search(p.b(),p.id(),"q=abc%25_").body().get("items").size()).isEqualTo(1);
  assertThat(search(p.b(),p.id(),"message_type=image").body().get("items").size()).isZero();
  assertThat(search(p.b(),p.id(),"q=abc&message_type=text").body().get("items").size()).isEqualTo(2);
  hide(p.b(),p.id(),literal.text("id"));
  assertThat(search(p.b(),p.id(),"q=abc").body().get("items").size()).isEqualTo(1);
  assertThat(search(p.a(),p.id(),"q=abc").body().get("items").size()).isEqualTo(2);
  assertThat(search(p.b(),p.id(),"q=").status()).isEqualTo(400);
  assertThat(search(p.b(),p.id(),"message_type=bad").status()).isEqualTo(400);
  assertThat(search(p.b(),p.id(),"q="+"x".repeat(101)).status()).isEqualTo(422);
  assertThat(search(p.b(),p.id(),"q=abc&cursor=invalid").status()).isEqualTo(400);
 }
 @Test void searchPaginationSkipsHiddenAndRecalledAtEqualTimestamps()throws Exception{
  Pair p=pair();var messages=new ArrayList<Reply>();
  for(int i=0;i<5;i++)messages.add(send(p.a(),p.id(),"interaction-page"+i,"find "+i));
  recall(p.a(),p.id(),messages.get(0).text("id"));hide(p.b(),p.id(),messages.get(1).text("id"));
  db.update("UPDATE messages SET created_at='2026-01-01T00:00:00Z'");
  var expected=db.queryForList("SELECT id FROM messages WHERE id NOT IN (?,?) ORDER BY id DESC",String.class,messages.get(0).text("id"),messages.get(1).text("id"));
  var first=search(p.b(),p.id(),"q=find&limit=2").body();
  var second=search(p.b(),p.id(),"q=find&limit=2&cursor="+first.get("next_cursor").asString()).body();
  assertThat(ids(first.get("items"))).containsExactlyElementsOf(expected.subList(0,2));
  assertThat(ids(second.get("items"))).containsExactly(expected.getLast());assertThat(second.get("has_more").asBoolean()).isFalse();
 }
 @Test void mutationsAndSearchEnforceMembershipAndConversationScope()throws Exception{
  Pair p=pair();User c=user();String other=friend(p.a(),c);Reply m=send(p.a(),p.id(),"interaction-scope","private");
  assertThat(recall(c,p.id(),m.text("id")).status()).isEqualTo(404);
  assertThat(hide(c,p.id(),m.text("id")).status()).isEqualTo(404);
  assertThat(clear(c,p.id()).status()).isEqualTo(404);
  assertThat(search(c,p.id(),"q=private").status()).isEqualTo(404);
  assertThat(hide(p.a(),other,m.text("id")).status()).isEqualTo(404);
  call("POST","/safety/blocks",Map.of("target_user_id",p.b().id()),p.a());
  assertThat(recall(p.a(),p.id(),m.text("id")).status()).isEqualTo(404);
  assertThat(clear(p.b(),p.id()).status()).isEqualTo(404);
 }
 @Test void duplicateRecallIsAtomicAndClearSendRaceKeepsUnreadConsistent()throws Exception{
  Pair p=pair();Reply m=send(p.a(),p.id(),"interaction-race","before");
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   for(var f:pool.invokeAll(List.<Callable<Reply>>of(()->recall(p.a(),p.id(),m.text("id")),()->recall(p.a(),p.id(),m.text("id")))))
    assertThat(f.get().status()).isEqualTo(200);
   for(var f:pool.invokeAll(List.<Callable<Reply>>of(()->clear(p.b(),p.id()),()->send(p.a(),p.id(),"interaction-after","after"))))
    assertThat(f.get().status()).isEqualTo(200);
  }
  assertThat(db.queryForObject("SELECT count(*) FROM chat_change_outbox WHERE event_type='message.recalled'",Integer.class)).isEqualTo(1);
  int visible=call("GET",path(p.id())+"/messages",null,p.b()).body().size();
  assertThat(visible).isBetween(0,1);assertThat(unread(p.b(),p.id())).isEqualTo(visible);
  assertThat(call("GET",path(p.id())+"/messages",null,p.a()).body().size()).isEqualTo(2);
 }
 @Test void outboxFailureRollsBackRecallAndPersonalHide()throws Exception{
  Pair p=pair();device(p.b());Reply m=send(p.a(),p.id(),"interaction-rollback","retain");
  db.execute("ALTER TABLE chat_change_outbox ADD CONSTRAINT interaction_failure CHECK(event_type NOT IN ('message.recalled','message.hidden','conversation.cleared'))");
  try{
   assertThat(recall(p.a(),p.id(),m.text("id")).status()).isEqualTo(500);
   assertThat(hide(p.b(),p.id(),m.text("id")).status()).isEqualTo(500);
   assertThat(clear(p.b(),p.id()).status()).isEqualTo(500);
   assertThat(call("GET",path(p.id())+"/messages",null,p.b()).body().get(0).get("content").asString()).isEqualTo("retain");
   assertThat(unread(p.b(),p.id())).isEqualTo(1);
   assertThat(db.queryForObject("SELECT status FROM notification_events WHERE source_id=?",String.class,m.text("id"))).isEqualTo("pending");
   assertThat(db.queryForObject("SELECT count(*) FROM chat_change_outbox WHERE event_type='message.created'",Integer.class)).isEqualTo(1);
  }finally{db.execute("ALTER TABLE chat_change_outbox DROP CONSTRAINT interaction_failure");}
 }
 @Test void hiddenIncomingNotificationIsSuppressedWithoutMarkingDelivered()throws Exception{
  Pair p=pair();device(p.b());Reply m=send(p.a(),p.id(),"interaction-push","private");
  hide(p.b(),p.id(),m.text("id"));
  assertThat(db.queryForObject("SELECT suppress_reason FROM notification_events WHERE source_id=?",String.class,m.text("id"))).isEqualTo("message_hidden");
  assertThat(db.queryForObject("SELECT delivered_at FROM message_receipts WHERE message_id=? AND user_id=?",java.sql.Timestamp.class,m.text("id"),p.b().id())).isNull();
 }
 @Test void erasureCleansRecallAndVisibilityStateWithoutAffectingOtherConversation()throws Exception{
  Pair p=pair();User c=user();String other=friend(p.b(),c);
  Reply m=send(p.a(),p.id(),"interaction-erase","erase");recall(p.a(),p.id(),m.text("id"));clear(p.b(),p.id());
  send(p.b(),other,"interaction-keep","keep");
  call("POST","/auth/account/deactivate",null,p.a());db.update("UPDATE users SET deactivation_due_at=clock_timestamp()-interval '1 second' WHERE id=?",p.a().id());
  assertThat(accounts.eraseDue(p.a().id())).isTrue();
  assertThat(count("messages")).isEqualTo(1);assertThat(count("message_receipts")).isEqualTo(2);
  assertThat(count("chat_change_outbox")).isEqualTo(1);
  assertThat(call("GET",path(other)+"/messages",null,c).body().get(0).get("content").asString()).isEqualTo("keep");
 }
}
