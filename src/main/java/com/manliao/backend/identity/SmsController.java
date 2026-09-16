package com.manliao.backend.identity;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.manliao.backend.common.RequestContextFilter;
@RestController
@RequestMapping("/auth")
public class SmsController {
 private final SmsService sms;
 public SmsController(SmsService sms) { this.sms=sms; }
 @PostMapping("/sms/send")
 public SmsDtos.Dispatch send(@Valid @RequestBody SmsDtos.Send input) { return sms.send(input); }
 @PostMapping("/register/verify")
 public AuthDtos.Tokens register(@Valid @RequestBody SmsDtos.Register input,HttpServletRequest request) {
   return sms.register(input,RequestContextFilter.requestId(request));
 }
 @PostMapping("/sms/login")
 public AuthDtos.Tokens login(@Valid @RequestBody SmsDtos.Login input,HttpServletRequest request) {
   return sms.login(input,RequestContextFilter.requestId(request));
 }
 @PostMapping("/password/reset")
 public Map<String,String> reset(@Valid @RequestBody SmsDtos.Reset input,HttpServletRequest request) {
   return sms.reset(input,RequestContextFilter.requestId(request));
 }
}
