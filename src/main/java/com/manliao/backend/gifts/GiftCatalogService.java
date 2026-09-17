package com.manliao.backend.gifts;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import com.manliao.backend.admin.*;
import com.manliao.backend.common.*;
import tools.jackson.databind.JsonNode;
@Service
public class GiftCatalogService {
 private final JdbcTemplate db;private final TransactionTemplate tx;private final AdminService admin;private final DatabaseRows rows;
 public GiftCatalogService(JdbcTemplate db,TransactionTemplate tx,AdminService admin,DatabaseRows rows){this.db=db;this.tx=tx;this.admin=admin;this.rows=rows;}
 private static final Set<String> FIELDS=Set.of("name","price_coins","icon_url","animation_url","renderer_type","min_client_version","presentation_tier","animation_duration_ms","sort_order","status");
 static ApiError invalid(){return new ApiError(422,"COMMON_VALIDATION_ERROR","礼物参数无效");}
 public Map<String,Object> find(String id){var list=db.queryForList("SELECT * FROM live_gift_catalog_items WHERE id=?",id);if(list.isEmpty())throw new ApiError(404,"COMMON_NOT_FOUND","礼物不存在");return rows.output(list.getFirst());}
 public List<Map<String,Object>> catalog(){return db.queryForList("SELECT code,name,price_coins,icon_url,animation_url,renderer_type,animation_checksum,animation_file_size,min_client_version,presentation_tier,animation_duration_ms,resource_version FROM live_gift_catalog_items WHERE status='active' ORDER BY sort_order,created_at,id");}
 public List<Map<String,Object>> list(AdminDtos.Principal actor){admin.require(actor,"gifts.read",false);return db.queryForList("SELECT * FROM live_gift_catalog_items ORDER BY sort_order,created_at,id").stream().map(rows::output).toList();}
 private Map<String,Object> values(JsonNode input,boolean create){
  if(input==null||!input.isObject())throw invalid();var result=new LinkedHashMap<String,Object>();
  if(create){result.put("renderer_type","static");result.put("presentation_tier","compact");result.put("animation_duration_ms",900);result.put("sort_order",0);result.put("status","active");}
  for(String field:FIELDS){var value=input.get(field);if(value==null)continue;
   boolean nullable=Set.of("icon_url","animation_url","min_client_version").contains(field);
   if(value.isNull()){if(!nullable)throw invalid();result.put(field,null);continue;}
   if(Set.of("price_coins","sort_order","animation_duration_ms").contains(field)){
    if(!value.isIntegralNumber()||!value.canConvertToLong())throw invalid();long n=value.asLong();long min=field.equals("price_coins")?1:field.equals("sort_order")?0:300,max=field.equals("price_coins")?1000000:field.equals("sort_order")?100000:10000;if(n<min||n>max)throw invalid();result.put(field,n);continue;
   }
   if(!value.isString())throw invalid();String v=value.asString();int max=field.endsWith("_url")?500:field.equals("name")?64:32;if(v.length()>max||(!nullable&&v.isBlank()))throw invalid();
   if(field.equals("status")&&!Set.of("active","inactive").contains(v)||field.equals("renderer_type")&&!Set.of("static","lottie","alpha_video").contains(v)||field.equals("presentation_tier")&&!Set.of("compact","spotlight","fullscreen").contains(v))throw invalid();
   if(field.endsWith("_url")&&!v.isBlank()){try{var uri=java.net.URI.create(v);if(!Set.of("https","http").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw invalid();}catch(IllegalArgumentException e){throw invalid();}}
   result.put(field,v);
  }
  if(create){var code=input.get("code");if(code==null||!code.isString()||!code.asString().matches("[a-z0-9_]{2,32}")||!result.containsKey("name")||!result.containsKey("price_coins"))throw invalid();result.put("code",code.asString());}
  return result;
 }
 public Map<String,Object> create(AdminDtos.Principal actor,JsonNode input,String request){
  var data=values(input,true);return tx.execute(s->{admin.require(actor,"gifts.write",true);String id="gift_catalog_"+UUID.randomUUID().toString().replace("-","");data.put("id",id);
   String columns=String.join(",",data.keySet()),params=String.join(",",Collections.nCopies(data.size(),"?"));
   if(db.update("INSERT INTO live_gift_catalog_items("+columns+") VALUES("+params+") ON CONFLICT(code) DO NOTHING",data.values().toArray())==0)throw new ApiError(409,"COMMON_CONFLICT","礼物编码已经存在");
   admin.audit(actor.id(),"live_gift_catalog.create","live_gift_catalog_item",id,data,request);return find(id);
  });
 }
 public Map<String,Object> update(AdminDtos.Principal actor,String id,JsonNode input,String request){
  var data=values(input,false);return tx.execute(s->{admin.require(actor,"gifts.write",true);db.queryForList("SELECT id FROM live_gift_catalog_items WHERE id=? FOR UPDATE",id);var old=find(id);
   if(!data.isEmpty()){
    // A changed external animation has no trusted checksum until uploaded locally.
    if(data.containsKey("animation_url")&&!Objects.equals(data.get("animation_url"),old.get("animation_url"))){data.put("animation_checksum",null);data.put("animation_file_size",null);}
    var args=new ArrayList<>(data.values());args.add(id);String fields=String.join(",",data.keySet().stream().map(k->k+"=?").toList());db.update("UPDATE live_gift_catalog_items SET "+fields+",resource_version=resource_version+1,updated_at=clock_timestamp() WHERE id=?",args.toArray());
   }
   admin.audit(actor.id(),"live_gift_catalog.update","live_gift_catalog_item",id,data,request);return find(id);
  });
 }
}
