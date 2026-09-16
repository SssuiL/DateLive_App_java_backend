package com.manliao.backend;
import com.manliao.backend.identity.TokenCodec;
import com.manliao.backend.common.ApiError;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class TokenCodecTests {
 static final String SECRET="isolated-token-codec-test-key-at-least-thirty-two-bytes";
 final TokenCodec codec=new TokenCodec(SECRET);
 @Test void issuedTokenAndForeignSignature() {
   var claims=codec.decode(codec.access("user_test","rt_test"));
   assertThat(claims.userId()).isEqualTo("user_test");
   assertThat(claims.sessionId()).isEqualTo("rt_test");
   var foreign=new TokenCodec("different-isolated-token-key-thirty-two-bytes");
   assertThatThrownBy(()->codec.decode(foreign.access("user_test","rt_test"))).isInstanceOf(ApiError.class);
 }
 @Test void expiredMissingExpiryAndWrongTypeAreRejected() {
   assertThatThrownBy(()->codec.decode(signed("access",Instant.now().minusSeconds(10)))).isInstanceOf(ApiError.class);
   assertThatThrownBy(()->codec.decode(signed("access",null))).isInstanceOf(ApiError.class);
   assertThatThrownBy(()->codec.decode(signed("refresh",Instant.now().plusSeconds(60)))).isInstanceOf(ApiError.class);
 }
 @Test void shortSigningSecretIsRejected() {
   assertThatThrownBy(()->new TokenCodec("short")).isInstanceOf(IllegalStateException.class);
 }
 String signed(String type,Instant expiry) {
   var builder=JwtClaimsSet.builder().subject("user_test").claim("type",type).claim("sid","rt_test");
   if(expiry!=null) builder.expiresAt(expiry);
   var encoder=new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256")));
   return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),builder.build())).getTokenValue();
 }
}
