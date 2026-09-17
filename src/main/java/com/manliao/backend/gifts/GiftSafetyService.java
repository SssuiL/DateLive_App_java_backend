package com.manliao.backend.gifts;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.*;
import com.manliao.backend.common.*;
import com.manliao.backend.admin.*;
import com.manliao.backend.billing.*;
import com.manliao.backend.identity.AuthDtos.Principal;
@Service
public class GiftSafetyService {
 private final JdbcTemplate db;private final TransactionTemplate tx;private final WalletService users;private final LedgerService ledger;private final AdminService admin;private final DatabaseRows rows;private final ObjectMapper json;
 @Value("${app.gifts.single-limit:10000}") private long single;
 @Value("${app.gifts.daily-limit:20000}") private long daily;
 @Value("${app.gifts.age-verification-required:true}") private boolean ageRequired;
 @Value("${app.gifts.confirmation-threshold:500}") private long confirmation;
 public GiftSafetyService(JdbcTemplate db,TransactionTemplate tx,WalletService users,LedgerService ledger,AdminService admin,DatabaseRows rows,ObjectMapper json){this.db=db;this.tx=tx;this.users=users;this.ledger=ledger;this.admin=admin;this.rows=rows;this.json=json;}
 private void config(){if(single<1||single>100000||daily<1||daily>500000||confirmation<1)throw new IllegalStateException("Invalid gift safety configuration");}
 private void init(String user){config();db.update("INSERT INTO gift_safety_settings(user_id) VALUES(?) ON CONFLICT DO NOTHING",user);}
 public long todaySpent(String user){return db.queryForObject("""
 SELECT coalesce(sum(t.amount_coins),0) FROM billing_transactions t WHERE t.initiated_by_user_id=? AND t.transaction_type='gift_charge'
 AND t.created_at>=(date_trunc('day',now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')
 AND NOT EXISTS(SELECT 1 FROM billing_transactions reversal WHERE reversal.reversal_of_transaction_id=t.id)
 """,Long.class,user);}
 private Map<String,Object> output(String user){init(user);var out=rows.output(db.queryForMap("SELECT * FROM gift_safety_settings WHERE user_id=?",user));long used=todaySpent(user),one=Math.min(single,out.get("single_limit_coins")==null?single:((Number)out.get("single_limit_coins")).longValue()),day=Math.min(daily,out.get("daily_limit_coins")==null?daily:((Number)out.get("daily_limit_coins")).longValue());out.put("age_verification_required",ageRequired);out.put("effective_single_limit_coins",one);out.put("effective_daily_limit_coins",day);out.put("today_spent_coins",used);out.put("today_remaining_coins",Math.max(0,day-used));out.put("high_value_confirmation_threshold_coins",confirmation);return out;}
 public Map<String,Object> get(Principal actor){return tx.execute(s->{users.lock(actor);return output(actor.userId());});}
 private ApiError invalid(){return new ApiError(422,"COMMON_VALIDATION_ERROR","礼物安全设置无效或超出平台限额");}
 public Map<String,Object> update(Principal actor,JsonNode input){if(input==null||!input.isObject())throw invalid();config();var changes=new LinkedHashMap<String,Object>();
  for(String key:List.of("single_limit_coins","daily_limit_coins","reminder_threshold_coins")){var node=input.get(key);if(node==null)continue;if(node.isNull()){if(!key.equals("reminder_threshold_coins"))changes.put(key,null);continue;}long max=key.equals("single_limit_coins")?single:key.equals("daily_limit_coins")?daily:100000;if(!node.isIntegralNumber()||!node.canConvertToLong()||node.asLong()<1||node.asLong()>max)throw invalid();changes.put(key,node.asLong());}
  for(String key:List.of("reminder_enabled","ranking_consent")){var n=input.get(key);if(n==null||n.isNull())continue;if(!n.isBoolean())throw invalid();changes.put(key,n.asBoolean());}
  var ack=input.get("acknowledge_settings_offer");if(ack!=null&&!ack.isNull()&&!ack.isBoolean())throw invalid();boolean acknowledged=ack!=null&&ack.isBoolean()&&ack.asBoolean();
  return tx.execute(s->{users.lock(actor);init(actor.userId());if(!changes.isEmpty()){String fields=String.join(",",changes.keySet().stream().map(k->k+"=?").toList());var args=new ArrayList<>(changes.values());args.add(actor.userId());db.update("UPDATE gift_safety_settings SET "+fields+",updated_at=clock_timestamp() WHERE user_id=?",args.toArray());}if(acknowledged)db.update("UPDATE gift_safety_settings SET first_gift_settings_offered_at=coalesce(first_gift_settings_offered_at,clock_timestamp()),updated_at=clock_timestamp() WHERE user_id=?",actor.userId());return output(actor.userId());});
 }
 /** Internal producer; caller holds user lock and commits a rejected gift outcome before returning the HTTP error. */
 public String record(String user,String room,String type,String severity,String status,long amount,Map<String,Object> details){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Gift risk recording requires transaction");String id="gift_risk_"+UUID.randomUUID().toString().replace("-","");db.update("INSERT INTO gift_risk_events(id,user_id,room_id,event_type,severity,status,amount_coins,details,resolved_at) VALUES(?,?,?,?,?,?,?,?::jsonb,CASE WHEN ? THEN clock_timestamp() ELSE NULL END)",id,user,room,type,severity,status,amount,json.writeValueAsString(details),status.equals("confirmed"));return id;}
 public Map<String,Object> age(AdminDtos.Principal actor,String user,GiftSafetyController.Age input,String request){return tx.execute(s->{admin.require(actor,"gift_risk.write",true);ledger.lockUsers(List.of(user));init(user);db.update("UPDATE gift_safety_settings SET age_status=?,updated_at=clock_timestamp() WHERE user_id=?",input.age_status(),user);var detail=Map.<String,Object>of("age_status",input.age_status(),"reason",input.reason().strip());record(user,null,"age_status_updated","info","confirmed",0,detail);admin.audit(actor.id(),"gift_risk.age_status.update","user",user,detail,request);return output(user);});}
 public List<Map<String,Object>> list(AdminDtos.Principal actor,String status,String type,String user,int limit){admin.require(actor,"gift_risk.read",false);if(limit<1||limit>500)throw invalid();return db.queryForList("SELECT * FROM gift_risk_events WHERE (?::text IS NULL OR status=?) AND (?::text IS NULL OR event_type=?) AND (?::text IS NULL OR user_id=?) ORDER BY created_at DESC,id DESC LIMIT ?",status,status,type,type,user,user,limit).stream().map(r->rows.output(r,"details")).toList();}
 public Map<String,Object> resolve(AdminDtos.Principal actor,String id,GiftSafetyController.Resolve input,String request){return tx.execute(s->{admin.require(actor,"gift_risk.write",true);var found=db.queryForList("SELECT * FROM gift_risk_events WHERE id=? FOR UPDATE",id);if(found.isEmpty())throw new ApiError(404,"LIVE_GIFT_RISK_EVENT_NOT_FOUND","风险记录不存在");if(!"open".equals(found.getFirst().get("status")))throw new ApiError(409,"COMMON_CONFLICT","风险记录已处理");db.update("UPDATE gift_risk_events SET status=?,resolution=?,reviewer_admin_id=?,resolved_at=clock_timestamp() WHERE id=?",input.decision(),input.resolution().strip(),actor.id(),id);admin.audit(actor.id(),"gift_risk.resolve","gift_risk_event",id,Map.of("decision",input.decision(),"resolution",input.resolution().strip()),request);return rows.output(db.queryForMap("SELECT * FROM gift_risk_events WHERE id=?",id),"details");});}
}
