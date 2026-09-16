package com.manliao.backend;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import com.manliao.backend.admin.*;
import com.manliao.backend.common.ApiError;
import static org.assertj.core.api.Assertions.*;
class AdminTokensTests {
 static final String SECRET="isolated-admin-token-test-secret-over-thirty-two-bytes";
 String signed(Instant expiry,String type,boolean identity)throws Exception {
   var claims=new JWTClaimsSet.Builder().claim("type",type);
   if(expiry!=null)claims.expirationTime(Date.from(expiry));
   if(identity)claims.subject("admin_test").claim("version",0L).claim("fingerprint","test-fingerprint");
   var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),claims.build());
   jwt.sign(new MACSigner(MessageDigest.getInstance("SHA-256").digest(("admin-access-v1:"+SECRET).getBytes(StandardCharsets.UTF_8))));
   return jwt.serialize();
 }
 @Test void validTokenAndForeignSignature()throws Exception {
   var tokens=new AdminTokens(SECRET);
   var principal=new AdminDtos.Principal("admin_test",3,"fingerprint");
   String token=tokens.sign(principal,"admin_access",null,Instant.now().plusSeconds(60));
   assertThat(tokens.decode(token,"admin_access").principal()).isEqualTo(principal);
   assertThatThrownBy(()->new AdminTokens(SECRET+"different").decode(token,"admin_access")).isInstanceOf(ApiError.class);
 }
 @Test void expiredMissingExpiryAndMissingIdentityAreRejected()throws Exception {
   var tokens=new AdminTokens(SECRET);
   for(String token:new String[]{signed(Instant.now().minusSeconds(1),"admin_access",true),signed(null,"admin_access",true),
     signed(Instant.now().plusSeconds(60),"admin_access",false)})
     assertThatThrownBy(()->tokens.decode(token,"admin_access")).isInstanceOf(ApiError.class);
 }
 @Test void tokenPurposesAndPreviewTargetAreRequired()throws Exception {
   var tokens=new AdminTokens(SECRET);
   String access=signed(Instant.now().plusSeconds(60),"admin_access",true);
   String preview=signed(Instant.now().plusSeconds(60),"admin_preview",true);
   assertThatThrownBy(()->tokens.decode(access,"admin_preview")).isInstanceOf(ApiError.class);
   assertThatThrownBy(()->tokens.decode(preview,"admin_preview")).isInstanceOf(ApiError.class);
   assertThatThrownBy(()->tokens.decode(preview,"admin_access")).isInstanceOf(ApiError.class);
 }
}
