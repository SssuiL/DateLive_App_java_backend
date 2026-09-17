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
 "app.auth.jwt-secret=isolated-group-tests-secret-at-least-thirty-two-bytes",
 "app.groups.public-base-url=https://groups.example.test","app.auth.legacy-registration-enabled=true","app.accounts.worker-enabled=false","app.media.derivatives-worker-enabled=false","app.media.jobs-worker-enabled=false","app.media.draft-worker-enabled=false"})
class GroupIntegrationTests {
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


 @Autowired com.manliao.backend.media.MediaService media;
 @Autowired com.manliao.backend.admin.AdminService admins;
 @Autowired com.manliao.backend.realtime.RealtimeStore realtime;
 static final java.nio.file.Path ROOT=root();
 static final byte[] FILE="<html>synthetic attachment — not an inline page</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
 static java.nio.file.Path root(){try{return java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("target"),"groups-it-").toAbsolutePath();}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
 @org.springframework.test.context.DynamicPropertySource static void storage(org.springframework.test.context.DynamicPropertyRegistry p){p.add("app.media.storage-root",()->ROOT.toString());}
 static byte[] gif(int frames,int width,int height,int delay)throws Exception{
  var writer=javax.imageio.ImageIO.getImageWritersByFormatName("gif").next();var bytes=new java.io.ByteArrayOutputStream();
  try(var out=new javax.imageio.stream.MemoryCacheImageOutputStream(bytes)){
   writer.setOutput(out);writer.prepareWriteSequence(null);
   for(int i=0;i<frames;i++){
    var image=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_RGB);
    var graphics=image.createGraphics();graphics.setColor(i%2==0?java.awt.Color.RED:java.awt.Color.BLUE);graphics.fillRect(0,0,width,height);graphics.dispose();
    var metadata=writer.getDefaultImageMetadata(javax.imageio.ImageTypeSpecifier.createFromRenderedImage(image),null);
    var tree=(javax.imageio.metadata.IIOMetadataNode)metadata.getAsTree("javax_imageio_gif_image_1.0");
    var gce=(javax.imageio.metadata.IIOMetadataNode)tree.getElementsByTagName("GraphicControlExtension").item(0);gce.setAttribute("delayTime",Integer.toString(delay));gce.setAttribute("disposalMethod","restoreToBackgroundColor");
    var comments=new javax.imageio.metadata.IIOMetadataNode("CommentExtensions");var comment=new javax.imageio.metadata.IIOMetadataNode("CommentExtension");comment.setAttribute("value","PRIVATE_GIF_COMMENT");comments.appendChild(comment);tree.appendChild(comments);
    if(i==0){var apps=new javax.imageio.metadata.IIOMetadataNode("ApplicationExtensions");var app=new javax.imageio.metadata.IIOMetadataNode("ApplicationExtension");app.setAttribute("applicationID","NETSCAPE");app.setAttribute("authenticationCode","2.0");app.setUserObject(new byte[]{1,2,0});apps.appendChild(app);tree.appendChild(apps);}
    metadata.setFromTree("javax_imageio_gif_image_1.0",tree);writer.writeToSequence(new javax.imageio.IIOImage(image,null,metadata),null);
   }writer.endWriteSequence();out.flush();return bytes.toByteArray();
  }finally{writer.dispose();}
 }
 Reply upload(User user,String conversation,byte[] bytes,String mime,String type,String source)throws Exception{
  String boundary="attachments-test-boundary";var out=new java.io.ByteArrayOutputStream();
  var fields=new LinkedHashMap<String,String>();fields.put("media_type",type);fields.put("source",source);if(conversation!=null)fields.put("conversation_id",conversation);
  for(var field:fields.entrySet())out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+field.getKey()+"\"\r\n\r\n"+field.getValue()+"\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
  out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"fixture.data\"\r\nContent-Type: "+mime+"\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
  out.write(bytes);out.write(("\r\n--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
  var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/media/upload")).header("Authorization","Bearer "+user.token())
   .header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
  return new Reply(response.statusCode(),json.readTree(response.body()));
 }
 String approved(User user,String conv)throws Exception{var r=upload(user,conv,FILE,"text/plain","file","chat");assertThat(r.status()).isEqualTo(200);media.resolveReview(r.text("id"),true,"fixture","synthetic");return r.text("id");}
 Reply sendAsset(User user,String conv,String asset,String key,String type,String kind)throws Exception{
  var body=new HashMap<String,Object>();body.put("type",type);body.put("media_asset_id",asset);body.put("client_message_id",key);body.put("content","attachment");if(kind!=null)body.put("media_kind",kind);
  return call("POST",path(conv)+"/messages",body,user);
 }
 String grant(User user,String asset)throws Exception{var r=call("POST","/media/"+asset+"/access-url",null,user);assertThat(r.status()).isEqualTo(200);return r.text("url");}
 HttpResponse<byte[]> download(String url)throws Exception{return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+url)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());}
 HttpResponse<byte[]> range(String url,String range)throws Exception{return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+url)).header("Range",range).GET().build(),HttpResponse.BodyHandlers.ofByteArray());}


 byte[] png(int width,int height)throws Exception{
  var image=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_ARGB);
  var g=image.createGraphics();g.setColor(java.awt.Color.RED);g.fillRect(0,0,width/2,height);g.dispose();
  var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",out);return out.toByteArray();
 }
 String imageAsset(User user,String conv)throws Exception{
  var r=upload(user,conv,png(1600,900),"image/png","image","chat");assertThat(r.status()).isEqualTo(200);
  media.resolveReview(r.text("id"),true,"fixture","synthetic image");return r.text("id");
 }
 String variant(User user,String id,String kind)throws Exception{
  var r=call("POST","/media/"+id+"/access-url?variant="+kind,null,user);assertThat(r.status()).as(r.body().toString()).isEqualTo(200);return r.text("url");
 }
 java.awt.image.BufferedImage decoded(String url)throws Exception{
  var response=download(url);assertThat(response.statusCode()).isEqualTo(200);assertThat(response.headers().firstValue("Content-Type")).contains("image/png");
  return javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(response.body()));
 }
 String key(String id){return db.queryForObject("SELECT storage_key FROM media_assets WHERE id=?",String.class,id);}
 String base(String key){return key.substring(0,key.lastIndexOf('.'));}
 void legacy(String id)throws Exception{
  String key=key(id);java.nio.file.Files.deleteIfExists(ROOT.resolve(base(key)+".thumb.png"));
  java.nio.file.Files.deleteIfExists(ROOT.resolve(base(key)+".display.png"));
  db.update("UPDATE media_assets SET image_derivatives_ready=false,image_derivatives_retry_at=now() WHERE id=?",id);
 }
 void absent(String key){
  for(String name:List.of(key,base(key)+".thumb.png",base(key)+".display.png"))assertThat(java.nio.file.Files.exists(ROOT.resolve(name))).as(name).isFalse();
 }
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
 record Group(String id,String conversation,String code){}
 Group group(User owner,String rule,int capacity)throws Exception{
  var body=new HashMap<String,Object>();body.put("name","迁移测试群");body.put("tags",List.of("游戏","音乐"));body.put("join_rule_type",rule);body.put("max_members",capacity);
  if(rule.equals("password"))body.put("join_password","fixture-password");if(rule.equals("question")){body.put("join_question","测试问题");body.put("join_answer","fixture-answer");}
  var r=call("POST","/groups/",body,owner);assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
  assertThat(r.body().has("join_secret_hash")).isFalse();assertThat(r.body().has("join_password")).isFalse();assertThat(r.body().has("join_answer")).isFalse();
  return new Group(r.text("id"),r.text("conversation_id"),r.text("link_code"));
 }
 Reply join(User user,Group group){try{return call("POST","/groups/"+group.id()+"/join-requests",Map.of(),user);}catch(Exception e){throw new RuntimeException(e);}}
 void invited(User actor,Group group,User target)throws Exception{var r=call("POST","/groups/"+group.id()+"/invite",Map.of("target_user_id",target.id()),actor);assertThat(r.status()).as(r.body().toString()).isEqualTo(200);}
 Reply memberRole(User actor,Group g,User target,String role)throws Exception{return call("PATCH","/groups/"+g.id()+"/members/"+target.id()+"/role",Map.of("role",role),actor);}
 Reply remove(User actor,Group g,User target)throws Exception{return call("DELETE","/groups/"+g.id()+"/members/"+target.id(),null,actor);}
 @Test void shareLinkUsesConfiguredDomainAndPublicPreviewDoesNotLeakSecrets()throws Exception{
  var a=user();var b=user();var g=group(a,"password",10);
  var shared=call("GET","/groups/"+g.id()+"/share-link",null,a);
  assertThat(shared.status()).isEqualTo(200);
  assertThat(shared.text("share_url")).isEqualTo("https://groups.example.test/g/"+g.code());
  assertThat(shared.text("share_text")).contains(shared.text("share_url"));
  assertThat(call("GET","/groups/"+g.id()+"/share-link",null,b).status()).isEqualTo(403);
  assertThat(call("GET","/groups/"+g.id()+"/share-link",null,null).status()).isEqualTo(401);
  var preview=call("GET","/group-links/"+g.code(),null,null);
  assertThat(preview.status()).isEqualTo(200);
  assertThat(preview.body().toString()).doesNotContain("join_secret_hash","join_password","owner_id","conversation_id");
  assertThat(call("POST","/join-by-link-code?link_code="+g.code(),Map.of(),b).status()).isNotEqualTo(200);
  assertThat(call("POST","/groups/join-by-link-code?link_code="+g.code(),Map.of("password","wrong"),b).text("status")).isEqualTo("rejected");
  assertThat(db.queryForObject("SELECT count(*) FROM group_members WHERE group_id=?",Integer.class,g.id())).isEqualTo(1);
 }
 @Test void publicInvitePageEscapesGroupNameAndDissolvedLinksStopWorking()throws Exception{
  var a=user();var g=group(a,"open",10);db.update("UPDATE groups SET name=? WHERE id=?","<script>alert(1)</script>",g.id());
  var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/g/"+g.code())).GET().build(),HttpResponse.BodyHandlers.ofString());
  assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).contains("&lt;script&gt;").doesNotContain("<script>");
  assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
  assertThat(response.headers().firstValue("Content-Security-Policy").orElse("")).contains("default-src 'none'");
  assertThat(call("GET","/group-links/"+g.code().substring(0,7),null,null).status()).isEqualTo(404);
  assertThat(call("DELETE","/groups/"+g.id(),null,a).status()).isEqualTo(200);
  assertThat(call("GET","/group-links/"+g.code(),null,null).status()).isEqualTo(404);
  assertThat(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/g/"+g.code())).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
 }
 @Test void publicDomainRejectsCredentialsPathsAndUnsafeSchemes(){
  for(String url:List.of("javascript:alert(1)","http://public.example","https://user:pass@example.test","https://example.test/path","https://example.test?next=other","https://example.test/#fragment"))
   assertThatThrownBy(()->new com.manliao.backend.groups.GroupLinks(url)).isInstanceOf(IllegalArgumentException.class);
  assertThat(new com.manliao.backend.groups.GroupLinks("https://example.test/").output(Map.of("link_code","abcd1234","name","test")).get("share_url")).isEqualTo("https://example.test/g/abcd1234");
 }
 @Test void ownerCreatesGroupAndCanSendAlone()throws Exception{
  var a=user();var g=group(a,"manual",500);
  assertThat(call("GET","/groups/",null,a).body().size()).isEqualTo(1);
  assertThat(call("GET","/groups/"+g.id(),null,a).text("current_user_role")).isEqualTo("owner");
  assertThat(call("GET","/conversations/",null,a).body().size()).isEqualTo(1);
  assertThat(send(a,g.conversation(),"single-owner-message","hello").status()).isEqualTo(200);
  assertThat(count("message_receipts")).isEqualTo(1);assertThat(count("notification_events")).isZero();
  assertThat(call("POST","/groups/"+g.id()+"/leave",null,a).status()).isEqualTo(409);
 }
 @Test void invalidGroupCreationAndTransactionRollbackLeaveNoHalfGroup()throws Exception{
  var a=user();assertThat(call("POST","/groups/",Map.of("name"," ","tags",List.of()),a).status()).isEqualTo(422);
  assertThat(call("POST","/groups/",Map.of("name","test","tags",List.of("x"),"join_rule_type","password"),a).status()).isEqualTo(422);
  db.execute("ALTER TABLE group_members ADD CONSTRAINT group_create_fixture CHECK(role<>'owner')");
  try{assertThat(call("POST","/groups/",Map.of("name","test","tags",List.of("x")),a).status()).isEqualTo(500);}
  finally{db.execute("ALTER TABLE group_members DROP CONSTRAINT group_create_fixture");}
  assertThat(count("groups")).isZero();assertThat(count("conversations")).isZero();
 }
 @Test void searchAndExactLinkCodeDoNotGrantMemberAccess()throws Exception{
  var a=user();var b=user();var g=group(a,"open",10);
  assertThat(call("GET","/groups/search?q=音乐",null,b).body().size()).isEqualTo(1);
  assertThat(call("GET","/groups/"+g.id(),null,b).status()).isEqualTo(200);
  assertThat(call("GET","/groups/"+g.id()+"/members",null,b).status()).isEqualTo(403);
  assertThat(call("GET",path(g.conversation()),null,b).status()).isEqualTo(404);
  assertThat(call("POST","/groups/join-by-link-code?link_code="+g.code().substring(0,4),Map.of(),b).status()).isEqualTo(404);
  var r=call("POST","/groups/join-by-link-code?link_code="+g.code(),Map.of(),b);assertThat(r.text("status")).isEqualTo("approved");assertThat(r.text("source")).isEqualTo("link_code");
 }
 @Test void manualRequestsRequireManagerAndCannotResolveAnotherGroup()throws Exception{
  var a=user();var b=user();var c=user();var g=group(a,"manual",10);var other=group(a,"manual",10);
  var request=join(b,g);assertThat(request.text("status")).isEqualTo("pending");assertThat(join(b,g).status()).isEqualTo(409);
  assertThat(call("GET","/groups/"+g.id()+"/join-requests",null,b).status()).isEqualTo(403);
  assertThat(call("POST","/groups/"+other.id()+"/join-requests/"+request.text("id")+"/approve",null,a).status()).isEqualTo(404);
  assertThat(call("POST","/groups/"+g.id()+"/join-requests/"+request.text("id")+"/approve",null,c).status()).isEqualTo(403);
  assertThat(call("POST","/groups/"+g.id()+"/join-requests/"+request.text("id")+"/approve",null,a).text("status")).isEqualTo("approved");
  assertThat(call("POST","/groups/"+g.id()+"/join-requests/"+request.text("id")+"/approve",null,a).status()).isEqualTo(404);
  var rejected=join(c,g);assertThat(call("POST","/groups/"+g.id()+"/join-requests/"+rejected.text("id")+"/reject",null,a).text("status")).isEqualTo("rejected");
  assertThat(call("GET","/groups/"+g.id()+"/join-requests",null,a).body().size()).isEqualTo(2);
 }
 @Test void passwordAndQuestionAreHashedAndRejectedGuessesDoNotJoin()throws Exception{
  var a=user();var b=user();
  for(String rule:List.of("password","question")){
   var g=group(a,rule,10);assertThat(db.queryForObject("SELECT join_secret_hash FROM groups WHERE id=?",String.class,g.id())).startsWith("pbkdf2_sha256$");
   assertThat(join(b,g).text("status")).isEqualTo("rejected");
   var payload=rule.equals("password")?Map.of("password","fixture-password"):Map.of("answer","fixture-answer");
   assertThat(call("POST","/groups/"+g.id()+"/join-requests",payload,b).text("status")).isEqualTo("approved");
  }
 }
 @Test void inviteOnlyAndRolesEnforceOwnerAdminBoundaries()throws Exception{
  var a=user();var b=user();var c=user();var d=user();var g=group(a,"invite_only",10);
  assertThat(join(b,g).status()).isEqualTo(403);invited(a,g,b);assertThat(memberRole(a,g,b,"admin").status()).isEqualTo(200);invited(b,g,c);invited(a,g,d);
  assertThat(memberRole(b,g,c,"admin").status()).isEqualTo(403);assertThat(memberRole(a,g,c,"admin").status()).isEqualTo(200);
  assertThat(remove(b,g,c).status()).isEqualTo(403);assertThat(remove(b,g,a).status()).isEqualTo(403);assertThat(remove(b,g,d).status()).isEqualTo(200);
  assertThat(memberRole(a,g,c,"member").status()).isEqualTo(200);assertThat(remove(b,g,c).status()).isEqualTo(200);
 }
 @Test void concurrentOpenJoinsCannotExceedCapacity()throws Exception{
  var a=user();var b=user();var c=user();var g=group(a,"open",2);var barrier=new CyclicBarrier(2);
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var results=pool.invokeAll(List.<Callable<Integer>>of(()->{barrier.await();return join(b,g).status();},()->{barrier.await();return join(c,g).status();}));
   var codes=new ArrayList<Integer>();for(var f:results)codes.add(f.get(10,TimeUnit.SECONDS));assertThat(codes).containsExactlyInAnyOrder(200,409);
  }
  assertThat(call("GET","/groups/"+g.id(),null,a).body().get("member_count").asInt()).isEqualTo(2);
 }
 @Test void ownerTransferIsAtomicAndOldOwnerCanLeave()throws Exception{
  var a=user();var b=user();var g=group(a,"open",10);join(b,g);
  assertThat(call("POST","/groups/"+g.id()+"/transfer-owner",Map.of("target_user_id",a.id()),b).status()).isEqualTo(403);
  assertThat(call("POST","/groups/"+g.id()+"/transfer-owner",Map.of("target_user_id",b.id()),a).text("owner_id")).isEqualTo(b.id());
  assertThat(db.queryForObject("SELECT count(*) FROM group_members WHERE group_id=? AND role='owner'",Integer.class,g.id())).isEqualTo(1);
  assertThat(call("POST","/groups/"+g.id()+"/leave",null,a).status()).isEqualTo(200);
 }
 @Test void groupReceiptsUnreadAndNotificationPreferencesArePerRecipient()throws Exception{
  var a=user();var b=user();var c=user();var g=group(a,"open",10);join(b,g);join(c,g);device(b);device(c);
  db.update("INSERT INTO notification_preferences(user_id,chat_messages_enabled,group_messages_enabled) VALUES(?,false,true),(?,true,false)",b.id(),c.id());
  var sent=send(a,g.conversation(),"group-many-receivers","hello all");assertThat(sent.status()).as(sent.body().toString()).isEqualTo(200);
  assertThat(count("message_receipts")).isEqualTo(3);assertThat(count("notification_events")).isEqualTo(2);
  assertThat(unread(b,g.conversation())).isEqualTo(1);assertThat(unread(c,g.conversation())).isEqualTo(1);
  assertThat(db.queryForObject("SELECT status FROM notification_events WHERE recipient_user_id=?",String.class,b.id())).isEqualTo("pending");
  assertThat(db.queryForObject("SELECT suppress_reason FROM notification_events WHERE recipient_user_id=?",String.class,c.id())).isEqualTo("notification_preference_disabled");
  read(b,g.conversation());assertThat(unread(b,g.conversation())).isZero();assertThat(unread(c,g.conversation())).isEqualTo(1);
  assertThat(send(a,g.conversation(),"group-many-receivers","hello all").text("id")).isEqualTo(sent.text("id"));assertThat(count("notification_events")).isEqualTo(2);
 }
 @Test void leaveAfterSendingKeepsOthersHistoryAndRejoinHasNoOldReceipts()throws Exception{
  var a=user();var b=user();var c=user();var g=group(a,"open",10);join(b,g);
  var sent=send(b,g.conversation(),"before-leave-message","old");assertThat(sent.status()).isEqualTo(200);
  assertThat(call("POST","/groups/"+g.id()+"/leave",null,b).status()).isEqualTo(200);
  assertThat(call("GET",path(g.conversation())+"/messages",null,b).status()).isEqualTo(404);
  assertThat(call("GET",path(g.conversation())+"/messages",null,a).body().size()).isEqualTo(1);
  join(b,g);join(c,g);assertThat(call("GET",path(g.conversation())+"/messages",null,b).body().size()).isZero();assertThat(call("GET",path(g.conversation())+"/messages",null,c).body().size()).isZero();
  assertThat(send(b,g.conversation(),"after-rejoin-message","new").status()).isEqualTo(200);
 }
 @Test void dissolvedGroupRevokesConversationAndMediaForEveryone()throws Exception{
  var a=user();var b=user();var g=group(a,"open",10);join(b,g);String mediaId=imageAsset(a,g.conversation());sendAsset(a,g.conversation(),mediaId,"dissolve-media-msg","image","image");String url=variant(b,mediaId,"thumbnail");
  assertThat(call("DELETE","/groups/"+g.id(),null,b).status()).isEqualTo(403);assertThat(call("DELETE","/groups/"+g.id(),null,a).text("status")).isEqualTo("dissolved");
  assertThat(call("GET","/groups/"+g.id(),null,b).status()).isEqualTo(410);assertThat(call("GET","/groups/",null,a).body().size()).isZero();
  assertThat(call("GET","/conversations/",null,b).body().size()).isZero();assertThat(download(url).statusCode()).isEqualTo(404);
 }
 @Test void removedMemberLosesExistingSignedMediaAndCannotUpload()throws Exception{
  var a=user();var b=user();var g=group(a,"open",10);join(b,g);String id=imageAsset(a,g.conversation());sendAsset(a,g.conversation(),id,"remove-media-message","image","image");String url=variant(b,id,"display");
  assertThat(remove(a,g,b).status()).isEqualTo(200);assertThat(download(url).statusCode()).isEqualTo(404);
  assertThat(upload(b,g.conversation(),png(2,2),"image/png","image","chat").status()).isEqualTo(404);
  assertThat(decoded(variant(a,id,"thumbnail"))).isNotNull();
 }
 @Test void websocketGroupDeliveryAckTypingAndRemovalGate()throws Exception{
  var a=user();var b=user();var c=user();var g=group(a,"open",10);join(b,g);join(c,g);
  try(Probe pb=open(b,"messages",true,false);Probe pc=open(c,"messages",true,false)){
   var sent=send(a,g.conversation(),"websocket-group-test","hello");realtime.publish();
   var fb=pb.await("message.created");var fc=pc.await("message.created");assertThat(fb.get("message").get("id").asString()).isEqualTo(sent.text("id"));assertThat(fc.get("message").get("id").asString()).isEqualTo(sent.text("id"));ack(pb,fb);ack(pc,fc);
   realtime.typing(a.token(),g.conversation(),"typing.start");assertThat(pb.await("typing.start").get("user_id").asString()).isEqualTo(a.id());
   assertThat(remove(a,g,b).status()).isEqualTo(200);send(a,g.conversation(),"after-removal-msg","new");realtime.publish();assertThat(pb.absent("message.created",800)).isTrue();assertThat(pc.await("message.created").get("message").get("content").asString()).isEqualTo("new");
  }
 }
 @Test void erasingOwnerTransfersGroupAndPreservesOtherMembersMedia()throws Exception{
  var a=user();var b=user();var g=group(a,"open",10);join(b,g);
  String own=imageAsset(a,g.conversation()),peer=imageAsset(b,g.conversation()),ownKey=key(own),peerKey=key(peer);
  var sent=sendAsset(a,g.conversation(),own,"erased-owner-message","image","image");sendAsset(b,g.conversation(),peer,"preserved-peer-msg","image","image");
  call("POST","/auth/account/deactivate",null,a);db.update("UPDATE users SET deactivation_due_at=now()-interval '1 second' WHERE id=?",a.id());
  assertThat(accounts.eraseDue(a.id())).isTrue();accounts.cleanupStorage();absent(ownKey);assertThat(java.nio.file.Files.exists(ROOT.resolve(peerKey))).isTrue();
  assertThat(call("GET","/groups/"+g.id(),null,b).text("owner_id")).isEqualTo(b.id());
  assertThat(db.queryForObject("SELECT content FROM messages WHERE id=?",String.class,sent.text("id"))).isEqualTo("已注销用户的消息");
  assertThat(send(b,g.conversation(),"after-owner-erasure","still works").status()).isEqualTo(200);assertThat(download(variant(b,peer,"display")).statusCode()).isEqualTo(200);
 }
 @Test void erasingSoleOwnerDissolvesWithoutForeignKeyFailures()throws Exception{
  var a=user();var observer=user();var g=group(a,"open",10);send(a,g.conversation(),"single-erasure-msg","secret");
  call("POST","/auth/account/deactivate",null,a);db.update("UPDATE users SET deactivation_due_at=now()-interval '1 second' WHERE id=?",a.id());assertThat(accounts.eraseDue(a.id())).isTrue();
  assertThat(call("GET","/groups/"+g.id(),null,observer).status()).isEqualTo(410);assertThat(count("group_members")).isZero();
 }
 @Test void pendingDeactivationOfPeerDoesNotFreezeRemainingGroup()throws Exception{
  var a=user();var b=user();var g=group(a,"open",10);join(b,g);call("POST","/auth/account/deactivate",null,b);
  assertThat(send(a,g.conversation(),"pending-peer-message","still active").status()).isEqualTo(200);
 }
 @Test void concurrentSendAndRemovalLeaveNoUnauthorizedReceipts()throws Exception{
  var a=user();var b=user();var g=group(a,"open",10);join(b,g);var barrier=new CyclicBarrier(2);
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var sending=pool.submit(()->{barrier.await();return send(b,g.conversation(),"racing-group-message","race");});var removing=pool.submit(()->{barrier.await();return remove(a,g,b);});
   assertThat(removing.get(15,TimeUnit.SECONDS).status()).isEqualTo(200);assertThat(sending.get(15,TimeUnit.SECONDS).status()).isIn(200,404);
  }
  assertThat(db.queryForObject("SELECT count(*) FROM message_receipts WHERE user_id=?",Integer.class,b.id())).isZero();assertThat(call("GET",path(g.conversation()),null,b).status()).isEqualTo(404);
 }
 @Test void invitationSupersedesPendingRequestWithoutDuplicateMembership()throws Exception{
  var a=user();var b=user();var g=group(a,"manual",10);var request=join(b,g);invited(a,g,b);
  assertThat(call("POST","/groups/"+g.id()+"/join-requests/"+request.text("id")+"/approve",null,a).status()).isEqualTo(404);
  assertThat(call("POST","/groups/"+g.id()+"/invite",Map.of("target_user_id",b.id()),a).status()).isEqualTo(409);
  assertThat(call("GET","/groups/"+g.id(),null,a).body().get("member_count").asInt()).isEqualTo(2);
 }
 @Test void manualPortraitApprovalMakesAProfileEligibleAndRejectRemovesIt()throws Exception{
  var a=user();var b=user();
  var uploaded=upload(b,null,png(10,10),"image/png","image","profile");assertThat(uploaded.status()).isEqualTo(200);String id=uploaded.text("id"),url="media:"+id;
  db.update("UPDATE user_profiles SET pending_photo_urls=jsonb_build_array(?::text) WHERE user_id=?",url,b.id());
  admins.bootstrap(new com.manliao.backend.admin.AdminDtos.Create("portrait_admin","Portrait-admin-secret-123","测试审核员","owner"));
  var login=admins.login(new com.manliao.backend.admin.AdminDtos.Login("portrait_admin","Portrait-admin-secret-123"),"portrait-test");
  String token=(String)login.get("access_token");
  var approve=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/admin/moderation/media/"+id+"/approve")).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"portrait_manual_approved\":true}")).build();
  var result=http.send(approve,HttpResponse.BodyHandlers.ofString());assertThat(result.statusCode()).isEqualTo(200);
  assertThat(db.queryForObject("SELECT portrait_manual_approved FROM media_assets WHERE id=?",Boolean.class,id)).isTrue();
  assertThat(call("GET","/explore/candidates",null,a).body().size()).isEqualTo(1);
  var reject=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/admin/moderation/media/"+id+"/reject")).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"fixture rejection\"}")).build();
  assertThat(http.send(reject,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
  assertThat(call("GET","/explore/candidates",null,a).body().size()).isZero();
 } @Test void newMemberCannotSeePreJoinMessageThroughReplyPreview()throws Exception{
  var a=user();var b=user();var g=group(a,"open",10);
  var old=send(a,g.conversation(),"before-join-private","only original members");
  join(b,g);
  var reply=call("POST","/conversations/"+g.conversation()+"/messages",Map.of("content","reply after join","client_message_id","after-join-reply","reply_to_message_id",old.text("id")),a);
  assertThat(reply.status()).isEqualTo(200);assertThat(reply.body().get("reply_preview").isNull()).isFalse();
  var history=call("GET","/conversations/"+g.conversation()+"/messages",null,b);
  assertThat(history.body().size()).isEqualTo(1);assertThat(history.body().get(0).get("reply_preview").isNull()).isTrue();
 }}
