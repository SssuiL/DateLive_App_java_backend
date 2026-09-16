package com.manliao.backend.common;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
@Component
public class DatabaseRows {
 private final ObjectMapper json;
 public DatabaseRows(ObjectMapper json) { this.json=json; }
 public Map<String,Object> output(Map<String,Object> row,String... jsonFields) {
   var result=new LinkedHashMap<String,Object>();
   row.forEach((key,value)->result.put(key,value instanceof Timestamp t?t.toInstant():value));
   for(String field:jsonFields) if(result.get(field)!=null)
     result.put(field,json.readTree(result.get(field).toString()));
   return result;
 }
}
