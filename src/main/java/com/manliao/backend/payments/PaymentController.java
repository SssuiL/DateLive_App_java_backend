package com.manliao.backend.payments;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.manliao.backend.identity.AuthDtos.Principal;
@RestController @RequestMapping("/payments")
public class PaymentController {
 public record Create(@NotBlank @Size(min=2,max=48) String package_code,@Size(min=2,max=32) String provider,@NotBlank @Size(min=8,max=64) String client_request_id){}
 public record Complete(@Size(min=8,max=128) String event_id,@Size(min=8,max=128) String provider_trade_no){}
 private final PaymentService payments;public PaymentController(PaymentService payments){this.payments=payments;}
 @GetMapping("/packages") public List<PaymentService.Package> packages(){return PaymentService.PACKAGES;}
 @PostMapping("/orders") public Map<String,Object> create(@AuthenticationPrincipal Principal actor,@Valid @RequestBody Create input){return payments.create(actor,input);}
 @GetMapping("/orders") public List<Map<String,Object>> list(@AuthenticationPrincipal Principal actor){return payments.list(actor);}
 @GetMapping("/orders/{id}") public Map<String,Object> get(@AuthenticationPrincipal Principal actor,@PathVariable String id){return payments.get(actor,id);}
 @PostMapping("/orders/{id}/mock/complete") public Map<String,Object> complete(@AuthenticationPrincipal Principal actor,@PathVariable String id,@Valid @RequestBody Complete input){return payments.complete(actor,id,input);}
}
