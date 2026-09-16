package com.manliao.backend.chat;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import com.manliao.backend.common.ApiError;
@Component
public class ChatCursor {
 private final ObjectMapper json;
 public ChatCursor(ObjectMapper json){this.json=json;}
 public record Position(String id,Timestamp time,Boolean pinned){}
 public Position decode(String value,boolean conversation){
  try{
   if(value.length()>1024)throw new IllegalArgumentException();
   var root=json.readTree(new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8));
   String timeKey=conversation?"last_active_at":"created_at";
   if(!root.path("id").isString()||!root.path(timeKey).isString()
      ||(conversation&&!root.path("pinned").isBoolean()))throw new IllegalArgumentException();
   String id=root.path("id").asString();
   if(id.isBlank()||id.length()>64)throw new IllegalArgumentException();
   var time=Timestamp.from(OffsetDateTime.parse(root.path(timeKey).asString()).toInstant());
   return new Position(id,time,conversation?root.path("pinned").asBoolean():null);
  }catch(RuntimeException e){throw new ApiError(400,"COMMON_VALIDATION_ERROR","分页游标无效",Map.of("field","cursor"));}
 }
 public String encode(Map<String,Object> row,boolean conversation){
  var payload=new LinkedHashMap<String,Object>();payload.put("id",row.get("id"));
  payload.put(conversation?"last_active_at":"created_at",row.get(conversation?"last_active_at":"created_at").toString());
  if(conversation)payload.put("pinned",row.get("pinned"));
  return Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
 }
 public int limit(String value,int max){
  try{int n=Integer.parseInt(value);if(n<1||n>max)throw new IllegalArgumentException();return n;}
  catch(IllegalArgumentException e){throw new ApiError(422,"COMMON_VALIDATION_ERROR","分页数量超出有效范围");}
 }
}
