package com.manliao.backend.identity;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.manliao.backend.common.ApiError;

@Component
public class AuthRateLimiter {
    private final JdbcTemplate db;
    private final TokenCodec tokens;
    private static final Map<String,Integer> LIMITS = Map.of(
        "/auth/register",30, "/auth/login",60, "/auth/refresh",120, "/auth/logout",120, "/media/upload",10, "/admin/auth/login",10,
        "/auth/sms/send",30, "/auth/sms/login",60, "/auth/register/verify",30, "/auth/password/reset",30);
    public AuthRateLimiter(JdbcTemplate db, TokenCodec tokens) { this.db = db; this.tokens = tokens; }
    public void admit(String method, String path, String remoteAddress) {
        Integer limit = LIMITS.get(path);
        if (!method.equals("POST") || limit == null) return;
        // Deliberately ignore untrusted X-Forwarded-For; trusted proxy support is a separate deployment setting.
        String key = path + ":" + tokens.digest(remoteAddress);
        Integer hits = db.queryForObject("""
            INSERT INTO auth_rate_windows(bucket_key,hits,expires_at)
            VALUES (?,1,now()+interval '60 seconds')
            ON CONFLICT(bucket_key) DO UPDATE SET
              hits=CASE WHEN auth_rate_windows.expires_at<=now() THEN 1 ELSE auth_rate_windows.hits+1 END,
              expires_at=CASE WHEN auth_rate_windows.expires_at<=now() THEN now()+interval '60 seconds' ELSE auth_rate_windows.expires_at END
            RETURNING hits
            """, Integer.class, key);
        if (hits != null && hits > limit) throw new ApiError(429, "AUTH_RATE_LIMITED", "请求过于频繁，请稍后再试");
    }
}
