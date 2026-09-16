package com.manliao.backend;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import tools.jackson.databind.*;
import com.manliao.backend.media.*;
import com.manliao.backend.identity.AuthDtos.Principal;
import static org.assertj.core.api.Assertions.*;
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.realtime.worker-enabled=false",
 "app.auth.jwt-secret=isolated-media-test-secret-at-least-thirty-two-bytes","app.auth.legacy-registration-enabled=true"})
class MediaIntegrationTests {
 static final Path ROOT=createRoot();
 static Path createRoot(){try{return Files.createTempDirectory(Path.of("target"),"media-it-").toAbsolutePath();}catch(IOException e){throw new UncheckedIOException(e);}}
 @DynamicPropertySource static void storage(DynamicPropertyRegistry properties){properties.add("app.media.storage-root",()->ROOT.toString());}
 @Value("${local.server.port}") int port;
 @Autowired JdbcTemplate db;@Autowired ObjectMapper mapper;@Autowired MediaService media;@Autowired MediaTokens tokens;@Autowired MediaStorage storage;
 final HttpClient http=HttpClient.newHttpClient();
 record Reply(int status,JsonNode json){String text(String key){return json.get(key).asString();}}
 @BeforeEach void clean(){db.execute("TRUNCATE users,auth_rate_windows,auth_verification_codes RESTART IDENTITY CASCADE");}
 Reply call(String method,String path,Object body,String token)throws Exception {
   var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20));
   if(token!=null)b.header("Authorization","Bearer "+token);
   if(body!=null)b.header("Content-Type","application/json");
   var r=http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
   return new Reply(r.statusCode(),r.body().isBlank()?null:mapper.readTree(r.body()));
 }
 Reply user(String suffix)throws Exception {
   var user=call("POST","/auth/register",Map.of("phone","1394000"+suffix,"password","Media-test-password","nickname","媒体测试"),null);
   assertThat(user.status()).isEqualTo(200);return user;
 }
 byte[] png()throws IOException {
   var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(3,2,BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray();
 }
 Reply upload(Reply user,byte[] bytes,String contentType,String filename,String mediaType,String source)throws Exception {
   String boundary="java-test-boundary";var out=new ByteArrayOutputStream();
   out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"media_type\"\r\n\r\n"+mediaType+"\r\n").getBytes(StandardCharsets.UTF_8));
   out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"source\"\r\n\r\n"+source+"\r\n").getBytes(StandardCharsets.UTF_8));
   out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+filename+"\"\r\nContent-Type: "+contentType+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
   out.write(bytes);out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
   var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/media/upload")).timeout(Duration.ofSeconds(20))
     .header("Content-Type","multipart/form-data; boundary="+boundary);
   if(user!=null)b.header("Authorization","Bearer "+user.text("access_token"));
   var r=http.send(b.POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
   return new Reply(r.statusCode(),mapper.readTree(r.body()));
 }
 Reply upload(Reply user)throws Exception{return upload(user,png(),"image/png","photo.png","image","profile");}
 HttpResponse<byte[]> download(String path)throws Exception {
   return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
 }
 long files()throws IOException {try(var paths=Files.list(ROOT)){return paths.filter(Files::isRegularFile).count();}}
 @Test void uploadNormalizesImageAndOwnerCanPreview()throws Exception {
   Reply user=user("0001"),asset=upload(user);
   assertThat(asset.status()).isEqualTo(200);
   assertThat(asset.text("status")).isEqualTo("review_pending");
   assertThat(asset.text("malware_status")).isEqualTo("skipped");
   assertThat(asset.text("url")).startsWith("media:media_");
   assertThat(asset.json().has("storage_key")).isFalse();
   var read=download(asset.text("preview_url"));
   assertThat(read.statusCode()).isEqualTo(200);
   assertThat(read.headers().firstValue("Cache-Control")).contains("private, no-store");
   assertThat(read.headers().firstValue("Content-Type")).contains("image/png");
   assertThat(ImageIO.read(new ByteArrayInputStream(read.body())).getWidth()).isEqualTo(3);
   Reply list=call("GET","/media/me",null,user.text("access_token"));
   assertThat(list.status()).isEqualTo(200);assertThat(list.json().size()).isEqualTo(1);
 }
 @Test void pendingMediaRequiresOwnerAndApprovedMediaRequiresProfileReference()throws Exception {
   Reply owner=user("0002"),other=user("0003"),asset=upload(owner);
   String endpoint="/media/"+asset.text("id")+"/access-url",otherToken=other.text("access_token");
   assertThat(call("POST",endpoint,null,otherToken).status()).isEqualTo(404);
   Reply profile=call("PATCH","/profiles/me",Map.of("avatar_url",asset.text("preview_url"),"photo_urls",List.of(asset.text("url"))),owner.text("access_token"));
   assertThat(profile.status()).isEqualTo(200);
   assertThat(profile.json().get("avatar_url").isNull()).isTrue();
   assertThat(download(profile.text("pending_avatar_url")).statusCode()).isEqualTo(200);
   media.resolveReview(asset.text("id"),true,"test-reviewer","test-only approval");
   Reply granted=call("POST",endpoint,null,otherToken);
   assertThat(granted.status()).isEqualTo(200);
   assertThat(download(granted.text("url")).statusCode()).isEqualTo(200);
   Reply publicProfile=call("GET","/profiles/"+owner.text("user_id"),null,otherToken);
   assertThat(publicProfile.text("avatar_url")).startsWith("/media/access/");
   assertThat(publicProfile.json().get("pending_avatar_url").isNull()).isTrue();
   call("PATCH","/profiles/me",Map.of("avatar_url","","photo_urls",List.of()),owner.text("access_token"));
   assertThat(download(granted.text("url")).statusCode()).isEqualTo(404);
 }
 @Test void blocksAndLogoutInvalidatePreviouslySignedUrls()throws Exception {
   Reply owner=user("0004"),other=user("0005"),asset=upload(owner);
   call("PATCH","/profiles/me",Map.of("avatar_url",asset.text("url")),owner.text("access_token"));
   media.resolveReview(asset.text("id"),true,"test-reviewer","test approval");
   Reply grant=call("POST","/media/"+asset.text("id")+"/access-url",null,other.text("access_token"));
   db.update("INSERT INTO blocks VALUES(?,?)",owner.text("user_id"),other.text("user_id"));
   assertThat(download(grant.text("url")).statusCode()).isEqualTo(404);
   db.update("DELETE FROM blocks");
   assertThat(download(grant.text("url")).statusCode()).isEqualTo(200);
   call("POST","/auth/logout",Map.of("refresh_token",other.text("refresh_token")),null);
   assertThat(download(grant.text("url")).statusCode()).isEqualTo(404);
 }
 @Test void deleteRevokesUrlsDetachesProfileAndRemovesFile()throws Exception {
   Reply owner=user("0006"),other=user("0007"),asset=upload(owner);
   String key=db.queryForObject("SELECT storage_key FROM media_assets",String.class);
   call("PATCH","/profiles/me",Map.of("avatar_url",asset.text("url"),"photo_urls",List.of(asset.text("url"))),owner.text("access_token"));
   assertThat(call("DELETE","/media/"+asset.text("id"),null,other.text("access_token")).status()).isEqualTo(404);
   assertThat(call("DELETE","/media/"+asset.text("id"),null,owner.text("access_token")).status()).isEqualTo(204);
   assertThat(download(asset.text("preview_url")).statusCode()).isEqualTo(404);
   assertThat(Files.exists(ROOT.resolve(key))).isFalse();
   assertThat(db.queryForObject("SELECT pending_avatar_url FROM user_profiles WHERE user_id=?",String.class,owner.text("user_id"))).isNull();
   assertThat(call("DELETE","/media/"+asset.text("id"),null,owner.text("access_token")).status()).isEqualTo(204);
   assertThatThrownBy(()->media.resolveReview(asset.text("id"),true,"test-reviewer","cannot restore deleted")).isInstanceOf(com.manliao.backend.common.ApiError.class);
 }
 @Test void malformedExpiredOrAuthTokensCannotReadMedia()throws Exception {
   Reply owner=user("0008"),asset=upload(owner);
   var key=new javax.crypto.spec.SecretKeySpec(java.security.MessageDigest.getInstance("SHA-256").digest(
     "media-access-v1:isolated-media-test-secret-at-least-thirty-two-bytes".getBytes(StandardCharsets.UTF_8)),"HmacSHA256");
   var encoder=new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(key));
   String expired=encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
     org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(),
     org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().subject(owner.text("user_id")).claim("sid",owner.text("session_id"))
       .claim("media_id",asset.text("id")).claim("type","media_access")
       .issuedAt(Instant.now().minusSeconds(600)).expiresAt(Instant.now().minusSeconds(5)).build())).getTokenValue();
   assertThat(download("/media/access/"+expired).statusCode()).isEqualTo(404);
   assertThat(download("/media/access/not-a-token").statusCode()).isEqualTo(404);
   assertThat(download("/media/access/"+owner.text("access_token")).statusCode()).isEqualTo(404);
   String url=asset.text("preview_url");
   int position=url.length()-10;
   String tampered=url.substring(0,position)+(url.charAt(position)=='a'?'b':'a')+url.substring(position+1);
   assertThat(download(tampered).statusCode()).isEqualTo(404);
   assertThat(call("POST","/media/"+asset.text("id")+"/access-url?variant=thumbnail",null,owner.text("access_token")).status()).isEqualTo(400);
 }
 @Test void rejectsFakeImagesWrongTypesAndUnsupportedSources()throws Exception {
   Reply owner=user("0009");long before=files();
   assertThat(upload(owner,"<script>evil</script>".getBytes(),"image/png","x.png","image","profile").status()).isEqualTo(400);
   assertThat(upload(owner,png(),"image/jpeg","x.jpg","image","profile").status()).isEqualTo(400);
   assertThat(upload(owner,png(),"image/png","x.png","video","profile").status()).isEqualTo(400);
   assertThat(upload(owner,png(),"image/png","x.png","image","chat").status()).isEqualTo(400);
   assertThat(files()).isEqualTo(before);
   assertThat(db.queryForObject("SELECT count(*) FROM media_assets",Integer.class)).isZero();
 }
 @Test void rejectsOversizedFilesAndPixelDimensions()throws Exception {
   Reply owner=user("0010");
   assertThat(upload(owner,new byte[8*1024*1024+1],"image/png","large.png","image","profile").status()).isEqualTo(413);
   var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2001,2000,BufferedImage.TYPE_BYTE_GRAY),"png",out);
   assertThat(upload(owner,out.toByteArray(),"image/png","pixels.png","image","profile").status()).isEqualTo(413);
 }
 @Test void filenameNeverControlsStoragePathAndUploadsRequireAuthentication()throws Exception {
   Reply owner=user("0011");
   Reply uploaded=upload(owner,png(),"image/png","../../outside.png","image","profile");
   assertThat(uploaded.status()).isEqualTo(200);
   assertThat(uploaded.text("original_filename")).isEqualTo("outside.png");
   assertThat(db.queryForObject("SELECT storage_key FROM media_assets",String.class)).matches("media_[a-f0-9]{32}\\.png");
   assertThatThrownBy(()->storage.open("../outside.png")).isInstanceOf(IOException.class);
   assertThat(upload(null,png(),"image/png","test.png","image","profile").status()).isEqualTo(401);
 }
 @Test void databaseFailureCleansUploadedFile()throws Exception {
   Reply owner=user("0012");long before=files();
   db.execute("ALTER TABLE media_assets ADD CONSTRAINT test_upload_failure CHECK(status<>'review_pending')");
   try {
     assertThat(upload(owner).status()).isEqualTo(500);
     assertThat(files()).isEqualTo(before);
     assertThat(db.queryForObject("SELECT count(*) FROM media_assets",Integer.class)).isZero();
   }finally{db.execute("ALTER TABLE media_assets DROP CONSTRAINT test_upload_failure");}
 }
 @Test void reviewFailureRollsBackStateAndRejectionRevokesAccess()throws Exception {
   Reply owner=user("0013"),asset=upload(owner);
   call("PATCH","/profiles/me",Map.of("avatar_url",asset.text("url")),owner.text("access_token"));
   db.execute("ALTER TABLE media_reviews ADD CONSTRAINT test_review_failure CHECK(action<>'approved')");
   try {
     assertThatThrownBy(()->media.resolveReview(asset.text("id"),true,"test-reviewer","test")).isInstanceOf(org.springframework.dao.DataAccessException.class);
     assertThat(db.queryForObject("SELECT status FROM media_assets",String.class)).isEqualTo("review_pending");
     assertThat(db.queryForObject("SELECT avatar_url FROM user_profiles",String.class)).isNull();
   }finally{db.execute("ALTER TABLE media_reviews DROP CONSTRAINT test_review_failure");}
   media.resolveReview(asset.text("id"),false,"test-reviewer","test rejection");
   assertThat(download(asset.text("preview_url")).statusCode()).isEqualTo(404);
   assertThat(db.queryForObject("SELECT pending_avatar_url FROM user_profiles",String.class)).isNull();
 }
}
