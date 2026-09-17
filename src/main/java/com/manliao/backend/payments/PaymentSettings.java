package com.manliao.backend.payments;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class PaymentSettings {
 @Value("${app.environment:production}") String environment;
 @Value("${app.payments.provider:disabled}") String provider;
 @Value("${app.payments.mock-enabled:false}") boolean mockEnabled;
 @Value("${app.payments.callback-secret:}") String callbackSecret;
 @Value("${app.payments.wechat-enabled:false}") boolean wechatEnabled;
 @Value("${app.payments.wechat-app-id:}") String appId;
 @Value("${app.payments.wechat-merchant-id:}") String merchantId;
 @Value("${app.payments.wechat-merchant-serial:}") String merchantSerial;
 @Value("${app.payments.wechat-private-key-path:}") String privateKeyPath;
 @Value("${app.payments.wechat-public-key-path:}") String publicKeyPath;
 @Value("${app.payments.wechat-public-key-id:}") String publicKeyId;
 @Value("${app.payments.wechat-api-v3-key:}") String apiV3Key;
 @Value("${app.payments.wechat-notify-url:}") String notifyUrl;
}
