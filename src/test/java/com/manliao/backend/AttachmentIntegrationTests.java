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
 "app.auth.jwt-secret=isolated-attachment-tests-secret-at-least-thirty-two-bytes",
 "app.auth.legacy-registration-enabled=true","app.accounts.worker-enabled=false"})
class AttachmentIntegrationTests {
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
 static java.nio.file.Path root(){try{return java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("target"),"attachments-it-").toAbsolutePath();}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
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

 @Test void animatedGifKeepsFramesTimingAndLoopButStripsComments()throws Exception{
  var p=pair();var r=upload(p.a(),p.id(),gif(2,4,3,12),"image/gif","image","chat");
  assertThat(r.status()).isEqualTo(200);assertThat(r.text("content_type")).isEqualTo("image/gif");assertThat(r.text("pipeline_version")).isEqualTo("java-gif-v2");
  byte[] result=download(r.text("preview_url")).body();

  assertThat(new String(result,java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("PRIVATE_GIF_COMMENT");
  var reader=javax.imageio.ImageIO.getImageReadersByFormatName("gif").next();
  try(var stream=new javax.imageio.stream.MemoryCacheImageInputStream(new java.io.ByteArrayInputStream(result))){
   reader.setInput(stream);assertThat(reader.getNumImages(true)).isEqualTo(2);
   assertThat(reader.read(0).getRGB(0,0)).isNotEqualTo(reader.read(1).getRGB(0,0));
   var metadata=reader.getImageMetadata(0).getAsTree("javax_imageio_gif_image_1.0");
   assertThat(((javax.imageio.metadata.IIOMetadataNode)metadata).getElementsByTagName("GraphicControlExtension").item(0).getAttributes().getNamedItem("delayTime").getNodeValue()).isEqualTo("12");
   var app=(javax.imageio.metadata.IIOMetadataNode)((javax.imageio.metadata.IIOMetadataNode)metadata).getElementsByTagName("ApplicationExtension").item(0);
   assertThat((byte[])app.getUserObject()).containsExactly((byte)1,(byte)2,(byte)0);
  }finally{reader.dispose();}
  assertThat(r.body().path("media_metadata").path("duration_ms").asInt()).isEqualTo(240);
  assertThat(call("POST","/media/"+r.text("id")+"/access-url",null,p.b()).status()).isEqualTo(404);
 }
 @Test void rejectsMalformedOverBudgetAndProfileGifs()throws Exception{
  var p=pair();byte[] valid=gif(2,4,3,10);
  assertThat(upload(p.a(),p.id(),Arrays.copyOf(valid,valid.length-5),"image/gif","image","chat").status()).isEqualTo(400);
  assertThat(upload(p.a(),p.id(),"not gif".getBytes(),"image/gif","image","chat").status()).isEqualTo(400);
  assertThat(upload(p.a(),p.id(),valid,"image/png","image","chat").status()).isEqualTo(400);
  assertThat(upload(p.a(),null,valid,"image/gif","image","profile").status()).isEqualTo(400);
  assertThat(upload(p.a(),p.id(),gif(121,1,1,2),"image/gif","image","chat").status()).isEqualTo(413);
  assertThat(upload(p.a(),p.id(),gif(1,1001,1000,2),"image/gif","image","chat").status()).isEqualTo(413);
  assertThat(upload(p.a(),p.id(),gif(2,2,2,4000),"image/gif","image","chat").status()).isEqualTo(413);
  assertThat(count("media_assets")).isZero();
 }
 @Test void gifSendUsesImageContractAndIsIdempotent()throws Exception{
  var p=pair();var a=upload(p.a(),p.id(),gif(2,4,3,10),"image/gif","image","chat");String id=a.text("id");
  assertThat(sendAsset(p.a(),p.id(),id,"gif-pending","image","gif").status()).isEqualTo(409);
  media.resolveReview(id,true,"fixture","synthetic gif");
  var sent=sendAsset(p.a(),p.id(),id,"gif-approved","image","gif");assertThat(sent.status()).isEqualTo(200);
  assertThat(sent.text("media_kind")).isEqualTo("gif");
  assertThat(sendAsset(p.a(),p.id(),id,"gif-approved","image","gif").text("id")).isEqualTo(sent.text("id"));
  assertThat(call("GET",path(p.id())+"/messages/search?message_type=image",null,p.b()).body().path("items").size()).isEqualTo(1);
  assertThat(download(grant(p.b(),id)).headers().firstValue("Content-Type")).contains("image/gif");
 }
 @Test void filesRemainByteExactAndAlwaysDownloadAsAttachments()throws Exception{
  var p=pair();var r=upload(p.a(),p.id(),FILE,"text/html","file","chat");
  assertThat(r.status()).isEqualTo(200);assertThat(r.text("content_type")).isEqualTo("application/octet-stream");
  assertThat(r.text("malware_status")).isEqualTo("skipped");assertThat(r.text("pipeline_version")).isEqualTo("java-file-v1");
  var data=download(r.text("preview_url"));assertThat(data.body()).containsExactly(FILE);
  assertThat(data.headers().firstValue("Content-Disposition").orElseThrow()).startsWith("attachment;");
  assertThat(data.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
  var partial=range(r.text("preview_url"),"bytes=0-7");assertThat(partial.statusCode()).isEqualTo(206);
  assertThat(partial.body()).containsExactly(Arrays.copyOf(FILE,8));
  assertThat(partial.headers().firstValue("Content-Disposition").orElseThrow()).startsWith("attachment;");
 }
 @Test void rejectsEmptyOversizeAndWrongScopeFiles()throws Exception{
  var p=pair();
  assertThat(upload(p.a(),p.id(),new byte[0],"text/plain","file","chat").status()).isEqualTo(400);
  assertThat(upload(p.a(),p.id(),new byte[32*1024*1024+1],"application/octet-stream","file","chat").status()).isEqualTo(413);
  assertThat(upload(p.a(),null,FILE,"text/plain","file","profile").status()).isEqualTo(400);
  assertThat(count("media_assets")).isZero();
 }
 @Test void filesCannotCrossOwnersConversationsOrMasqueradeAsImages()throws Exception{
  var p=pair();String id=approved(p.a(),p.id());var c=user();String other=friend(p.a(),c);
  assertThat(sendAsset(p.b(),p.id(),id,"file-owner","file",null).status()).isEqualTo(404);
  assertThat(sendAsset(p.a(),other,id,"file-conversation","file",null).status()).isEqualTo(400);
  assertThat(sendAsset(p.a(),p.id(),id,"file-image","image","gif").status()).isEqualTo(400);
  assertThat(sendAsset(p.a(),p.id(),id,"file-kind","file","gif").status()).isEqualTo(422);
  assertThat(call("PATCH","/profiles/me",Map.of("avatar_url","media:"+id),p.a()).status()).isEqualTo(404);
 }
 @Test void pendingFileBlockedAndConcurrentRetriesProduceOneMessage()throws Exception{
  var p=pair();var r=upload(p.a(),p.id(),FILE,"text/plain","file","chat");String id=r.text("id");
  assertThat(sendAsset(p.a(),p.id(),id,"file-pending","file",null).status()).isEqualTo(409);
  media.resolveReview(id,true,"fixture","synthetic");
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var replies=pool.invokeAll(List.<Callable<Reply>>of(()->sendAsset(p.a(),p.id(),id,"file-concurrent","file",null),()->sendAsset(p.a(),p.id(),id,"file-concurrent","file",null)));
   var a=replies.get(0).get();var b=replies.get(1).get();assertThat(a.status()).isEqualTo(200);assertThat(b.text("id")).isEqualTo(a.text("id"));
  }
  assertThat(count("messages")).isEqualTo(1);assertThat(unread(p.b(),p.id())).isEqualTo(1);
  assertThat(call("GET",path(p.id())+"/messages/search?message_type=file",null,p.b()).body().path("items").size()).isEqualTo(1);
 }
 @Test void hiddenRecalledAndLoggedOutLinksLoseFileAccess()throws Exception{
  var p=pair();String id=approved(p.a(),p.id());var sent=sendAsset(p.a(),p.id(),id,"file-hide","file",null);
  String mine=grant(p.a(),id),peer=grant(p.b(),id);
  call("DELETE",path(p.id())+"/messages/"+sent.text("id"),null,p.b());
  assertThat(download(peer).statusCode()).isEqualTo(404);assertThat(download(mine).statusCode()).isEqualTo(200);
  call("POST",path(p.id())+"/messages/"+sent.text("id")+"/recall",null,p.a());assertThat(download(mine).statusCode()).isEqualTo(404);
  String next=approved(p.a(),p.id()),url=grant(p.a(),next);
  call("DELETE","/auth/sessions/"+p.a().session(),null,p.a());assertThat(download(url).statusCode()).isEqualTo(404);
 }
 @Test void adminFilePreviewUsesAttachmentAndRejectionRevokesGif()throws Exception{
  db.execute("TRUNCATE admin_users CASCADE");admins.bootstrap(new com.manliao.backend.admin.AdminDtos.Create("attachment_owner","Attachment-admin-password","Owner","owner"));
  var login=call("POST","/admin/auth/login",Map.of("username","attachment_owner","password","Attachment-admin-password"),null);var admin=new User("",login.text("access_token"),"");
  var p=pair();String id=approved(p.a(),p.id());
  var preview=call("POST","/admin/moderation/media/"+id+"/preview-url",null,admin);
  assertThat(download(preview.text("url")).headers().firstValue("Content-Disposition").orElseThrow()).startsWith("attachment;");
  var gif=upload(p.a(),p.id(),gif(2,4,3,10),"image/gif","image","chat");String gid=gif.text("id");
  call("POST","/admin/moderation/media/"+gid+"/approve",Map.of("reason","synthetic"),admin);sendAsset(p.a(),p.id(),gid,"gif-review","image","gif");
  String url=grant(p.b(),gid);call("POST","/admin/moderation/media/"+gid+"/reject",Map.of("reason","synthetic rejection"),admin);
  assertThat(download(url).statusCode()).isEqualTo(404);
 }
 @Test void erasureAndDeleteCleanGifAndFiles()throws Exception{
  var p=pair();String file=approved(p.b(),p.id());var gif=upload(p.a(),p.id(),gif(2,4,3,10),"image/gif","image","chat");
  String gifKey=db.queryForObject("SELECT storage_key FROM media_assets WHERE id=?",String.class,gif.text("id"));
  call("DELETE","/media/"+gif.text("id"),null,p.a());assertThat(java.nio.file.Files.exists(ROOT.resolve(gifKey))).isFalse();
  String fileKey=db.queryForObject("SELECT storage_key FROM media_assets WHERE id=?",String.class,file);
  call("POST","/auth/account/deactivate",null,p.a());db.update("UPDATE users SET deactivation_due_at=now()-interval '1 second' WHERE id=?",p.a().id());
  assertThat(accounts.eraseDue(p.a().id())).isTrue();assertThat(accounts.cleanupStorage()).isEqualTo(1);
  assertThat(java.nio.file.Files.exists(ROOT.resolve(fileKey))).isFalse();
 }
 @Test void uploadAndMessageRollbackLeaveNoOrphanOrPartialBinding()throws Exception{
  var p=pair();long before;try(var files=java.nio.file.Files.list(ROOT)){before=files.filter(java.nio.file.Files::isRegularFile).count();}
  db.execute("ALTER TABLE media_assets ADD CONSTRAINT attachment_fixture CHECK(media_type<>'file')");
  try{assertThat(upload(p.a(),p.id(),FILE,"text/plain","file","chat").status()).isEqualTo(500);}
  finally{db.execute("ALTER TABLE media_assets DROP CONSTRAINT attachment_fixture");}
  try(var files=java.nio.file.Files.list(ROOT)){assertThat(files.filter(java.nio.file.Files::isRegularFile).count()).isEqualTo(before);}
  String id=approved(p.a(),p.id());db.execute("ALTER TABLE chat_change_outbox ADD CONSTRAINT attachment_outbox CHECK(event_type<>'message.created')");
  try{assertThat(sendAsset(p.a(),p.id(),id,"file-rollback","file",null).status()).isEqualTo(500);assertThat(count("messages")).isZero();}
  finally{db.execute("ALTER TABLE chat_change_outbox DROP CONSTRAINT attachment_outbox");}
  assertThat(sendAsset(p.a(),p.id(),id,"file-rollback","file",null).status()).isEqualTo(200);
 }


 @Test void realWebSocketDeliversBothGifAndFileTypes()throws Exception{
  var p=pair();String file=approved(p.a(),p.id());var asset=upload(p.a(),p.id(),gif(2,4,3,10),"image/gif","image","chat");String gif=asset.text("id");media.resolveReview(gif,true,"fixture","synthetic");
  var ready=new CompletableFuture<Void>();var queue=new LinkedBlockingQueue<JsonNode>();
  var socket=http.newWebSocketBuilder().header("Authorization","Bearer "+p.b().token()).buildAsync(URI.create("ws://127.0.0.1:"+port+"/ws/messages?reliable=true"),new WebSocket.Listener(){
   final StringBuilder buffer=new StringBuilder();
   public CompletionStage<?> onText(WebSocket ws,CharSequence text,boolean last){
    buffer.append(text);if(last){var frame=json.readTree(buffer.toString());buffer.setLength(0);
     if("realtime.ready".equals(frame.path("type").asString()))ready.complete(null);
     if("message.created".equals(frame.path("type").asString()))queue.add(frame.path("message"));
    }ws.request(1);return null;
   }
  }).get(10,TimeUnit.SECONDS);
  try{
   ready.get(10,TimeUnit.SECONDS);assertThat(sendAsset(p.a(),p.id(),file,"file-websocket","file",null).status()).isEqualTo(200);
   assertThat(sendAsset(p.a(),p.id(),gif,"gif-websocket","image","gif").status()).isEqualTo(200);realtime.publish();
   var first=queue.poll(10,TimeUnit.SECONDS);var second=queue.poll(10,TimeUnit.SECONDS);assertThat(first).isNotNull();assertThat(second).isNotNull();
   var messages=Map.of(first.path("media_asset_id").asString(),first,second.path("media_asset_id").asString(),second);
   assertThat(messages.get(file).path("type").asString()).isEqualTo("file");assertThat(messages.get(gif).path("media_kind").asString()).isEqualTo("gif");
  }finally{socket.abort();}
 }


 @Test void gifRetainsCustomPaletteAndTransparentPixels()throws Exception{
  byte[] red={0,(byte)255,0,0},green={0,0,(byte)255,0},blue={0,0,0,(byte)255};
  var model=new java.awt.image.IndexColorModel(2,4,red,green,blue,0);
  var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_BYTE_BINARY,model);
  image.getRaster().setSample(0,0,0,1);image.getRaster().setSample(1,0,0,0);
  var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"gif",bytes);
  var p=pair();var asset=upload(p.a(),p.id(),bytes.toByteArray(),"image/gif","image","chat");assertThat(asset.status()).isEqualTo(200);
  var decoded=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(download(asset.text("preview_url")).body()));
  assertThat(decoded.getRGB(0,0)).isEqualTo(0xffff0000);assertThat(decoded.getRGB(1,0)>>>24).isZero();
 }

}