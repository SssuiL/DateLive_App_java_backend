package com.manliao.backend;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"app.realtime.worker-enabled=false",
 "app.auth.jwt-secret=isolated-disabled-register-test-key-over-thirty-two-bytes",
 "app.auth.legacy-registration-enabled=false"})
class RegistrationDisabledTests {
 @Value("${local.server.port}") int port;
 @Autowired JdbcTemplate db;

 @Test void smsIsUnavailableByDefaultAndNeverCreatesACode() throws Exception {
   var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/auth/sms/send"))
     .header("Content-Type","application/json")
     .POST(HttpRequest.BodyPublishers.ofString("{\"phone\":\"13990000001\",\"purpose\":\"login\"}")).build();
   var reply=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
   assertThat(reply.statusCode()).isEqualTo(503);
   assertThat(reply.body()).contains("AUTH_SMS_PROVIDER_UNAVAILABLE").doesNotContain("dev_code");
   assertThat(db.queryForObject("SELECT count(*) FROM auth_verification_codes",Integer.class)).isZero();
 }

 @Test void passwordOnlyRegistrationIsClosedWithoutExplicitEnablement() throws Exception {
   var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/auth/register"))
     .header("Content-Type","application/json")
     .POST(HttpRequest.BodyPublishers.ofString("""
       {"phone":"13990000000","nickname":"test","password":"test-password"}
       """)).build();
   var reply=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
   assertThat(reply.statusCode()).isEqualTo(404);
   assertThat(db.queryForObject("SELECT count(*) FROM users",Integer.class)).isZero();
 }
}
