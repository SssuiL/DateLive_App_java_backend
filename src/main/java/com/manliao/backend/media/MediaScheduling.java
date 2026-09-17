package com.manliao.backend.media;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
@org.springframework.scheduling.annotation.EnableScheduling
@Configuration
class MediaScheduling {
 @Bean(name="taskScheduler") ThreadPoolTaskScheduler backgroundScheduler(){return scheduler("background-");}
 @Bean(name="mediaProcessingScheduler") ThreadPoolTaskScheduler mediaScheduler(){return scheduler("media-processing-");}
 private ThreadPoolTaskScheduler scheduler(String prefix){
  var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix(prefix);return scheduler;
 }
}