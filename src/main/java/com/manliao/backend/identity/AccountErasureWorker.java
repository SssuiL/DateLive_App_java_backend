package com.manliao.backend.identity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(name="app.accounts.worker-enabled",havingValue="true",matchIfMissing=false)
public class AccountErasureWorker {
 private final AccountLifecycleService accounts;
 public AccountErasureWorker(AccountLifecycleService accounts){
  this.accounts=accounts;
  org.slf4j.LoggerFactory.getLogger(AccountErasureWorker.class).info("Account erasure worker enabled: initial delay 60s, interval 60s; only expired deactivation requests are eligible");
 }
 @Scheduled(initialDelay=60000,fixedDelay=60000)
 public void process(){accounts.processDue();accounts.cleanupStorage();}
}
