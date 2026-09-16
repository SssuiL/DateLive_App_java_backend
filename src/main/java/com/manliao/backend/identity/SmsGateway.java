package com.manliao.backend.identity;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.manliao.backend.common.ApiError;
/** Local delivery adapter. Real providers must be added and independently verified. */
@Component
public class SmsGateway {
 private final String provider;
 private final boolean returnCode;
 public SmsGateway(@Value("${app.sms.provider:disabled}") String provider,
                   @Value("${app.sms.return-dev-code:false}") boolean returnCode) {
   if (!provider.equals("disabled") && !provider.equals("development"))
     throw new IllegalArgumentException("Unsupported Java SMS provider");
   this.provider=provider; this.returnCode=returnCode;
 }
 public void requireAvailable() {
   if (!provider.equals("development"))
     throw new ApiError(503,"AUTH_SMS_PROVIDER_UNAVAILABLE","短信服务尚未配置");
 }
 public String send(String phone,String code,String purpose) {
   requireAvailable();
   return "dev_"+UUID.randomUUID();
 }
 public String provider() { return provider; }
 public String visibleCode(String code) { return provider.equals("development") && returnCode ? code : null; }
}
