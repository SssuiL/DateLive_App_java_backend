package com.manliao.backend.payments;
import java.util.*;
import java.time.*;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import com.manliao.backend.common.*;
import com.manliao.backend.identity.AuthDtos.Principal;
import com.manliao.backend.billing.*;
@Service
public class PaymentService {
 public record Package(String code,String name,long amount_fen,long coin_amount,String currency){}
 public static final List<Package> PACKAGES=List.of(new Package("coins_6","6 金币",600,6,"CNY"),new Package("coins_30","30 金币",3000,30,"CNY"),new Package("coins_68","68 金币",6800,68,"CNY"),new Package("coins_128","128 金币",12800,128,"CNY"));
 private final JdbcTemplate db;private final TransactionTemplate tx;private final WalletService wallet;private final LedgerService ledger;private final PaymentGateway gateway;private final PaymentSettings settings;private final DatabaseRows rows;private final ObjectMapper json;
 public PaymentService(JdbcTemplate db,TransactionTemplate tx,WalletService wallet,LedgerService ledger,PaymentGateway gateway,PaymentSettings settings,DatabaseRows rows,ObjectMapper json){this.db=db;this.tx=tx;this.wallet=wallet;this.ledger=ledger;this.gateway=gateway;this.settings=settings;this.rows=rows;this.json=json;}
 private static String id(String prefix){return prefix+"_"+UUID.randomUUID().toString().replace("-","");}
 private static Instant instant(Object t){return ((Timestamp)t).toInstant();}
 private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private Map<String,Object> order(String id){var found=db.queryForList("SELECT * FROM payment_orders WHERE id=?",id);if(found.isEmpty())throw new ApiError(404,"PAYMENT_ORDER_NOT_FOUND","支付订单不存在");return found.getFirst();}
 private void owner(Principal actor,Map<String,Object> order){if(!actor.userId().equals(order.get("user_id")))throw new ApiError(403,"PAYMENT_ORDER_FORBIDDEN","无权操作他人的支付订单");}
 private void closeExpired(String id){db.update("UPDATE payment_orders SET status='closed',closed_at=coalesce(closed_at,clock_timestamp()),updated_at=clock_timestamp() WHERE id=? AND status='pending' AND expires_at<=now()",id);}
 private Map<String,Object> output(Map<String,Object> order){var out=rows.output(order,"checkout_payload");for(String k:List.of("client_request_id","checkout_claim","checkout_until","checkout_error"))out.remove(k);return out;}
 private record Reservation(Map<String,Object> order,String claim){}
 public Map<String,Object> create(Principal actor,PaymentController.Create input){
  String provider=(input.provider()==null?settings.provider:input.provider()).strip().toLowerCase(Locale.ROOT);gateway.check(provider);
  var product=PACKAGES.stream().filter(p->p.code().equals(input.package_code().strip())).findFirst().orElseThrow(()->new ApiError(404,"PAYMENT_PACKAGE_NOT_FOUND","金币套餐不存在"));
  String request=input.client_request_id().strip();if(request.length()<8)throw new ApiError(422,"COMMON_VALIDATION_ERROR","支付请求号长度不足");
  var reserved=tx.execute(s->{
   wallet.lock(actor);var found=db.queryForList("SELECT * FROM payment_orders WHERE user_id=? AND client_request_id=? FOR UPDATE",actor.userId(),request);String id;
   if(found.isEmpty()){
    id=id("payment");db.update("INSERT INTO payment_orders(id,merchant_order_no,user_id,provider,client_request_id,package_code,package_name,amount_fen,coin_amount,expires_at) VALUES(?,?,?,?,?,?,?,?,?,now()+interval '30 minutes')",id,id("DL"),actor.userId(),provider,request,product.code(),product.name(),product.amount_fen(),product.coin_amount());
   }else{var existing=found.getFirst();id=(String)existing.get("id");if(!provider.equals(existing.get("provider"))||!product.code().equals(existing.get("package_code")))throw new ApiError(409,"BILLING_IDEMPOTENCY_CONFLICT","同一请求号不能用于不同套餐或服务商");}
   closeExpired(id);var current=order(id);
   if(!"pending".equals(current.get("status"))||current.get("checkout_url")!=null||current.get("checkout_payload")!=null)return new Reservation(current,null);
   if(current.get("checkout_until")!=null&&instant(current.get("checkout_until")).isAfter(Instant.now()))throw new ApiError(409,"REQUEST_IN_PROGRESS","支付订单正在创建，请稍后查询或重试");
   String claim=UUID.randomUUID().toString();db.update("UPDATE payment_orders SET checkout_claim=?,checkout_until=now()+interval '30 seconds',checkout_error=NULL WHERE id=?",claim,id);return new Reservation(current,claim);
  });
  if(reserved.claim()==null)return output(reserved.order());String id=(String)reserved.order().get("id");
  PaymentGateway.Checkout checkout;
  try{checkout=gateway.checkout(reserved.order());}catch(RuntimeException failure){db.update("UPDATE payment_orders SET checkout_claim=NULL,checkout_until=NULL,checkout_error='PAYMENT_PROVIDER_UNAVAILABLE' WHERE id=? AND checkout_claim=?",id,reserved.claim());throw PaymentGateway.unavailable();}
  return tx.execute(s->{
   wallet.lock(actor);db.queryForList("SELECT id FROM payment_orders WHERE id=? FOR UPDATE",id);
   db.update("UPDATE payment_orders SET checkout_url=?,checkout_payload=?::jsonb,checkout_claim=NULL,checkout_until=NULL,checkout_error=NULL,updated_at=clock_timestamp() WHERE id=? AND checkout_claim=?",checkout.url(),checkout.payload()==null?null:json.writeValueAsString(checkout.payload()),id,reserved.claim());return output(order(id));
  });
 }
 public List<Map<String,Object>> list(Principal actor){return tx.execute(s->{wallet.lock(actor);db.update("UPDATE payment_orders SET status='closed',closed_at=clock_timestamp(),updated_at=clock_timestamp() WHERE user_id=? AND status='pending' AND expires_at<=now()",actor.userId());return db.queryForList("SELECT * FROM payment_orders WHERE user_id=? ORDER BY created_at DESC,id DESC",actor.userId()).stream().map(this::output).toList();});}
 public Map<String,Object> get(Principal actor,String id){return tx.execute(s->{wallet.lock(actor);owner(actor,order(id));closeExpired(id);return output(order(id));});}
 public Map<String,Object> complete(Principal actor,String id,PaymentController.Complete input){
  gateway.check("mock");return tx.execute(s->{wallet.lock(actor);var order=order(id);owner(actor,order);if(!"mock".equals(order.get("provider")))throw new ApiError(400,"PAYMENT_MOCK_FORBIDDEN","仅模拟订单可模拟完成");
   if(input.event_id()!=null){
    var previous=db.queryForList("SELECT * FROM payment_callback_events WHERE provider='mock' AND provider_event_id=?",input.event_id());
    if(!previous.isEmpty()){
     var old=previous.getFirst();if(!order.get("merchant_order_no").equals(old.get("merchant_order_no"))||(input.provider_trade_no()!=null&&!input.provider_trade_no().equals(old.get("provider_trade_no"))))throw new ApiError(409,"BILLING_IDEMPOTENCY_CONFLICT","模拟回调参数不一致");
     return callback(old,true);
    }
   }
   return process(new PaymentGateway.Event("mock",input.event_id()==null?id("mock_event"):input.event_id(),"payment_succeeded",(String)order.get("merchant_order_no"),input.provider_trade_no()==null?id("mock_trade"):input.provider_trade_no(),((Number)order.get("amount_fen")).longValue(),"CNY",Instant.now()));
  });
 }
 private Map<String,Object> callback(Map<String,Object> row,boolean duplicate){var out=rows.output(row);out.remove("payload_hash");out.put("duplicate",duplicate);return out;}
 private Map<String,Object> callback(String id,boolean duplicate){return callback(db.queryForMap("SELECT * FROM payment_callback_events WHERE id=?",id),duplicate);}
 private Map<String,Object> finish(String id,String status,String reason){db.update("UPDATE payment_callback_events SET processing_status=?,error_message=?,processed_at=clock_timestamp() WHERE id=?",status,reason,id);return callback(id,false);}
 public Map<String,Object> receive(String provider,Map<String,String> headers,byte[] body){
  gateway.check(provider);PaymentGateway.Event event;
  try{event=gateway.parse(provider,headers,body);}catch(ApiError bad){if(bad.status()==400||bad.status()==401)invalid(provider,body,bad.status()!=401);throw bad;}
  return process(event);
 }
 private void invalid(String provider,byte[] body,boolean signed){
  String hash=hash(body);db.update("INSERT INTO payment_callback_events(id,provider,provider_event_id,event_type,merchant_order_no,amount_fen,currency,signature_valid,processing_status,error_message,payload_hash,occurred_at,processed_at) VALUES(?,?,?,'invalid_callback','',0,'',?,'rejected','回调签名或格式无效',?,now(),now()) ON CONFLICT(provider,provider_event_id) DO NOTHING",id("callback"),provider,"invalid:"+hash,signed,hash);
 }
 private Map<String,Object> process(PaymentGateway.Event event){
  gateway.valid(event);String fingerprint=hash(json.writeValueAsString(List.of(event.provider(),event.eventId(),event.type(),event.merchant(),Objects.toString(event.trade(),""),event.amount(),event.currency(),event.occurred().toString())).getBytes(StandardCharsets.UTF_8));
  return tx.execute(s->{
   var found=db.queryForList("SELECT * FROM payment_orders WHERE provider=? AND merchant_order_no=?",event.provider(),event.merchant());
   if(!found.isEmpty())db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",found.getFirst().get("user_id"));
   db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","payment-event:"+event.provider()+":"+event.eventId());
   var previous=db.queryForList("SELECT * FROM payment_callback_events WHERE provider=? AND provider_event_id=?",event.provider(),event.eventId());
   if(!previous.isEmpty()){
    if(!fingerprint.equals(previous.getFirst().get("payload_hash")))throw new ApiError(409,"BILLING_IDEMPOTENCY_CONFLICT","同一回调事件包含不同业务参数");return callback(previous.getFirst(),true);
   }
   Map<String,Object> order=null;if(!found.isEmpty()){db.queryForList("SELECT id FROM payment_orders WHERE id=? FOR UPDATE",found.getFirst().get("id"));order=order((String)found.getFirst().get("id"));}
   String id=id("callback");db.update("INSERT INTO payment_callback_events(id,provider,provider_event_id,event_type,merchant_order_no,payment_order_id,provider_trade_no,amount_fen,currency,signature_valid,processing_status,payload_hash,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,true,'received',?,?)",id,event.provider(),event.eventId(),event.type(),event.merchant(),order==null?null:order.get("id"),event.trade(),event.amount(),event.currency(),fingerprint,Timestamp.from(event.occurred()));
   if(order==null)return finish(id,"rejected","本地支付订单不存在");
   if(((Number)order.get("amount_fen")).longValue()!=event.amount()||!order.get("currency").equals(event.currency()))return finish(id,"rejected","回调金额或币种不匹配");
   if(event.trade()==null||event.trade().isBlank())return finish(id,"rejected","支付服务商交易号不能为空");
   if(!event.type().equals("payment_succeeded"))return finish(id,"rejected","不支持的支付事件类型");
   if(event.occurred().isAfter(Instant.now().plusSeconds(300))||event.occurred().isBefore(instant(order.get("created_at")).minusSeconds(5)))return finish(id,"rejected","付款时间不合理");
   if("paid".equals(order.get("status")))return finish(id,"ignored","订单已支付，未重复发行金币");
   if(event.occurred().isAfter(instant(order.get("expires_at")))){db.update("UPDATE payment_orders SET status='closed',closed_at=coalesce(closed_at,clock_timestamp()) WHERE id=?",order.get("id"));return finish(id,"rejected","支付订单已过期");}
   db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","payment-trade:"+event.provider()+":"+event.trade());
   if(db.queryForObject("SELECT count(*) FROM payment_orders WHERE provider=? AND provider_trade_no=? AND id<>?",Integer.class,event.provider(),event.trade(),order.get("id"))>0)return finish(id,"rejected","支付服务商交易号已被使用");
   String user=(String)order.get("user_id"),orderId=(String)order.get("id");long coins=((Number)order.get("coin_amount")).longValue();
   var result=ledger.postIncomingPayment(new LedgerService.Posting("payment-order:"+orderId,"payment_recharge",coins,List.of(LedgerService.spendable(user,coins,"payment_coin_credit"),LedgerService.platform("platform_coin_issuance",-coins,"payment_coin_issuance")),user,"payment_order",orderId,Map.of("provider",event.provider(),"merchant_order_no",event.merchant(),"provider_trade_no",event.trade(),"amount_fen",event.amount(),"currency",event.currency(),"package_code",order.get("package_code")),null,"recharge","支付充值："+order.get("package_name")));
   db.update("UPDATE payment_orders SET status='paid',provider_trade_no=?,billing_transaction_id=?,paid_at=?,updated_at=clock_timestamp() WHERE id=?",event.trade(),result.id(),Timestamp.from(event.occurred()),orderId);return finish(id,"processed",null);
  });
 }
 public List<Map<String,Object>> adminOrders(String user,String provider,String status,String merchant,int limit){return query("payment_orders",new String[]{"user_id","provider","status","merchant_order_no"},new String[]{user,provider,status,merchant},limit).stream().map(this::output).toList();}
 public List<Map<String,Object>> adminCallbacks(String provider,String status,String merchant,int limit){return query("payment_callback_events",new String[]{"provider","processing_status","merchant_order_no"},new String[]{provider,status,merchant},limit).stream().map(r->callback(r,false)).toList();}
 private List<Map<String,Object>> query(String table,String[] columns,String[] values,int limit){
  if(limit<1||limit>500)throw new ApiError(422,"COMMON_VALIDATION_ERROR","分页数量超出范围");var args=new ArrayList<Object>();String where="true";for(int i=0;i<columns.length;i++)if(values[i]!=null){where+=" AND "+columns[i]+"=?";args.add(values[i]);}args.add(limit);return db.queryForList("SELECT * FROM "+table+" WHERE "+where+" ORDER BY created_at DESC,id DESC LIMIT ?",args.toArray());
 }
}
