package com.manliao.backend.payments;
import java.util.*;
import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import com.manliao.backend.common.ApiError;
@RestController
public class PaymentCallbackController {
 private final PaymentService payments;public PaymentCallbackController(PaymentService payments){this.payments=payments;}
 @PostMapping("/callbacks/payments/{provider}") public Map<String,Object> receive(@PathVariable String provider,HttpServletRequest request)throws IOException{
  byte[] body=request.getInputStream().readNBytes(65537);if(body.length>65536)throw new ApiError(413,"COMMON_VALIDATION_ERROR","回调报文过大");
  var headers=new HashMap<String,String>();for(String name:Collections.list(request.getHeaderNames()))headers.put(name.toLowerCase(Locale.ROOT),request.getHeader(name));
  String normalized=provider.strip().toLowerCase(Locale.ROOT);var result=payments.receive(normalized,headers,body);
  if(normalized.equals("wechat_pay")){
   if(result.get("processing_status").equals("rejected"))throw PaymentGateway.invalid(400);
   return Map.of("code","SUCCESS","message","成功");
  }return result;
 }
}
