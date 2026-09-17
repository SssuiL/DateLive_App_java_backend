package com.manliao.backend.gifts;
import java.util.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.*;
import com.manliao.backend.admin.*;
import com.manliao.backend.common.*;
@Service
public class GiftAssets {
 private final JdbcTemplate db;private final TransactionTemplate tx;private final AdminService admin;private final GiftCatalogService catalog;private final ObjectMapper json;private final String base;
 private final java.util.concurrent.Semaphore decoding=new java.util.concurrent.Semaphore(1);
 public GiftAssets(JdbcTemplate db,TransactionTemplate tx,AdminService admin,GiftCatalogService catalog,ObjectMapper json,@Value("${app.gifts.public-base-url:http://127.0.0.1:8200}") String base){this.db=db;this.tx=tx;this.admin=admin;this.catalog=catalog;this.json=json;this.base=base.replaceAll("/+$","");}
 private static ApiError invalid(){return new ApiError(422,"MEDIA_TYPE_INVALID","礼物素材格式无效");}
 private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private String type(byte[] bytes,String kind){
  if(kind.equals("animation")){
   try{
    String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();var n=json.readTree(text);
    if(!n.isObject()||!n.has("v")||!n.get("v").isString()||!n.has("layers")||!n.get("layers").isArray())throw invalid();
    for(String k:List.of("fr","ip","op"))if(!n.has(k)||!n.get(k).isNumber()||!Double.isFinite(n.get(k).asDouble()))throw invalid();
    if(n.get("fr").asDouble()<=0||n.get("op").asDouble()<=n.get("ip").asDouble())throw invalid();return "application/json";
   }catch(ApiError e){throw e;}catch(Exception e){throw invalid();}
  }
  // WebP keeps the legacy format; check the complete RIFF container and chunk bounds.
  if(bytes.length>=20&&new String(bytes,0,4,StandardCharsets.US_ASCII).equals("RIFF")&&new String(bytes,8,4,StandardCharsets.US_ASCII).equals("WEBP")){
   var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);if(Integer.toUnsignedLong(b.getInt(4))+8!=bytes.length)throw invalid();boolean frame=false;long p=12;
   while(p<bytes.length){if(p+8>bytes.length)throw invalid();String chunk=new String(bytes,(int)p,4,StandardCharsets.US_ASCII);long size=Integer.toUnsignedLong(b.getInt((int)p+4));if(size<1||p+8+size>bytes.length)throw invalid();if(Set.of("VP8 ","VP8L","ANMF").contains(chunk))frame=true;p+=8+size+(size%2);}
   if(p!=bytes.length||!frame)throw invalid();return "image/webp";
  }
  if(!decoding.tryAcquire())throw new ApiError(429,"MEDIA_BUSY","素材处理中，请稍后再试");
  try(var stream=new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))){
   var readers=javax.imageio.ImageIO.getImageReaders(stream);if(!readers.hasNext())throw invalid();var reader=readers.next();
   try{String format=reader.getFormatName().toLowerCase(Locale.ROOT);if(!Set.of("png","jpeg").contains(format))throw invalid();reader.setInput(stream,true,true);int w=reader.getWidth(0),h=reader.getHeight(0);if(w<1||h<1||(long)w*h>4000000)throw new ApiError(413,"MEDIA_FILE_TOO_LARGE","素材图片像素过多");if(reader.read(0)==null)throw invalid();return format.equals("png")?"image/png":"image/jpeg";}finally{reader.dispose();}
  }catch(ApiError e){throw e;}catch(IOException|RuntimeException e){throw invalid();}finally{decoding.release();}
 }
 public Map<String,Object> upload(AdminDtos.Principal actor,String gift,String kind,MultipartFile file,String request)throws IOException{
  admin.require(actor,"gifts.write",false);catalog.find(gift);kind=kind.strip().toLowerCase(Locale.ROOT);if(!Set.of("icon","animation").contains(kind))throw GiftCatalogService.invalid();int max=kind.equals("icon")?5*1024*1024:2*1024*1024;
  if(file.getSize()>max)throw new ApiError(413,"MEDIA_FILE_TOO_LARGE","素材文件过大");byte[] bytes;try(var in=file.getInputStream()){bytes=in.readNBytes(max+1);}if(bytes.length>max)throw new ApiError(413,"MEDIA_FILE_TOO_LARGE","素材文件过大");if(bytes.length==0)throw invalid();String mime=type(bytes,kind),checksum=hash(bytes),asset="gift_asset_"+UUID.randomUUID().toString().replace("-","");String assetType=kind;
  return tx.execute(s->{admin.require(actor,"gifts.write",true);db.queryForList("SELECT id FROM live_gift_catalog_items WHERE id=? FOR UPDATE",gift);catalog.find(gift);
   db.update("INSERT INTO live_gift_assets(id,gift_id,asset_type,content_type,content,checksum) VALUES(?,?,?,?,?,?)",asset,gift,assetType,mime,bytes,checksum);String url=base+"/gift-assets/"+asset;
   if(assetType.equals("icon"))db.update("UPDATE live_gift_catalog_items SET icon_url=?,resource_version=resource_version+1,updated_at=clock_timestamp() WHERE id=?",url,gift);
   else db.update("UPDATE live_gift_catalog_items SET animation_url=?,renderer_type='lottie',animation_checksum=?,animation_file_size=?,resource_version=resource_version+1,updated_at=clock_timestamp() WHERE id=?",url,checksum,bytes.length,gift);
   admin.audit(actor.id(),"live_gift_catalog.asset_upload","live_gift_catalog_item",gift,Map.of("asset_type",assetType,"checksum",checksum,"file_size",bytes.length),request);return catalog.find(gift);
  });
 }
 public record Asset(byte[] bytes,String type,String checksum){}
 public Asset read(String id){if(!id.matches("gift_asset_[a-f0-9]{32}"))throw new ApiError(404,"COMMON_NOT_FOUND","素材不存在");var found=db.queryForList("SELECT content,content_type,checksum FROM live_gift_assets WHERE id=?",id);if(found.isEmpty())throw new ApiError(404,"COMMON_NOT_FOUND","素材不存在");var row=found.getFirst();return new Asset((byte[])row.get("content"),(String)row.get("content_type"),(String)row.get("checksum"));}
}
