package com.manliao.backend.media;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class MediaTemporaryTests {
 @Test void cleanupOnlyRemovesAgedProcessorFilesWithoutRecursing()throws Exception{
  Path root=Files.createTempDirectory(Path.of("target"),"temp-cleanup-");var storage=new MediaStorage(root.toString());var temp=storage.tempDirectory();
  Path old=Files.writeString(temp.resolve("video-normalized-123.mp4"),"old");Files.setLastModifiedTime(old,FileTime.from(Instant.now().minusSeconds(90000)));
  Path fresh=Files.writeString(temp.resolve("voice-123.m4a"),"active"),unknown=Files.writeString(temp.resolve("do-not-delete.txt"),"unknown");Files.setLastModifiedTime(unknown,FileTime.from(Instant.EPOCH));
  Path directory=Files.createDirectory(temp.resolve("derivative-directory.tmp"));Files.writeString(directory.resolve("keep"),"directory");Files.setLastModifiedTime(directory,FileTime.from(Instant.EPOCH));
  assertThat(storage.cleanupAbandonedTemporaryFiles()).isEqualTo(1);assertThat(old).doesNotExist();assertThat(fresh).exists();assertThat(unknown).exists();assertThat(directory.resolve("keep")).exists();
 }
 @Test void cleanupLimitsDeletedFilesPerRun()throws Exception{
  Path root=Files.createTempDirectory(Path.of("target"),"temp-bounded-");var storage=new MediaStorage(root.toString());var temp=storage.tempDirectory();
  for(int n=0;n<105;n++){var file=Files.writeString(temp.resolve("derivative-"+n+".tmp"),"old");Files.setLastModifiedTime(file,FileTime.from(Instant.EPOCH));}
  assertThat(storage.cleanupAbandonedTemporaryFiles()).isEqualTo(100);assertThat(storage.cleanupAbandonedTemporaryFiles()).isEqualTo(5);
 }
}
