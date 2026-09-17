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
 "app.auth.jwt-secret=isolated-media-jobs-tests-secret-at-least-thirty-two-bytes",
 "app.auth.legacy-registration-enabled=true","app.accounts.worker-enabled=false","app.media.derivatives-worker-enabled=false","app.media.jobs-worker-enabled=false","app.media.draft-worker-enabled=false"})
class MediaJobsIntegrationTests {
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
 static java.nio.file.Path root(){try{return java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("target"),"media-jobs-it-").toAbsolutePath();}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
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
  var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/media/upload?async=true")).header("Authorization","Bearer "+user.token())
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
 @Autowired com.manliao.backend.media.MediaJobs jobs;
 @Autowired com.manliao.backend.media.MediaStorage mediaStorage;
 @Autowired org.springframework.transaction.support.TransactionTemplate tx;
 String queued(User user,String conversation)throws Exception{
  var result=upload(user,conversation,png(30,20),"image/png","image",conversation==null?"profile":"chat");
  assertThat(result.status()).as(result.body().toString()).isEqualTo(200);assertThat(result.text("processing_status")).isEqualTo("queued");return result.text("id");
 }
 String state(String id){return db.queryForObject("SELECT processing_status FROM media_assets WHERE id=?",String.class,id);}
 void age(String id){db.update("UPDATE media_assets SET created_at=now()-interval '25 hours' WHERE id=?",id);}
 void due(String id){db.update("UPDATE media_processing_jobs SET available_at=now()-interval '1 second' WHERE media_id=?",id);}
 void absentBundle(String id)throws Exception{for(String suffix:List.of(".png",".gif",".bin",".wav",".mp4",".thumb.png",".display.png"))assertThat(java.nio.file.Files.exists(ROOT.resolve(id+suffix))).isFalse();}
 @Test void queuedImageIsDurablePrivateAndUnusableUntilReady()throws Exception{
  var p=pair();String id=queued(p.a(),p.id());
  assertThat(count("media_processing_jobs")).isEqualTo(1);absentBundle(id);
  var own=call("GET","/media/"+id,null,p.a());assertThat(own.status()).isEqualTo(200);
  assertThat(own.body().get("preview_url").isNull()).isTrue();assertThat(own.body().has("storage_key")).isFalse();assertThat(own.body().has("payload")).isFalse();
  assertThat(call("GET","/media/"+id,null,p.b()).status()).isEqualTo(404);
  assertThat(call("GET","/media/me",null,p.a()).body().size()).isEqualTo(1);
  assertThat(call("POST","/media/"+id+"/access-url",null,p.a()).status()).isEqualTo(404);
  assertThat(sendAsset(p.a(),p.id(),id,"queued-test","image","image").status()).isEqualTo(409);
  assertThatThrownBy(()->media.resolveReview(id,true,"fixture","pending")).isInstanceOf(com.manliao.backend.common.ApiError.class);
  assertThat(media.backfillImageDerivatives()).isZero();
  // A new worker object recovers exclusively from the database, without an in-memory queue.
  var restarted=new com.manliao.backend.media.MediaJobs(db,tx,media,mediaStorage);
  assertThat(restarted.processNext()).isTrue();assertThat(state(id)).isEqualTo("ready");assertThat(count("media_processing_jobs")).isZero();
  assertThat(jobs.processNext()).isFalse();
  assertThat(decoded(variant(p.a(),id,"thumbnail")).getWidth()).isEqualTo(30);
  media.resolveReview(id,true,"fixture","processed");assertThat(sendAsset(p.a(),p.id(),id,"ready-test","image","image").status()).isEqualTo(200);
 }
 @Test void malformedImageFailsWithoutPublishingBytes()throws Exception{
  var u=user();var result=upload(u,null,new byte[]{1,2,3},"image/png","image","profile");String id=result.text("id");
  assertThat(jobs.processNext()).isTrue();assertThat(state(id)).isEqualTo("failed");assertThat(count("media_processing_jobs")).isZero();absentBundle(id);
  var get=call("GET","/media/"+id,null,u);assertThat(get.text("processing_error")).isEqualTo("MEDIA_TYPE_INVALID");
  assertThat(get.body().get("processed_at").isNull()).isTrue();assertThat(call("POST","/media/"+id+"/access-url",null,u).status()).isEqualTo(404);
  media.cleanupDeleted();assertThat(call("GET","/media/me",null,u).body().size()).isEqualTo(1);
 }
 @Test void admissionRejectsWrongMimeEmptyAndOversizeWithoutJobs()throws Exception{
  var u=user();assertThat(upload(u,null,png(2,2),"text/plain","image","profile").status()).isEqualTo(400);
  assertThat(upload(u,null,new byte[0],"image/png","image","profile").status()).isEqualTo(400);
  assertThat(upload(u,null,new byte[8*1024*1024+1],"image/png","image","profile").status()).isEqualTo(413);
  assertThat(count("media_processing_jobs")).isZero();assertThat(count("media_assets")).isZero();
 }
 @Test void perUserQueueLimitIsAtomicAndDeletionFreesSlot()throws Exception{
  var u=user();var barrier=new CyclicBarrier(6);
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var tasks=new ArrayList<Callable<Integer>>();for(int i=0;i<6;i++)tasks.add(()->{barrier.await();return upload(u,null,png(2,2),"image/png","image","profile").status();});
   var codes=new ArrayList<Integer>();for(var f:pool.invokeAll(tasks))codes.add(f.get(20,TimeUnit.SECONDS));
   assertThat(codes.stream().filter(c->c==200).count()).isEqualTo(4);assertThat(codes.stream().filter(c->c==429).count()).isEqualTo(2);
  }
  assertThat(count("media_assets")).isEqualTo(4);String id=db.queryForObject("SELECT min(id) FROM media_assets",String.class);
  assertThat(call("DELETE","/media/"+id,null,u).status()).isEqualTo(204);assertThat(count("media_processing_jobs")).isEqualTo(3);
  queued(u,null);assertThat(count("media_processing_jobs")).isEqualTo(4);
 }
 @Test void queueInsertionFailureRollsBackAssetAndRawPayload()throws Exception{
  var u=user();db.execute("ALTER TABLE media_processing_jobs ADD CONSTRAINT queue_fixture CHECK(false)");
  try{assertThat(upload(u,null,png(2,2),"image/png","image","profile").status()).isEqualTo(500);}
  finally{db.execute("ALTER TABLE media_processing_jobs DROP CONSTRAINT queue_fixture");}
  assertThat(count("media_assets")).isZero();assertThat(count("media_processing_jobs")).isZero();
 }
 @Test void temporaryStorageFailureRetriesThenRecovers()throws Exception{
  var u=user();String id=queued(u,null);var blocked=ROOT.resolve(id+".display.png");java.nio.file.Files.createDirectory(blocked);var sentinel=blocked.resolve("keep");java.nio.file.Files.writeString(sentinel,"fixture");
  try{
   assertThat(jobs.processNext()).isTrue();assertThat(state(id)).isEqualTo("queued");
   assertThat(db.queryForObject("SELECT attempts FROM media_processing_jobs WHERE media_id=?",Integer.class,id)).isEqualTo(1);
   assertThat(jobs.processNext()).isFalse();
  }finally{java.nio.file.Files.delete(sentinel);java.nio.file.Files.delete(blocked);}
  due(id);assertThat(jobs.processNext()).isTrue();assertThat(state(id)).as(db.queryForMap("SELECT processing_error,media_type FROM media_assets WHERE id=?",id).toString()).isEqualTo("ready");
  assertThat(call("GET","/media/"+id,null,u).body().get("processing_error").isNull()).isTrue();
 }
 @Test void threeTemporaryFailuresReleasePayloadAndAllowCleanupRetry()throws Exception{
  var u=user();String id=queued(u,null);var blocked=ROOT.resolve(id+".display.png");java.nio.file.Files.createDirectory(blocked);var sentinel=blocked.resolve("keep");java.nio.file.Files.writeString(sentinel,"fixture");
  try{for(int i=0;i<3;i++){due(id);assertThat(jobs.processNext()).isTrue();}assertThat(state(id)).isEqualTo("failed");assertThat(count("media_processing_jobs")).isZero();}
  finally{java.nio.file.Files.delete(sentinel);java.nio.file.Files.delete(blocked);}
  media.cleanupDeleted();absentBundle(id);assertThat(key(id)).isNull();
 }
 @Test void publicationRollbackKeepsJobAndRetryReplacesUncommittedFiles()throws Exception{
  var u=user();String id=queued(u,null);
  db.execute("ALTER TABLE media_assets ADD CONSTRAINT publication_fixture CHECK(processing_status<>'ready')");
  try{assertThatThrownBy(()->jobs.processNext()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);}
  finally{db.execute("ALTER TABLE media_assets DROP CONSTRAINT publication_fixture");}
  assertThat(state(id)).isEqualTo("queued");assertThat(count("media_processing_jobs")).isEqualTo(1);
  assertThat(call("POST","/media/"+id+"/access-url",null,u).status()).isEqualTo(404);
  assertThat(jobs.processNext()).isTrue();assertThat(state(id)).as(db.queryForMap("SELECT processing_error,media_type FROM media_assets WHERE id=?",id).toString()).isEqualTo("ready");assertThat(count("media_processing_jobs")).isZero();
 }
 @Test void concurrentWorkersPublishOnceAndExplicitDeleteCannotResurrect()throws Exception{
  var u=user();String id=queued(u,null);var barrier=new CyclicBarrier(2);
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var results=pool.invokeAll(List.<Callable<Boolean>>of(()->{barrier.await();return jobs.processNext();},()->{barrier.await();return jobs.processNext();}));
   int completed=0;for(var f:results)if(f.get(15,TimeUnit.SECONDS))completed++;assertThat(completed).isEqualTo(1);
  }
  assertThat(state(id)).isEqualTo("ready");
  for(int i=0;i<3;i++){
   String next=queued(u,null);var race=new CyclicBarrier(2);
   try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
    var results=pool.invokeAll(List.<Callable<Void>>of(()->{race.await();jobs.processNext();return null;},()->{race.await();media.delete(new AuthDtos.Principal(u.id(),u.session()),next);return null;}));
    for(var f:results)f.get(15,TimeUnit.SECONDS);
   }
   absentBundle(next);assertThat(count("media_processing_jobs")).isZero();
  }
 }
 @Test void accountErasureCascadesBothParticipantsQueuedPayloads()throws Exception{
  var p=pair();String a=queued(p.a(),p.id()),b=queued(p.b(),p.id());
  call("POST","/auth/account/deactivate",null,p.a());db.update("UPDATE users SET deactivation_due_at=now()-interval '1 second' WHERE id=?",p.a().id());
  assertThat(accounts.eraseDue(p.a().id())).isTrue();assertThat(count("media_processing_jobs")).isZero();assertThat(jobs.processNext()).isFalse();
  accounts.cleanupStorage();absentBundle(a);absentBundle(b);
 }
 @Test void draftExpiryReclaimsReadyQueuedAndFailedButKeepsFresh()throws Exception{
  var u=user();String ready=queued(u,null);jobs.processNext();String queued=queued(u,null);
  var failed=upload(u,null,new byte[]{1},"image/png","image","profile");
  db.update("UPDATE media_processing_jobs SET available_at=now()+interval '1 hour' WHERE media_id=?",queued);jobs.processNext();
  String bad=failed.text("id");assertThat(state(bad)).isEqualTo("failed");String fresh=queued(u,null);
  for(String id:List.of(ready,queued,bad))age(id);
  assertThat(jobs.reclaimDrafts()).isEqualTo(3);assertThat(jobs.reclaimDrafts()).isZero();
  for(String id:List.of(ready,queued,bad)){assertThat(call("GET","/media/"+id,null,u).status()).isEqualTo(404);absentBundle(id);}
  assertThat(call("GET","/media/"+fresh,null,u).status()).isEqualTo(200);assertThat(count("media_processing_jobs")).isEqualTo(1);
 }
 @Test void draftsKeepCurrentPendingProfileReferencesAndBoundMessages()throws Exception{
  var p=pair();var profileIds=new ArrayList<String>();
  for(int i=0;i<4;i++){String id=queued(p.a(),null);jobs.processNext();age(id);profileIds.add(id);}
  db.update("UPDATE user_profiles SET avatar_url=?,pending_avatar_url=?,photo_urls=jsonb_build_array(?::text),pending_photo_urls=jsonb_build_array(?::text) WHERE user_id=?",
   "media:"+profileIds.get(0),"media:"+profileIds.get(1),"media:"+profileIds.get(2),"media:"+profileIds.get(3),p.a().id());
  String sent=queued(p.a(),p.id());jobs.processNext();media.resolveReview(sent,true,"fixture","send");
  assertThat(sendAsset(p.a(),p.id(),sent,"bound-test","image","image").status()).isEqualTo(200);age(sent);
  assertThat(jobs.reclaimDrafts()).isZero();
  for(String id:profileIds)assertThat(call("GET","/media/"+id,null,p.a()).status()).isEqualTo(200);
  assertThat(call("GET","/media/"+sent,null,p.a()).status()).isEqualTo(200);
 }
 @Test void draftRechecksReferencesAfterWaitingForUserLock()throws Exception{
  var u=user();String id=queued(u,null);jobs.processNext();age(id);
  var started=new CountDownLatch(1);
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var reference=pool.submit(()->tx.execute(status->{
    db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",u.id());started.countDown();
    try{Thread.sleep(250);}catch(InterruptedException e){throw new RuntimeException(e);}
    db.update("UPDATE user_profiles SET pending_avatar_url=? WHERE user_id=?","media:"+id,u.id());return true;
   }));
   assertThat(started.await(3,TimeUnit.SECONDS)).isTrue();var cleanup=pool.submit(()->jobs.reclaimDrafts());
   assertThat(reference.get(10,TimeUnit.SECONDS)).isTrue();assertThat(cleanup.get(10,TimeUnit.SECONDS)).isZero();
  }
  assertThat(call("GET","/media/"+id,null,u).status()).isEqualTo(200);
 }
 @Test void fileAndGifJobsUseTheSameValidatedPipelines()throws Exception{
  var p=pair();var file=upload(p.a(),p.id(),FILE,"text/plain","file","chat");assertThat(file.status()).isEqualTo(200);jobs.processNext();
  assertThat(download(grant(p.a(),file.text("id"))).body()).isEqualTo(FILE);
  var animation=upload(p.a(),p.id(),gif(2,64,48,12),"image/gif","image","chat");assertThat(animation.status()).isEqualTo(200);jobs.processNext();
  assertThat(state(animation.text("id"))).isEqualTo("ready");assertThat(decoded(variant(p.a(),animation.text("id"),"thumbnail")).getWidth()).isEqualTo(64);
  assertThat(download(variant(p.a(),animation.text("id"),"display")).headers().firstValue("Content-Type")).contains("image/gif");
 }
 @Test void queuedProfileCannotBeAttachedAndGetRequiresActiveSession()throws Exception{
  var u=user();String id=queued(u,null);
  assertThat(call("PATCH","/profiles/me",Map.of("avatar_url","media:"+id),u).status()).isEqualTo(409);
  call("DELETE","/auth/sessions/"+u.session(),null,u);assertThat(call("GET","/media/"+id,null,u).status()).isEqualTo(401);
 }
 @Test void globalJobAndByteLimitsRejectWithoutPartialAssets()throws Exception{
  var users=new ArrayList<User>();for(int i=0;i<6;i++)users.add(user());
  for(int i=0;i<5;i++)for(int n=0;n<4;n++){db.execute("DELETE FROM auth_rate_windows");queued(users.get(i),null);}
  assertThat(upload(users.get(5),null,png(2,2),"image/png","image","profile").status()).isEqualTo(429);
  assertThat(count("media_processing_jobs")).isEqualTo(20);assertThat(count("media_assets")).isEqualTo(20);
  db.execute("DELETE FROM media_processing_jobs");db.execute("DELETE FROM media_assets");db.execute("DELETE FROM auth_rate_windows");
  var p=pair();for(int i=0;i<4;i++)for(var u:List.of(p.a(),p.b()))assertThat(upload(u,p.id(),FILE,"text/plain","file","chat").status()).isEqualTo(200);
  db.execute("UPDATE media_processing_jobs SET payload=convert_to(repeat('x',33554432),'UTF8')");
  assertThat(upload(users.get(5),null,png(2,2),"image/png","image","profile").status()).isEqualTo(429);
  assertThat(count("media_processing_jobs")).isEqualTo(8);assertThat(count("media_assets")).isEqualTo(8);
 }
 byte[] avFixture(boolean video)throws Exception{
  var destination=ROOT.resolve("fixture-"+UUID.randomUUID()+(video?".mp4":".m4a"));
  var command=new ArrayList<>(List.of(System.getenv("JAVA_MEDIA_FFMPEG"),"-v","error","-nostdin","-f","lavfi","-i","sine=frequency=440:sample_rate=44100"));
  if(video)command.addAll(List.of("-f","lavfi","-i","color=c=blue:s=64x48:r=24","-c:v","libx264"));
  command.addAll(List.of("-t","0.5","-c:a","aac","-threads","1",destination.toString()));
  var process=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
  try{assertThat(process.waitFor(15,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();return java.nio.file.Files.readAllBytes(destination);}
  finally{process.destroyForcibly();java.nio.file.Files.deleteIfExists(destination);}
 }
 @Test void voiceAndVideoAreTranscodedOnlyByWorker()throws Exception{
  var p=pair();
  for(boolean video:List.of(false,true)){
   var result=upload(p.a(),p.id(),avFixture(video),video?"video/mp4":"audio/mp4",video?"video":"voice","chat");
   assertThat(result.status()).as(result.body().toString()).isEqualTo(200);String id=result.text("id");absentBundle(id);
   assertThat(jobs.processNext()).isTrue();assertThat(state(id)).as(db.queryForMap("SELECT processing_error,media_type FROM media_assets WHERE id=?",id).toString()).isEqualTo("ready");
   var output=call("GET","/media/"+id,null,p.a());assertThat(output.body().get("media_metadata").get("duration_ms").asInt()).isBetween(100,1000);
   assertThat(download(grant(p.a(),id)).headers().firstValue("Content-Type")).contains(video?"video/mp4":"audio/wav");
   if(video)assertThat(decoded(variant(p.a(),id,"thumbnail")).getWidth()).isEqualTo(64);
   media.resolveReview(id,true,"fixture","processed");assertThat(sendAsset(p.a(),p.id(),id,"av-test-"+video,video?"video":"voice",null).status()).isEqualTo(200);
  }
 }
 @Test void concurrentDraftAndSendEitherPreservesBoundAssetOrRejectsSend()throws Exception{
  var p=pair();String id=queued(p.a(),p.id());jobs.processNext();media.resolveReview(id,true,"fixture","ready");age(id);
  var barrier=new CyclicBarrier(2);
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var sending=pool.submit(()->{barrier.await();return sendAsset(p.a(),p.id(),id,"race-send-test","image","image");});
   var reclaiming=pool.submit(()->{barrier.await();return jobs.reclaimDrafts();});
   var sent=sending.get(15,TimeUnit.SECONDS);int deleted=reclaiming.get(15,TimeUnit.SECONDS);
   if(sent.status()==200){assertThat(deleted).isZero();assertThat(java.nio.file.Files.exists(ROOT.resolve(id+".png"))).isTrue();}
   else{assertThat(sent.status()).isEqualTo(409);assertThat(deleted).isEqualTo(1);absentBundle(id);}
  }
 }
}
