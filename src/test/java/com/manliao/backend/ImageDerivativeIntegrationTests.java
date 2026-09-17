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
 "app.auth.jwt-secret=isolated-derivative-tests-secret-at-least-thirty-two-bytes",
 "app.auth.legacy-registration-enabled=true","app.accounts.worker-enabled=false","app.media.derivatives-worker-enabled=false"})
class ImageDerivativeIntegrationTests {
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
 static java.nio.file.Path root(){try{return java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("target"),"derivatives-it-").toAbsolutePath();}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
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
 @Test void staticImageGetsBoundedDisplayAndTransparentThumbnail()throws Exception{
  var p=pair();String id=imageAsset(p.a(),p.id());var thumb=decoded(variant(p.a(),id,"thumbnail"));var display=decoded(variant(p.a(),id,"display"));
  assertThat(thumb.getWidth()).isEqualTo(320);assertThat(thumb.getHeight()).isEqualTo(180);
  assertThat(display.getWidth()).isEqualTo(1280);assertThat(display.getHeight()).isEqualTo(720);
  assertThat(thumb.getRGB(0,0)).isEqualTo(0xffff0000);assertThat(thumb.getRGB(319,0)>>>24).isZero();
  assertThat(decoded(grant(p.a(),id)).getWidth()).isEqualTo(1600);
  var metadata=media.metadata(id);assertThat(metadata).containsEntry("pipeline_version","java-image-v2").doesNotContainKeys("image_derivatives_ready","image_derivatives_retry_at","storage_key");
 }
 @Test void smallImagesAreNotUpscaledAndGifDisplayKeepsAnimation()throws Exception{
  var p=pair();var small=upload(p.a(),p.id(),png(3,2),"image/png","image","chat");
  assertThat(decoded(variant(p.a(),small.text("id"),"thumbnail")).getWidth()).isEqualTo(3);
  var asset=upload(p.a(),p.id(),gif(2,64,48,12),"image/gif","image","chat");String id=asset.text("id");
  assertThat(decoded(variant(p.a(),id,"thumbnail")).getRGB(0,0)).isEqualTo(0xffff0000);
  var display=download(variant(p.a(),id,"display"));assertThat(display.headers().firstValue("Content-Type")).contains("image/gif");
  assertThat(display.body()).containsExactly(download(grant(p.a(),id)).body());
  var reader=javax.imageio.ImageIO.getImageReadersByFormatName("gif").next();
  try(var input=new javax.imageio.stream.MemoryCacheImageInputStream(new java.io.ByteArrayInputStream(display.body()))){reader.setInput(input);assertThat(reader.getNumImages(true)).isEqualTo(2);}
  finally{reader.dispose();}
 }
 @Test void signedDisplayCanRenewAndCannotBecomeAnotherVariant()throws Exception{
  var p=pair();String id=imageAsset(p.a(),p.id()),url=variant(p.a(),id,"display");
  var claims=json.readTree(Base64.getUrlDecoder().decode(url.substring("/media/access/".length()).split("\\.")[1]));
  assertThat(claims.path("variant").asString()).isEqualTo("display");
  assertThat(decoded(variant(p.a(),claims.path("media_id").asString(),claims.path("variant").asString())).getWidth()).isEqualTo(1280);
  assertThat(download(url+"?variant=original").statusCode()).isEqualTo(400);
  assertThat(call("POST","/media/"+id+"/access-url?variant=display",null,p.b()).status()).isEqualTo(404);
  assertThat(call("POST","/media/"+id+"/access-url?variant=../../outside",null,p.a()).status()).isEqualTo(400);
 }
 @Test void hidingAndRecallRevokeEveryImageVariant()throws Exception{
  var p=pair();String id=imageAsset(p.a(),p.id());var sent=sendAsset(p.a(),p.id(),id,"derivatives-hide","image","image");
  String owner=variant(p.a(),id,"display"),thumb=variant(p.b(),id,"thumbnail"),display=variant(p.b(),id,"display");
  assertThat(range(thumb,"bytes=0-7").statusCode()).isEqualTo(206);
  call("DELETE",path(p.id())+"/messages/"+sent.text("id"),null,p.b());
  assertThat(download(thumb).statusCode()).isEqualTo(404);assertThat(download(display).statusCode()).isEqualTo(404);assertThat(download(owner).statusCode()).isEqualTo(200);
  call("POST",path(p.id())+"/messages/"+sent.text("id")+"/recall",null,p.a());assertThat(download(owner).statusCode()).isEqualTo(404);
 }
 @Test void profileReferenceAndBlockControlThumbnailAccess()throws Exception{
  var owner=user();var peer=user();var asset=upload(owner,null,png(30,20),"image/png","image","profile");String id=asset.text("id");
  assertThat(call("POST","/media/"+id+"/access-url?variant=thumbnail",null,peer).status()).isEqualTo(404);
  assertThat(call("PATCH","/profiles/me",Map.of("avatar_url","media:"+id),owner).status()).isEqualTo(200);
  media.resolveReview(id,true,"fixture","synthetic profile");
  String thumb=variant(peer,id,"thumbnail");assertThat(download(thumb).statusCode()).isEqualTo(200);
  db.update("INSERT INTO blocks VALUES(?,?)",owner.id(),peer.id());assertThat(download(thumb).statusCode()).isEqualTo(404);
 }
 @Test void administratorPreviewsAndRejectionRevokesUserThumbnail()throws Exception{
  db.execute("TRUNCATE admin_users CASCADE");admins.bootstrap(new com.manliao.backend.admin.AdminDtos.Create("derivative_owner","Derivative-admin-password","Owner","owner"));
  var login=call("POST","/admin/auth/login",Map.of("username","derivative_owner","password","Derivative-admin-password"),null);var admin=new User("",login.text("access_token"),"");
  var p=pair();var asset=upload(p.a(),p.id(),png(1600,900),"image/png","image","chat");String id=asset.text("id");
  var preview=call("POST","/admin/moderation/media/"+id+"/preview-url?variant=display",null,admin);
  assertThat(preview.status()).isEqualTo(200);assertThat(decoded(preview.text("url")).getWidth()).isEqualTo(1280);
  String thumb=variant(p.a(),id,"thumbnail");
  call("POST","/admin/moderation/media/"+id+"/reject",Map.of("reason","synthetic"),admin);assertThat(download(thumb).statusCode()).isEqualTo(404);
  call("POST","/admin/auth/logout",null,admin);assertThat(download(preview.text("url")).statusCode()).isEqualTo(401);
 }
 @Test void legacyBackfillIsIdempotentAndPreservesOriginalAndBinding()throws Exception{
  var p=pair();String id=imageAsset(p.a(),p.id());var sent=sendAsset(p.a(),p.id(),id,"legacy-derivatives","image","image");
  byte[] original=download(grant(p.b(),id)).body();legacy(id);
  assertThat(((Map<?,?>)media.metadata(id).get("derivatives"))).isEmpty();
  assertThat(media.backfillImageDerivatives()).isEqualTo(1);assertThat(media.backfillImageDerivatives()).isZero();
  assertThat(decoded(variant(p.b(),id,"thumbnail")).getWidth()).isEqualTo(320);
  assertThat(download(grant(p.b(),id)).body()).containsExactly(original);
  assertThat(db.queryForObject("SELECT message_id FROM media_assets WHERE id=?",String.class,id)).isEqualTo(sent.text("id"));
 }
 @Test void failedBackfillDefersRetryAndRecoversAfterSourceRestored()throws Exception{
  var p=pair();String id=imageAsset(p.a(),p.id());legacy(id);var file=ROOT.resolve(key(id));byte[] source=java.nio.file.Files.readAllBytes(file);java.nio.file.Files.delete(file);
  assertThat(media.backfillImageDerivatives()).isZero();
  assertThat(db.queryForObject("SELECT image_derivatives_ready FROM media_assets WHERE id=?",Boolean.class,id)).isFalse();
  assertThat(db.queryForObject("SELECT image_derivatives_retry_at>now() FROM media_assets WHERE id=?",Boolean.class,id)).isTrue();
  java.nio.file.Files.write(file,source);assertThat(media.backfillImageDerivatives()).isZero();
  db.update("UPDATE media_assets SET image_derivatives_retry_at=now() WHERE id=?",id);
  assertThat(media.backfillImageDerivatives()).isEqualTo(1);
 }
 @Test void deleteAndAccountErasureRemoveOriginalAndAllDerivatives()throws Exception{
  var p=pair();String id=imageAsset(p.a(),p.id()),first=key(id);
  assertThat(call("DELETE","/media/"+id,null,p.a()).status()).isEqualTo(204);absent(first);
  String second=imageAsset(p.b(),p.id()),secondKey=key(second);
  var gif=upload(p.a(),p.id(),gif(2,64,48,12),"image/gif","image","chat");String gifKey=key(gif.text("id"));
  call("POST","/auth/account/deactivate",null,p.a());db.update("UPDATE users SET deactivation_due_at=now()-interval '1 second' WHERE id=?",p.a().id());
  assertThat(accounts.eraseDue(p.a().id())).isTrue();assertThat(accounts.cleanupStorage()).isEqualTo(2);absent(secondKey);absent(gifKey);
 }
 @Test void insertRollbackCleansDerivativeBundle()throws Exception{
  var p=pair();long before;try(var files=java.nio.file.Files.list(ROOT)){before=files.filter(java.nio.file.Files::isRegularFile).count();}
  db.execute("ALTER TABLE media_assets ADD CONSTRAINT derivatives_fixture CHECK(media_type<>'image')");
  try{assertThat(upload(p.a(),p.id(),png(30,20),"image/png","image","chat").status()).isEqualTo(500);}
  finally{db.execute("ALTER TABLE media_assets DROP CONSTRAINT derivatives_fixture");}
  try(var files=java.nio.file.Files.list(ROOT)){assertThat(files.filter(java.nio.file.Files::isRegularFile).count()).isEqualTo(before);}
  try(var files=java.nio.file.Files.list(ROOT.resolve("multipart"))){assertThat(files.count()).isZero();}
 }
 @Test void concurrentBackfillAndDeleteDoNotResurrectFiles()throws Exception{
  var p=pair();
  for(int iteration=0;iteration<3;iteration++){
   String id=imageAsset(p.a(),p.id()),key=key(id);legacy(id);var barrier=new CyclicBarrier(2);
   try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
    var tasks=pool.invokeAll(List.<Callable<Void>>of(
     ()->{barrier.await();media.backfillImageDerivatives();return null;},
     ()->{barrier.await();media.delete(new AuthDtos.Principal(p.a().id(),p.a().session()),id);return null;}));
    for(var task:tasks)task.get(10,TimeUnit.SECONDS);
   }absent(key);assertThat(db.queryForObject("SELECT storage_key FROM media_assets WHERE id=?",String.class,id)).isNull();
  }
 }
 @Test void filesCannotRequestImageVariantsAndLogoutRevokesDisplay()throws Exception{
  var p=pair();String file=approved(p.a(),p.id());
  assertThat(call("POST","/media/"+file+"/access-url?variant=display",null,p.a()).status()).isEqualTo(400);
  String id=imageAsset(p.a(),p.id()),url=variant(p.a(),id,"display");
  call("DELETE","/auth/sessions/"+p.a().session(),null,p.a());assertThat(download(url).statusCode()).isEqualTo(404);
 }

}