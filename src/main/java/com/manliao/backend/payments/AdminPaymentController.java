package com.manliao.backend.payments;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.manliao.backend.admin.*;
@RestController @RequestMapping("/admin/payments")
public class AdminPaymentController {
 private final AdminService admin;private final PaymentService payments;
 public AdminPaymentController(AdminService admin,PaymentService payments){this.admin=admin;this.payments=payments;}
 @GetMapping("/orders") public List<Map<String,Object>> orders(@AuthenticationPrincipal AdminDtos.Principal actor,@RequestParam(required=false) String user_id,@RequestParam(required=false) String provider,@RequestParam(required=false) String status,@RequestParam(required=false) String merchant_order_no,@RequestParam(defaultValue="100") int limit){admin.require(actor,"payments.read",false);return payments.adminOrders(user_id,provider,status,merchant_order_no,limit);}
 @GetMapping("/callback-events") public List<Map<String,Object>> callbacks(@AuthenticationPrincipal AdminDtos.Principal actor,@RequestParam(required=false) String provider,@RequestParam(required=false) String processing_status,@RequestParam(required=false) String merchant_order_no,@RequestParam(defaultValue="100") int limit){admin.require(actor,"payments.read",false);return payments.adminCallbacks(provider,processing_status,merchant_order_no,limit);}
}
