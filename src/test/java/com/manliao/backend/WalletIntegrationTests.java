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
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.auth.legacy-registration-enabled=true","app.auth.jwt-secret=isolated-wallet-tests-secret-at-least-thirty-two-bytes","app.accounts.worker-enabled=false","app.realtime.worker-enabled=false","app.media.derivatives-worker-enabled=false","app.media.jobs-worker-enabled=false","app.media.draft-worker-enabled=false","app.environment=test","app.billing.dev-recharge-enabled=true"})
class WalletIntegrationTests {
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
  var r=call("POST","/auth/register",Map.of("phone","1398100"+String.format("%04d",sequence++),"nickname","账本测试","password","Wallet-test-password"),null);
  assertThat(r.status()).isEqualTo(200);return new User(r.text("user_id"),r.text("access_token"),r.text("session_id"));
 }
 Reply recharge(User user,long amount,String key)throws Exception{return call("POST","/wallet/recharge/dev",Map.of("amount",amount,"client_request_id",key),user);}
 long balance(User user)throws Exception{return call("GET","/wallet/me",null,user).body().get("balance").asLong();}
 int count(String table){return db.queryForObject("SELECT count(*) FROM "+table,Integer.class);}
 LedgerService.Posting credit(User user,long amount,String key){return new LedgerService.Posting(key,"recharge",amount,List.of(LedgerService.spendable(user.id(),amount,"credit"),LedgerService.platform("platform_coin_issuance",-amount,"issuance")),user.id(),"fixture",key,Map.of(),null,"recharge","fixture");}
 LedgerService.Posting charge(User user,long amount,String key){return new LedgerService.Posting(key,"call_charge",amount,List.of(LedgerService.spendable(user.id(),-amount,"spend"),LedgerService.platform("platform_call_income",amount,"income")),user.id(),"fixture",key,Map.of(),null,"call_charge","fixture");}
 void balanced(){assertThat(db.queryForObject("SELECT count(*) FROM(SELECT transaction_id FROM coin_ledger_entries GROUP BY transaction_id HAVING sum(amount)<>0 OR count(*)<2)s",Integer.class)).isZero();assertThat(db.queryForObject("SELECT coalesce(sum(balance),0) FROM coin_accounts",Long.class)).isZero();}
 @Test void emptyWalletInitializesOnceAndRequiresAuthentication()throws Exception{
  var a=user();assertThat(balance(a)).isZero();assertThat(balance(a)).isZero();assertThat(count("wallets")).isEqualTo(1);assertThat(count("coin_accounts")).isEqualTo(1);
  assertThat(call("GET","/wallet/me",null,null).status()).isEqualTo(401);assertThat(call("GET","/wallet/ledger",null,a).body().size()).isZero();
 }
 @Test void rechargeIsBalancedIdempotentAndRejectsPayloadConflict()throws Exception{
  var a=user();assertThat(recharge(a,25,"same-request-001").status()).isEqualTo(200);assertThat(recharge(a,25,"same-request-001").status()).isEqualTo(200);
  assertThat(recharge(a,26,"same-request-001").status()).isEqualTo(409);assertThat(balance(a)).isEqualTo(25);assertThat(count("billing_transactions")).isEqualTo(1);assertThat(count("coin_ledger_entries")).isEqualTo(2);assertThat(count("wallet_transactions")).isEqualTo(1);balanced();
 }
 @Test void concurrentIdenticalRechargeCreditsExactlyOnce()throws Exception{
  var a=user();try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var gate=new CountDownLatch(1);var work=new ArrayList<Future<Reply>>();for(int i=0;i<12;i++)work.add(pool.submit(()->{gate.await();return recharge(a,100,"concurrent-same-001");}));gate.countDown();for(var item:work)assertThat(item.get(15,TimeUnit.SECONDS).status()).isEqualTo(200);}
  assertThat(balance(a)).isEqualTo(100);assertThat(count("billing_transactions")).isEqualTo(1);balanced();
 }
 @Test void concurrentDifferentRechargeDoesNotLoseUpdates()throws Exception{
  var a=user();try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var work=new ArrayList<Future<Reply>>();for(int i=0;i<10;i++){String key="different-request-"+i;work.add(pool.submit(()->recharge(a,10,key)));}for(var item:work)assertThat(item.get(15,TimeUnit.SECONDS).status()).isEqualTo(200);}
  assertThat(balance(a)).isEqualTo(100);assertThat(count("billing_transactions")).isEqualTo(10);balanced();
 }
 @Test void concurrentDebitsNeverOverdraw()throws Exception{
  var a=user();recharge(a,30,"charge-initial-001");
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var gate=new CountDownLatch(1);var work=new ArrayList<Future<Integer>>();for(int i=0;i<2;i++){String key="debit-"+i;work.add(pool.submit(()->{gate.await();try{ledger.post(charge(a,20,key));return 200;}catch(ApiError e){return 403;}}));}gate.countDown();assertThat(List.of(work.get(0).get(),work.get(1).get())).containsExactlyInAnyOrder(200,403);}
  assertThat(balance(a)).isEqualTo(10);assertThat(count("billing_transactions")).isEqualTo(2);balanced();
 }
 @Test void insufficientFundsRollsBackEveryLedgerLeg()throws Exception{
  var a=user();recharge(a,10,"insufficient-initial");int before=count("coin_accounts");
  assertThatThrownBy(()->ledger.post(charge(a,11,"insufficient-charge"))).isInstanceOf(ApiError.class);
  assertThat(balance(a)).isEqualTo(10);assertThat(count("coin_accounts")).isEqualTo(before);assertThat(count("billing_transactions")).isEqualTo(1);balanced();
 }
 @Test void transactionFailureRollsBackBalanceAndHistory()throws Exception{
  var a=user();db.execute("ALTER TABLE wallet_transactions ADD CONSTRAINT reject_fixture CHECK(false) NOT VALID");
  try{assertThat(recharge(a,20,"rollback-request-001").status()).isEqualTo(500);}finally{db.execute("ALTER TABLE wallet_transactions DROP CONSTRAINT reject_fixture");}
  assertThat(count("billing_transactions")).isZero();assertThat(count("coin_ledger_entries")).isZero();assertThat(balance(a)).isZero();assertThat(recharge(a,20,"rollback-request-001").status()).isEqualTo(200);balanced();
 }
 @Test void validatesAmountsKeysAndPagination()throws Exception{
  var a=user();for(long amount:List.of(-1L,0L,100001L))assertThat(recharge(a,amount,"invalid-request-001").status()).isEqualTo(422);
  assertThat(recharge(a,1,"short").status()).isEqualTo(422);assertThat(recharge(a,1,"        ").status()).isEqualTo(422);
  assertThat(call("GET","/wallet/bills?direction=invalid",null,a).status()).isEqualTo(422);assertThat(call("GET","/wallet/bills?limit=501",null,a).status()).isEqualTo(422);assertThat(call("GET","/wallet/summary?days=0",null,a).status()).isEqualTo(422);
 }
 @Test void userLedgerNeverExposesOtherAccounts()throws Exception{
  var a=user();var b=user();recharge(a,50,"privacy-request-001");
  var result=call("GET","/wallet/ledger",null,a);assertThat(result.body().size()).isEqualTo(1);var entries=result.body().get(0).get("entries");assertThat(entries.size()).isEqualTo(1);assertThat(entries.get(0).get("owner_id").asString()).isEqualTo(a.id());assertThat(result.body().get(0).has("payload_hash")).isFalse();
  assertThat(call("GET","/wallet/ledger",null,b).body().size()).isZero();assertThat(call("GET","/admin/billing/accounts",null,a).status()).isIn(401,403);
 }
 @Test void billsAndSummarySeparateIncomeAndExpense()throws Exception{
  var a=user();recharge(a,100,"summary-initial-001");ledger.post(charge(a,30,"summary-call-001"));
  var result=call("GET","/wallet/summary",null,a).body();assertThat(result.get("balance").asLong()).isEqualTo(70);assertThat(result.get("income_coins").asLong()).isEqualTo(100);assertThat(result.get("spent_coins").asLong()).isEqualTo(30);assertThat(result.get("call_spent_coins").asLong()).isEqualTo(30);assertThat(result.get("bill_count").asLong()).isEqualTo(2);
  var expense=call("GET","/wallet/bills?direction=expense",null,a).body();assertThat(expense.size()).isEqualTo(1);assertThat(expense.get(0).get("amount").asLong()).isEqualTo(-30);
  assertThat(call("GET","/wallet/bills?transaction_type=recharge",null,a).body().size()).isEqualTo(1);assertThat(call("GET","/wallet/transactions",null,a).body().size()).isEqualTo(2);balanced();
 }
 @Test void historyCannotBeEditedAndDatabaseRejectsIncompletePosting()throws Exception{
  var a=user();recharge(a,20,"immutable-request-001");
  assertThatThrownBy(()->db.update("UPDATE coin_ledger_entries SET amount=1")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  assertThatThrownBy(()->db.update("DELETE FROM billing_transactions")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  assertThatThrownBy(()->tx.executeWithoutResult(s->db.update("INSERT INTO billing_transactions(id,idempotency_key,transaction_type,amount_coins,payload_hash) VALUES('broken','broken','fixture',1,'hash')"))).isInstanceOf(RuntimeException.class);
  assertThat(count("billing_transactions")).isEqualTo(1);assertThat(balance(a)).isEqualTo(20);balanced();
 }
 @Test void accountErasureRetainsBalancedFinancialHistory()throws Exception{
  var a=user();recharge(a,20,"erasure-request-001");call("POST","/auth/account/deactivate",null,a);assertThat(recharge(a,20,"erasure-blocked-001").status()).isEqualTo(403);
  db.update("UPDATE users SET deactivation_due_at=now()-interval '1 second' WHERE id=?",a.id());assertThat(accounts.eraseDue(a.id())).isTrue();
  assertThat(count("wallet_transactions")).isEqualTo(1);assertThat(db.queryForObject("SELECT balance FROM wallets WHERE user_id=?",Long.class,a.id())).isEqualTo(20);balanced();
 }
 @Test void largeBalancesRemainIntegersAndOverflowFailsClosed()throws Exception{
  var a=user();ledger.post(credit(a,Long.MAX_VALUE,"maximum"));assertThat(balance(a)).isEqualTo(Long.MAX_VALUE);
  assertThatThrownBy(()->ledger.post(credit(a,1,"overflow"))).isInstanceOf(ApiError.class);assertThat(balance(a)).isEqualTo(Long.MAX_VALUE);assertThat(count("billing_transactions")).isEqualTo(1);balanced();
 }
 @Test void inconsistentWalletRequiresReconciliation()throws Exception{
  var a=user();recharge(a,20,"reconcile-initial");db.update("UPDATE wallets SET balance=21 WHERE user_id=?",a.id());
  assertThat(call("GET","/wallet/me",null,a).status()).isEqualTo(409);assertThat(recharge(a,1,"reconcile-blocked").status()).isEqualTo(409);assertThat(count("billing_transactions")).isEqualTo(1);
 }
 @Test void twoWayTransfersLockUsersInStableOrder()throws Exception{
  var a=user();var b=user();recharge(a,100,"transfer-initial-a");recharge(b,100,"transfer-initial-b");
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var gate=new CountDownLatch(1);var work=new ArrayList<Future<LedgerService.Result>>();
   for(int i=0;i<12;i++){User from=i%2==0?a:b,to=i%2==0?b:a;String key="transfer-"+i;work.add(pool.submit(()->{gate.await();return ledger.post(new LedgerService.Posting(key,"adjustment",10,List.of(LedgerService.spendable(from.id(),-10,"sender"),LedgerService.spendable(to.id(),10,"recipient")),from.id(),"fixture",key,Map.of(),null,"adjustment","fixture"));}));}
   gate.countDown();for(var item:work)assertThat(item.get(15,TimeUnit.SECONDS).created()).isTrue();
  }
  assertThat(balance(a)).isEqualTo(100);assertThat(balance(b)).isEqualTo(100);balanced();
 }
 @Test void unbalancedPostingAndFrozenAccountsAreRejected()throws Exception{
  var a=user();recharge(a,10,"frozen-initial");
  assertThatThrownBy(()->ledger.post(new LedgerService.Posting("unbalanced","fixture",10,List.of(LedgerService.spendable(a.id(),10,"credit"),LedgerService.platform("platform_coin_issuance",-9,"issuance")),a.id(),null,null,Map.of(),null,null,null))).isInstanceOf(ApiError.class);
  db.update("UPDATE coin_accounts SET status='frozen' WHERE user_id=?",a.id());assertThat(recharge(a,10,"frozen-request-001").status()).isEqualTo(403);assertThat(count("billing_transactions")).isEqualTo(1);balanced();
 }
 @Test void productionModeRejectsEvenExplicitlyEnabledMockRecharge()throws Exception{
  var a=user();org.springframework.test.util.ReflectionTestUtils.setField(wallet,"environment","production");
  try{assertThat(recharge(a,1,"production-request").status()).isEqualTo(403);}finally{org.springframework.test.util.ReflectionTestUtils.setField(wallet,"environment","test");}
 }
 @Test void disabledDevelopmentSwitchCannotCreditWallet()throws Exception{
  var a=user();org.springframework.test.util.ReflectionTestUtils.setField(wallet,"development",false);
  try{assertThat(recharge(a,1,"disabled-request").status()).isEqualTo(403);assertThat(count("billing_transactions")).isZero();}
  finally{org.springframework.test.util.ReflectionTestUtils.setField(wallet,"development",true);}
 }
 @Test void adminBillingQueriesExposeBalancedEntriesOnlyToAuthorizedRoles()throws Exception{
  var a=user();recharge(a,25,"admin-ledger-initial");
  admins.bootstrap(new com.manliao.backend.admin.AdminDtos.Create("wallet_owner","Owner-test-password-123","owner","owner"));
  var login=call("POST","/admin/auth/login",Map.of("username","wallet_owner","password","Owner-test-password-123"),null);
  var owner=new User("unused",login.text("access_token"),null);
  var records=call("GET","/admin/billing/transactions?user_id="+a.id(),null,owner);assertThat(records.status()).isEqualTo(200);assertThat(records.body().get(0).get("entries").size()).isEqualTo(2);
  assertThat(call("GET","/admin/billing/transactions?reference_type=missing",null,owner).body().size()).isZero();
  assertThat(call("GET","/admin/billing/accounts?owner_id="+a.id(),null,owner).body().size()).isEqualTo(1);
  assertThat(call("POST","/admin/admin-users",Map.of("username","wallet_auditor","password","Auditor-test-password-123","display_name","audit","role","auditor"),owner).status()).isEqualTo(200);
  var auditorLogin=call("POST","/admin/auth/login",Map.of("username","wallet_auditor","password","Auditor-test-password-123"),null);
  var auditor=new User("unused",auditorLogin.text("access_token"),null);
  assertThat(call("GET","/admin/billing/accounts",null,auditor).status()).isEqualTo(200);
  db.update("UPDATE admin_users SET status='disabled' WHERE username='wallet_auditor'");
  assertThat(call("GET","/admin/billing/accounts",null,auditor).status()).isIn(401,403);
 }
 @Test void metadataKeyOrderingDoesNotCreateFalseIdempotencyConflict()throws Exception{
  var a=user();var first=credit(a,10,"canonical");var left=new LinkedHashMap<String,Object>();left.put("a",1);left.put("b",Map.of("nested",2));var right=new LinkedHashMap<String,Object>();right.put("b",Map.of("nested",2));right.put("a",1);
  var x=new LedgerService.Posting(first.key(),first.type(),first.amount(),first.legs(),first.actor(),first.referenceType(),first.referenceId(),left,null,first.legacyType(),first.remark());
  var y=new LedgerService.Posting(first.key(),first.type(),first.amount(),first.legs(),first.actor(),first.referenceType(),first.referenceId(),right,null,first.legacyType(),first.remark());
  assertThat(ledger.post(x).created()).isTrue();assertThat(ledger.post(y).created()).isFalse();assertThat(balance(a)).isEqualTo(10);
 }
}
