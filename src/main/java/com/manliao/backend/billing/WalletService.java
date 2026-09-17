package com.manliao.backend.billing;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.manliao.backend.common.*;
import com.manliao.backend.identity.AuthDtos.Principal;
@Service
public class WalletService {
 private final JdbcTemplate db;private final TransactionTemplate tx;private final LedgerService ledger;private final DatabaseRows rows;
 @Value("${app.billing.dev-recharge-enabled:false}") private boolean development;
 @Value("${app.environment:production}") private String environment;
 private static final Map<String,String> TITLES=Map.of("opening_balance","期初金币余额","recharge","开发充值","payment_recharge","金币充值","gift_charge","直播礼物","gift_refund","礼物退款","call_charge","通话消费","adjustment","余额调整");
 public WalletService(JdbcTemplate db,TransactionTemplate tx,LedgerService ledger,DatabaseRows rows){this.db=db;this.tx=tx;this.ledger=ledger;this.rows=rows;}
 public void lock(Principal actor){
  ledger.lockUsers(List.of(actor.userId()));
  if(db.queryForObject("SELECT count(*) FROM refresh_tokens WHERE id=? AND user_id=? AND revoked_at IS NULL AND expires_at>now()",Integer.class,actor.sessionId(),actor.userId())==0)throw new ApiError(401,"AUTH_INVALID_TOKEN","登录会话已失效");
 }
 public Map<String,Object> get(Principal actor){return tx.execute(s->{lock(actor);return rows.output(ledger.ensureWallet(actor.userId()));});}
 public Map<String,Object> recharge(Principal actor,WalletController.Recharge input){
  if(!development||"production".equalsIgnoreCase(environment))throw new ApiError(403,"PAYMENT_MOCK_FORBIDDEN","当前环境未开放模拟充值");
  return tx.execute(s->{
   lock(actor);String request=input.client_request_id()==null?UUID.randomUUID().toString():input.client_request_id().strip();
   if(request.length()<8)throw new ApiError(422,"COMMON_VALIDATION_ERROR","请求号长度不足");
   String remark=input.remark()==null?"开发阶段模拟充值":input.remark();
   ledger.post(new LedgerService.Posting("dev-recharge:"+actor.userId()+":"+request,"recharge",input.amount(),
    List.of(LedgerService.spendable(actor.userId(),input.amount(),"user_recharge"),LedgerService.platform("platform_coin_issuance",-input.amount(),"coin_issuance")),
    actor.userId(),"dev_recharge",request,Map.of("remark",remark),null,"recharge",remark));
   return rows.output(ledger.ensureWallet(actor.userId()));
  });
 }
 public List<Map<String,Object>> transactions(Principal actor){return tx.execute(s->{lock(actor);return db.queryForList("SELECT id,user_id,type,amount,balance_after,reference_type,reference_id,remark,created_at FROM wallet_transactions WHERE user_id=? ORDER BY created_at DESC,id DESC",actor.userId()).stream().map(r->rows.output(r)).toList();});}
 public List<Map<String,Object>> ledger(Principal actor,String type){return tx.execute(s->{lock(actor);ledger.ensureWallet(actor.userId());return transactions(actor.userId(),type,null,null,null,100,actor.userId());});}
 public List<Map<String,Object>> transactions(String user,String type,String referenceType,String referenceId,String key,int limit,String viewer){
  if(limit<1||limit>500)throw new ApiError(422,"COMMON_VALIDATION_ERROR","分页数量超出范围");
  var args=new ArrayList<Object>();String where="true";
  if(user!=null){where+=" AND (t.initiated_by_user_id=? OR EXISTS(SELECT 1 FROM coin_ledger_entries e JOIN coin_accounts a ON a.id=e.account_id WHERE e.transaction_id=t.id AND a.user_id=?))";args.add(user);args.add(user);}
  String[] columns={"transaction_type","reference_type","reference_id","idempotency_key"},values={type,referenceType,referenceId,key};
  for(int i=0;i<columns.length;i++)if(values[i]!=null){where+=" AND t."+columns[i]+"=?";args.add(values[i]);}args.add(limit);
  return db.queryForList("SELECT t.* FROM billing_transactions t WHERE "+where+" ORDER BY t.created_at DESC,t.id DESC LIMIT ?",args.toArray()).stream().map(t->transaction(t,viewer)).toList();
 }
 private Map<String,Object> transaction(Map<String,Object> record,String viewer){
  var result=rows.output(record,"metadata");result.remove("payload_hash");
  var args=new ArrayList<Object>(List.of(record.get("id")));String clause="";if(viewer!=null){clause=" AND a.user_id=?";args.add(viewer);}
  result.put("entries",db.queryForList("SELECT e.id,e.account_id,a.owner_type,a.owner_id,a.account_type,e.entry_role,e.amount,e.balance_after,e.created_at FROM coin_ledger_entries e JOIN coin_accounts a ON a.id=e.account_id WHERE e.transaction_id=?"+clause+" ORDER BY e.created_at,e.id",args.toArray()).stream().map(r->rows.output(r)).toList());return result;
 }
 public List<Map<String,Object>> accounts(String owner,String type){
  var args=new ArrayList<Object>();String where="true";
  if(owner!=null){where+=" AND owner_id=?";args.add(owner);}if(type!=null){where+=" AND account_type=?";args.add(type);}
  return db.queryForList("SELECT id,owner_type,owner_id,account_type,balance,status,updated_at FROM coin_accounts WHERE "+where+" ORDER BY updated_at DESC,id DESC",args.toArray()).stream().map(r->rows.output(r)).toList();
 }
 public List<Map<String,Object>> bills(Principal actor,String direction,String type,int limit){
  if(!Set.of("all","income","expense").contains(direction)||limit<1||limit>500)throw new ApiError(422,"COMMON_VALIDATION_ERROR","账单筛选无效");
  return tx.execute(s->{lock(actor);ledger.ensureWallet(actor.userId());var args=new ArrayList<Object>(List.of(actor.userId()));String where="";
   if(!direction.equals("all"))where+=" AND e.amount"+(direction.equals("income")?">0":"<0");if(type!=null){where+=" AND t.transaction_type=?";args.add(type);}args.add(limit);
   return db.queryForList("SELECT t.id AS transaction_id,t.transaction_type,e.amount,e.balance_after,t.status,t.reference_type,t.reference_id,t.reversal_of_transaction_id,t.created_at FROM billing_transactions t JOIN coin_ledger_entries e ON e.transaction_id=t.id JOIN coin_accounts a ON a.id=e.account_id WHERE a.user_id=? AND a.account_type='user_spendable'"+where+" ORDER BY t.created_at DESC,t.id DESC,e.id DESC LIMIT ?",args.toArray()).stream().map(r->{var out=rows.output(r);out.put("title",TITLES.getOrDefault(r.get("transaction_type"),"金币变动"));out.put("direction",((Number)r.get("amount")).longValue()>0?"income":"expense");return out;}).toList();
  });
 }
 public Map<String,Object> summary(Principal actor,int days){
  if(days<1||days>365)throw new ApiError(422,"COMMON_VALIDATION_ERROR","统计天数超出范围");
  return tx.execute(s->{lock(actor);var wallet=ledger.ensureWallet(actor.userId());
   var out=db.queryForMap("""
    SELECT coalesce(sum(e.amount) FILTER(WHERE e.amount>0),0) AS income_coins,
     coalesce(sum(-e.amount) FILTER(WHERE e.amount<0),0) AS spent_coins,
     coalesce(sum(e.amount) FILTER(WHERE e.amount>0 AND t.transaction_type IN('recharge','payment_recharge')),0) AS recharge_coins,
     coalesce(sum(-e.amount) FILTER(WHERE e.amount<0 AND t.transaction_type='gift_charge'),0) AS gift_spent_coins,
     coalesce(sum(-e.amount) FILTER(WHERE e.amount<0 AND t.transaction_type='call_charge'),0) AS call_spent_coins,
     coalesce(sum(e.amount) FILTER(WHERE e.amount>0 AND t.transaction_type LIKE '%refund'),0) AS refunded_coins,count(*) AS bill_count
    FROM billing_transactions t JOIN coin_ledger_entries e ON e.transaction_id=t.id JOIN coin_accounts a ON a.id=e.account_id
    WHERE a.user_id=? AND a.account_type='user_spendable' AND t.created_at>=now()-(? * interval '1 day')
    """,actor.userId(),days);out.put("balance",wallet.get("balance"));out.put("days",days);return out;
  });
 }
}
