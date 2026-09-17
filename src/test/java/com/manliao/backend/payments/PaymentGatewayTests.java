package com.manliao.backend.payments;
import java.util.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;
import com.manliao.backend.common.ApiError;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.http.DefaultHttpClientBuilder;
import com.wechat.pay.java.service.payments.app.AppServiceExtension;
import static org.assertj.core.api.Assertions.*;
class PaymentGatewayTests {
 static KeyPair merchant,platform;static Path root;static final String KEY="01234567890123456789012345678901";
 PaymentSettings settings;PaymentGateway gateway;final ObjectMapper json=new ObjectMapper();
 @BeforeAll static void keys()throws Exception{
  var gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);merchant=gen.generateKeyPair();platform=gen.generateKeyPair();root=Files.createTempDirectory(Path.of("target"),"payment-crypto-");
  Files.writeString(root.resolve("merchant.pem"),pem("PRIVATE KEY",merchant.getPrivate().getEncoded()));Files.writeString(root.resolve("platform.pem"),pem("PUBLIC KEY",platform.getPublic().getEncoded()));
 }
 static String pem(String name,byte[] data){return "-----BEGIN "+name+"-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(data)+"\n-----END "+name+"-----\n";}
 @BeforeEach void setup(){
  settings=new PaymentSettings();settings.environment="test";settings.provider="wechat_pay";settings.mockEnabled=false;settings.wechatEnabled=true;settings.callbackSecret="";
  settings.appId="wx-synthetic-app";settings.merchantId="synthetic-merchant";settings.merchantSerial="synthetic-serial";settings.privateKeyPath=root.resolve("merchant.pem").toString();settings.publicKeyPath=root.resolve("platform.pem").toString();settings.publicKeyId="PUB_KEY_ID_SYNTHETIC";settings.apiV3Key=KEY;settings.notifyUrl="https://merchant.example.test/callbacks/payments/wechat_pay";gateway=new PaymentGateway(settings,json);
 }
 String sign(PrivateKey key,String value)throws Exception{var s=Signature.getInstance("SHA256withRSA");s.initSign(key);s.update(value.getBytes(StandardCharsets.UTF_8));return Base64.getEncoder().encodeToString(s.sign());}
 boolean verify(PublicKey key,String value,String signature)throws Exception{var s=Signature.getInstance("SHA256withRSA");s.initVerify(key);s.update(value.getBytes(StandardCharsets.UTF_8));return s.verify(Base64.getDecoder().decode(signature));}
 Map<String,Object> transaction(){return new LinkedHashMap<>(Map.of("appid",settings.appId,"mchid",settings.merchantId,"out_trade_no","DL_fixture","transaction_id","wx_fixture_trade","trade_state","SUCCESS","trade_type","APP","success_time",Instant.now().toString(),"amount",Map.of("total",3000,"currency","CNY")));}
 byte[] envelope(Map<String,Object> transaction)throws Exception{
  var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8),"AES"),new GCMParameterSpec(128,"0123456789ab".getBytes(StandardCharsets.UTF_8)));cipher.updateAAD("transaction".getBytes(StandardCharsets.UTF_8));
  String encrypted=Base64.getEncoder().encodeToString(cipher.doFinal(json.writeValueAsBytes(transaction)));
  return json.writeValueAsBytes(Map.of("id","notification-fixture","event_type","TRANSACTION.SUCCESS","create_time",Instant.now().toString(),"resource_type","encrypt-resource","resource",Map.of("algorithm","AEAD_AES_256_GCM","associated_data","transaction","nonce","0123456789ab","original_type","transaction","ciphertext",encrypted)));
 }
 Map<String,String> headers(byte[] body,long time)throws Exception{
  String timestamp=Long.toString(time),nonce="fixture-notification-nonce";
  return new HashMap<>(Map.of("wechatpay-timestamp",timestamp,"wechatpay-nonce",nonce,"wechatpay-serial",settings.publicKeyId,"wechatpay-signature",sign(platform.getPrivate(),timestamp+"\n"+nonce+"\n"+new String(body,StandardCharsets.UTF_8)+"\n")));
 }
 void error(int status,org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOfSatisfying(ApiError.class,e->assertThat(e.status()).isEqualTo(status));}
 @Test void sdkVerifiesSignatureAndDecryptsRealAesGcmNotification()throws Exception{
  byte[] body=envelope(transaction());var e=gateway.parse("wechat_pay",headers(body,Instant.now().getEpochSecond()),body);assertThat(e.amount()).isEqualTo(3000);assertThat(e.merchant()).isEqualTo("DL_fixture");assertThat(e.type()).isEqualTo("payment_succeeded");
 }
 @Test void tamperedBodyAndUnknownPublicKeyAreRejected()throws Exception{
  byte[] body=envelope(transaction());var h=headers(body,Instant.now().getEpochSecond());byte[] changed=Arrays.copyOf(body,body.length+1);changed[changed.length-1]=32;error(401,()->gateway.parse("wechat_pay",h,changed));h.put("wechatpay-serial","WRONG_KEY");error(401,()->gateway.parse("wechat_pay",h,body));
 }
 @Test void signedNotificationOutsideTimeWindowIsRejected()throws Exception{
  byte[] body=envelope(transaction());var old=headers(body,Instant.now().minusSeconds(301).getEpochSecond());var future=headers(body,Instant.now().plusSeconds(601).getEpochSecond());error(401,()->gateway.parse("wechat_pay",old,body));error(401,()->gateway.parse("wechat_pay",future,body));
 }
 @Test void authenticatedWrongMerchantOrAppIsRejected()throws Exception{
  for(String field:List.of("appid","mchid")){var t=transaction();t.put(field,"other-merchant-or-app");byte[] body=envelope(t);var h=headers(body,Instant.now().getEpochSecond());error(400,()->gateway.parse("wechat_pay",h,body));}
 }
 @Test void wrongAesKeyDoesNotProduceTrustedEvent()throws Exception{
  byte[] body=envelope(transaction());var h=headers(body,Instant.now().getEpochSecond());settings.apiV3Key="11111111111111111111111111111111";error(400,()->gateway.parse("wechat_pay",h,body));
 }
 @Test void missingConfigurationFailsBeforeNetwork(){settings.merchantId="";error(503,()->gateway.check("wechat_pay"));settings.mockEnabled=true;settings.environment="production";error(403,()->gateway.check("mock"));}
 Map<String,Object> order(){return Map.of("provider","wechat_pay","merchant_order_no","DL_fixture","package_name","30 金币","amount_fen",3000,"expires_at",java.sql.Timestamp.from(Instant.now().plusSeconds(1800)));}
 String authField(String auth,String name){var m=java.util.regex.Pattern.compile(name+"=\"([^\"]+)\"").matcher(auth);assertThat(m.find()).isTrue();return m.group(1);}
 void mockSignedTransport(boolean valid,AtomicBoolean called)throws Exception{
  var config=new RSAPublicKeyConfig.Builder().merchantId(settings.merchantId).merchantSerialNumber(settings.merchantSerial).privateKey(merchant.getPrivate()).publicKey(platform.getPublic()).publicKeyId(settings.publicKeyId).apiV3Key(KEY).build();
  var transport=new okhttp3.OkHttpClient.Builder().addInterceptor(chain->{
   called.set(true);var request=chain.request();var buffer=new okio.Buffer();request.body().writeTo(buffer);String body=buffer.readUtf8(),auth=request.header("Authorization");
   try{
    assertThat(request.url().host()).isEqualTo("api.mch.weixin.qq.com");assertThat(verify(merchant.getPublic(),request.method()+"\n"+request.url().encodedPath()+"\n"+authField(auth,"timestamp")+"\n"+authField(auth,"nonce_str")+"\n"+body+"\n",authField(auth,"signature"))).isTrue();
    String response="{\"prepay_id\":\"synthetic-prepay-id\"}",time=Long.toString(Instant.now().getEpochSecond()),nonce="fixture-response";
    return new okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK").header("Content-Type","application/json").header("Wechatpay-Serial",settings.publicKeyId).header("Wechatpay-Timestamp",time).header("Wechatpay-Nonce",nonce).header("Wechatpay-Signature",valid?sign(platform.getPrivate(),time+"\n"+nonce+"\n"+response+"\n"):"invalid").body(okhttp3.ResponseBody.create(okhttp3.MediaType.get("application/json"),response)).build();
   }catch(GeneralSecurityException e){throw new java.io.IOException(e);}catch(Exception e){throw new java.io.IOException(e);}
  }).build();
  var http=new DefaultHttpClientBuilder().config(config).okHttpClient(transport).build();
  org.springframework.test.util.ReflectionTestUtils.setField(gateway,"app",new AppServiceExtension.Builder().config(config).httpClient(http).build());
 }
 @Test void sdkSignsPrepayVerifiesResponseAndSignsAppParameters()throws Exception{
  var called=new AtomicBoolean();mockSignedTransport(true,called);var checkout=gateway.checkout(order());assertThat(called).isTrue();var p=checkout.payload();assertThat(p.get("prepay_id")).isEqualTo("synthetic-prepay-id");
  assertThat(verify(merchant.getPublic(),settings.appId+"\n"+p.get("timestamp")+"\n"+p.get("nonce_str")+"\n"+p.get("prepay_id")+"\n",p.get("sign"))).isTrue();
 }
 @Test void sdkRejectsForgedCheckoutResponse()throws Exception{var called=new AtomicBoolean();mockSignedTransport(false,called);error(503,()->gateway.checkout(order()));assertThat(called).isTrue();}
}
