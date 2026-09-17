package com.manliao.backend.payments;
import java.util.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;
import com.manliao.backend.common.ApiError;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.http.DefaultHttpClientBuilder;
import com.wechat.pay.java.core.notification.*;
import com.wechat.pay.java.service.payments.app.AppServiceExtension;
import com.wechat.pay.java.service.payments.app.model.*;
import com.wechat.pay.java.service.payments.model.Transaction;
@Service
public class PaymentGateway {
 public record Checkout(String url,Map<String,String> payload){}
 public record Event(String provider,String eventId,String type,String merchant,String trade,long amount,String currency,Instant occurred){}
 private final PaymentSettings settings;private final ObjectMapper json;
 private volatile AppServiceExtension app;private volatile NotificationParser parser;
 public PaymentGateway(PaymentSettings settings,ObjectMapper json){this.settings=settings;this.json=json;}
 public void check(String provider){
  if("disabled".equals(provider))throw unavailable();
  if("mock".equals(provider)){
   if(!settings.mockEnabled||"production".equalsIgnoreCase(settings.environment))throw new ApiError(403,"PAYMENT_MOCK_FORBIDDEN","当前环境未开放模拟支付");return;
  }
  if(!"wechat_pay".equals(provider))throw new ApiError(400,"PAYMENT_PROVIDER_UNSUPPORTED","暂不支持该支付服务商");
  if(!settings.wechatEnabled)throw unavailable();
  for(String v:List.of(settings.appId,settings.merchantId,settings.merchantSerial,settings.privateKeyPath,settings.publicKeyPath,settings.publicKeyId,settings.apiV3Key,settings.notifyUrl))if(v.isBlank())throw unavailable();
  if(settings.apiV3Key.getBytes(StandardCharsets.UTF_8).length!=32)throw unavailable();
  try{var uri=java.net.URI.create(settings.notifyUrl);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)throw unavailable();}catch(IllegalArgumentException bad){throw unavailable();}
 }
 private synchronized AppServiceExtension app(){
  if(app==null){
   var config=new RSAPublicKeyConfig.Builder().merchantId(settings.merchantId).merchantSerialNumber(settings.merchantSerial).privateKeyFromPath(settings.privateKeyPath).publicKeyFromPath(settings.publicKeyPath).publicKeyId(settings.publicKeyId).apiV3Key(settings.apiV3Key).build();
   var http=new DefaultHttpClientBuilder().config(config).connectTimeoutMs(3000).readTimeoutMs(8000).writeTimeoutMs(5000).disableRetryOnConnectionFailure().build();
   app=new AppServiceExtension.Builder().config(config).httpClient(http).build();
  }return app;
 }
 private synchronized NotificationParser parser(){
  if(parser==null)parser=new NotificationParser(new RSAPublicKeyNotificationConfig.Builder().publicKeyFromPath(settings.publicKeyPath).publicKeyId(settings.publicKeyId).apiV3Key(settings.apiV3Key).build());return parser;
 }
 public Checkout checkout(Map<String,Object> order){
  String provider=(String)order.get("provider");check(provider);
  if(provider.equals("mock"))return new Checkout("datelive-mock://payments/"+order.get("merchant_order_no")+"?amount_fen="+order.get("amount_fen")+"&currency=CNY",null);
  try{
   var request=new PrepayRequest();request.setAppid(settings.appId);request.setMchid(settings.merchantId);request.setDescription("漫聊-"+order.get("package_name"));request.setOutTradeNo((String)order.get("merchant_order_no"));request.setNotifyUrl(settings.notifyUrl);
   request.setTimeExpire(((java.sql.Timestamp)order.get("expires_at")).toInstant().atOffset(ZoneOffset.UTC).toString());
   var amount=new Amount();amount.setTotal(Math.toIntExact(((Number)order.get("amount_fen")).longValue()));amount.setCurrency("CNY");request.setAmount(amount);
   var response=app().prepayWithRequestPayment(request);
   return new Checkout(null,Map.of("app_id",response.getAppid(),"partner_id",response.getPartnerId(),"prepay_id",response.getPrepayId(),"package_value",response.getPackageVal(),"nonce_str",response.getNonceStr(),"timestamp",response.getTimestamp(),"sign",response.getSign()));
  }catch(RuntimeException failure){throw unavailable();}
 }
 private void timestamp(String raw){
  try{long t=Long.parseLong(raw),now=Instant.now().getEpochSecond();if(t<now-300||t>now+300)throw invalid(401);}catch(NumberFormatException bad){throw invalid(401);}
 }
 public Event parse(String provider,Map<String,String> headers,byte[] body){
  check(provider);if(body.length>65536)throw new ApiError(413,"COMMON_VALIDATION_ERROR","回调报文过大");
  if(provider.equals("mock")){
   if(settings.callbackSecret.getBytes(StandardCharsets.UTF_8).length<32)throw unavailable();String time=headers.get("x-datelive-timestamp");timestamp(time);
   try{
    var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(settings.callbackSecret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));mac.update((time+".").getBytes(StandardCharsets.UTF_8));
    if(!MessageDigest.isEqual(HexFormat.of().formatHex(mac.doFinal(body)).getBytes(StandardCharsets.US_ASCII),headers.getOrDefault("x-datelive-signature","").getBytes(StandardCharsets.US_ASCII)))throw invalid(401);
   }catch(java.security.GeneralSecurityException failure){throw new IllegalStateException(failure);}
   try{var node=json.readTree(body);return valid(new Event(provider,string(node,"event_id"),string(node,"event_type"),string(node,"merchant_order_no"),node.path("provider_trade_no").isNull()?null:node.path("provider_trade_no").asString(),number(node,"amount_fen"),string(node,"currency").toUpperCase(Locale.ROOT),Instant.parse(string(node,"occurred_at"))));}catch(RuntimeException malformed){throw invalid(400);}
  }
  timestamp(headers.get("wechatpay-timestamp"));
  try{
   var request=new RequestParam.Builder().serialNumber(headers.get("wechatpay-serial")).nonce(headers.get("wechatpay-nonce")).signature(headers.get("wechatpay-signature")).timestamp(headers.get("wechatpay-timestamp")).signType(headers.get("wechatpay-signature-type")).body(new String(body,StandardCharsets.UTF_8)).build();
   var transaction=parser().parse(request,Transaction.class);var envelope=json.readTree(body);
   if(!settings.appId.equals(transaction.getAppid())||!settings.merchantId.equals(transaction.getMchid())||transaction.getTradeType()!=Transaction.TradeTypeEnum.APP)throw invalid(400);
   String type="TRANSACTION.SUCCESS".equals(string(envelope,"event_type"))&&transaction.getTradeState()==Transaction.TradeStateEnum.SUCCESS?"payment_succeeded":"unsupported";
   return valid(new Event(provider,string(envelope,"id"),type,transaction.getOutTradeNo(),transaction.getTransactionId(),transaction.getAmount().getTotal(),transaction.getAmount().getCurrency(),OffsetDateTime.parse(transaction.getSuccessTime()).toInstant()));
  }catch(com.wechat.pay.java.core.exception.ValidationException bad){throw invalid(401);}catch(RuntimeException malformed){throw invalid(400);}
 }
 private String string(JsonNode n,String key){var value=n.get(key);if(value==null||!value.isString())throw invalid(400);return value.asString();}
 private long number(JsonNode n,String key){var value=n.get(key);if(value==null||!value.isIntegralNumber()||!value.canConvertToLong())throw invalid(400);return value.asLong();}
 public Event valid(Event e){
  if(e.eventId()==null||e.eventId().isBlank()||e.eventId().length()>128||e.type()==null||e.type().isBlank()||e.type().length()>48||e.merchant()==null||e.merchant().isBlank()||e.merchant().length()>64||e.trade()!=null&&e.trade().length()>128||e.currency()==null||e.currency().length()>8||e.amount()<=0||e.occurred()==null)throw invalid(400);return e;
 }
 public static ApiError unavailable(){return new ApiError(503,"PAYMENT_PROVIDER_UNAVAILABLE","支付服务暂不可用，请检查配置或稍后重试");}
 public static ApiError invalid(int status){return new ApiError(status,"PAYMENT_CALLBACK_INVALID",status==401?"支付回调签名无效或已过期":"支付回调数据无效");}
}
