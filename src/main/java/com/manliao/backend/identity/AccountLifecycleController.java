package com.manliao.backend.identity;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.manliao.backend.common.RequestContextFilter;
@RestController
@RequestMapping("/auth/account")
public class AccountLifecycleController {
 private final AccountLifecycleService accounts;
 public AccountLifecycleController(AccountLifecycleService accounts){this.accounts=accounts;}
 @PostMapping("/deactivate")
 public Map<String,Object> deactivate(@AuthenticationPrincipal AuthDtos.Principal user,HttpServletRequest request){
   return accounts.deactivate(user,RequestContextFilter.requestId(request));
 }
 @PostMapping("/restore")
 public Map<String,Object> restore(@AuthenticationPrincipal AuthDtos.Principal user,HttpServletRequest request){
   return accounts.restore(user,RequestContextFilter.requestId(request));
 }
}
