package com.manliao.backend.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Component;

@Component
public class PasswordHasher {
    private static final int ITERATIONS = 600_000;
    private final SecureRandom random = new SecureRandom();
    public String hash(String password) {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        String salt = HexFormat.of().formatHex(bytes);
        return "pbkdf2_sha256$" + ITERATIONS + "$" + salt + "$" + derive(password, salt, ITERATIONS);
    }
    public boolean matches(String password, String encoded) {
        if (encoded == null || encoded.length() > 255) return false;
        try {
            String[] parts = encoded.split("\\$", -1);
            if (!parts[0].equals("pbkdf2_sha256") || (parts.length != 3 && parts.length != 4)) return false;
            int iterations = parts.length == 3 ? 120_000 : Integer.parseInt(parts[1]);
            if (iterations < 120_000 || iterations > 2_000_000) return false;
            String salt = parts[parts.length - 2];
            String expected = parts[parts.length - 1];
            if (!salt.matches("[0-9a-f]{32}") || !expected.matches("[0-9a-f]{64}")) return false;
            return MessageDigest.isEqual(HexFormat.of().parseHex(expected),
                HexFormat.of().parseHex(derive(password, salt, iterations)));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }
    private String derive(String password, String salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(),
            salt.getBytes(StandardCharsets.UTF_8), iterations, 256);
        try {
            return HexFormat.of().formatHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).getEncoded());
        } catch (java.security.GeneralSecurityException error) {
            throw new IllegalStateException("PBKDF2 unavailable", error);
        } finally {
            spec.clearPassword();
        }
    }
}
