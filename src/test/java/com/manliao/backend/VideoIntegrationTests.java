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
 "app.auth.jwt-secret=isolated-video-tests-secret-at-least-thirty-two-bytes",
 "app.auth.legacy-registration-enabled=true","app.accounts.worker-enabled=false"})
class VideoIntegrationTests {
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
 static byte[] valid;
 static java.nio.file.Path root(){try{return java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("target"),"video-it-").toAbsolutePath();}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
 @org.springframework.test.context.DynamicPropertySource static void storage(org.springframework.test.context.DynamicPropertyRegistry p){p.add("app.media.storage-root",()->ROOT.toString());}
 @BeforeAll static void fixtures()throws Exception{valid=fixture(1.25,true,"libx264");}
 static byte[] fixture(double seconds,boolean audio,String codec)throws Exception{
  String executable=System.getenv("JAVA_MEDIA_FFMPEG");assertThat(executable).as("Install FFmpeg; video tests never skip").isNotBlank();
  var target=ROOT.resolve("fixture-"+UUID.randomUUID()+(audio?".mp4":".mov"));
  var cmd=new ArrayList<>(List.of(executable,"-v","error","-nostdin","-f","lavfi","-i","color=c=blue:s=64x48:r=24"));
  if(audio)cmd.addAll(List.of("-f","lavfi","-i","sine=frequency=440:sample_rate=44100","-c:a","aac"));
  cmd.addAll(List.of("-t",Double.toString(seconds),"-c:v",codec,"-threads","1","-metadata","comment=PRIVATE_TEST_METADATA","-movflags","+faststart",target.toString()));
  var process=new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
  try{assertThat(process.waitFor(15,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();return java.nio.file.Files.readAllBytes(target);}
  finally{process.destroyForcibly();java.nio.file.Files.deleteIfExists(target);}
 }

 Reply upload(User u,String conv,byte[] bytes,String mime,String source)throws Exception{
  String boundary="video-upload-boundary";var output=new java.io.ByteArrayOutputStream();
  var fields=new LinkedHashMap<String,String>();fields.put("media_type","video");fields.put("source",source);if(conv!=null)fields.put("conversation_id",conv);
  for(var f:fields.entrySet())output.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+f.getKey()+"\"\r\n\r\n"+f.getValue()+"\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
  output.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"recording.mp4\"\r\nContent-Type: "+mime+"\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
  output.write(bytes);output.write(("\r\n--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
  var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/media/upload")).timeout(java.time.Duration.ofSeconds(45)).header("Authorization","Bearer "+u.token())
   .header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(output.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
  return new Reply(response.statusCode(),json.readTree(response.body()));
 }
 Reply upload(User u,String conv)throws Exception{return upload(u,conv,valid,"video/mp4","chat");}
 String approved(User u,String conv)throws Exception{var r=upload(u,conv);assertThat(r.status()).isEqualTo(200);media.resolveReview(r.text("id"),true,"fixture","synthetic tone");return r.text("id");}
 Reply video(User u,String conv,String asset,String key,int duration)throws Exception{return call("POST",path(conv)+"/messages",Map.of("type","video","content","[语音]","media_asset_id",asset,"client_message_id",key,"duration_seconds",duration),u);}
 HttpResponse<byte[]> download(String url)throws Exception{return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+url)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());}
 String grant(User u,String id)throws Exception{var r=call("POST","/media/"+id+"/access-url",null,u);assertThat(r.status()).isEqualTo(200);return r.text("url");}

 @Test void normalizesVideoAndBuildsPrivateThumbnail()throws Exception{
  var p=pair();var r=upload(p.a(),p.id());assertThat(r.status()).isEqualTo(200);
  assertThat(r.text("media_type")).isEqualTo("video");assertThat(r.text("status")).isEqualTo("review_pending");
  assertThat(r.text("content_type")).isEqualTo("video/mp4");assertThat(r.text("pipeline_version")).isEqualTo("java-video-v1");
  assertThat(r.body().path("media_metadata").path("duration_ms").asInt()).isBetween(1200,1400);
  var movie=download(r.text("preview_url"));assertThat(movie.statusCode()).isEqualTo(200);
  assertThat(movie.headers().firstValue("Content-Type")).contains("video/mp4");
  assertThat(new String(movie.body(),java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("PRIVATE_TEST_METADATA");
  var thumb=call("POST","/media/"+r.text("id")+"/access-url?variant=thumbnail",null,p.a());
  assertThat(thumb.status()).isEqualTo(200);var png=download(thumb.text("url"));
  String token=thumb.text("url").substring("/media/access/".length());
  var claims=json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
  assertThat(claims.path("variant").asString()).isEqualTo("thumbnail");
  assertThat(download(thumb.text("url")+"?variant=original").statusCode()).isEqualTo(400);
  var renewed=call("POST","/media/"+claims.path("media_id").asString()+"/access-url?variant="+claims.path("variant").asString(),null,p.a());
  assertThat(download(renewed.text("url")).headers().firstValue("Content-Type")).contains("image/png");
  var cover=call("POST","/media/"+r.text("id")+"/access-url?variant=cover",null,p.a());
  assertThat(download(cover.text("url")).body()).containsExactly(png.body());
  assertThat(png.headers().firstValue("Content-Type")).contains("image/png");
  var image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png.body()));assertThat(image.getWidth()).isEqualTo(64);
  assertThat(call("POST","/media/"+r.text("id")+"/access-url?variant=thumbnail",null,p.b()).status()).isEqualTo(404);
  assertThat(download(r.text("preview_url")+"?variant=../../escape").statusCode()).isEqualTo(400);
 }
 @Test void malformedWrongMimeOversizeAndWrongScopeAreRejected()throws Exception{
  var p=pair();assertThat(upload(p.a(),p.id(),"broken".getBytes(),"video/mp4","chat").status()).isEqualTo(400);
  assertThat(upload(p.a(),p.id(),valid,"image/png","chat").status()).isEqualTo(400);
  assertThat(upload(p.a(),null,valid,"video/mp4","profile").status()).isEqualTo(400);
  assertThat(upload(p.a(),p.id(),new byte[32*1024*1024+1],"video/mp4","chat").status()).isEqualTo(413);
  assertThat(count("media_assets")).isZero();
  try(var paths=java.nio.file.Files.list(ROOT.resolve("multipart"))){assertThat(paths.count()).isZero();}
 }
 @Test void rejectsLongVideoAndUnsupportedCodecsAndAcceptsSilentMov()throws Exception{
  var p=pair();
  assertThat(upload(p.a(),p.id(),fixture(61,true,"libx264"),"video/mp4","chat").status()).isEqualTo(400);
  assertThat(upload(p.a(),p.id(),fixture(1,true,"mpeg4"),"video/mp4","chat").status()).isEqualTo(400);
  assertThat(upload(p.a(),p.id(),fixture(1,false,"libx264"),"video/quicktime","chat").status()).isEqualTo(200);
 }
 HttpResponse<byte[]> range(String url,String range)throws Exception{
  return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+url)).header("Range",range).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
 }
 @Test void rangePlaybackReturnsExactBytesAndRejectsInvalidRanges()throws Exception{
  var p=pair();String id=approved(p.a(),p.id()),url=grant(p.a(),id);byte[] full=download(url).body();
  var first=range(url,"bytes=0-31");assertThat(first.statusCode()).isEqualTo(206);
  assertThat(first.headers().firstValue("Content-Range")).contains("bytes 0-31/"+full.length);
  assertThat(first.body()).containsExactly(Arrays.copyOfRange(full,0,32));
  assertThat(range(url,"bytes=-16").body()).containsExactly(Arrays.copyOfRange(full,full.length-16,full.length));
  assertThat(range(url,"bytes=32-").body()).containsExactly(Arrays.copyOfRange(full,32,full.length));
  for(String invalid:List.of("bytes=999999999-","bytes=10-2","bytes=-0","bytes=-","bytes=0-1,3-4","bytes=999999999999999999999999-","words=0-1")){
   var result=range(url,invalid);assertThat(result.statusCode()).as(invalid).isEqualTo(416);
   assertThat(result.headers().firstValue("Content-Range")).contains("bytes */"+full.length);assertThat(result.body()).isEmpty();
  }
 }
 @Test void thumbnailRevocationAndDeletionFollowOriginal()throws Exception{
  var p=pair();String id=approved(p.a(),p.id());var sent=video(p.a(),p.id(),id,"video-thumbnail-revoke",1);
  String url=call("POST","/media/"+id+"/access-url?variant=thumbnail",null,p.b()).text("url");
  assertThat(range(url,"bytes=0-7").statusCode()).isEqualTo(206);
  call("DELETE",path(p.id())+"/messages/"+sent.text("id"),null,p.b());
  assertThat(range(url,"bytes=0-7").statusCode()).isEqualTo(404);
  String key=db.queryForObject("SELECT storage_key FROM media_assets WHERE id=?",String.class,id);
  call("DELETE","/media/"+id,null,p.a());
  assertThat(java.nio.file.Files.exists(ROOT.resolve(key.replace(".mp4",".thumb.png")))).isFalse();
 }

 @Test void serverDurationOverridesClientAndRetryIsIdempotent()throws Exception{
  var p=pair();var pending=upload(p.a(),p.id());String id=pending.text("id");
  assertThat(video(p.a(),p.id(),id,"video-pending",60).status()).isEqualTo(409);
  media.resolveReview(id,true,"fixture","synthetic");
  var sent=video(p.a(),p.id(),id,"video-real-duration",60);assertThat(sent.status()).isEqualTo(200);
  assertThat(sent.body().path("duration_seconds").asInt()).isEqualTo(2);assertThat(sent.body().path("media_kind").isNull()).isTrue();
  assertThat(video(p.a(),p.id(),id,"video-real-duration",1).text("id")).isEqualTo(sent.text("id"));
  assertThat(count("messages")).isEqualTo(1);assertThat(unread(p.b(),p.id())).isEqualTo(1);
  assertThat(call("GET",path(p.id())+"/messages/search?message_type=video",null,p.b()).body().path("items").size()).isEqualTo(1);
  assertThat(download(grant(p.b(),id)).statusCode()).isEqualTo(200);
 }
 @Test void videoCannotCrossOwnerConversationOrUseImageKind()throws Exception{
  var p=pair();var third=user();String other=friend(p.a(),third);String id=approved(p.a(),p.id());
  assertThat(video(p.b(),p.id(),id,"video-wrong-owner",1).status()).isEqualTo(404);
  assertThat(video(p.a(),other,id,"video-wrong-room",1).status()).isEqualTo(400);
  assertThat(call("POST",path(p.id())+"/messages",Map.of("type","image","content","x","media_asset_id",id),p.a()).status()).isEqualTo(400);
  assertThat(call("POST",path(p.id())+"/messages",Map.of("type","video","content","x","media_asset_id",id,"media_kind","image"),p.a()).status()).isEqualTo(422);
  assertThat(call("PATCH","/profiles/me",Map.of("avatar_url","media:"+id),p.a()).status()).isEqualTo(404);
 }
 @Test void hideRecallBlockAndLogoutRevokeAudioLinks()throws Exception{
  var p=pair();String id=approved(p.a(),p.id());var sent=video(p.a(),p.id(),id,"video-hidden",1);
  String own=grant(p.a(),id),peer=grant(p.b(),id);
  call("DELETE",path(p.id())+"/messages/"+sent.text("id"),null,p.b());
  assertThat(download(peer).statusCode()).isEqualTo(404);assertThat(download(own).statusCode()).isEqualTo(200);
  var recalled=call("POST",path(p.id())+"/messages/"+sent.text("id")+"/recall",null,p.a());
  assertThat(recalled.body().path("duration_seconds").asInt()).isZero();assertThat(recalled.body().path("media_asset_id").isNull()).isTrue();
  assertThat(download(own).statusCode()).isEqualTo(404);
  String second=approved(p.a(),p.id());video(p.a(),p.id(),second,"video-blocked",1);String secondUrl=grant(p.b(),second);
  db.update("INSERT INTO blocks VALUES(?,?)",p.a().id(),p.b().id());assertThat(download(secondUrl).statusCode()).isEqualTo(404);db.update("DELETE FROM blocks");
  assertThat(call("DELETE","/auth/sessions/"+p.b().session(),null,p.b()).status()).isEqualTo(200);assertThat(download(secondUrl).statusCode()).isEqualTo(404);
 }
 @Test void realWebSocketCarriesVideoIdentityAndMeasuredDuration()throws Exception{
  var p=pair();String id=approved(p.a(),p.id());var received=new CompletableFuture<JsonNode>();var ready=new CompletableFuture<Void>();
  var socket=http.newWebSocketBuilder().header("Authorization","Bearer "+p.b().token()).buildAsync(URI.create("ws://127.0.0.1:"+port+"/ws/messages?reliable=true"),new WebSocket.Listener(){
   final StringBuilder buffer=new StringBuilder();
   public CompletionStage<?> onText(WebSocket ws,CharSequence text,boolean last){
    buffer.append(text);if(last){var frame=json.readTree(buffer.toString());buffer.setLength(0);
     if(frame.path("type").asString().equals("realtime.ready"))ready.complete(null);
     if(frame.path("type").asString().equals("message.created"))received.complete(frame);
    }ws.request(1);return null;
   }
  }).get(10,TimeUnit.SECONDS);
  try{
   ready.get(10,TimeUnit.SECONDS);assertThat(video(p.a(),p.id(),id,"video-websocket",1).status()).isEqualTo(200);realtime.publish();
   var message=received.get(10,TimeUnit.SECONDS).path("message");assertThat(message.path("media_asset_id").asString()).isEqualTo(id);
   assertThat(message.path("duration_seconds").asInt()).isEqualTo(2);assertThat(message.path("type").asString()).isEqualTo("video");
  }finally{socket.abort();}
 }
 @Test void transactionFailureLeavesVideoUnboundForRetry()throws Exception{
  var p=pair();String id=approved(p.a(),p.id());
  db.execute("ALTER TABLE chat_change_outbox ADD CONSTRAINT video_failure CHECK(event_type<>'message.created')");
  try{assertThat(video(p.a(),p.id(),id,"video-rollback",1).status()).isEqualTo(500);assertThat(count("messages")).isZero();
   assertThat(db.queryForObject("SELECT message_id FROM media_assets WHERE id=?",String.class,id)).isNull();
  }finally{db.execute("ALTER TABLE chat_change_outbox DROP CONSTRAINT video_failure");}
  assertThat(video(p.a(),p.id(),id,"video-rollback",1).status()).isEqualTo(200);
 }
 @Test void deletedVideoFilesAndErasedConversationFilesAreRemoved()throws Exception{
  var p=pair();String id=approved(p.a(),p.id());String key=db.queryForObject("SELECT storage_key FROM media_assets WHERE id=?",String.class,id);
  String url=grant(p.a(),id);assertThat(call("DELETE","/media/"+id,null,p.a()).status()).isEqualTo(204);
  assertThat(download(url).statusCode()).isEqualTo(404);assertThat(java.nio.file.Files.exists(ROOT.resolve(key))).isFalse();
  String second=approved(p.b(),p.id());video(p.b(),p.id(),second,"video-erasure",1);
  String key2=db.queryForObject("SELECT storage_key FROM media_assets WHERE id=?",String.class,second);
  call("POST","/auth/account/deactivate",null,p.a());db.update("UPDATE users SET deactivation_due_at=now()-interval '1 second' WHERE id=?",p.a().id());
  assertThat(accounts.eraseDue(p.a().id())).isTrue();assertThat(accounts.cleanupStorage()).isEqualTo(1);
  assertThat(java.nio.file.Files.exists(ROOT.resolve(key2))).isFalse();assertThat(count("media_assets")).isZero();assertThat(java.nio.file.Files.exists(ROOT.resolve(key2.replace(".mp4",".thumb.png")))).isFalse();
 }
 @Test void missingVideoToolsFailClosedWithoutWritingAsset()throws Exception{
  var processor=new com.manliao.backend.media.VideoProcessor("","",json);
  assertThatThrownBy(()->processor.decode(new org.springframework.mock.web.MockMultipartFile("file","x.mp4","video/mp4",valid),ROOT))
   .isInstanceOf(com.manliao.backend.common.ApiError.class).hasMessageContaining("未配置");
 }
 @Test void concurrentRetriesOnlyCreateOneVideoMessage()throws Exception{
  var p=pair();String id=approved(p.a(),p.id());
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var futures=pool.invokeAll(List.<Callable<Reply>>of(()->video(p.a(),p.id(),id,"video-concurrent",1),()->video(p.a(),p.id(),id,"video-concurrent",1)));
   var a=futures.get(0).get();var b=futures.get(1).get();assertThat(a.status()).isEqualTo(200);assertThat(b.text("id")).isEqualTo(a.text("id"));
  }assertThat(count("messages")).isEqualTo(1);assertThat(unread(p.b(),p.id())).isEqualTo(1);
 }
 @Test void adminPreviewHasVideoTypeAndRejectionRevokesPlayback()throws Exception{
  db.execute("TRUNCATE admin_users CASCADE");admins.bootstrap(new com.manliao.backend.admin.AdminDtos.Create("video_owner","Video-admin-password","Video","owner"));
  var login=call("POST","/admin/auth/login",Map.of("username","video_owner","password","Video-admin-password"),null);var admin=new User("",login.text("access_token"),"");
  var p=pair();var asset=upload(p.a(),p.id());String id=asset.text("id");
  var list=call("GET","/admin/moderation/media?media_type=video&source=chat",null,admin);assertThat(list.body().size()).isEqualTo(1);
  var preview=call("POST","/admin/moderation/media/"+id+"/preview-url",null,admin);var audio=download(preview.text("url"));
  assertThat(audio.statusCode()).isEqualTo(200);assertThat(audio.headers().firstValue("Content-Type")).contains("video/mp4");
  var thumb=call("POST","/admin/moderation/media/"+id+"/preview-url?variant=thumbnail",null,admin);
  assertThat(thumb.status()).isEqualTo(200);assertThat(download(thumb.text("url")).headers().firstValue("Content-Type")).contains("image/png");
  assertThat(range(preview.text("url"),"bytes=0-7").statusCode()).isEqualTo(206);
  assertThat(call("POST","/admin/moderation/media/"+id+"/approve",Map.of("reason","synthetic audio"),admin).status()).isEqualTo(200);
  video(p.a(),p.id(),id,"video-moderated",1);String url=grant(p.b(),id);
  assertThat(call("POST","/admin/moderation/media/"+id+"/reject",Map.of("reason","synthetic rejection"),admin).status()).isEqualTo(200);
  assertThat(download(url).statusCode()).isEqualTo(404);
 }

 @Test void acceptsHevcM4vAndRejectsTruncatedVideo()throws Exception{
  var p=pair();
  assertThat(upload(p.a(),p.id(),fixture(1,true,"libx265"),"video/x-m4v","chat").status()).isEqualTo(200);
  assertThat(upload(p.a(),p.id(),Arrays.copyOf(valid,valid.length/2),"video/mp4","chat").status()).isEqualTo(400);
 }
 @Test void failedAssetInsertCleansVideoAndThumbnail()throws Exception{
  var p=pair();long before;
  try(var files=java.nio.file.Files.list(ROOT)){before=files.filter(java.nio.file.Files::isRegularFile).count();}
  db.execute("ALTER TABLE media_assets ADD CONSTRAINT reject_video_fixture CHECK(media_type<>'video')");
  try{assertThat(upload(p.a(),p.id()).status()).isEqualTo(500);assertThat(count("media_assets")).isZero();}
  finally{db.execute("ALTER TABLE media_assets DROP CONSTRAINT reject_video_fixture");}
  try(var files=java.nio.file.Files.list(ROOT)){assertThat(files.filter(java.nio.file.Files::isRegularFile).count()).isEqualTo(before);}
 }

}
