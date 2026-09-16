package com.manliao.backend.media;
import java.io.*;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
/** Authorization happens before opening the stream, including range and thumbnail requests. */
public final class MediaResponse {
 private MediaResponse(){}
 public static ResponseEntity<InputStreamResource> build(MediaStorage.Opened opened,String range)throws IOException{
  long size=opened.size(),start=0,end=size-1;
  var headers=new org.springframework.http.HttpHeaders();headers.set("Cache-Control","private, no-store");headers.set("X-Content-Type-Options","nosniff");headers.set("Referrer-Policy","no-referrer");headers.set("Content-Type",opened.contentType());headers.set("Accept-Ranges","bytes");
  if(opened.filename()!=null)headers.setContentDisposition(org.springframework.http.ContentDisposition.attachment().filename(opened.filename(),java.nio.charset.StandardCharsets.UTF_8).build());
  if(range!=null){
   try{
    if(!range.matches("bytes=[0-9]*-[0-9]*"))throw new IllegalArgumentException();
    String[] parts=range.substring(6).split("-",-1);
    if(parts[0].isEmpty()){long suffix=Long.parseLong(parts[1]);if(suffix<=0)throw new IllegalArgumentException();start=Math.max(0,size-suffix);}
    else{start=Long.parseLong(parts[0]);if(!parts[1].isEmpty())end=Math.min(end,Long.parseLong(parts[1]));}
    if(start>=size||start>end)throw new IllegalArgumentException();
   }catch(IllegalArgumentException invalid){
    opened.stream().close();return ResponseEntity.status(416).headers(headers).header("Content-Range","bytes */"+size).contentLength(0).build();
   }
   headers.set("Content-Range","bytes "+start+"-"+end+"/"+size);
  }
  long length=end-start+1;
  try{opened.stream().skipNBytes(start);}catch(IOException e){opened.stream().close();throw e;}
  InputStream limited=new FilterInputStream(opened.stream()){
   long remaining=length;
   @Override public int read()throws IOException{if(remaining==0)return -1;int value=super.read();if(value>=0)remaining--;return value;}
   @Override public int read(byte[] bytes,int offset,int count)throws IOException{
    if(count==0)return 0;if(remaining==0)return -1;int read=in.read(bytes,offset,(int)Math.min(count,remaining));if(read>0)remaining-=read;return read;
   }
  };
  return ResponseEntity.status(range==null?200:206).headers(headers).contentLength(length).body(new InputStreamResource(limited));
 }
}
