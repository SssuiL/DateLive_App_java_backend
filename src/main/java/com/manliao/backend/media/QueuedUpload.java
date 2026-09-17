package com.manliao.backend.media;
import java.io.*;
import java.nio.file.*;
import org.springframework.web.multipart.MultipartFile;
/** A bounded payload restored from the durable queue; never a client-controlled file path. */
record QueuedUpload(byte[] bytes,String mime,String filename) implements MultipartFile {
 public String getName(){return "file";}
 public String getOriginalFilename(){return filename;}
 public String getContentType(){return mime;}
 public boolean isEmpty(){return bytes.length==0;}
 public long getSize(){return bytes.length;}
 public byte[] getBytes(){return bytes.clone();}
 public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}
 public void transferTo(File destination)throws IOException{Files.write(destination.toPath(),bytes);}
}
