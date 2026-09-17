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
 "app.auth.jwt-secret=isolated-post-tests-secret-at-least-thirty-two-bytes",
 "app.groups.public-base-url=https://groups.example.test","app.auth.legacy-registration-enabled=true","app.accounts.worker-enabled=false","app.media.derivatives-worker-enabled=false","app.media.jobs-worker-enabled=false","app.media.draft-worker-enabled=false"})
class PostIntegrationTests {
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
 static java.nio.file.Path root(){try{return java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("target"),"posts-it-").toAbsolutePath();}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
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
 @Autowired com.manliao.backend.media.MediaJobs jobs;
 Reply post(User u,String visibility,String text)throws Exception{return call("POST","/posts/",Map.of("text",text,"visibility",visibility),u);}
 Reply comment(User u,String post,String text,String parent)throws Exception{var body=new HashMap<String,Object>();body.put("content",text);if(parent!=null)body.put("parent_comment_id",parent);return call("POST","/posts/"+post+"/comments",body,u);}
 String image(User u,String source,boolean approve)throws Exception{var item=upload(u,null,png(32,24),"image/png","image",source);assertThat(item.status()).as(item.body().toString()).isEqualTo(200);if(approve)media.resolveReview(item.text("id"),true,"fixture","approved");return item.text("id");}
 Reply withMedia(User u,String asset,String visibility,boolean save)throws Exception{return call("POST","/posts/",Map.of("type","image","media_asset_ids",List.of(asset),"visibility",visibility,"allow_media_save",save),u);}
 @Test void textValidationDefaultsAndLocalModeration()throws Exception{
  var a=user();assertThat(call("POST","/posts/",Map.of(),a).status()).isEqualTo(400);
  assertThat(call("POST","/posts/",Map.of("text","x".repeat(1001)),a).status()).isEqualTo(422);
  assertThat(post(a,"public","添加微信交流").status()).isEqualTo(400);
  var created=call("POST","/posts/",Map.of("text","  first post  "),a);assertThat(created.status()).isEqualTo(201);assertThat(created.text("text")).isEqualTo("first post");assertThat(created.text("visibility")).isEqualTo("friends");
  assertThat(created.body().get("media").size()).isZero();
 }
 @Test void visibilityAndFeedDistinguishSelfFriendsAndStrangers()throws Exception{
  var a=user();var b=user();var c=user();friend(a,b);
  var pub=post(a,"public","public");var friends=post(a,"friends","friends");var own=post(a,"private","private");
  assertThat(call("GET","/posts/"+pub.text("id"),null,c).status()).isEqualTo(200);
  assertThat(call("GET","/posts/"+friends.text("id"),null,c).status()).isEqualTo(404);
  assertThat(call("GET","/posts/"+own.text("id"),null,b).status()).isEqualTo(404);
  assertThat(call("GET","/posts/feed",null,a).body().size()).isEqualTo(3);
  assertThat(call("GET","/posts/feed",null,b).body().size()).isEqualTo(2);
  assertThat(call("GET","/posts/feed",null,c).body().size()).isZero();
  assertThat(call("GET","/posts/user/"+a.id(),null,c).body().size()).isEqualTo(1);
 }
 @Test void feedCursorStableTieBreakingAndValidation()throws Exception{
  var a=user();for(int i=0;i<4;i++)post(a,"public","post "+i);db.update("UPDATE posts SET created_at='2026-01-01T00:00:00Z'");
  var first=call("GET","/posts/feed/page?limit=2",null,a);assertThat(first.status()).isEqualTo(200);assertThat(first.body().get("has_more").asBoolean()).isTrue();
  var second=call("GET","/posts/feed/page?limit=2&cursor="+first.text("next_cursor"),null,a);
  var ids=new HashSet<String>();for(var r:first.body().get("items"))ids.add(r.get("id").asString());for(var r:second.body().get("items"))ids.add(r.get("id").asString());assertThat(ids).hasSize(4);
  assertThat(second.body().get("has_more").asBoolean()).isFalse();assertThat(call("GET","/posts/feed/page?cursor=invalid",null,a).status()).isEqualTo(400);
  assertThat(call("GET","/posts/feed?limit=0",null,a).status()).isEqualTo(422);assertThat(call("GET","/posts/feed?offset=-1",null,a).status()).isEqualTo(422);
 }
 @Test void concurrentPostLikesAreIdempotentAndAggregateNotifications()throws Exception{
  var a=user();var b=user();var c=user();String p=post(a,"public","likes").text("id");
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var gate=new CountDownLatch(1);var results=new ArrayList<Future<Reply>>();for(int i=0;i<8;i++)results.add(pool.submit(()->{gate.await();return call("POST","/posts/"+p+"/likes",null,b);}));gate.countDown();for(var r:results)assertThat(r.get(15,TimeUnit.SECONDS).status()).isEqualTo(200);}
  assertThat(call("POST","/posts/"+p+"/likes",null,c).body().get("like_count").asInt()).isEqualTo(2);
  assertThat(db.queryForObject("SELECT count(*) FROM notification_events WHERE event_type='post_liked'",Integer.class)).isEqualTo(1);
  assertThat(db.queryForObject("SELECT (payload->>'like_count')::int FROM notification_events WHERE event_type='post_liked'",Integer.class)).isEqualTo(2);
  assertThat(call("DELETE","/posts/"+p+"/likes",null,b).body().get("like_count").asInt()).isEqualTo(1);
 }
 @Test void blockAndInactiveAuthorHidePostsAndPreventWrites()throws Exception{
  var a=user();var b=user();String p=post(a,"public","visible").text("id");
  call("POST","/safety/blocks",Map.of("target_user_id",a.id()),b);
  assertThat(call("GET","/posts/"+p,null,b).status()).isEqualTo(404);assertThat(call("POST","/posts/"+p+"/likes",null,b).status()).isEqualTo(404);
  db.update("DELETE FROM blocks");db.update("UPDATE users SET status='deactivation_pending' WHERE id=?",a.id());
  assertThat(call("GET","/posts/"+p,null,b).status()).isEqualTo(404);assertThat(post(a,"public","new").status()).isEqualTo(403);
 }
 @Test void commentThreadsReplyOrderingAndDeletedRootTombstone()throws Exception{
  var a=user();var b=user();String p=post(a,"public","thread").text("id");
  String root=comment(b,p,"root",null).text("id");String child=comment(a,p,"first reply",root).text("id");comment(b,p,"nested reply",child);
  var list=call("GET","/posts/"+p+"/comments",null,a);assertThat(list.body().size()).isEqualTo(3);
  assertThat(list.body().get(2).get("root_comment_id").asString()).isEqualTo(root);
  assertThat(list.body().get(2).get("reply_to_user_id").asString()).isEqualTo(a.id());
  var page=call("GET","/posts/"+p+"/comments/page?reply_preview_limit=1",null,a);assertThat(page.body().get("items").size()).isEqualTo(1);assertThat(page.body().get("items").get(0).get("replies").size()).isEqualTo(1);
  assertThat(page.body().get("items").get(0).get("reply_count").asInt()).isEqualTo(2);
  assertThat(call("DELETE","/posts/"+p+"/comments/"+root,null,b).status()).isEqualTo(204);
  var after=call("GET","/posts/"+p+"/comments",null,a);assertThat(after.body().get(0).get("is_deleted").asBoolean()).isTrue();assertThat(after.body().get(0).get("content").isNull()).isTrue();
  assertThat(comment(a,p,"cannot reply to deleted",root).status()).isEqualTo(404);
 }
 @Test void commentPagesAndCrossPostCursorsCannotCrossThreads()throws Exception{
  var a=user();String p=post(a,"public","one").text("id"),q=post(a,"public","two").text("id");
  String root=comment(a,p,"first",null).text("id");String second=comment(a,p,"second",null).text("id");String other=comment(a,q,"other",null).text("id");
  var page=call("GET","/posts/"+p+"/comments/page?limit=1",null,a);assertThat(page.body().get("has_more").asBoolean()).isTrue();
  assertThat(call("GET","/posts/"+p+"/comments/page?limit=1&cursor="+page.text("next_cursor"),null,a).body().get("items").size()).isEqualTo(1);
  assertThat(call("GET","/posts/"+p+"/comments/page?cursor="+other,null,a).status()).isEqualTo(400);
  assertThat(comment(a,p,"cross post",other).status()).isEqualTo(404);
  String reply=comment(a,p,"reply",root).text("id");
  assertThat(call("GET","/posts/"+p+"/comments/"+second+"/replies?cursor="+reply,null,a).status()).isEqualTo(400);
 }
 @Test void commentLikesAndAuthorModerationPermissions()throws Exception{
  var a=user();var b=user();var c=user();String p=post(a,"public","moderation").text("id");String comment=comment(b,p,"hello",null).text("id");
  assertThat(call("POST","/posts/"+p+"/comments/"+comment+"/likes",null,c).body().get("like_count").asInt()).isEqualTo(1);
  assertThat(call("POST","/posts/"+p+"/comments/"+comment+"/likes",null,c).body().get("like_count").asInt()).isEqualTo(1);
  assertThat(call("DELETE","/posts/"+p+"/comments/"+comment+"/likes",null,c).body().get("like_count").asInt()).isZero();
  assertThat(call("DELETE","/posts/"+p+"/comments/"+comment,null,c).status()).isEqualTo(403);
  assertThat(call("DELETE","/posts/"+p+"/comments/"+comment,null,a).status()).isEqualTo(204);
 }
 @Test void commentRateLimitPreservesPriorCommentsAndResetsByWindow()throws Exception{
  var a=user();String p=post(a,"public","limits").text("id");
  for(int i=0;i<8;i++)assertThat(comment(a,p,"comment "+i,null).status()).isEqualTo(201);
  assertThat(comment(a,p,"too many",null).status()).isEqualTo(429);assertThat(count("post_comments")).isEqualTo(8);
  db.update("UPDATE auth_rate_windows SET expires_at=now()-interval '1 second' WHERE bucket_key=?", "post-comment:"+a.id());
  assertThat(comment(a,p,"next window",null).status()).isEqualTo(201);
 }
 @Test void postMediaApprovalRejectionAndExistingSignedUrlRevalidation()throws Exception{
  var a=user();var b=user();String asset=image(a,"post",false);var p=withMedia(a,asset,"public",true);
  assertThat(p.status()).isEqualTo(201);assertThat(p.text("moderation_status")).isEqualTo("review_pending");String id=p.text("id");
  assertThat(call("GET","/posts/"+id,null,b).status()).isEqualTo(404);
  media.resolveReview(asset,true,"fixture","approved");var visible=call("GET","/posts/"+id,null,b);assertThat(visible.status()).isEqualTo(200);String url=visible.body().get("media").get(0).get("url").asString();assertThat(download(url).statusCode()).isEqualTo(200);
  media.resolveReview(asset,false,"fixture","rejected");
  assertThat(call("GET","/posts/"+id,null,b).status()).isEqualTo(404);assertThat(download(url).statusCode()).isEqualTo(404);
  assertThat(call("GET","/posts/"+id,null,a).text("moderation_status")).isEqualTo("rejected");
 }
 @Test void mediaCannotBeStolenReusedOrBoundFromAnotherSource()throws Exception{
  var a=user();var b=user();String asset=image(a,"post",true),profile=image(a,"profile",true);
  assertThat(withMedia(b,asset,"public",true).status()).isEqualTo(404);assertThat(withMedia(a,profile,"public",true).status()).isEqualTo(404);
  assertThat(withMedia(a,asset,"public",true).status()).isEqualTo(201);assertThat(withMedia(a,asset,"public",true).status()).isEqualTo(404);
  assertThat(call("POST","/posts/",Map.of("type","video","media_asset_ids",List.of()),a).status()).isEqualTo(400);
 }
 @Test void oneMediaCannotBePublishedTwiceConcurrently()throws Exception{
  var a=user();String asset=image(a,"post",true);
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var gate=new CountDownLatch(1);var x=pool.submit(()->{gate.await();return withMedia(a,asset,"public",true);});var y=pool.submit(()->{gate.await();return withMedia(a,asset,"public",true);});gate.countDown();assertThat(List.of(x.get().status(),y.get().status())).containsExactlyInAnyOrder(201,404);}
  assertThat(count("posts")).isEqualTo(1);
 }
 @Test void commentImageAndGifApprovalKeepPendingContentPrivate()throws Exception{
  var a=user();var b=user();String p=post(a,"public","image replies").text("id");
  var asset=upload(b,null,gif(2,4,4,10),"image/gif","image","post_comment");assertThat(asset.status()).isEqualTo(200);
  var comment=call("POST","/posts/"+p+"/comments",Map.of("media_asset_id",asset.text("id")),b);assertThat(comment.status()).isEqualTo(201);assertThat(comment.text("media_kind")).isEqualTo("gif");
  assertThat(call("GET","/posts/"+p+"/comments",null,a).body().size()).isZero();
  assertThat(call("POST","/posts/"+p+"/comments",Map.of("media_asset_id",asset.text("id")),b).status()).isEqualTo(404);
  media.resolveReview(asset.text("id"),true,"fixture","approved");
  var visible=call("GET","/posts/"+p+"/comments",null,a);assertThat(visible.body().size()).isEqualTo(1);String url=visible.body().get(0).get("media").get("url").asString();assertThat(download(url).statusCode()).isEqualTo(200);
  assertThat(db.queryForObject("SELECT count(*) FROM notification_events WHERE event_type='post_commented'",Integer.class)).isEqualTo(1);
  call("DELETE","/posts/"+p+"/comments/"+comment.text("id"),null,b);assertThat(download(url).statusCode()).isEqualTo(404);
 }
 @Test void mediaSavePreferenceAndDownloadNames()throws Exception{
  var a=user();var b=user();String asset=image(a,"post",true);String p=withMedia(a,asset,"public",false).text("id");
  assertThat(call("GET","/posts/"+p+"/media/"+asset+"/download",null,b).status()).isEqualTo(403);
  var download=call("GET","/posts/"+p+"/media/"+asset+"/download",null,a);assertThat(download.status()).isEqualTo(200);assertThat(download.text("filename")).endsWith(".png");
  assertThat(download(download.text("url")).statusCode()).isEqualTo(200);
 }
 @Test void emojiReactionReplaceAggregateDeleteAndValidation()throws Exception{
  var a=user();var b=user();var c=user();String asset=image(a,"post",true);String p=withMedia(a,asset,"public",true).text("id");String path="/posts/"+p+"/media/"+asset+"/reaction";
  assertThat(call("PUT",path,Map.of("emoji","👍"),b).body().get("reaction_total").asInt()).isEqualTo(1);
  assertThat(call("PUT",path,Map.of("emoji","❤️"),b).body().get("reaction_total").asInt()).isEqualTo(1);
  assertThat(call("PUT",path,Map.of("emoji","❤️"),c).body().get("reaction_counts").get("❤️").asInt()).isEqualTo(2);
  assertThat(call("PUT",path,Map.of("emoji","not an emoji"),b).status()).isEqualTo(400);
  assertThat(call("GET",path+"s",null,a).body().get("reaction_total").asInt()).isEqualTo(2);
  assertThat(call("DELETE",path,null,b).body().get("reaction_total").asInt()).isEqualTo(1);
  assertThat(db.queryForObject("SELECT count(*) FROM notification_events WHERE event_type='post_media_reacted'",Integer.class)).isEqualTo(1);
 }
 @Test void deletePostRevokesExistingMediaAndPreventsPeerDeletion()throws Exception{
  var a=user();var b=user();String asset=image(a,"post",true);String p=withMedia(a,asset,"public",true).text("id");String url=grant(b,asset);
  assertThat(call("DELETE","/posts/"+p,null,b).status()).isEqualTo(403);assertThat(call("DELETE","/posts/"+p,null,a).status()).isEqualTo(204);
  assertThat(call("GET","/posts/"+p,null,a).status()).isEqualTo(404);assertThat(download(url).statusCode()).isEqualTo(404);
 }
 @Test void friendDeletionAndBlockInvalidateOldPostMediaGrants()throws Exception{
  var a=user();var b=user();friend(a,b);String asset=image(a,"post",true);withMedia(a,asset,"friends",true);String url=grant(b,asset);
  call("DELETE","/friends/"+a.id(),null,b);assertThat(download(url).statusCode()).isEqualTo(404);
  friend(a,b);String restored=grant(b,asset);call("POST","/safety/blocks",Map.of("target_user_id",b.id()),a);assertThat(download(restored).statusCode()).isEqualTo(404);
 }
 @Test void draftCleanupKeepsPublishedPostAndCommentMedia()throws Exception{
  var a=user();String asset=image(a,"post",true),commentAsset=image(a,"post_comment",true),draft=image(a,"post",true);
  String p=withMedia(a,asset,"public",true).text("id");call("POST","/posts/"+p+"/comments",Map.of("media_asset_id",commentAsset),a);
  db.update("UPDATE media_assets SET created_at=now()-interval '48 hours'");assertThat(jobs.reclaimDrafts()).isEqualTo(1);
  assertThat(db.queryForObject("SELECT status FROM media_assets WHERE id=?",String.class,draft)).isEqualTo("deleted");
  assertThat(call("GET","/posts/"+p,null,a).body().get("media").size()).isEqualTo(1);assertThat(grant(a,commentAsset)).isNotBlank();
 }
 @Test void accountErasurePreservesOtherAuthorsReplyThreadsAndMedia()throws Exception{
  var a=user();var b=user();String own=image(a,"post",true),other=image(b,"post",true);
  String first=withMedia(a,own,"public",true).text("id"),second=withMedia(b,other,"public",true).text("id");
  assertThat(((Number)((Map<?,?>)accounts.preview(a.id()).get("counts")).get("posts")).longValue()).isEqualTo(1);
  String root=comment(a,second,"delete my text",null).text("id");comment(b,second,"keep my reply",root);call("POST","/posts/"+second+"/likes",null,a);
  call("POST","/auth/account/deactivate",null,a);db.update("UPDATE users SET deactivation_due_at=now()-interval '1 second' WHERE id=?",a.id());
  assertThat(accounts.eraseDue(a.id())).isTrue();accounts.cleanupStorage();
  assertThat(call("GET","/posts/"+first,null,b).status()).isEqualTo(404);assertThat(call("GET","/posts/"+second,null,b).status()).isEqualTo(200);
  var replies=call("GET","/posts/"+second+"/comments",null,b);assertThat(replies.body().size()).isEqualTo(2);assertThat(replies.body().get(0).get("content").isNull()).isTrue();assertThat(replies.body().get(1).get("content").asString()).isEqualTo("keep my reply");
  assertThat(db.queryForObject("SELECT count(*) FROM media_assets WHERE id=?",Integer.class,own)).isZero();assertThat(download(grant(b,other)).statusCode()).isEqualTo(200);
 }
 @Test void pendingAsyncPostMediaBindsBeforeProcessingAndFailsClosed()throws Exception{
  var a=user();var principal=new AuthDtos.Principal(a.id(),a.session());
  var asset=jobs.enqueue(principal,"image","post",null,new org.springframework.mock.web.MockMultipartFile("file","broken.png","image/png","invalid image".getBytes()));
  String id=(String)asset.get("id");var p=withMedia(a,id,"public",true);assertThat(p.status()).isEqualTo(201);
  assertThat(jobs.processNext()).isTrue();assertThat(call("GET","/posts/"+p.text("id"),null,a).text("moderation_status")).isEqualTo("rejected");
 }
 @Test void postCreationFailureRollsBackMediaBinding()throws Exception{
  var a=user();String asset=image(a,"post",true);db.execute("ALTER TABLE posts ADD CONSTRAINT reject_fixture CHECK(false) NOT VALID");
  try{assertThat(withMedia(a,asset,"public",true).status()).isEqualTo(500);assertThat(db.queryForObject("SELECT post_id FROM media_assets WHERE id=?",String.class,asset)).isNull();}
  finally{db.execute("ALTER TABLE posts DROP CONSTRAINT reject_fixture");}
  assertThat(withMedia(a,asset,"public",true).status()).isEqualTo(201);
 }
 @Test void postOwnerCanRemovePendingCommentWithoutExposingItsImage()throws Exception{
  var a=user();var b=user();var c=user();String p=post(a,"public","owner moderation").text("id");
  String asset=image(b,"post_comment",false);var pending=call("POST","/posts/"+p+"/comments",Map.of("media_asset_id",asset),b);
  assertThat(pending.status()).isEqualTo(201);String url=grant(b,asset);
  assertThat(call("DELETE","/posts/"+p+"/comments/"+pending.text("id"),null,c).status()).isEqualTo(404);
  assertThat(call("POST","/media/"+asset+"/access-url",null,a).status()).isEqualTo(404);
  assertThat(call("DELETE","/posts/"+p+"/comments/"+pending.text("id"),null,a).status()).isEqualTo(204);
  assertThat(download(url).statusCode()).isEqualTo(404);
  media.resolveReview(asset,true,"fixture","approved after removal");
  assertThat(call("GET","/posts/"+p+"/comments",null,b).body().size()).isZero();
  assertThat(db.queryForObject("SELECT count(*) FROM notification_events WHERE event_type='post_commented'",Integer.class)).isZero();
 }
 @Test void asyncPostImagePublishesOnlyAfterProcessingAndApproval()throws Exception{
  var a=user();var b=user();var principal=new AuthDtos.Principal(a.id(),a.session());
  var asset=jobs.enqueue(principal,"image","post",null,new org.springframework.mock.web.MockMultipartFile("file","image.png","image/png",png(32,24)));
  String id=(String)asset.get("id");var created=withMedia(a,id,"public",true);String p=created.text("id");
  assertThat(created.body().get("media").get(0).get("url").asString()).isEmpty();
  assertThat(call("GET","/posts/"+p,null,b).status()).isEqualTo(404);
  assertThat(jobs.processNext()).isTrue();assertThat(call("GET","/posts/"+p,null,b).status()).isEqualTo(404);
  media.resolveReview(id,true,"fixture","approved");
  assertThat(call("GET","/posts/"+p,null,b).status()).isEqualTo(200);
  assertThat(download(grant(b,id)).statusCode()).isEqualTo(200);
 }
 @Test void voiceAndVideoPostsUseRealTranscodingAndRevocableAccess()throws Exception{
  var a=user();var b=user();
  for(String type:List.of("voice","video")){
   byte[] bytes=type.equals("voice")?VoiceIntegrationTests.fixture(1.25,false,"aac"):VideoIntegrationTests.fixture(1.25,true,"libx264");
   var asset=upload(a,null,bytes,type.equals("voice")?"audio/mp4":"video/mp4",type,"post");
   assertThat(asset.status()).as(asset.body().toString()).isEqualTo(200);
   var created=call("POST","/posts/",Map.of("type",type,"media_asset_ids",List.of(asset.text("id")),"visibility","public"),a);
   assertThat(created.status()).isEqualTo(201);String id=created.text("id");
   assertThat(call("GET","/posts/"+id,null,b).status()).isEqualTo(404);
   media.resolveReview(asset.text("id"),true,"fixture","approved");
   assertThat(call("GET","/posts/"+id,null,b).text("type")).isEqualTo(type);
   String url=grant(b,asset.text("id"));assertThat(download(url).statusCode()).isEqualTo(200);
   if(type.equals("video"))assertThat(call("GET","/posts/"+id+"/media/"+asset.text("id")+"/download",null,b).text("filename")).endsWith(".mp4");
   assertThat(call("DELETE","/posts/"+id,null,a).status()).isEqualTo(204);assertThat(download(url).statusCode()).isEqualTo(404);
  }
 }
 @Test void approvalRacingPostDeletionCannotRestoreDeletedPost()throws Exception{
  var a=user();var b=user();String asset=image(a,"post",false),p=withMedia(a,asset,"public",true).text("id");
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var gate=new CountDownLatch(1);var approval=pool.submit(()->{gate.await();media.resolveReview(asset,true,"fixture","approved");return true;});
   var deletion=pool.submit(()->{gate.await();return call("DELETE","/posts/"+p,null,a);});gate.countDown();
   assertThat(approval.get(15,TimeUnit.SECONDS)).isTrue();assertThat(deletion.get(15,TimeUnit.SECONDS).status()).isEqualTo(204);
  }
  assertThat(call("GET","/posts/"+p,null,b).status()).isEqualTo(404);
  assertThat(call("POST","/media/"+asset+"/access-url",null,a).status()).isEqualTo(404);
 }
 @Test void commentNotificationDoesNotSplitEmojiSurrogates()throws Exception{
  var a=user();var b=user();String p=post(a,"public","unicode").text("id");
  String text="x".repeat(59)+"😀";
  assertThat(comment(b,p,text,null).status()).isEqualTo(201);
  assertThat(db.queryForObject("SELECT body FROM notification_events WHERE event_type='post_commented'",String.class)).isEqualTo(text);
 }
}
