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
 "app.auth.jwt-secret=isolated-realtime-tests-secret-at-least-thirty-two-bytes",
 "app.auth.legacy-registration-enabled=true","app.accounts.worker-enabled=false"})
class RealtimeIntegrationTests {
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
 @Autowired com.manliao.backend.realtime.RealtimeStore realtime;
 @Autowired org.testcontainers.postgresql.PostgreSQLContainer postgres;
 final List<Probe> probes=new ArrayList<>();
 final class Probe implements WebSocket.Listener,AutoCloseable{
  final BlockingQueue<JsonNode> frames=new LinkedBlockingQueue<>();final CompletableFuture<Integer> closed=new CompletableFuture<>();
  final StringBuilder partial=new StringBuilder();final boolean autoAck;WebSocket socket;
  Probe(boolean autoAck){this.autoAck=autoAck;}
  @Override public void onOpen(WebSocket ws){socket=ws;ws.request(1);}
  @Override public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last){
   partial.append(data);
   if(last){
    var frame=json.readTree(partial.toString());partial.setLength(0);frames.add(frame);
    if(autoAck&&frame.has("event_id")&&!frame.path("type").asString().equals("ack.confirmed"))
     ws.sendText(json.writeValueAsString(Map.of("type","ack","event_id",frame.path("event_id").asString())),true);
   }
   ws.request(1);return null;
  }
  @Override public CompletionStage<?> onClose(WebSocket ws,int status,String reason){closed.complete(status);return null;}
  @Override public void onError(WebSocket ws,Throwable error){closed.completeExceptionally(error);}
  void send(Object value){socket.sendText(json.writeValueAsString(value),true).join();}
  JsonNode await(String type)throws Exception{return await(type,x->true);}
  JsonNode await(String type,java.util.function.Predicate<JsonNode> condition)throws Exception{
   long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
   while(System.nanoTime()<end){var frame=frames.poll(200,TimeUnit.MILLISECONDS);if(frame!=null&&type.equals(frame.path("type").asString())&&condition.test(frame))return frame;}
   throw new AssertionError("Timed out waiting for "+type);
  }
  boolean absent(String type,long millis)throws Exception{
   long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
   while(System.nanoTime()<end){var frame=frames.poll(100,TimeUnit.MILLISECONDS);if(frame!=null&&type.equals(frame.path("type").asString()))return false;}return true;
  }
  @Override public void close(){try{if(socket!=null&&!socket.isOutputClosed())socket.sendClose(1000,"test_complete").get(2,TimeUnit.SECONDS);closed.get(3,TimeUnit.SECONDS);}catch(Exception ignored){if(socket!=null)socket.abort();}}
 }
 Probe open(User user,String channel,boolean reliable,boolean autoAck)throws Exception{return openAt(port,user,channel,reliable,autoAck,null,false);}
 Probe openAt(int target,User user,String channel,boolean reliable,boolean autoAck,String cursor,boolean queryToken)throws Exception{
  var probe=new Probe(autoAck);probes.add(probe);
  String url="ws://127.0.0.1:"+target+"/ws/"+channel+"?reliable="+reliable+(cursor==null?"":"&cursor="+cursor)+(queryToken?"&token="+user.token():"");
  var builder=http.newWebSocketBuilder();if(!queryToken)builder.header("Authorization","Bearer "+user.token());
  probe.socket=builder.buildAsync(URI.create(url),probe).get(10,TimeUnit.SECONDS);
  probe.await("realtime.ready");return probe;
 }
 void ack(Probe probe,JsonNode frame)throws Exception{probe.send(Map.of("type","ack","event_id",frame.get("event_id").asString()));probe.await("ack.confirmed",x->x.get("event_id").asString().equals(frame.get("event_id").asString()));}
 @AfterEach void closeSockets(){for(var probe:probes)probe.close();probes.clear();}
 @Test void websocketAuthenticationAndQueryCompatibility()throws Exception{
  assertThatThrownBy(()->http.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:"+port+"/ws/messages"),new Probe(false)).join()).hasCauseInstanceOf(WebSocketHandshakeException.class);
  User a=user();try(Probe p=openAt(port,a,"messages",false,false,null,true)){p.send(Map.of("type","ping"));assertThat(p.await("pong")).isNotNull();}
  assertThatThrownBy(()->http.newWebSocketBuilder().header("Authorization","Bearer invalid").buildAsync(URI.create("ws://127.0.0.1:"+port+"/ws/messages"),new Probe(false)).join()).hasCauseInstanceOf(WebSocketHandshakeException.class);
 }
 @Test void messageAckUpdatesDeliveryOnlyAfterClientConfirmation()throws Exception{
  Pair p=pair();Probe receiver=open(p.b(),"messages",true,false);
  Reply m=send(p.a(),p.id(),"ws-ack-message","hello");realtime.publish();
  JsonNode event=receiver.await("message.created");
  assertThat(event.path("message").path("id").asString()).isEqualTo(m.text("id"));
  assertThat(db.queryForObject("SELECT delivered_at FROM message_receipts WHERE message_id=? AND user_id=?",java.sql.Timestamp.class,m.text("id"),p.b().id())).isNull();
  ack(receiver,event);
  assertThat(db.queryForObject("SELECT delivered_at FROM message_receipts WHERE message_id=? AND user_id=?",java.sql.Timestamp.class,m.text("id"),p.b().id())).isNotNull();
  assertThat(unread(p.b(),p.id())).isEqualTo(1);
  receiver.send(Map.of("type","ack","event_id",event.get("event_id").asString()));receiver.await("ack.confirmed");
  assertThat(db.queryForObject("SELECT count(*) FROM chat_change_outbox WHERE event_type='message.delivered'",Integer.class)).isEqualTo(1);
 }
 @Test void unackedEventReplaysAfterDisconnectAndCheckpointResumes()throws Exception{
  Pair p=pair();Probe first=open(p.b(),"messages",true,false);
  send(p.a(),p.id(),"ws-replay-message","replay");realtime.publish();
  JsonNode event=first.await("message.created");first.close();
  Probe second=open(p.b(),"messages",true,false);
  JsonNode replay=second.await("message.created");assertThat(replay.path("event_id").asString()).isEqualTo(event.path("event_id").asString());
  ack(second,replay);second.close();
  Probe third=open(p.b(),"messages",true,false);
  assertThat(third.absent("message.created",800)).isTrue();
  assertThat(db.queryForObject("SELECT last_ack FROM realtime_checkpoints WHERE session_id=? AND channel='messages'",Long.class,p.b().session())).isEqualTo(Long.parseLong(event.get("event_id").asString()));
 }
 @Test void reliableModeResendsSameEventWithoutAck()throws Exception{
  Pair p=pair();Probe receiver=open(p.b(),"messages",true,false);
  send(p.a(),p.id(),"ws-resend-message","retry");realtime.publish();
  var first=receiver.await("message.created");var retry=receiver.await("message.created");
  assertThat(retry.get("event_id").asString()).isEqualTo(first.get("event_id").asString());ack(receiver,retry);
 }
 @Test void multipleConnectionsGetIndependentCopiesAndPersonalChangesStayPrivate()throws Exception{
  Pair p=pair();Probe a=open(p.a(),"messages",false,true),b1=open(p.b(),"messages",false,true),b2=open(p.b(),"messages",false,true);
  var m=send(p.a(),p.id(),"ws-multidevice","multi");realtime.publish();
  a.await("message.created");b1.await("message.created");b2.await("message.created");
  call("DELETE",path(p.id())+"/messages/"+m.text("id"),null,p.b());realtime.publish();
  assertThat(b1.await("message.hidden").path("message_id").asString()).isEqualTo(m.text("id"));
  assertThat(b2.await("message.hidden").path("message_id").asString()).isEqualTo(m.text("id"));
  assertThat(a.absent("message.hidden",800)).isTrue();
 }
 @Test void notificationsPushCurrentUnreadSnapshotAndChanges()throws Exception{
  Pair p=pair();Probe probe=open(p.b(),"notifications",false,true);
  assertThat(probe.await("notification.unread_count").path("unread_count").asInt()).isEqualTo(1);
  send(p.a(),p.id(),"ws-notification","notify");realtime.publish();
  assertThat(probe.await("notification.unread_count",x->x.path("unread_count").asInt()==2)).isNotNull();
  call("POST","/notifications/events/read-all",null,p.b());realtime.publish();
  probe.await("notification.unread_count",x->x.path("unread_count").asInt()==0);
 }
 @Test void readAndRecallEventsUseLatestRedactedState()throws Exception{
  Pair p=pair();var m=send(p.a(),p.id(),"ws-recall-old","private-original");realtime.publish();
  call("POST",path(p.id())+"/messages/"+m.text("id")+"/recall",null,p.a());realtime.publish();
  Probe b=open(p.b(),"messages",false,true);
  var recalled=b.await("message.recalled");
  assertThat(recalled.toString()).doesNotContain("private-original");
  assertThat(recalled.path("message").path("content").asString()).isEqualTo("消息已撤回");
  read(p.b(),p.id());realtime.publish();
  b.await("message.read");
 }
 @Test void blockAndPersonalClearPreventStaleBodyReplay()throws Exception{
  Pair p=pair();send(p.a(),p.id(),"ws-hidden-body","must-not-replay");realtime.publish();
  call("DELETE",path(p.id())+"/messages",null,p.b());realtime.publish();
  Probe b=open(p.b(),"messages",false,true);b.await("conversation.cleared");
  assertThat(b.absent("message.created",700)).isTrue();
  b.close();call("POST","/safety/blocks",Map.of("target_user_id",p.b().id()),p.a());
  Probe blocked=open(p.b(),"messages",false,true);
  assertThat(blocked.absent("message.created",700)).isTrue();
 }
 @Test void revokedSessionIsDisconnectedWithoutWaitingForPing()throws Exception{
  Pair p=pair();Probe b=open(p.b(),"messages",false,false);
  assertThat(call("DELETE","/auth/sessions/"+p.b().session(),null,p.b()).status()).isEqualTo(200);
  assertThat(b.closed.get(5,TimeUnit.SECONDS)).isEqualTo(1008);
 }
 @Test void forgedAckCannotConfirmAnUnsentOrForeignEvent()throws Exception{
  Pair p=pair();User c=user();send(p.a(),p.id(),"ws-forged","secret");realtime.publish();
  long event=db.queryForObject("SELECT min(id) FROM realtime_events WHERE recipient_user_id=? AND channel='messages'",Long.class,p.b().id());
  Probe attacker=open(c,"messages",false,false);attacker.send(Map.of("type","ack","event_id",Long.toString(event)));
  assertThat(attacker.closed.get(5,TimeUnit.SECONDS)).isEqualTo(1008);
  assertThat(db.queryForObject("SELECT delivered_at FROM message_receipts WHERE user_id=? AND read_at IS NULL",java.sql.Timestamp.class,p.b().id())).isNull();
 }
 @Test void typingAndPresenceAreSharedAndMembershipProtected()throws Exception{
  Pair p=pair();Probe a=open(p.a(),"messages",false,true),b=open(p.b(),"messages",false,true);
  assertThat(a.await("presence.updated",x->x.path("user_id").asString().equals(p.b().id())&&x.path("online").asBoolean())).isNotNull();
  a.send(Map.of("type","typing.start","conversation_id",p.id()));b.await("typing.start");
  User c=user();Probe stranger=open(c,"messages",false,false);stranger.send(Map.of("type","typing.start","conversation_id",p.id()));
  assertThat(stranger.closed.get(5,TimeUnit.SECONDS)).isEqualTo(1008);
  b.close();a.await("presence.updated",x->x.path("user_id").asString().equals(p.b().id())&&!x.path("online").asBoolean());
 }
 @Test void failedPublicationRetriesAtomicallyWithoutDuplicateRecipientEvents()throws Exception{
  Pair p=pair();send(p.a(),p.id(),"ws-publication","recover");
  db.execute("ALTER TABLE realtime_events ADD CONSTRAINT realtime_test_failure CHECK(channel<>'messages')");
  try{
   realtime.publish();assertThat(db.queryForObject("SELECT count(*) FROM realtime_events WHERE channel='messages'",Integer.class)).isZero();
   assertThat(db.queryForObject("SELECT last_error_code FROM chat_change_outbox",String.class)).isEqualTo("REALTIME_PUBLICATION_FAILED");
   assertThat(db.queryForObject("SELECT delivered_at FROM chat_change_outbox",java.sql.Timestamp.class)).isNull();
  }finally{db.execute("ALTER TABLE realtime_events DROP CONSTRAINT realtime_test_failure");}
  db.update("UPDATE chat_change_outbox SET next_retry_at=clock_timestamp()");
  realtime.publish();realtime.publish();
  assertThat(db.queryForObject("SELECT count(*) FROM realtime_events WHERE channel='messages'",Integer.class)).isEqualTo(2);
 }
 @Test void concurrentPublishersDoNotDuplicateEvents()throws Exception{
  Pair p=pair();send(p.a(),p.id(),"ws-concurrent-publish","once");
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var first=pool.submit(()->realtime.publish());var second=pool.submit(()->realtime.publish());
   first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);
  }
  assertThat(db.queryForObject("SELECT count(*) FROM realtime_events WHERE channel='messages'",Integer.class)).isEqualTo(2);
 }
 @Test void secondApplicationInstanceSharesEventsAndConnectionPresence()throws Exception{
  Pair p=pair();Probe local=open(p.b(),"messages",false,true);
  try(var second=new org.springframework.boot.builder.SpringApplicationBuilder(ManliaoBackendApplication.class).run(
    "--server.port=0","--server.address=127.0.0.1","--spring.datasource.url="+postgres.getJdbcUrl(),
    "--spring.datasource.username="+postgres.getUsername(),"--spring.datasource.password="+postgres.getPassword(),
    "--app.auth.jwt-secret=isolated-realtime-tests-secret-at-least-thirty-two-bytes",
    "--app.accounts.worker-enabled=false","--app.realtime.worker-enabled=false",
    "--spring.main.banner-mode=off","--logging.level.root=WARN")){
   int secondPort=second.getEnvironment().getRequiredProperty("local.server.port",Integer.class);
   Probe remote=openAt(secondPort,p.b(),"messages",false,true,null,false);
   send(p.a(),p.id(),"ws-two-nodes","cross-node");
   second.getBean(com.manliao.backend.realtime.RealtimeStore.class).publish();
   local.await("message.created");remote.await("message.created");
   assertThat(db.queryForObject("SELECT count(*) FROM realtime_connections WHERE user_id=?",Integer.class,p.b().id())).isEqualTo(2);
   remote.close();
  }
 }
 @Test void accountErasureRemovesReplayAndConnectionsAndClosesSocket()throws Exception{
  Pair p=pair();Probe b=open(p.b(),"messages",false,true);
  send(p.a(),p.id(),"ws-erasure","erase");realtime.publish();b.await("message.created");
  call("POST","/auth/account/deactivate",null,p.b());
  db.update("UPDATE users SET deactivation_due_at=clock_timestamp()-interval '1 second' WHERE id=?",p.b().id());
  assertThat(accounts.eraseDue(p.b().id())).isTrue();
  assertThat(b.closed.get(5,TimeUnit.SECONDS)).isEqualTo(1008);
  assertThat(count("realtime_events")).isZero();assertThat(count("realtime_checkpoints")).isZero();assertThat(count("realtime_connections")).isZero();
 }
}
