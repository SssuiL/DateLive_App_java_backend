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
 "app.auth.jwt-secret=isolated-voice-tests-secret-at-least-thirty-two-bytes",
 "app.auth.legacy-registration-enabled=true","app.accounts.worker-enabled=false"})
class VoiceIntegrationTests {
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
 static java.nio.file.Path root(){try{return java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("target"),"voice-it-").toAbsolutePath();}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
 @org.springframework.test.context.DynamicPropertySource static void storage(org.springframework.test.context.DynamicPropertyRegistry p){p.add("app.media.storage-root",()->ROOT.toString());}
 @BeforeAll static void fixtures()throws Exception{valid=fixture(1.25,false,"aac");}
 static byte[] fixture(double seconds,boolean video,String codec)throws Exception{
  String executable=System.getenv("JAVA_MEDIA_FFMPEG");assertThat(executable).as("Install/configure FFmpeg; voice tests must not silently skip").isNotBlank();
  var target=ROOT.resolve("fixture-"+UUID.randomUUID()+(video?".mp4":".m4a"));
  var cmd=new ArrayList<>(List.of(executable,"-v","error","-nostdin","-f","lavfi","-i","sine=frequency=440:sample_rate=44100"));
  if(video)cmd.addAll(List.of("-f","lavfi","-i","color=c=black:s=16x16:r=1","-c:v","mpeg4"));
  cmd.addAll(List.of("-t",Double.toString(seconds),"-c:a",codec,"-ac","1","-metadata","comment=PRIVATE_TEST_METADATA","-movflags","+faststart",target.toString()));
  var process=new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
  try{assertThat(process.waitFor(15,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();return java.nio.file.Files.readAllBytes(target);}
  finally{process.destroyForcibly();java.nio.file.Files.deleteIfExists(target);}
 }
 Reply upload(User u,String conv,byte[] bytes,String mime,String source)throws Exception{
  String boundary="voice-upload-boundary";var output=new java.io.ByteArrayOutputStream();
  var fields=new LinkedHashMap<String,String>();fields.put("media_type","voice");fields.put("source",source);if(conv!=null)fields.put("conversation_id",conv);
  for(var f:fields.entrySet())output.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+f.getKey()+"\"\r\n\r\n"+f.getValue()+"\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
  output.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"recording.m4a\"\r\nContent-Type: "+mime+"\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
  output.write(bytes);output.write(("\r\n--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
  var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/media/upload")).timeout(java.time.Duration.ofSeconds(45)).header("Authorization","Bearer "+u.token())
   .header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(output.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
  return new Reply(response.statusCode(),json.readTree(response.body()));
 }
 Reply upload(User u,String conv)throws Exception{return upload(u,conv,valid,"audio/mp4","chat");}
 String approved(User u,String conv)throws Exception{var r=upload(u,conv);assertThat(r.status()).isEqualTo(200);media.resolveReview(r.text("id"),true,"fixture","synthetic tone");return r.text("id");}
 Reply voice(User u,String conv,String asset,String key,int duration)throws Exception{return call("POST",path(conv)+"/messages",Map.of("type","voice","content","[语音]","media_asset_id",asset,"client_message_id",key,"duration_seconds",duration),u);}
 HttpResponse<byte[]> download(String url)throws Exception{return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+url)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());}
 String grant(User u,String id)throws Exception{var r=call("POST","/media/"+id+"/access-url",null,u);assertThat(r.status()).isEqualTo(200);return r.text("url");}
 @Test void uploadDecodesToPcmWavAndCalculatesMetadata()throws Exception{
  var p=pair();var r=upload(p.a(),p.id());assertThat(r.status()).isEqualTo(200);
  assertThat(r.text("media_type")).isEqualTo("voice");assertThat(r.text("status")).isEqualTo("review_pending");
  assertThat(r.text("content_type")).isEqualTo("audio/wav");assertThat(r.text("pipeline_version")).isEqualTo("java-voice-v1");
  assertThat(r.body().path("media_metadata").path("duration_ms").asInt()).isBetween(1200,1400);
  var audio=download(r.text("preview_url"));assertThat(audio.statusCode()).isEqualTo(200);
  assertThat(audio.headers().firstValue("Content-Type")).contains("audio/wav");
  assertThat(new String(audio.body(),java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("PRIVATE_TEST_METADATA");
  try(var stream=javax.sound.sampled.AudioSystem.getAudioInputStream(new java.io.ByteArrayInputStream(audio.body()))){
   assertThat(stream.getFormat().getSampleRate()).isEqualTo(16000);assertThat(stream.getFormat().getChannels()).isEqualTo(1);
   assertThat(stream.getFrameLength()).isBetween(19200L,22400L);
  }
  assertThat(call("POST","/media/"+r.text("id")+"/access-url",null,p.b()).status()).isEqualTo(404);
 }
 @Test void malformedWrongMimeOversizeAndWrongScopeAreRejected()throws Exception{
  var p=pair();assertThat(upload(p.a(),p.id(),"broken".getBytes(),"audio/mp4","chat").status()).isEqualTo(400);
  assertThat(upload(p.a(),p.id(),valid,"image/png","chat").status()).isEqualTo(400);
  assertThat(upload(p.a(),null,valid,"audio/mp4","profile").status()).isEqualTo(400);
  assertThat(upload(p.a(),p.id(),new byte[8*1024*1024+1],"audio/mp4","chat").status()).isEqualTo(413);
  assertThat(count("media_assets")).isZero();
  try(var paths=java.nio.file.Files.list(ROOT.resolve("multipart"))){assertThat(paths.filter(pth->pth.getFileName().toString().startsWith("voice-")).count()).isZero();}
 }
 @Test void actualLongShortVideoOrNonAacTracksAreRejected()throws Exception{
  var p=pair();
  assertThat(upload(p.a(),p.id(),fixture(61,false,"aac"),"audio/mp4","chat").status()).isEqualTo(400);
  assertThat(upload(p.a(),p.id(),fixture(0.01,false,"aac"),"audio/mp4","chat").status()).isEqualTo(400);
  assertThat(upload(p.a(),p.id(),fixture(1,true,"aac"),"audio/mp4","chat").status()).isEqualTo(400);
  assertThat(upload(p.a(),p.id(),fixture(1,false,"alac"),"audio/mp4","chat").status()).isEqualTo(400);
  assertThat(count("media_assets")).isZero();
 }
 @Test void serverDurationOverridesClientAndRetryIsIdempotent()throws Exception{
  var p=pair();var pending=upload(p.a(),p.id());String id=pending.text("id");
  assertThat(voice(p.a(),p.id(),id,"voice-pending",60).status()).isEqualTo(409);
  media.resolveReview(id,true,"fixture","synthetic");
  var sent=voice(p.a(),p.id(),id,"voice-real-duration",60);assertThat(sent.status()).isEqualTo(200);
  assertThat(sent.body().path("duration_seconds").asInt()).isEqualTo(2);assertThat(sent.body().path("media_kind").isNull()).isTrue();
  assertThat(voice(p.a(),p.id(),id,"voice-real-duration",1).text("id")).isEqualTo(sent.text("id"));
  assertThat(count("messages")).isEqualTo(1);assertThat(unread(p.b(),p.id())).isEqualTo(1);
  assertThat(call("GET",path(p.id())+"/messages/search?message_type=voice",null,p.b()).body().path("items").size()).isEqualTo(1);
  assertThat(download(grant(p.b(),id)).statusCode()).isEqualTo(200);
 }
 @Test void voiceCannotCrossOwnerConversationOrUseImageKind()throws Exception{
  var p=pair();var third=user();String other=friend(p.a(),third);String id=approved(p.a(),p.id());
  assertThat(voice(p.b(),p.id(),id,"voice-wrong-owner",1).status()).isEqualTo(404);
  assertThat(voice(p.a(),other,id,"voice-wrong-room",1).status()).isEqualTo(400);
  assertThat(call("POST",path(p.id())+"/messages",Map.of("type","image","content","x","media_asset_id",id),p.a()).status()).isEqualTo(400);
  assertThat(call("POST",path(p.id())+"/messages",Map.of("type","voice","content","x","media_asset_id",id,"media_kind","image"),p.a()).status()).isEqualTo(422);
  assertThat(call("PATCH","/profiles/me",Map.of("avatar_url","media:"+id),p.a()).status()).isEqualTo(404);
 }
 @Test void hideRecallBlockAndLogoutRevokeAudioLinks()throws Exception{
  var p=pair();String id=approved(p.a(),p.id());var sent=voice(p.a(),p.id(),id,"voice-hidden",1);
  String own=grant(p.a(),id),peer=grant(p.b(),id);
  call("DELETE",path(p.id())+"/messages/"+sent.text("id"),null,p.b());
  assertThat(download(peer).statusCode()).isEqualTo(404);assertThat(download(own).statusCode()).isEqualTo(200);
  var recalled=call("POST",path(p.id())+"/messages/"+sent.text("id")+"/recall",null,p.a());
  assertThat(recalled.body().path("duration_seconds").asInt()).isZero();assertThat(recalled.body().path("media_asset_id").isNull()).isTrue();
  assertThat(download(own).statusCode()).isEqualTo(404);
  String second=approved(p.a(),p.id());voice(p.a(),p.id(),second,"voice-blocked",1);String secondUrl=grant(p.b(),second);
  db.update("INSERT INTO blocks VALUES(?,?)",p.a().id(),p.b().id());assertThat(download(secondUrl).statusCode()).isEqualTo(404);db.update("DELETE FROM blocks");
  assertThat(call("DELETE","/auth/sessions/"+p.b().session(),null,p.b()).status()).isEqualTo(200);assertThat(download(secondUrl).statusCode()).isEqualTo(404);
 }
 @Test void realWebSocketCarriesVoiceIdentityAndMeasuredDuration()throws Exception{
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
   ready.get(10,TimeUnit.SECONDS);assertThat(voice(p.a(),p.id(),id,"voice-websocket",1).status()).isEqualTo(200);realtime.publish();
   var message=received.get(10,TimeUnit.SECONDS).path("message");assertThat(message.path("media_asset_id").asString()).isEqualTo(id);
   assertThat(message.path("duration_seconds").asInt()).isEqualTo(2);assertThat(message.path("type").asString()).isEqualTo("voice");
  }finally{socket.abort();}
 }
 @Test void transactionFailureLeavesVoiceUnboundForRetry()throws Exception{
  var p=pair();String id=approved(p.a(),p.id());
  db.execute("ALTER TABLE chat_change_outbox ADD CONSTRAINT voice_failure CHECK(event_type<>'message.created')");
  try{assertThat(voice(p.a(),p.id(),id,"voice-rollback",1).status()).isEqualTo(500);assertThat(count("messages")).isZero();
   assertThat(db.queryForObject("SELECT message_id FROM media_assets WHERE id=?",String.class,id)).isNull();
  }finally{db.execute("ALTER TABLE chat_change_outbox DROP CONSTRAINT voice_failure");}
  assertThat(voice(p.a(),p.id(),id,"voice-rollback",1).status()).isEqualTo(200);
 }
 @Test void deletedVoiceFilesAndErasedConversationFilesAreRemoved()throws Exception{
  var p=pair();String id=approved(p.a(),p.id());String key=db.queryForObject("SELECT storage_key FROM media_assets WHERE id=?",String.class,id);
  String url=grant(p.a(),id);assertThat(call("DELETE","/media/"+id,null,p.a()).status()).isEqualTo(204);
  assertThat(download(url).statusCode()).isEqualTo(404);assertThat(java.nio.file.Files.exists(ROOT.resolve(key))).isFalse();
  String second=approved(p.b(),p.id());voice(p.b(),p.id(),second,"voice-erasure",1);
  String key2=db.queryForObject("SELECT storage_key FROM media_assets WHERE id=?",String.class,second);
  call("POST","/auth/account/deactivate",null,p.a());db.update("UPDATE users SET deactivation_due_at=now()-interval '1 second' WHERE id=?",p.a().id());
  assertThat(accounts.eraseDue(p.a().id())).isTrue();assertThat(accounts.cleanupStorage()).isEqualTo(1);
  assertThat(java.nio.file.Files.exists(ROOT.resolve(key2))).isFalse();assertThat(count("media_assets")).isZero();
 }
 @Test void missingAudioToolsFailClosedWithoutWritingAsset()throws Exception{
  var processor=new com.manliao.backend.media.VoiceProcessor("","",json);
  assertThatThrownBy(()->processor.decode(new org.springframework.mock.web.MockMultipartFile("file","x.m4a","audio/mp4",valid),ROOT))
   .isInstanceOf(com.manliao.backend.common.ApiError.class).hasMessageContaining("未配置");
 }
 @Test void concurrentRetriesOnlyCreateOneVoiceMessage()throws Exception{
  var p=pair();String id=approved(p.a(),p.id());
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var futures=pool.invokeAll(List.<Callable<Reply>>of(()->voice(p.a(),p.id(),id,"voice-concurrent",1),()->voice(p.a(),p.id(),id,"voice-concurrent",1)));
   var a=futures.get(0).get();var b=futures.get(1).get();assertThat(a.status()).isEqualTo(200);assertThat(b.text("id")).isEqualTo(a.text("id"));
  }assertThat(count("messages")).isEqualTo(1);assertThat(unread(p.b(),p.id())).isEqualTo(1);
 }
 @Test void adminPreviewHasAudioTypeAndRejectionRevokesPlayback()throws Exception{
  db.execute("TRUNCATE admin_users CASCADE");admins.bootstrap(new com.manliao.backend.admin.AdminDtos.Create("voice_owner","Voice-admin-password","Voice","owner"));
  var login=call("POST","/admin/auth/login",Map.of("username","voice_owner","password","Voice-admin-password"),null);var admin=new User("",login.text("access_token"),"");
  var p=pair();var asset=upload(p.a(),p.id());String id=asset.text("id");
  var list=call("GET","/admin/moderation/media?media_type=voice&source=chat",null,admin);assertThat(list.body().size()).isEqualTo(1);
  var preview=call("POST","/admin/moderation/media/"+id+"/preview-url",null,admin);var audio=download(preview.text("url"));
  assertThat(audio.statusCode()).isEqualTo(200);assertThat(audio.headers().firstValue("Content-Type")).contains("audio/wav");
  assertThat(call("POST","/admin/moderation/media/"+id+"/approve",Map.of("reason","synthetic audio"),admin).status()).isEqualTo(200);
  voice(p.a(),p.id(),id,"voice-moderated",1);String url=grant(p.b(),id);
  assertThat(call("POST","/admin/moderation/media/"+id+"/reject",Map.of("reason","synthetic rejection"),admin).status()).isEqualTo(200);
  assertThat(download(url).statusCode()).isEqualTo(404);
 }
}
