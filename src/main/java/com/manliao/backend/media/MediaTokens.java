package com.manliao.backend.media;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import com.manliao.backend.common.ApiError;
import com.manliao.backend.identity.AuthDtos.Principal;
@Component
public class MediaTokens {
 private final JwtEncoder encoder;private final NimbusJwtDecoder decoder;
 public MediaTokens(@Value("${app.auth.jwt-secret}") String secret) throws Exception {
   var key=new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(("media-access-v1:"+secret).getBytes(StandardCharsets.UTF_8)),"HmacSHA256");
   encoder=new NimbusJwtEncoder(new ImmutableSecret<>(key));
   decoder=NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
   decoder.setJwtValidator(new JwtTimestampValidator(Duration.ZERO));
 }
 public record Grant(Principal principal,String mediaId,String variant) {}
 public String sign(Principal user,String id,Instant expires) {return sign(user,id,expires,"original");}
 public String sign(Principal user,String id,Instant expires,String variant) {
   return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
     JwtClaimsSet.builder().subject(user.userId()).claim("sid",user.sessionId()).claim("media_id",id)
      .claim("variant",variant).claim("type","media_access").issuedAt(Instant.now()).expiresAt(expires).build())).getTokenValue();
 }
 public Grant decode(String token) {
   try {
     if(token.length()>4096) throw new IllegalArgumentException();
     var jwt=decoder.decode(token);
     String sid=jwt.getClaimAsString("sid"),id=jwt.getClaimAsString("media_id");
     if(!"media_access".equals(jwt.getClaimAsString("type")) || jwt.getExpiresAt()==null ||
       jwt.getSubject()==null || sid==null || id==null || !id.matches("media_[a-f0-9]{32}")) throw new IllegalArgumentException();
     String variant=java.util.Optional.ofNullable(jwt.getClaimAsString("variant")).orElse("original");
     if(!java.util.Set.of("original","thumbnail","cover").contains(variant))throw new IllegalArgumentException();
     return new Grant(new Principal(jwt.getSubject(),sid),id,variant);
   }catch(JwtException|IllegalArgumentException e){throw new ApiError(404,"MEDIA_NOT_FOUND","媒体地址无效或已过期");}
 }
}
