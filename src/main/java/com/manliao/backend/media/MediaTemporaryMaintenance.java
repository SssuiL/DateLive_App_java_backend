package com.manliao.backend.media;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
@Component
@ConditionalOnProperty(name="app.media.temp-worker-enabled",havingValue="true")
class MediaTemporaryMaintenance {
 private final MediaStorage storage;
 MediaTemporaryMaintenance(MediaStorage storage){this.storage=storage;}
 @Scheduled(initialDelay=60000,fixedDelay=3600000,scheduler="mediaProcessingScheduler")
 public void cleanup()throws IOException{storage.cleanupAbandonedTemporaryFiles();}
}
