package com.manliao.backend.realtime;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class RealtimePublisher {
 private final RealtimeStore store;private final boolean enabled;
 public RealtimePublisher(RealtimeStore store,@Value("${app.realtime.worker-enabled:true}") boolean enabled){this.store=store;this.enabled=enabled;}
 @Scheduled(initialDelay=1000,fixedDelay=500)
 public void tick(){if(enabled)store.publish();}
}
