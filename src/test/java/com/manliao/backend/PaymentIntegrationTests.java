package com.manliao.backend;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import com.manliao.backend.billing.*;
import com.manliao.backend.identity.*;
import com.manliao.backend.common.ApiError;
import static org.assertj.core.api.Assertions.*;
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.auth.legacy-registration-enabled=true","app.auth.jwt-secret=isolated-wallet-tests-secret-at-least-thirty-two-bytes","app.accounts.worker-enabled=false","app.realtime.worker-enabled=false","app.media.derivatives-worker-enabled=false","app.media.jobs-worker-enabled=false","app.media.draft-worker-enabled=false","app.environment=test","app.payments.provider=mock","app.payments.mock-enabled=true","app.payments.callback-secret=isolated-payment-callback-secret-thirty-two-bytes","app.billing.dev-recharge-enabled=true"})
class PaymentIntegrationTests {
 @Value("${local.server.port}") int port;
 @Autowired com.manliao.backend.admin.AdminService admins;
 @Autowired JdbcTemplate db;@Autowired ObjectMapper json;@Autowired LedgerService ledger;@Autowired WalletService wallet;@Autowired AccountLifecycleService accounts;@Autowired TransactionTemplate tx;
 final HttpClient http=HttpClient.newHttpClient();int sequence;
 record Reply(int status,JsonNode body){String text(String key){return body.get(key).asString();}}
 record User(String id,String token,String session){}
 @BeforeEach void clean(){db.execute("TRUNCATE admin_users,users,conversations,auth_rate_windows RESTART IDENTITY CASCADE");sequence=1;}
 Reply call(String method,String path,Object body,User user)throws Exception{
  var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(java.time.Duration.ofSeconds(15));if(user!=null)b.header("Authorization","Bearer "+user.token());if(body!=null)b.header("Content-Type","application/json");
  var r=http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
  return new Reply(r.statusCode(),r.body().isBlank()?null:json.readTree(r.body()));
 }
 User user()throws Exception{
  var r=call("POST","/auth/register",Map.of("phone","1398200"+String.format("%04d",sequence++),"nickname","账本测试","password","Wallet-test-password"),null);
  assertThat(r.status()).isEqualTo(200);return new User(r.text("user_id"),r.text("access_token"),r.text("session_id"));
 }
 Reply recharge(User user,long amount,String key)throws Exception{return call("POST","/wallet/recharge/dev",Map.of("amount",amount,"client_request_id",key),user);}
 long balance(User user)throws Exception{return call("GET","/wallet/me",null,user).body().get("balance").asLong();}
 int count(String table){return db.queryForObject("SELECT count(*) FROM "+table,Integer.class);}
 @Autowired com.manliao.backend.payments.PaymentService payments;
 @Autowired com.manliao.backend.payments.PaymentSettings settings;
 @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.manliao.backend.payments.PaymentGateway gateway;
 Reply order(User u,String key)throws Exception{return call("POST","/payments/orders",Map.of("package_code","coins_30","client_request_id",key,"provider","mock"),u);}
 Map<String,Object> event(Reply order,String id){return new LinkedHashMap<>(Map.of("event_id",id,"event_type","payment_succeeded","merchant_order_no",order.text("merchant_order_no"),"provider_trade_no","trade-"+id,"amount_fen",3000,"currency","CNY","occurred_at",java.time.Instant.now().toString()));}
 Reply callback(Map<String,Object> event)throws Exception{return callback(json.writeValueAsBytes(event),Long.toString(java.time.Instant.now().getEpochSecond()),false);}
 Reply callback(byte[] body,String timestamp,boolean tamper)throws Exception{
  var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec("isolated-payment-callback-secret-thirty-two-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));mac.update((timestamp+".").getBytes(java.nio.charset.StandardCharsets.UTF_8));String signature=HexFormat.of().formatHex(mac.doFinal(body));
  var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/callbacks/payments/mock")).header("Content-Type","application/json").header("X-Datelive-Timestamp",timestamp).header("X-Datelive-Signature",tamper?"invalid":signature).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
  var r=http.send(request,HttpResponse.BodyHandlers.ofString());return new Reply(r.statusCode(),json.readTree(r.body()));
 }
 @Test void packagesArePublicAndAmountsComeFromServer()throws Exception{
  var packages=call("GET","/payments/packages",null,null);assertThat(packages.status()).isEqualTo(200);assertThat(packages.body().size()).isEqualTo(4);
  var a=user();var created=call("POST","/payments/orders",Map.of("package_code","coins_30","client_request_id","server-amount-test","amount_fen",1,"coin_amount",999),a);
  assertThat(created.status()).isEqualTo(200);assertThat(created.body().get("amount_fen").asLong()).isEqualTo(3000);assertThat(created.body().get("coin_amount").asLong()).isEqualTo(30);assertThat(created.text("checkout_url")).startsWith("datelive-mock:");assertThat(created.body().has("checkout_claim")).isFalse();
 }
 @Test void orderIdempotencyAndPeerAuthorization()throws Exception{
  var a=user();var b=user();var first=order(a,"same-order-request");var second=order(a,"same-order-request");assertThat(second.text("id")).isEqualTo(first.text("id"));
  assertThat(call("POST","/payments/orders",Map.of("package_code","coins_6","client_request_id","same-order-request"),a).status()).isEqualTo(409);
  assertThat(call("GET","/payments/orders/"+first.text("id"),null,b).status()).isEqualTo(403);assertThat(call("GET","/payments/orders",null,b).body().size()).isZero();assertThat(count("payment_orders")).isEqualTo(1);
 }
 @Test void concurrentOrderCreationHasOneMerchantOrder()throws Exception{
  var a=user();try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var gate=new CountDownLatch(1);var work=new ArrayList<Future<Reply>>();for(int i=0;i<8;i++)work.add(pool.submit(()->{gate.await();return order(a,"concurrent-order-request");}));gate.countDown();for(var r:work)assertThat(r.get(15,TimeUnit.SECONDS).status()).isIn(200,409);}
  assertThat(count("payment_orders")).isEqualTo(1);assertThat(order(a,"concurrent-order-request").status()).isEqualTo(200);
 }
 @Test void checkoutFailureKeepsOrderForSafeRetry()throws Exception{
  var a=user();org.mockito.Mockito.doAnswer(invocation->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();throw new RuntimeException("synthetic network failure");}).doCallRealMethod().when(gateway).checkout(org.mockito.ArgumentMatchers.anyMap());
  assertThat(order(a,"retry-order-request").status()).isEqualTo(503);String merchant=db.queryForObject("SELECT merchant_order_no FROM payment_orders",String.class);
  var retry=order(a,"retry-order-request");assertThat(retry.status()).isEqualTo(200);assertThat(retry.text("merchant_order_no")).isEqualTo(merchant);assertThat(count("payment_orders")).isEqualTo(1);assertThat(count("billing_transactions")).isZero();
 }
 @Test void signedCallbackCreditsExactlyOnceAndRejectsChangedEvent()throws Exception{
  var a=user();var o=order(a,"callback-once-order");var e=event(o,"callback-once-event");
  assertThat(callback(e).text("processing_status")).isEqualTo("processed");assertThat(callback(e).body().get("duplicate").asBoolean()).isTrue();
  e.put("amount_fen",3001);assertThat(callback(e).status()).isEqualTo(409);assertThat(balance(a)).isEqualTo(30);assertThat(count("billing_transactions")).isEqualTo(1);assertThat(count("coin_ledger_entries")).isEqualTo(2);
 }
 @Test void simultaneousCallbacksCannotDoubleCredit()throws Exception{
  var a=user();var o=order(a,"parallel-payment-order");var same=event(o,"parallel-payment-event");
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var gate=new CountDownLatch(1);var work=new ArrayList<Future<Reply>>();for(int i=0;i<10;i++)work.add(pool.submit(()->{gate.await();return callback(same);}));gate.countDown();for(var r:work)assertThat(r.get(15,TimeUnit.SECONDS).status()).isEqualTo(200);}
  assertThat(balance(a)).isEqualTo(30);assertThat(count("payment_callback_events")).isEqualTo(1);assertThat(count("billing_transactions")).isEqualTo(1);
 }
 @Test void differentEventsForPaidOrderAreIgnored()throws Exception{
  var a=user();var o=order(a,"different-events-order");callback(event(o,"first-paid-event"));var next=callback(event(o,"second-paid-event"));assertThat(next.text("processing_status")).isEqualTo("ignored");assertThat(balance(a)).isEqualTo(30);assertThat(count("billing_transactions")).isEqualTo(1);
 }
 @Test void invalidSignatureAndExpiredTimestampNeverCredit()throws Exception{
  var a=user();var o=order(a,"invalid-signature-order");byte[] body=json.writeValueAsBytes(event(o,"invalid-signature-event"));
  assertThat(callback(body,Long.toString(java.time.Instant.now().getEpochSecond()),true).status()).isEqualTo(401);
  assertThat(callback(body,"1",false).status()).isEqualTo(401);assertThat(balance(a)).isZero();assertThat(count("billing_transactions")).isZero();
  assertThat(callback(body,Long.toString(java.time.Instant.now().getEpochSecond()),false).text("processing_status")).isEqualTo("processed");assertThat(balance(a)).isEqualTo(30);
 }
 @Test void malformedAndOversizedBodiesAreRejected()throws Exception{
  assertThat(callback("{".getBytes(),Long.toString(java.time.Instant.now().getEpochSecond()),false).status()).isEqualTo(400);
  assertThat(callback(new byte[65537],Long.toString(java.time.Instant.now().getEpochSecond()),false).status()).isEqualTo(413);
  assertThat(count("billing_transactions")).isZero();
 }
 @Test void mismatchedMoneyCurrencyTradeAndTimeAreAuditedWithoutCredit()throws Exception{
  var a=user();var o=order(a,"mismatch-payment-order");
  for(String kind:List.of("amount_fen","currency","provider_trade_no","event_type","occurred_at")){
   var e=event(o,"mismatch-"+kind);e.put(kind,switch(kind){case "amount_fen"->2999;case "currency"->"USD";case "provider_trade_no"->"";case "event_type"->"refund";default->java.time.Instant.now().plusSeconds(600).toString();});
   assertThat(callback(e).text("processing_status")).isEqualTo("rejected");
  }assertThat(balance(a)).isZero();assertThat(count("billing_transactions")).isZero();
 }
 @Test void expiredOrderClosesButTimelyPaymentMayArriveLate()throws Exception{
  var a=user();var o=order(a,"late-payment-order");var e=event(o,"late-payment-event");
  db.update("UPDATE payment_orders SET expires_at=now()-interval '1 second',created_at=now()-interval '1 hour' WHERE id=?",o.text("id"));
  assertThat(call("GET","/payments/orders/"+o.text("id"),null,a).text("status")).isEqualTo("closed");e.put("occurred_at",java.time.Instant.now().minusSeconds(10).toString());assertThat(callback(e).text("processing_status")).isEqualTo("processed");assertThat(balance(a)).isEqualTo(30);
 }
 @Test void paymentAfterExpiryDoesNotCredit()throws Exception{
  var a=user();var o=order(a,"too-late-payment-order");db.update("UPDATE payment_orders SET expires_at=now()-interval '1 second' WHERE id=?",o.text("id"));assertThat(callback(event(o,"too-late-payment-event")).text("processing_status")).isEqualTo("rejected");assertThat(balance(a)).isZero();
 }
 @Test void sameProviderTradeCannotPayTwoOrders()throws Exception{
  var a=user();var first=order(a,"trade-reuse-order-a");var second=order(a,"trade-reuse-order-b");var e=event(first,"trade-first-event");callback(e);var other=event(second,"trade-second-event");other.put("provider_trade_no",e.get("provider_trade_no"));assertThat(callback(other).text("processing_status")).isEqualTo("rejected");assertThat(balance(a)).isEqualTo(30);
 }
 @Test void databaseFailureRollsBackCallbackOrderAndCoins()throws Exception{
  var a=user();var o=order(a,"rollback-payment-order");var e=event(o,"rollback-payment-event");db.execute("ALTER TABLE wallet_transactions ADD CONSTRAINT reject_payment_fixture CHECK(false) NOT VALID");
  try{assertThat(callback(e).status()).isEqualTo(500);}finally{db.execute("ALTER TABLE wallet_transactions DROP CONSTRAINT reject_payment_fixture");}
  assertThat(count("billing_transactions")).isZero();assertThat(count("payment_callback_events")).isZero();assertThat(call("GET","/payments/orders/"+o.text("id"),null,a).text("status")).isEqualTo("pending");assertThat(callback(e).text("processing_status")).isEqualTo("processed");
 }
 @Test void mockCompletionIsOwnedIdempotentAndForbiddenInProduction()throws Exception{
  var a=user();var b=user();var o=order(a,"mock-complete-order");String path="/payments/orders/"+o.text("id")+"/mock/complete";var body=Map.of("event_id","mock-complete-event");
  assertThat(call("POST",path,body,b).status()).isEqualTo(403);assertThat(call("POST",path,body,a).text("processing_status")).isEqualTo("processed");assertThat(call("POST",path,body,a).body().get("duplicate").asBoolean()).isTrue();
  org.springframework.test.util.ReflectionTestUtils.setField(settings,"environment","production");try{assertThat(call("POST",path,Map.of(),a).status()).isEqualTo(403);assertThat(order(a,"production-payment-order").status()).isEqualTo(403);}finally{org.springframework.test.util.ReflectionTestUtils.setField(settings,"environment","test");}
 }
 @Test void verifiedIncomingPaymentDoesNotReactivateErasedUser()throws Exception{
  var a=user();var o=order(a,"erased-payment-order");var e=event(o,"erased-payment-event");call("POST","/auth/account/deactivate",null,a);db.update("UPDATE users SET deactivation_due_at=now()-interval '1 second' WHERE id=?",a.id());assertThat(accounts.eraseDue(a.id())).isTrue();
  assertThat(callback(e).text("processing_status")).isEqualTo("processed");assertThat(db.queryForObject("SELECT balance FROM wallets WHERE user_id=?",Long.class,a.id())).isEqualTo(30);assertThat(db.queryForObject("SELECT status FROM users WHERE id=?",String.class,a.id())).isEqualTo("deactivated");assertThat(call("GET","/wallet/me",null,a).status()).isIn(401,403);
 }
 @Test void adminPaymentAuditRequiresAdminAndDoesNotExposeRawPayload()throws Exception{
  var a=user();var o=order(a,"admin-payment-order");callback(event(o,"admin-payment-event"));assertThat(call("GET","/admin/payments/orders",null,a).status()).isIn(401,403);
  admins.bootstrap(new com.manliao.backend.admin.AdminDtos.Create("payment_owner","Payment-admin-password-123","owner","owner"));var login=call("POST","/admin/auth/login",Map.of("username","payment_owner","password","Payment-admin-password-123"),null);var admin=new User("unused",login.text("access_token"),null);
  assertThat(call("GET","/admin/payments/orders?user_id="+a.id(),null,admin).body().size()).isEqualTo(1);var events=call("GET","/admin/payments/callback-events?processing_status=processed",null,admin);assertThat(events.body().size()).isEqualTo(1);assertThat(events.body().get(0).has("payload_hash")).isFalse();assertThat(events.body().get(0).has("payload")).isFalse();
 }
 @Test void callbackCanFinishWhileCheckoutRequestIsStillReturning()throws Exception{
  var a=user();org.mockito.Mockito.doAnswer(invocation->{
   Map<String,Object> row=invocation.getArgument(0);var e=new LinkedHashMap<String,Object>();e.put("event_id","during-checkout-event");e.put("event_type","payment_succeeded");e.put("merchant_order_no",row.get("merchant_order_no"));e.put("provider_trade_no","during-checkout-trade");e.put("amount_fen",3000);e.put("currency","CNY");e.put("occurred_at",java.time.Instant.now().toString());
   assertThat(callback(e).text("processing_status")).isEqualTo("processed");return invocation.callRealMethod();
  }).when(gateway).checkout(org.mockito.ArgumentMatchers.anyMap());
  var created=order(a,"during-checkout-order");assertThat(created.status()).isEqualTo(200);assertThat(created.text("status")).isEqualTo("paid");assertThat(balance(a)).isEqualTo(30);
 }
 @Test void concurrentTradeReuseAcrossUsersCreditsOnlyOneAccount()throws Exception{
  var a=user();var b=user();var left=event(order(a,"trade-race-order-a"),"trade-race-event-a");var right=event(order(b,"trade-race-order-b"),"trade-race-event-b");left.put("provider_trade_no","shared-racing-trade");right.put("provider_trade_no","shared-racing-trade");
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var gate=new CountDownLatch(1);var x=pool.submit(()->{gate.await();return callback(left);});var y=pool.submit(()->{gate.await();return callback(right);});gate.countDown();assertThat(List.of(x.get(15,TimeUnit.SECONDS).text("processing_status"),y.get(15,TimeUnit.SECONDS).text("processing_status"))).containsExactlyInAnyOrder("processed","rejected");}
  assertThat(balance(a)+balance(b)).isEqualTo(30);assertThat(count("billing_transactions")).isEqualTo(1);
 }
 @Test void abandonedCheckoutClaimExpiresAndRetainsMerchantOrder()throws Exception{
  var a=user();var initial=order(a,"claim-recovery-order");db.update("UPDATE payment_orders SET checkout_url=NULL,checkout_claim='abandoned',checkout_until=now()+interval '1 minute' WHERE id=?",initial.text("id"));
  assertThat(order(a,"claim-recovery-order").status()).isEqualTo(409);db.update("UPDATE payment_orders SET checkout_until=now()-interval '1 second' WHERE id=?",initial.text("id"));
  var retry=order(a,"claim-recovery-order");assertThat(retry.status()).isEqualTo(200);assertThat(retry.text("merchant_order_no")).isEqualTo(initial.text("merchant_order_no"));
 }
 @Test void unsupportedProviderDoesNotWriteMalformedAuditRows()throws Exception{
  assertThat(call("POST","/callbacks/payments/"+"x".repeat(100),Map.of(),null).status()).isEqualTo(400);assertThat(count("payment_callback_events")).isZero();
 }
}
