package com.manliao.backend;
import com.manliao.backend.identity.PasswordHasher;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class PasswordHasherTests {
 private final PasswordHasher hasher=new PasswordHasher();
 @Test void verifiesPythonUtf8GoldenVector() {
   String hash="pbkdf2_sha256$0123456789abcdef0123456789abcdef$970cb48086b53d3678de8423907bce5d2a4a87d2fbd96e49168f66da984cc94a";
   assertThat(hasher.matches("Test-password-中文123",hash)).isTrue();
   assertThat(hasher.matches("wrong",hash)).isFalse();
 }
 @Test void newHashesAreSaltedAndVerify() {
   String first=hasher.hash("hello-中文-password");
   String second=hasher.hash("hello-中文-password");
   assertThat(first).isNotEqualTo(second);
   assertThat(hasher.matches("hello-中文-password",first)).isTrue();
   assertThat(hasher.matches("hello-中文-password!",first)).isFalse();
 }
 @Test void corruptOrUnboundedHashCostsFailClosed() {
   assertThat(hasher.matches("password","broken")).isFalse();
   assertThat(hasher.matches("password",null)).isFalse();
   assertThat(hasher.matches("password","pbkdf2_sha256$999999999$0123456789abcdef0123456789abcdef$"+"0".repeat(64))).isFalse();
 }
}
