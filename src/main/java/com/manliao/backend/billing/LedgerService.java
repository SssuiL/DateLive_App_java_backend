package com.manliao.backend.billing;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import com.manliao.backend.common.ApiError;
/** Internal balanced ledger. Callers must acquire user rows in ascending order before other business locks. */
@Service
public class LedgerService {
 public record Leg(String ownerType,String ownerId,String accountType,long amount,String role){}
 public record Posting(String key,String type,long amount,List<Leg> legs,String actor,String referenceType,String referenceId,Map<String,Object> metadata,String reversal,String legacyType,String remark){}
 public record Result(String id,boolean created){}
 private final JdbcTemplate db;private final TransactionTemplate tx;private final ObjectMapper json;
 public LedgerService(JdbcTemplate db,TransactionTemplate tx,ObjectMapper json){this.db=db;this.tx=tx;this.json=json;}
 public static Leg spendable(String user,long amount,String role){return new Leg("user",user,"user_spendable",amount,role);}
 public static Leg platform(String account,long amount,String role){return new Leg("platform","datelive-platform",account,amount,role);}
 private static String id(String prefix){return prefix+"_"+UUID.randomUUID().toString().replace("-","");}
 public void lockUsers(Collection<String> users){lockUsers(users,true);}
 private void lockUsers(Collection<String> users,boolean active){
  for(String user:new TreeSet<>(users)){
   var row=db.queryForList("SELECT status FROM users WHERE id=? FOR UPDATE",user);
   if(row.isEmpty())throw new ApiError(404,"USER_NOT_FOUND","用户不存在");
   if(active&&!"active".equals(row.getFirst().get("status")))throw new ApiError(403,"COMMON_FORBIDDEN","当前账号不可进行金币交易");
  }
 }
 public Map<String,Object> ensureWallet(String user){
  db.update("INSERT INTO wallets(user_id) VALUES(?) ON CONFLICT DO NOTHING",user);
  var wallet=db.queryForMap("SELECT * FROM wallets WHERE user_id=?",user);
  // Java starts with an empty isolated database. Never silently import an unexplained opening balance.
  var accounts=db.queryForList("SELECT id,balance FROM coin_accounts WHERE user_id=? AND account_type='user_spendable'",user);
  if(accounts.isEmpty()&&((Number)wallet.get("balance")).longValue()!=0)throw new ApiError(409,"BILLING_RECONCILIATION_REQUIRED","钱包期初余额需要对账");
  if(!accounts.isEmpty()&&((Number)accounts.getFirst().get("balance")).longValue()!=((Number)wallet.get("balance")).longValue())throw new ApiError(409,"BILLING_RECONCILIATION_REQUIRED","钱包与账本余额不一致");
  ensureAccount(spendable(user,1,"bootstrap"));return wallet;
 }
 private String accountKey(Leg leg){return leg.ownerType()+":"+leg.ownerId()+":"+leg.accountType();}
 private Map<String,Object> ensureAccount(Leg leg){
  db.update("INSERT INTO coin_accounts(id,owner_type,owner_id,user_id,account_type) VALUES(?,?,?,?,?) ON CONFLICT(owner_type,owner_id,account_type) DO NOTHING",id("coin"),leg.ownerType(),leg.ownerId(),leg.ownerType().equals("user")?leg.ownerId():null,leg.accountType());
  return db.queryForMap("SELECT * FROM coin_accounts WHERE owner_type=? AND owner_id=? AND account_type=? FOR UPDATE",leg.ownerType(),leg.ownerId(),leg.accountType());
 }
 private Object canonical(Object value){
  if(value instanceof Map<?,?> map){var sorted=new TreeMap<String,Object>();map.forEach((k,v)->sorted.put(k.toString(),canonical(v)));return sorted;}
  if(value instanceof Collection<?> list)return list.stream().map(this::canonical).toList();return value;
 }
 private String hash(Posting p){
  var value=new TreeMap<String,Object>();value.put("type",p.type());value.put("amount",p.amount());value.put("legs",p.legs());value.put("actor",p.actor());value.put("reference_type",p.referenceType());value.put("reference_id",p.referenceId());value.put("metadata",canonical(p.metadata()==null?Map.of():p.metadata()));value.put("reversal",p.reversal());value.put("legacy_type",p.legacyType());value.put("remark",p.remark());
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
 }
 private void validate(Posting p){
  if(p.key()==null||p.key().isBlank()||p.key().length()>160||p.type()==null||p.type().isBlank()||p.amount()<=0||p.legs()==null||p.legs().size()<2)throw new IllegalArgumentException("Invalid ledger posting");
  BigInteger total=BigInteger.ZERO,positive=BigInteger.ZERO;
  for(var leg:p.legs()){
   if(!Set.of("user","platform").contains(leg.ownerType())||leg.ownerId()==null||leg.accountType()==null||leg.role()==null||leg.amount()==0)throw new IllegalArgumentException("Invalid ledger leg");
   total=total.add(BigInteger.valueOf(leg.amount()));if(leg.amount()>0)positive=positive.add(BigInteger.valueOf(leg.amount()));
  }
  if(total.signum()!=0||!positive.equals(BigInteger.valueOf(p.amount())))throw new ApiError(500,"BILLING_UNBALANCED_TRANSACTION","账本分录不平衡");
 }
 public Result post(Posting p){return post(p,false);}
 /** Verified incoming payment may credit a retained account; it never enables spending or restores an account. */
 public Result postIncomingPayment(Posting p){
  if(!"payment_recharge".equals(p.type())||!"payment_order".equals(p.referenceType())||p.legs().stream().anyMatch(l->l.ownerType().equals("user")?(!l.accountType().equals("user_spendable")||l.amount()<=0):(!l.accountType().equals("platform_coin_issuance")||l.amount()>=0)))throw new IllegalArgumentException("Not an incoming payment credit");
  return post(p,true);
 }
 private Result post(Posting p,boolean incoming){
  validate(p);String hash=hash(p);return tx.execute(status->{
   var users=new TreeSet<String>();for(var leg:p.legs())if(leg.ownerType().equals("user"))users.add(leg.ownerId());if(p.actor()!=null)users.add(p.actor());lockUsers(users,!incoming);
   db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","billing:"+p.key().strip());
   var existing=db.queryForList("SELECT id,payload_hash FROM billing_transactions WHERE idempotency_key=?",p.key().strip());
   if(!existing.isEmpty()){
    if(!hash.equals(existing.getFirst().get("payload_hash")))throw new ApiError(409,"BILLING_IDEMPOTENCY_CONFLICT","同一请求号不能用于不同交易参数");
    return new Result((String)existing.getFirst().get("id"),false);
   }
   for(String user:users)ensureWallet(user);
   var unique=new TreeMap<String,Leg>();for(var leg:p.legs())unique.put(accountKey(leg),leg);
   var accounts=new HashMap<String,Map<String,Object>>();unique.forEach((key,leg)->accounts.put(key,ensureAccount(leg)));
   for(var account:accounts.values())if(!"active".equals(account.get("status")))throw new ApiError(403,"BILLING_ACCOUNT_FROZEN","金币账户已冻结");
   String id=id("billing");
   db.update("INSERT INTO billing_transactions(id,idempotency_key,transaction_type,initiated_by_user_id,reference_type,reference_id,amount_coins,payload_hash,reversal_of_transaction_id,metadata) VALUES(?,?,?,?,?,?,?,?,?,?::jsonb)",id,p.key().strip(),p.type(),p.actor(),p.referenceType(),p.referenceId(),p.amount(),hash,p.reversal(),json.writeValueAsString(p.metadata()==null?Map.of():p.metadata()));
   for(var leg:p.legs()){
    var account=accounts.get(accountKey(leg));long next;
    try{next=Math.addExact(((Number)account.get("balance")).longValue(),leg.amount());}catch(ArithmeticException overflow){throw new ApiError(409,"BILLING_AMOUNT_OVERFLOW","金币余额超出范围");}
    if(leg.accountType().equals("user_spendable")&&next<0)throw new ApiError(403,"WALLET_INSUFFICIENT_BALANCE","金币余额不足");
    db.update("UPDATE coin_accounts SET balance=?,updated_at=clock_timestamp() WHERE id=?",next,account.get("id"));account.put("balance",next);
    db.update("INSERT INTO coin_ledger_entries(id,transaction_id,account_id,entry_role,amount,balance_after) VALUES(?,?,?,?,?,?)",id("ledger"),id,account.get("id"),leg.role(),leg.amount(),next);
    if(leg.accountType().equals("user_spendable")){
     db.update("UPDATE wallets SET balance=?,updated_at=clock_timestamp() WHERE user_id=?",next,leg.ownerId());
     if(p.legacyType()!=null)db.update("INSERT INTO wallet_transactions(id,user_id,type,amount,balance_after,reference_type,reference_id,remark,billing_transaction_id) VALUES(?,?,?,?,?,?,?,?,?)",id("wtx"),leg.ownerId(),p.legacyType(),leg.amount(),next,p.referenceType(),p.referenceId(),p.remark(),id);
    }
   }
   return new Result(id,true);
  });
 }
}
