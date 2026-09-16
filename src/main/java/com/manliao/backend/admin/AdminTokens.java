package com.manliao.backend.admin;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import com.manliao.backend.common.ApiError;
@Component
public class AdminTokens {
 private final JwtEncoder encoder;private final NimbusJwtDecoder decoder;
 public AdminTokens(@Value("${app.auth.jwt-secret}") String secret)throws Exception {
   var key=new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(("admin-access-v1:"+secret).getBytes(StandardCharsets.UTF_8)),"HmacSHA256");
   encoder=new NimbusJwtEncoder(new ImmutableSecret<>(key));
   decoder=NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
   decoder.setJwtValidator(new JwtTimestampValidator(Duration.ZERO));
 }
 public String fingerprint(String hash) {
   try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(hash.getBytes(StandardCharsets.UTF_8)));}
   catch(Exception e){throw new IllegalStateException(e);}
 }
 public record Grant(AdminDtos.Principal principal,String mediaId,String variant) {}
 public String sign(AdminDtos.Principal user,String type,String media,Instant expires) {return sign(user,type,media,expires,"original");}
 public String sign(AdminDtos.Principal user,String type,String media,Instant expires,String variant) {
   var claims=JwtClaimsSet.builder().subject(user.id()).issuedAt(Instant.now()).expiresAt(expires)
     .claim("type",type).claim("version",user.version()).claim("fingerprint",user.fingerprint());
   if(media!=null)claims.claim("media_id",media).claim("variant",variant);
   return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims.build())).getTokenValue();
 }
 public Grant decode(String token,String type) {
   try {
     if(token.length()>4096)throw new IllegalArgumentException();
     var jwt=decoder.decode(token);Number version=jwt.getClaim("version");
     String fingerprint=jwt.getClaimAsString("fingerprint"),media=jwt.getClaimAsString("media_id");
     if(!type.equals(jwt.getClaimAsString("type")) || jwt.getExpiresAt()==null || jwt.getSubject()==null ||
       version==null || fingerprint==null || (type.equals("admin_preview") && media==null))throw new IllegalArgumentException();
     String variant=Optional.ofNullable(jwt.getClaimAsString("variant")).orElse("original");
     if(!Set.of("original","thumbnail","cover").contains(variant))throw new IllegalArgumentException();
     return new Grant(new AdminDtos.Principal(jwt.getSubject(),version.longValue(),fingerprint),media,variant);
   }catch(RuntimeException e){throw new ApiError(401,"AUTH_INVALID_TOKEN","管理员凭证无效或已过期");}
 }
}
