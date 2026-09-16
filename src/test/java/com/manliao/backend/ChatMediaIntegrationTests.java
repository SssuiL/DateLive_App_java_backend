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
 "app.auth.jwt-secret=isolated-chat-media-tests-secret-at-least-thirty-two-bytes",
 "app.auth.legacy-registration-enabled=true","app.accounts.worker-enabled=false"})
class ChatMediaIntegrationTests {
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
 @Autowired com.manliao.backend.realtime.RealtimeStore realtime;
 @Autowired com.manliao.backend.admin.AdminService admins;
 static final java.nio.file.Path ROOT=root();
 static java.nio.file.Path root(){try{return java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("target"),"chat-images-").toAbsolutePath();}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
 @org.springframework.test.context.DynamicPropertySource static void storage(org.springframework.test.context.DynamicPropertyRegistry p){p.add("app.media.storage-root",()->ROOT.toString());}
 byte[] png()throws Exception{
  var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(3,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray();
 }
 Reply upload(User user,String conversation,String source,byte[] bytes)throws Exception{
  String boundary="chat-image-test";var out=new java.io.ByteArrayOutputStream();
  var fields=new LinkedHashMap<String,String>();fields.put("media_type","image");fields.put("source",source);if(conversation!=null)fields.put("conversation_id",conversation);
  for(var field:fields.entrySet())out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+field.getKey()+"\"\r\n\r\n"+field.getValue()+"\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
  out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"image.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
  out.write(bytes);out.write(("\r\n--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
  var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/media/upload")).header("Authorization","Bearer "+user.token())
   .header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
  return new Reply(response.statusCode(),json.readTree(response.body()));
 }
 Reply upload(User user,String conversation)throws Exception{return upload(user,conversation,"chat",png());}
 String approved(User user,String conversation)throws Exception{
  var asset=upload(user,conversation);assertThat(asset.status()).isEqualTo(200);media.resolveReview(asset.text("id"),true,"fixture-reviewer","isolated synthetic image");return asset.text("id");
 }
 Reply image(User user,String conversation,String asset,String key)throws Exception{return call("POST",path(conversation)+"/messages",Map.of("type","image","media_asset_id",asset,"content","[图片]","client_message_id",key),user);}
 String grant(User user,String asset)throws Exception{
  var result=call("POST","/media/"+asset+"/access-url",null,user);assertThat(result.status()).isEqualTo(200);return result.text("url");
 }
 int download(String url)throws Exception{return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+url)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode();}
 long files()throws Exception{try(var paths=java.nio.file.Files.list(ROOT)){return paths.filter(java.nio.file.Files::isRegularFile).count();}}
 @Test void uploadRequiresConversationMembershipAndValidScope()throws Exception{
  var p=pair();var stranger=user();long before=files();
  assertThat(upload(stranger,p.id()).status()).isEqualTo(404);
  assertThat(upload(p.a(),null,"chat",png()).status()).isEqualTo(400);
  assertThat(upload(p.a(),p.id(),"profile",png()).status()).isEqualTo(400);
  assertThat(files()).isEqualTo(before);assertThat(count("media_assets")).isZero();
  var asset=upload(p.a(),p.id());assertThat(asset.status()).isEqualTo(200);
  assertThat(asset.text("source")).isEqualTo("chat");assertThat(asset.text("conversation_id")).isEqualTo(p.id());
  assertThat(download(asset.text("preview_url"))).isEqualTo(200);
  assertThat(call("POST","/media/"+asset.text("id")+"/access-url",null,p.b()).status()).isEqualTo(404);
 }
 @Test void approvedImageFlowsThroughHistorySearchAndRealtime()throws Exception{
  var p=pair();var asset=upload(p.a(),p.id());String id=asset.text("id");
  assertThat(image(p.a(),p.id(),id,"pending-image").status()).isEqualTo(409);
  assertThat(count("messages")).isZero();assertThat(unread(p.b(),p.id())).isZero();
  media.resolveReview(id,true,"fixture","approved");
  var sent=image(p.a(),p.id(),id,"approved-image");assertThat(sent.status()).isEqualTo(200);
  assertThat(sent.text("media_asset_id")).isEqualTo(id);assertThat(sent.text("media_kind")).isEqualTo("image");
  assertThat(db.queryForObject("SELECT message_id FROM media_assets WHERE id=?",String.class,id)).isEqualTo(sent.text("id"));
  assertThat(download(grant(p.b(),id))).isEqualTo(200);
  assertThat(call("GET",path(p.id())+"/messages/search?message_type=image",null,p.b()).body().get("items").size()).isEqualTo(1);
  realtime.publish();
  var event=realtime.events(p.b().id(),"messages",0).getFirst();
  var frame=realtime.frame(p.b().id(),event);
  assertThat(((Map<?,?>)frame.get("message")).get("media_asset_id")).isEqualTo(id);
  assertThat(unread(p.b(),p.id())).isEqualTo(1);
  assertThat(call("PATCH","/profiles/me",Map.of("avatar_url","media:"+id),p.a()).status()).isEqualTo(404);
 }
 @Test void mediaCannotCrossOwnerConversationProfileOrMessage()throws Exception{
  var p=pair();var third=user();String other=friend(p.a(),third);
  String id=approved(p.a(),p.id());
  assertThat(image(p.b(),p.id(),id,"wrong-owner").status()).isEqualTo(404);
  assertThat(image(p.a(),other,id,"wrong-conversation").status()).isEqualTo(400);
  var profile=upload(p.a(),null,"profile",png());media.resolveReview(profile.text("id"),true,"fixture","approved");
  assertThat(image(p.a(),p.id(),profile.text("id"),"wrong-source").status()).isEqualTo(400);
  assertThat(image(p.a(),p.id(),id,"first-binding").status()).isEqualTo(200);
  assertThat(image(p.a(),p.id(),id,"second-binding").status()).isEqualTo(409);
  assertThat(count("messages")).isEqualTo(1);
 }
 @Test void concurrentRetriesBindOnceAndCompareTypeAndMediaKind()throws Exception{
  var p=pair();String id=approved(p.a(),p.id());
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var results=pool.invokeAll(List.<Callable<Reply>>of(()->image(p.a(),p.id(),id,"same-image-key"),()->image(p.a(),p.id(),id,"same-image-key")));
   var first=results.get(0).get();var second=results.get(1).get();
   assertThat(first.status()).isEqualTo(200);assertThat(second.text("id")).isEqualTo(first.text("id"));
  }
  assertThat(count("messages")).isEqualTo(1);assertThat(unread(p.b(),p.id())).isEqualTo(1);
  assertThat(send(p.a(),p.id(),"same-image-key","[图片]").status()).isEqualTo(409);
  assertThat(call("POST",path(p.id())+"/messages",Map.of("client_message_id","same-image-key","type","image","content","[图片]","media_asset_id",id,"media_kind","sticker"),p.a()).status()).isEqualTo(409);
 }
 @Test void personalHideRevokesEvenOwnerUrlsAndRecallRevokesBoth()throws Exception{
  var p=pair();String id=approved(p.a(),p.id());var sent=image(p.a(),p.id(),id,"hide-image-key");
  String aUrl=grant(p.a(),id),bUrl=grant(p.b(),id);
  assertThat(call("DELETE",path(p.id())+"/messages/"+sent.text("id"),null,p.a()).status()).isEqualTo(200);
  assertThat(download(aUrl)).isEqualTo(404);assertThat(download(bUrl)).isEqualTo(200);
  assertThat(call("GET","/media/me",null,p.a()).body().get(0).get("preview_url").isNull()).isTrue();
  String second=approved(p.a(),p.id());var recall=image(p.a(),p.id(),second,"recall-image-key");
  String preview=grant(p.b(),second);
  var recalled=call("POST",path(p.id())+"/messages/"+recall.text("id")+"/recall",null,p.a());
  assertThat(recalled.status()).isEqualTo(200);assertThat(recalled.body().get("media_asset_id").isNull()).isTrue();
  assertThat(recalled.body().get("media_kind").isNull()).isTrue();
  assertThat(download(preview)).isEqualTo(404);assertThat(call("POST","/media/"+second+"/access-url",null,p.a()).status()).isEqualTo(404);
 }
 @Test void clearOnlyRevokesClearingMembersMediaAccess()throws Exception{
  var p=pair();String id=approved(p.a(),p.id());image(p.a(),p.id(),id,"clear-image-key");
  String aUrl=grant(p.a(),id),bUrl=grant(p.b(),id);
  assertThat(call("DELETE",path(p.id())+"/messages",null,p.b()).status()).isEqualTo(200);
  assertThat(download(bUrl)).isEqualTo(404);assertThat(download(aUrl)).isEqualTo(200);
 }
 @Test void blockRevokesUrlsWhileUnfriendRetainsHistoryButStopsUploads()throws Exception{
  var p=pair();String id=approved(p.a(),p.id());image(p.a(),p.id(),id,"block-image-key");String url=grant(p.b(),id);
  db.update("INSERT INTO blocks(actor_user_id,target_user_id) VALUES(?,?)",p.a().id(),p.b().id());
  assertThat(download(url)).isEqualTo(404);assertThat(upload(p.a(),p.id()).status()).isEqualTo(404);
  db.update("DELETE FROM blocks");db.update("DELETE FROM friendships WHERE conversation_id=?",p.id());
  assertThat(download(url)).isEqualTo(200);long before=files();
  assertThat(upload(p.a(),p.id()).status()).isEqualTo(403);assertThat(files()).isEqualTo(before);
 }
 @Test void rejectionAndDeletionRevokeIssuedUrlsAndMaskMediaFields()throws Exception{
  var p=pair();String id=approved(p.a(),p.id());image(p.a(),p.id(),id,"reject-image-key");String url=grant(p.b(),id);
  media.resolveReview(id,false,"fixture","rejected");assertThat(download(url)).isEqualTo(404);
  var history=call("GET",path(p.id())+"/messages",null,p.b()).body().get(0);
  assertThat(history.get("media_asset_id").isNull()).isTrue();
  String second=approved(p.a(),p.id());image(p.a(),p.id(),second,"delete-image-key");String other=grant(p.b(),second);
  String key=db.queryForObject("SELECT storage_key FROM media_assets WHERE id=?",String.class,second);
  assertThat(call("DELETE","/media/"+second,null,p.a()).status()).isEqualTo(204);
  assertThat(download(other)).isEqualTo(404);assertThat(java.nio.file.Files.exists(ROOT.resolve(key))).isFalse();
 }
 @Test void invalidFilesAndUnsupportedKindsLeaveNoChatWrites()throws Exception{
  var p=pair();long before=files();
  assertThat(upload(p.a(),p.id(),"chat","not an image".getBytes()).status()).isEqualTo(400);
  assertThat(files()).isEqualTo(before);assertThat(count("media_assets")).isZero();
  String id=approved(p.a(),p.id());
  for(String type:List.of("unsupported"))assertThat(call("POST",path(p.id())+"/messages",Map.of("type",type,"content","x","media_asset_id",id),p.a()).status()).isEqualTo(422);
  assertThat(call("POST",path(p.id())+"/messages",Map.of("type","image","content","x","media_asset_id",id,"media_kind","gif"),p.a()).status()).isEqualTo(400);
  assertThat(count("messages")).isZero();
  var sticker=call("POST",path(p.id())+"/messages",Map.of("type","image","content","[贴纸]","media_asset_id",id,"media_kind","sticker"),p.a());
  assertThat(sticker.status()).isEqualTo(200);assertThat(sticker.text("media_kind")).isEqualTo("sticker");
 }
 @Test void outboxFailureRollsBackMediaBindingAndAllowsRetry()throws Exception{
  var p=pair();String id=approved(p.a(),p.id());
  db.execute("ALTER TABLE chat_change_outbox ADD CONSTRAINT image_failure CHECK(event_type<>'message.created')");
  try{
   assertThat(image(p.a(),p.id(),id,"rollback-image-key").status()).isEqualTo(500);
   assertThat(count("messages")).isZero();assertThat(unread(p.b(),p.id())).isZero();
   assertThat(db.queryForObject("SELECT message_id FROM media_assets WHERE id=?",String.class,id)).isNull();
  }finally{db.execute("ALTER TABLE chat_change_outbox DROP CONSTRAINT image_failure");}
  assertThat(image(p.a(),p.id(),id,"rollback-image-key").status()).isEqualTo(200);
 }
 @Test void erasureRemovesBothSidesConversationImagesAndPreservesUnrelatedMedia()throws Exception{
  var p=pair();var third=user();String other=friend(p.b(),third);
  String a=approved(p.a(),p.id()),b=approved(p.b(),p.id()),keep=approved(p.b(),other);
  image(p.a(),p.id(),a,"erase-a-image");image(p.b(),p.id(),b,"erase-b-image");image(p.b(),other,keep,"keep-b-image");
  var keys=db.queryForList("SELECT storage_key FROM media_assets WHERE conversation_id=?",String.class,p.id());
  String keepKey=db.queryForObject("SELECT storage_key FROM media_assets WHERE id=?",String.class,keep);
  assertThat(call("POST","/auth/account/deactivate",null,p.a()).status()).isEqualTo(200);
  db.update("UPDATE users SET deactivation_due_at=now()-interval '1 second' WHERE id=?",p.a().id());
  assertThat(accounts.eraseDue(p.a().id())).isTrue();assertThat(accounts.cleanupStorage()).isEqualTo(2);
  assertThat(count("media_assets")).isEqualTo(1);assertThat(count("messages")).isEqualTo(1);
  for(String key:keys)assertThat(java.nio.file.Files.exists(ROOT.resolve(key))).isFalse();
  assertThat(java.nio.file.Files.exists(ROOT.resolve(keepKey))).isTrue();assertThat(download(grant(third,keep))).isEqualTo(200);
 }
 @Test void adminFiltersAndReviewsChatImagesUsingExistingEndpoints()throws Exception{
  db.execute("TRUNCATE admin_users CASCADE");
  admins.bootstrap(new com.manliao.backend.admin.AdminDtos.Create("image_owner","Image-admin-password","Images","owner"));
  var login=call("POST","/admin/auth/login",Map.of("username","image_owner","password","Image-admin-password"),null);
  var admin=new User("",login.text("access_token"),"");
  var p=pair();var asset=upload(p.a(),p.id());upload(p.a(),null,"profile",png());
  var found=call("GET","/admin/moderation/media?source=chat&conversation_id="+p.id(),null,admin);
  assertThat(found.status()).isEqualTo(200);assertThat(found.body().size()).isEqualTo(1);
  assertThat(found.body().get(0).get("id").asString()).isEqualTo(asset.text("id"));
  assertThat(call("GET","/admin/moderation/media?source=profile",null,admin).body().size()).isEqualTo(1);
  assertThat(call("POST","/admin/moderation/media/"+asset.text("id")+"/approve",Map.of("reason","synthetic PNG"),admin).status()).isEqualTo(200);
  var sent=image(p.a(),p.id(),asset.text("id"),"admin-image-key");assertThat(sent.status()).isEqualTo(200);
  assertThat(call("GET","/admin/moderation/media?message_id="+sent.text("id"),null,admin).body().size()).isEqualTo(1);
 }

 @Test void imagePayloadTravelsThroughRealWebSocket()throws Exception{
  var p=pair();String asset=approved(p.a(),p.id());
  var ready=new CompletableFuture<Void>();var received=new CompletableFuture<JsonNode>();
  var socket=http.newWebSocketBuilder().header("Authorization","Bearer "+p.b().token())
   .buildAsync(URI.create("ws://127.0.0.1:"+port+"/ws/messages?reliable=true"),new java.net.http.WebSocket.Listener(){
    final StringBuilder body=new StringBuilder();
    public CompletionStage<?> onText(java.net.http.WebSocket ws,CharSequence text,boolean last){
     body.append(text);
     if(last){var frame=json.readTree(body.toString());body.setLength(0);
      if(frame.path("type").asString().equals("realtime.ready"))ready.complete(null);
      if(frame.path("type").asString().equals("message.created"))received.complete(frame);
     }
     ws.request(1);return null;
    }
   }).get(10,TimeUnit.SECONDS);
  try{
   ready.get(10,TimeUnit.SECONDS);var sent=image(p.a(),p.id(),asset,"websocket-image");assertThat(sent.status()).isEqualTo(200);
   realtime.publish();var frame=received.get(10,TimeUnit.SECONDS);
   assertThat(frame.path("message").path("media_asset_id").asString()).isEqualTo(asset);
   assertThat(frame.path("message").path("type").asString()).isEqualTo("image");
   assertThat(download(grant(p.b(),asset))).isEqualTo(200);
  }finally{socket.abort();}
 }
 @Test void failedErasureRollsBackBothOwnersMediaAndStorageJobs()throws Exception{
  var p=pair();String a=approved(p.a(),p.id()),b=approved(p.b(),p.id());
  image(p.a(),p.id(),a,"rollback-erasure-a");image(p.b(),p.id(),b,"rollback-erasure-b");
  assertThat(call("POST","/auth/account/deactivate",null,p.a()).status()).isEqualTo(200);
  db.update("UPDATE users SET deactivation_due_at=now()-interval '1 second' WHERE id=?",p.a().id());
  db.execute("ALTER TABLE users ADD CONSTRAINT image_erasure_failure CHECK(status<>'deactivated')");
  try{
   assertThatThrownBy(()->accounts.eraseDue(p.a().id())).isInstanceOf(RuntimeException.class);
   assertThat(count("media_assets")).isEqualTo(2);assertThat(count("messages")).isEqualTo(2);assertThat(count("storage_deletion_jobs")).isZero();
  }finally{db.execute("ALTER TABLE users DROP CONSTRAINT image_erasure_failure");}
  assertThat(accounts.eraseDue(p.a().id())).isTrue();assertThat(accounts.cleanupStorage()).isEqualTo(2);
 }

}