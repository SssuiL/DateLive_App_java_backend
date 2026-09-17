package com.manliao.backend.billing;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.manliao.backend.admin.*;
@RestController @RequestMapping("/admin/billing")
public class AdminBillingController {
 private final AdminService admin;private final WalletService wallet;
 public AdminBillingController(AdminService admin,WalletService wallet){this.admin=admin;this.wallet=wallet;}
 @GetMapping("/transactions") public List<Map<String,Object>> transactions(@AuthenticationPrincipal AdminDtos.Principal actor,
  @RequestParam(required=false) String user_id,@RequestParam(required=false) String transaction_type,@RequestParam(required=false) String reference_type,
  @RequestParam(required=false) String reference_id,@RequestParam(required=false) String idempotency_key,@RequestParam(defaultValue="100") int limit){
  admin.require(actor,"billing.read",false);return wallet.transactions(user_id,transaction_type,reference_type,reference_id,idempotency_key,limit,null);
 }
 @GetMapping("/accounts") public List<Map<String,Object>> accounts(@AuthenticationPrincipal AdminDtos.Principal actor,@RequestParam(required=false) String owner_id,@RequestParam(required=false) String account_type){admin.require(actor,"billing.read",false);return wallet.accounts(owner_id,account_type);}
}
