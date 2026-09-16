package com.manliao.backend.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import com.manliao.backend.common.ApiError;

@Component
public class TokenCodec {
    private final JwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final SecureRandom random = new SecureRandom();
    public TokenCodec(@Value("${app.auth.jwt-secret}") String secret) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) throw new IllegalStateException("JAVA_JWT_SECRET must contain at least 32 UTF-8 bytes");
        var secretKey = new SecretKeySpec(key, "HmacSHA256");
        encoder = new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
        decoder = NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new JwtTimestampValidator(Duration.ZERO));
    }
    public String access(String userId, String sessionId) {
        Instant now = Instant.now();
        return encoder.encode(JwtEncoderParameters.from(
            JwsHeader.with(MacAlgorithm.HS256).build(),
            JwtClaimsSet.builder().subject(userId).issuedAt(now).expiresAt(now.plusSeconds(1800))
                .claim("type", "access").claim("sid", sessionId).build())).getTokenValue();
    }
    public AuthDtos.Principal decode(String token) {
        if (token.length() > 4096) throw invalid();
        try {
            Jwt jwt = decoder.decode(token);
            String sid = jwt.getClaimAsString("sid");
            if (!"access".equals(jwt.getClaimAsString("type")) || jwt.getExpiresAt() == null
                || jwt.getSubject() == null || sid == null || sid.isBlank()) throw invalid();
            return new AuthDtos.Principal(jwt.getSubject(), sid);
        } catch (JwtException | IllegalArgumentException error) {
            throw invalid();
        }
    }
    public String refresh() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
    public String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
    private ApiError invalid() { return new ApiError(401, "AUTH_INVALID_TOKEN", "登录凭证无效"); }
}
