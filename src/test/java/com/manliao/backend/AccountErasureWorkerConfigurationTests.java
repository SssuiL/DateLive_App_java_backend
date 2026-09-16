package com.manliao.backend;
import com.manliao.backend.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class AccountErasureWorkerConfigurationTests {
 @Configuration(proxyBeanMethods=false)
 @EnableScheduling
 @Import(AccountErasureWorker.class)
 static class Config {
  @Bean AccountLifecycleService accounts(){return mock(AccountLifecycleService.class);}
 }
 final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(Config.class);
 @Test void enabledWorkerIsScheduledAndRunsBothCleanupStages(){
  runner.withPropertyValues("app.accounts.worker-enabled=true").run(context->{
   assertThat(context).hasSingleBean(AccountErasureWorker.class);
   var scheduled=context.getBean(ScheduledAnnotationBeanPostProcessor.class);
   assertThat(scheduled.getScheduledTasks()).hasSize(1);
   context.getBean(AccountErasureWorker.class).process();
   var service=context.getBean(AccountLifecycleService.class);
   var order=inOrder(service);
   order.verify(service).processDue();
   order.verify(service).cleanupStorage();
  });
 }
 @Test void explicitDisablePreventsScheduling(){
  runner.withPropertyValues("app.accounts.worker-enabled=false").run(context->{
   assertThat(context).doesNotHaveBean(AccountErasureWorker.class);
   assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).isEmpty();
  });
 }
}
