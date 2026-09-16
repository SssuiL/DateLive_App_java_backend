package com.manliao.backend.identity;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.manliao.backend.common.ApiError;
@Component
public class UserWriteGuard {
 private final JdbcTemplate db;
 public UserWriteGuard(JdbcTemplate db){this.db=db;}
 public void lock(String id) {
   if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("User write guard requires transaction");
   var users=db.queryForList("SELECT status FROM users WHERE id=? FOR UPDATE",id);
   if(users.isEmpty() || !Set.of("active","deactivation_pending").contains(users.getFirst().get("status")))
     throw new ApiError(403,"ACCOUNT_DEACTIVATED","账号当前不可写入");
 }
}
