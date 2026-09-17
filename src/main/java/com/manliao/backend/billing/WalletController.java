package com.manliao.backend.billing;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.manliao.backend.identity.AuthDtos.Principal;
@RestController @RequestMapping("/wallet")
public class WalletController {
 public record Recharge(@NotNull @Min(1) @Max(100000) Long amount,@Size(max=120) String remark,@Size(min=8,max=64) String client_request_id){}
 private final WalletService wallet;
 public WalletController(WalletService wallet){this.wallet=wallet;}
 @GetMapping("/me") public Map<String,Object> me(@AuthenticationPrincipal Principal u){return wallet.get(u);}
 @PostMapping("/recharge/dev") public Map<String,Object> recharge(@AuthenticationPrincipal Principal u,@Valid @RequestBody Recharge input){return wallet.recharge(u,input);}
 @GetMapping("/transactions") public List<Map<String,Object>> transactions(@AuthenticationPrincipal Principal u){return wallet.transactions(u);}
 @GetMapping("/ledger") public List<Map<String,Object>> ledger(@AuthenticationPrincipal Principal u,@RequestParam(required=false) String transaction_type){return wallet.ledger(u,transaction_type);}
 @GetMapping("/bills") public List<Map<String,Object>> bills(@AuthenticationPrincipal Principal u,@RequestParam(defaultValue="all") String direction,@RequestParam(required=false) String transaction_type,@RequestParam(defaultValue="100") int limit){return wallet.bills(u,direction,transaction_type,limit);}
 @GetMapping("/summary") public Map<String,Object> summary(@AuthenticationPrincipal Principal u,@RequestParam(defaultValue="30") int days){return wallet.summary(u,days);}
}
