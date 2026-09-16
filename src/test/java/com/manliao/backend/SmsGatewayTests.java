package com.manliao.backend;
import org.junit.jupiter.api.Test;
import com.manliao.backend.identity.SmsGateway;
import com.manliao.backend.common.ApiError;
import static org.assertj.core.api.Assertions.*;
class SmsGatewayTests {
 @Test void developmentCodeRequiresExplicitOptIn() {
   assertThat(new SmsGateway("development",false).visibleCode("123456")).isNull();
   assertThat(new SmsGateway("development",true).visibleCode("123456")).isEqualTo("123456");
 }
 @Test void disabledProviderNeverReturnsOrSendsCode() {
   var gateway=new SmsGateway("disabled",true);
   assertThat(gateway.visibleCode("123456")).isNull();
   assertThatThrownBy(()->gateway.send("13990000002","123456","login")).isInstanceOf(ApiError.class);
 }
 @Test void unsupportedProviderFailsConfigurationInsteadOfPretendingToSend() {
   assertThatThrownBy(()->new SmsGateway("tencent_cloud",false)).isInstanceOf(IllegalArgumentException.class);
 }
}
