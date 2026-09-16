package com.manliao.backend.media;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;
import com.manliao.backend.common.ApiError;
@Component
public class VoiceProcessor {
 private final String ffmpeg,ffprobe;private final ObjectMapper json;private final Semaphore slots=new Semaphore(1);
 public VoiceProcessor(@Value("${app.media.ffmpeg:}") String ffmpeg,@Value("${app.media.ffprobe:}") String ffprobe,ObjectMapper json){this.ffmpeg=ffmpeg;this.ffprobe=ffprobe;this.json=json;}
 public record Decoded(byte[] wav,int durationMs){}
 public Decoded decode(MultipartFile file,Path temporary)throws IOException{
  if(ffmpeg.isBlank()||ffprobe.isBlank()||!Files.isRegularFile(Path.of(ffmpeg))||!Files.isRegularFile(Path.of(ffprobe)))throw new ApiError(503,"MEDIA_PROCESSOR_UNAVAILABLE","语音处理工具未配置");
  if(!Set.of("audio/mp4","audio/x-m4a").contains(Optional.ofNullable(file.getContentType()).orElse(""))||file.isEmpty())throw invalid();
  if(file.getSize()>8*1024*1024)throw new ApiError(413,"MEDIA_FILE_TOO_LARGE","语音不能超过 8MB");
  if(!slots.tryAcquire())throw new ApiError(429,"MEDIA_BUSY","语音处理中，请稍后重试");
  Path input=null;
  try{
   byte[] bytes;try(var in=file.getInputStream()){bytes=in.readNBytes(8*1024*1024+1);}
   if(bytes.length>8*1024*1024)throw new ApiError(413,"MEDIA_FILE_TOO_LARGE","语音不能超过 8MB");
   input=Files.createTempFile(temporary,"voice-",".m4a");Files.write(input,bytes);
   var metadata=json.readTree(run(List.of(ffprobe,"-v","error","-max_alloc","67108864","-protocol_whitelist","file","-f","mov","-enable_drefs","0","-use_absolute_path","0","-show_entries","stream=codec_type,codec_name,profile,channels,sample_rate,duration:format=duration","-of","json",input.toString()),65536));
   var streams=metadata.path("streams");if(streams.size()!=1)throw invalid();var stream=streams.get(0);
   if(!"audio".equals(stream.path("codec_type").asString())||!"aac".equals(stream.path("codec_name").asString())||!"LC".equals(stream.path("profile").asString()))throw invalid();
   int channels=stream.path("channels").asInt(),rate=Integer.parseInt(stream.path("sample_rate").asString());
   if(channels<1||channels>2||rate<8000||rate>48000)throw invalid();
   double advertised=Double.parseDouble(metadata.path("format").path("duration").asString());
   if(!Double.isFinite(advertised)||advertised<=0||advertised>60.5)throw duration();
   // Bounded full decoding checks real samples instead of trusting a duration header.
   byte[] pcm=run(List.of(ffmpeg,"-v","error","-nostdin","-xerror","-err_detect","explode","-max_alloc","67108864","-threads","1",
    "-protocol_whitelist","file,pipe","-f","mov","-enable_drefs","0","-use_absolute_path","0","-i",input.toString(),
    "-map","0:a:0","-vn","-sn","-dn","-map_metadata","-1","-ac","1","-ar","16000","-t","61","-threads","1","-c:a","pcm_s16le","-f","s16le","pipe:1"),2000000);
   if(pcm.length<3200||pcm.length%2!=0||pcm.length>1936000)throw duration();
   int millis=(int)Math.round(pcm.length/32.0);var wav=ByteBuffer.allocate(44+pcm.length).order(ByteOrder.LITTLE_ENDIAN);
   wav.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36+pcm.length);
   wav.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1);
   wav.putInt(16000).putInt(32000).putShort((short)2).putShort((short)16);
   wav.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);
   return new Decoded(wav.array(),millis);
  }catch(ApiError e){throw e;}catch(NumberFormatException e){throw invalid();}
  finally{try{if(input!=null)Files.deleteIfExists(input);}finally{slots.release();}}
 }
 private byte[] run(List<String> command,int maximum)throws IOException{
  var builder=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
  var environment=builder.environment();var keep=new HashMap<String,String>();
  for(String key:List.of("SystemRoot","WINDIR","TEMP","TMP"))if(environment.containsKey(key))keep.put(key,environment.get(key));
  environment.clear();environment.putAll(keep);
  Process process;try{process=builder.start();}catch(IOException missing){throw new ApiError(503,"MEDIA_PROCESSOR_UNAVAILABLE","语音处理工具不可用");}
  var result=new FutureTask<byte[]>(()->{try(var in=process.getInputStream()){return in.readNBytes(maximum+1);}});Thread.ofVirtual().start(result);
  try{
   if(!process.waitFor(20,TimeUnit.SECONDS))throw new ApiError(503,"MEDIA_PROCESSING_TIMEOUT","语音处理超时");
   byte[] bytes=result.get(2,TimeUnit.SECONDS);if(process.exitValue()!=0||bytes.length>maximum)throw invalid();return bytes;
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw new ApiError(503,"MEDIA_PROCESSING_INTERRUPTED","语音处理已中断");}
   catch(ExecutionException|TimeoutException e){throw invalid();}
  finally{process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();result.cancel(true);}
 }
 private ApiError invalid(){return new ApiError(400,"MEDIA_TYPE_INVALID","需要可解码的单音轨 AAC-LC M4A 语音");}
 private ApiError duration(){return new ApiError(400,"MEDIA_DURATION_INVALID","语音须为 0.1 至 60 秒，编码尾帧允许最多 0.5 秒余量");}
}
