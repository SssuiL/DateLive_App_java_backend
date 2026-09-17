package com.manliao.backend;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("media-worker")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={
 "app.auth.jwt-secret=isolated-worker-test-secret-at-least-thirty-two-bytes",
 "app.media.jobs-worker-enabled=false","app.media.derivatives-worker-enabled=false","app.media.draft-worker-enabled=false"})
class MediaWorkerIntegrationTests {
 @Autowired ApplicationContext context;
 @Test void workerStartsWithoutHttpSecurityOrServletWebsocket(){
  assertThat(context).isNotInstanceOf(org.springframework.web.context.WebApplicationContext.class);
  assertThat(context.getBeansOfType(org.springframework.security.web.SecurityFilterChain.class)).isEmpty();
  assertThat(context.getBeansOfType(org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean.class)).isEmpty();
  assertThat(context.getBeansOfType(com.manliao.backend.identity.AccountErasureWorker.class)).isEmpty();
  assertThat(context.getBean(com.manliao.backend.media.MediaJobs.class)).isNotNull();
  assertThat(context.getEnvironment().getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("2");
 }
}
