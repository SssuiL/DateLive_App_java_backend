package com.manliao.backend.media;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import com.manliao.backend.common.ApiError;
@Component
public class MediaStorage {
 private final Path root;
 private final java.util.concurrent.Semaphore decoding=new java.util.concurrent.Semaphore(1);
 public MediaStorage(@Value("${app.media.storage-root:.data/media}") String path) throws IOException {
   root=Path.of(path).toAbsolutePath().normalize();
   Files.createDirectories(root);
   checkRoot();
 }
 private void checkRoot() throws IOException {
   for(Path p=root;p!=null;p=p.getParent())
     if(Files.isSymbolicLink(p) || !p.toRealPath().equals(p.toAbsolutePath().normalize()))
       throw new IOException("Media storage must not contain redirected directories");
 }
 private Path path(String key) throws IOException {
   checkRoot();
   if(!key.matches("media_[a-f0-9]{32}\\.(png|gif|bin|wav|mp4|thumb\\.png|display\\.png)")) throw new IOException("Invalid storage key");
   Path target=root.resolve(key).normalize();
   if(!target.getParent().equals(root) || Files.isSymbolicLink(target)) throw new IOException("Invalid storage path");
   return target;
 }
 /** Bounded, non-recursive cleanup of processor-owned files abandoned for over a day. */
 public int cleanupAbandonedTemporaryFiles()throws IOException{
  Path temporary=tempDirectory();int removed=0;var cutoff=java.time.Instant.now().minusSeconds(86400);
  try(var entries=Files.list(temporary)){
   for(Path candidate:entries.limit(1000).toList()){
    String name=candidate.getFileName().toString();
    if(!name.matches("(voice-.*\\.m4a|video-.*\\.(mov|mp4|png)|derivative-.*\\.tmp)"))continue;
    if(!candidate.toAbsolutePath().normalize().getParent().equals(temporary))throw new IOException("Invalid temporary path");
    if(Files.isRegularFile(candidate,LinkOption.NOFOLLOW_LINKS)&&Files.getLastModifiedTime(candidate,LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)){
     Files.deleteIfExists(candidate);if(++removed>=100)break;
    }
   }
  }
  return removed;
 }
 public record Stored(String key,long size,String sha256,int width,int height,int durationMs,String contentType) {}
 public Path tempDirectory() throws IOException {
   checkRoot();Path temp=root.resolve("multipart");Files.createDirectories(temp);
   if(!temp.toRealPath().equals(temp.toAbsolutePath().normalize())) throw new IOException("Invalid multipart directory");
   return temp;
 }
 public Stored save(String id,MultipartFile file) throws IOException {
   if(!decoding.tryAcquire()) throw new ApiError(429,"MEDIA_BUSY","图片处理中，请稍后再试");
   try{
    var stored="image/gif".equals(file.getContentType())?saveGif(id,file):saveImage(id,file);
    try{writeImageDerivatives(stored.key());return stored;}
    catch(IOException|RuntimeException failure){try{delete(stored.key());}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw failure;}
   }finally{decoding.release();}
 }
 private Stored saveImage(String id,MultipartFile file) throws IOException {
   if(file.isEmpty()) throw new ApiError(400,"MEDIA_TYPE_INVALID","文件不能为空");
   if(file.getSize()>8*1024*1024) throw new ApiError(413,"MEDIA_FILE_TOO_LARGE","图片不能超过 8MB");
   byte[] input;
   try(var in=file.getInputStream()){input=in.readNBytes(8*1024*1024+1);}
   if(input.length>8*1024*1024) throw new ApiError(413,"MEDIA_FILE_TOO_LARGE","图片不能超过 8MB");
   byte[] normalized;int width,height;
   try(var stream=new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
     var readers=ImageIO.getImageReaders(stream);
     if(!readers.hasNext()) throw invalid();
     var reader=readers.next();
     try {
       String format=reader.getFormatName().toLowerCase(Locale.ROOT);
       if(!Set.of("png","jpeg").contains(format)) throw invalid();
       String claimed=Optional.ofNullable(file.getContentType()).orElse("");
       if(!claimed.equals(format.equals("png")?"image/png":"image/jpeg")) throw invalid();
       reader.setInput(stream,true,true);
       width=reader.getWidth(0);height=reader.getHeight(0);
       if(width<1 || height<1 || (long)width*height>4000000) throw new ApiError(413,"MEDIA_FILE_TOO_LARGE","图片像素数超过限制");
       var decoded=reader.read(0);
       var output=new ByteArrayOutputStream();
       if(!ImageIO.write(decoded,"png",output)) throw invalid();
       normalized=output.toByteArray();
       if(normalized.length>16*1024*1024) throw new ApiError(413,"MEDIA_FILE_TOO_LARGE","转换后的图片超过限制");
     } finally {reader.dispose();}
   } catch(ApiError e){throw e;} catch(IOException|RuntimeException e){throw invalid();}
   String key=id+".png";Path target=path(key);
   var stream=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW);
   try(stream){stream.write(normalized);}
   catch(IOException e){Files.deleteIfExists(target);throw e;}
   try {
     return new Stored(key,normalized.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized)),width,height,0,"image/png");
   } catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
 }
 private Stored writeBytes(String id,String extension,byte[] bytes,int width,int height,int duration,String type)throws IOException{
  Path target=path(id+extension);var out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW);
  try(out){out.write(bytes);}catch(IOException failure){Files.deleteIfExists(target);throw failure;}
  try{return new Stored(id+extension,bytes.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),width,height,duration,type);}
  catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
 }
 private Stored saveGif(String id,MultipartFile file)throws IOException{
  if(file.isEmpty())throw invalid();if(file.getSize()>8*1024*1024)throw new ApiError(413,"MEDIA_FILE_TOO_LARGE","GIF 不能超过 8MB");
  byte[] bytes;try(var in=file.getInputStream()){bytes=in.readNBytes(8*1024*1024+1);}
  if(bytes.length>8*1024*1024)throw new ApiError(413,"MEDIA_FILE_TOO_LARGE","GIF 不能超过 8MB");
  var decoded=GifProcessor.decode(bytes);return writeBytes(id,".gif",decoded.bytes(),decoded.width(),decoded.height(),decoded.durationMs(),"image/gif");
 }
 public Stored saveFile(String id,MultipartFile file)throws IOException{
  if(file.isEmpty())throw new ApiError(400,"MEDIA_TYPE_INVALID","文件不能为空");
  if(file.getSize()>32*1024*1024)throw new ApiError(413,"MEDIA_FILE_TOO_LARGE","文件不能超过 32MB");
  byte[] bytes;try(var in=file.getInputStream()){bytes=in.readNBytes(32*1024*1024+1);}
  if(bytes.length>32*1024*1024)throw new ApiError(413,"MEDIA_FILE_TOO_LARGE","文件不能超过 32MB");
  return writeBytes(id,".bin",bytes,0,0,0,"application/octet-stream");
 }
 public Stored saveVoice(String id,MultipartFile file,VoiceProcessor processor)throws IOException{
  var decoded=processor.decode(file,tempDirectory());String key=id+".wav";Path target=path(key);
  var output=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW);
  try(output){output.write(decoded.wav());}
  catch(IOException failure){Files.deleteIfExists(target);throw failure;}
  try{return new Stored(key,decoded.wav().length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(decoded.wav())),0,0,decoded.durationMs(),"audio/wav");}
  catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
 }
 public Stored saveVideo(String id,MultipartFile file,VideoProcessor processor)throws IOException{
  var decoded=processor.decode(file,tempDirectory());String key=id+".mp4";Path target=path(key),thumb=path(id+".thumb.png");
  boolean created=false,thumbCreated=false;
  try{
   try(var out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)){created=true;out.write(decoded.mp4());}
   try(var out=Files.newOutputStream(thumb,StandardOpenOption.CREATE_NEW)){thumbCreated=true;out.write(decoded.thumbnail());}
   return new Stored(key,decoded.mp4().length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(decoded.mp4())),decoded.width(),decoded.height(),decoded.durationMs(),"video/mp4");
  }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
  catch(IOException failure){if(created)Files.deleteIfExists(target);if(thumbCreated)Files.deleteIfExists(thumb);throw failure;}
 }
 public void rebuildImageDerivatives(String key)throws IOException{
  if(!decoding.tryAcquire())throw new ApiError(429,"MEDIA_BUSY","图片处理中，请稍后重试");
  try{writeImageDerivatives(key);}finally{decoding.release();}
 }
 private void writeImageDerivatives(String key)throws IOException{
  if(!key.matches("media_[a-f0-9]{32}\\.(png|gif)"))throw new IOException("Image original required");
  byte[] source;try(var in=Files.newInputStream(path(key),LinkOption.NOFOLLOW_LINKS)){source=in.readNBytes(16*1024*1024+1);}
  if(source.length>16*1024*1024)throw new IOException("Normalized image too large");
  var images=ImageDerivatives.create(source,key.endsWith(".gif"));
  String base=key.substring(0,key.lastIndexOf('.'));
  writeDerivative(base+".thumb.png",images.thumbnail());
  if(images.display()!=null)writeDerivative(base+".display.png",images.display());
 }
 private void writeDerivative(String key,byte[] content)throws IOException{
  Path target=path(key),temporary=Files.createTempFile(tempDirectory(),"derivative-",".tmp");
  try{
   Files.write(temporary,content);
   // Existing files only belong to a pending, locked asset retry; publication follows successful completion.
   Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
  }finally{Files.deleteIfExists(temporary);}
 }
 public static String signedVariant(String signed,String requested){
  if(requested!=null&&!requested.equals(signed))throw new ApiError(400,"MEDIA_TYPE_INVALID","媒体变体与签名不一致");
  return signed;
 }
 public static String variantKey(Map<String,Object> asset,String variant){
  String key=(String)asset.get("storage_key");
  if(variant==null||variant.equals("original"))return key;
  if(Set.of("thumbnail","cover").contains(variant)&&"video".equals(asset.get("media_type"))&&key!=null&&key.endsWith(".mp4"))return key.substring(0,key.length()-4)+".thumb.png";
  if("image".equals(asset.get("media_type"))&&Boolean.TRUE.equals(asset.get("image_derivatives_ready"))&&key!=null){
   String base=key.substring(0,key.lastIndexOf('.'));
   if("thumbnail".equals(variant))return base+".thumb.png";
   if("display".equals(variant))return key.endsWith(".gif")?key:base+".display.png";
  }
  throw new ApiError(400,"MEDIA_TYPE_INVALID","该媒体没有请求的变体");
 }
 public record Opened(InputStream stream,long size,String contentType,String filename) {
  public Opened(InputStream stream,long size,String contentType){this(stream,size,contentType,null);}
 }
 public Opened open(String key) throws IOException {return open(key,null);}
 public Opened open(String key,String filename) throws IOException {
   Path target=path(key);
   if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)) throw new ApiError(404,"MEDIA_NOT_FOUND","媒体文件不存在");
   long size=Files.size(target);
   return new Opened(Files.newInputStream(target,LinkOption.NOFOLLOW_LINKS),size,key.endsWith(".wav")?"audio/wav":key.endsWith(".mp4")?"video/mp4":key.endsWith(".gif")?"image/gif":key.endsWith(".bin")?"application/octet-stream":"image/png",key.endsWith(".bin")?(filename==null||filename.isBlank()?"attachment.bin":filename):null);
 }
 public void delete(String key) throws IOException {
  var keys=new ArrayList<String>();keys.add(key);
  if(key.matches("media_[a-f0-9]{32}\\.(png|gif|mp4)")){
   String base=key.substring(0,key.lastIndexOf('.'));keys.add(base+".thumb.png");
   if(key.endsWith(".png"))keys.add(base+".display.png");
  }
  IOException failure=null;
  for(String item:keys)try{Files.deleteIfExists(path(item));}catch(IOException e){if(failure==null)failure=e;else failure.addSuppressed(e);}
  if(failure!=null)throw failure;
 }
 private ApiError invalid(){return new ApiError(400,"MEDIA_TYPE_INVALID","仅支持有效的 PNG 或 JPEG 图片");}
}
