package com.manliao.backend.media;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.*;
import com.manliao.backend.common.ApiError;
@Component
public class VideoProcessor {
 private final String ffmpeg,ffprobe;private final ObjectMapper json;private final Semaphore slots=new Semaphore(1);
 public VideoProcessor(@Value("${app.media.ffmpeg:}") String ffmpeg,@Value("${app.media.ffprobe:}") String ffprobe,ObjectMapper json){this.ffmpeg=ffmpeg;this.ffprobe=ffprobe;this.json=json;}
 public record Decoded(byte[] mp4,byte[] thumbnail,int width,int height,int durationMs){}
 public Decoded decode(MultipartFile file,Path temporary)throws IOException{
  if(ffmpeg.isBlank()||ffprobe.isBlank()||!Files.isRegularFile(Path.of(ffmpeg))||!Files.isRegularFile(Path.of(ffprobe)))throw new ApiError(503,"MEDIA_PROCESSOR_UNAVAILABLE","视频处理工具未配置");
  if(file.isEmpty()||!Set.of("video/mp4","video/quicktime","video/x-m4v").contains(Optional.ofNullable(file.getContentType()).orElse("")))throw invalid();
  if(file.getSize()>32*1024*1024)throw new ApiError(413,"MEDIA_FILE_TOO_LARGE","视频不能超过 32MB");
  if(!slots.tryAcquire())throw new ApiError(429,"MEDIA_BUSY","视频处理中，请稍后重试");
  Path input=null,output=null,thumbnail=null;
  try{
   byte[] bytes;try(var in=file.getInputStream()){bytes=in.readNBytes(32*1024*1024+1);}
   if(bytes.length>32*1024*1024)throw new ApiError(413,"MEDIA_FILE_TOO_LARGE","视频不能超过 32MB");
   input=Files.createTempFile(temporary,"video-",".mov");Files.write(input,bytes);
   var metadata=probe(input);int videos=0,audios=0;
   for(var stream:metadata.path("streams")){
    String type=stream.path("codec_type").asString();
    if(type.equals("video")){
     videos++;int w=stream.path("width").asInt(),h=stream.path("height").asInt();
     if(!Set.of("h264","hevc").contains(stream.path("codec_name").asString())||w<2||h<2||w>3840||h>3840||(long)w*h>8294400||stream.path("disposition").path("attached_pic").asInt()!=0)throw invalid();
     String[] rate=stream.path("r_frame_rate").asString().split("/");
     double fps=rate.length==2?Double.parseDouble(rate[0])/Double.parseDouble(rate[1]):0;
     if(!Double.isFinite(fps)||fps<=0||fps>60)throw invalid();
    }else if(type.equals("audio")){
     audios++;int channels=stream.path("channels").asInt(),rate=Integer.parseInt(stream.path("sample_rate").asString());
     if(!"aac".equals(stream.path("codec_name").asString())||channels<1||channels>2||rate<8000||rate>48000)throw invalid();
    }else throw invalid();
   }
   if(videos!=1||audios>1)throw invalid();duration(metadata);
   output=Files.createTempFile(temporary,"video-normalized-",".mp4");
   // Decode up to the rejection boundary; never silently accept a clipped long video.
   run(List.of(ffmpeg,"-v","error","-nostdin","-y","-xerror","-err_detect","explode","-max_alloc","67108864","-threads","1",
    "-protocol_whitelist","file","-f","mov","-enable_drefs","0","-use_absolute_path","0","-i",input.toString(),
    "-filter_threads","1","-map","0:v:0","-map","0:a:0?","-sn","-dn","-map_metadata","-1","-map_chapters","-1",
    "-vf","scale=w='min(1280,iw)':h='min(1280,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2,setsar=1",
    "-r","30","-t","61","-c:v","libx264","-preset","veryfast","-crf","25","-maxrate","3M","-bufsize","3M","-pix_fmt","yuv420p","-threads","1",
    "-c:a","aac","-ac","2","-ar","48000","-b:a","128k","-fs","33554432","-movflags","+faststart",output.toString()),65536);
   if(Files.size(output)==0||Files.size(output)>=31*1024*1024)throw invalid();
   var normalized=probe(output);int millis=duration(normalized);var video=normalized.path("streams").get(0);
   thumbnail=Files.createTempFile(temporary,"video-thumbnail-",".png");
   run(List.of(ffmpeg,"-v","error","-nostdin","-y","-threads","1","-protocol_whitelist","file","-i",output.toString(),
    "-filter_threads","1","-map","0:v:0","-frames:v","1","-vf","scale=w='min(320,iw)':h='min(320,ih)':force_original_aspect_ratio=decrease",
    "-map_metadata","-1","-threads","1",thumbnail.toString()),65536);
   if(Files.size(thumbnail)>1024*1024)throw invalid();
   return new Decoded(Files.readAllBytes(output),Files.readAllBytes(thumbnail),video.path("width").asInt(),video.path("height").asInt(),millis);
  }catch(ApiError e){throw e;}catch(NumberFormatException e){throw invalid();}
  finally{
   try{for(Path p:Arrays.asList(input,output,thumbnail))if(p!=null)Files.deleteIfExists(p);}
   finally{slots.release();}
  }
 }
 private JsonNode probe(Path input)throws IOException{
  return json.readTree(run(List.of(ffprobe,"-v","error","-max_alloc","67108864","-protocol_whitelist","file","-f","mov",
   "-enable_drefs","0","-use_absolute_path","0","-show_streams","-show_format","-of","json",input.toString()),262144));
 }
 private int duration(JsonNode metadata){
  double seconds=Double.parseDouble(metadata.path("format").path("duration").asString());
  if(!Double.isFinite(seconds)||seconds<0.1||seconds>60.5)throw new ApiError(400,"MEDIA_DURATION_INVALID","视频须为 0.1 至 60 秒，编码尾帧允许最多 0.5 秒余量");
  return (int)Math.round(seconds*1000);
 }
 private byte[] run(List<String> command,int maximum)throws IOException{
  var builder=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
  var environment=builder.environment();var keep=new HashMap<String,String>();
  for(String key:List.of("SystemRoot","WINDIR","TEMP","TMP"))if(environment.containsKey(key))keep.put(key,environment.get(key));
  environment.clear();environment.putAll(keep);
  Process process;try{process=builder.start();}catch(IOException missing){throw new ApiError(503,"MEDIA_PROCESSOR_UNAVAILABLE","视频处理工具不可用");}
  var result=new FutureTask<byte[]>(()->{try(var in=process.getInputStream()){return in.readNBytes(maximum+1);}});Thread.ofVirtual().start(result);
  try{
   if(!process.waitFor(60,TimeUnit.SECONDS))throw new ApiError(503,"MEDIA_PROCESSING_TIMEOUT","视频处理超时");
   byte[] bytes=result.get(2,TimeUnit.SECONDS);if(process.exitValue()!=0||bytes.length>maximum)throw invalid();return bytes;
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw new ApiError(503,"MEDIA_PROCESSING_INTERRUPTED","视频处理已中断");}
   catch(ExecutionException|TimeoutException e){throw invalid();}
  finally{process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();result.cancel(true);}
 }
 private ApiError invalid(){return new ApiError(400,"MEDIA_TYPE_INVALID","需要有效的 MP4/MOV 视频，支持 H.264/HEVC 和可选 AAC 音轨，最高 4K/60fps");}
}
