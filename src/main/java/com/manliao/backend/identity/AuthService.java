package com.manliao.backend.identity;

import java.sql.Timestamp;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.manliao.backend.common.ApiError;

@Service
public class AuthService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final PasswordHasher passwords;
    private final TokenCodec tokens;
    private final boolean legacyRegistration;
    // Equalize the unknown-user password verification path without holding a DB connection.
    private final String dummyHash;
    public AuthService(JdbcTemplate db, TransactionTemplate tx, PasswordHasher passwords, TokenCodec tokens,
            @Value("${app.auth.legacy-registration-enabled:false}") boolean legacyRegistration) {
        this.db = db; this.tx = tx; this.passwords = passwords; this.tokens = tokens;
        this.legacyRegistration = legacyRegistration;
        this.dummyHash = passwords.hash(UUID.randomUUID().toString());
    }
    public AuthDtos.Tokens register(AuthDtos.Registration input, String requestId) {
        if (!legacyRegistration) throw new ApiError(404, "COMMON_NOT_FOUND", "该注册入口未开放，请使用短信验证注册");
        String phone = normalizePhone(input.phone());
        // Expensive password derivation happens before opening a transaction.
        String hash = passwords.hash(input.password());
        return tx.execute(status -> {
            String userId = id("user");
            int inserted = db.update("""
                INSERT INTO users(id,phone,nickname,password_hash) VALUES (?,?,?,?)
                ON CONFLICT(phone) DO NOTHING
                """, userId, phone, input.nickname().strip(), hash);
            if (inserted == 0) throw new ApiError(409, "PHONE_ALREADY_REGISTERED", "手机号已注册");
            db.update("INSERT INTO user_profiles(user_id,nickname) VALUES (?,?)", userId, input.nickname().strip());
            var result = issue(userId, input.deviceId(), input.deviceName(), input.platform());
            audit(userId, result.sessionId(), "registration", "success", null, requestId);
            return result;
        });
    }
    public AuthDtos.Tokens login(AuthDtos.Login input, String requestId) {
        var user = one("SELECT id,password_hash,status FROM users WHERE phone=?", normalizePhone(input.phone()));
        boolean valid = passwords.matches(input.password(), user == null ? dummyHash : (String) user.get("password_hash"));
        if (user == null || !valid) {
            audit(null, null, "password_login", "failure", "AUTH_INVALID_CREDENTIALS", requestId);
            throw new ApiError(401, "AUTH_INVALID_CREDENTIALS", "手机号或密码错误");
        }
        return tx.execute(status -> {
            // Recheck the user under a row lock after CPU work to honor concurrent bans.
            var current = one("SELECT status,password_hash FROM users WHERE id=? FOR UPDATE", user.get("id"));
            requireUser(current);
            if (!Objects.equals(current.get("password_hash"),user.get("password_hash")))
                throw new ApiError(401,"AUTH_INVALID_CREDENTIALS","密码已变更，请重新登录");
            var result = issue((String) user.get("id"), input.deviceId(), input.deviceName(), input.platform());
            audit(result.userId(), result.sessionId(), "password_login", "success", null, requestId);
            return result;
        });
    }
    public AuthDtos.Tokens refresh(String token, String requestId) {
        return tx.execute(status -> {
            var owner = one("SELECT user_id FROM refresh_tokens WHERE token_hash=?", tokens.digest(token));
            if (owner == null) throw new ApiError(401,"AUTH_REFRESH_TOKEN_INVALID","刷新凭证无效");
            requireUser(one("SELECT status FROM users WHERE id=? FOR UPDATE",owner.get("user_id")));
            var session = one("SELECT *, expires_at <= now() AS expired FROM refresh_tokens WHERE token_hash=? FOR UPDATE", tokens.digest(token));
            if (session == null) throw new ApiError(401, "AUTH_REFRESH_TOKEN_INVALID", "刷新凭证无效");
            if (session.get("revoked_at") != null) throw new ApiError(401, "AUTH_REFRESH_TOKEN_REVOKED", "刷新凭证已失效");
            if (Boolean.TRUE.equals(session.get("expired"))) throw new ApiError(401, "AUTH_REFRESH_TOKEN_EXPIRED", "刷新凭证已过期，请重新登录");
            requireUser(one("SELECT status FROM users WHERE id=? FOR UPDATE", session.get("user_id")));
            var replacement = issue((String) session.get("user_id"), (String) session.get("device_id"),
                (String) session.get("device_name"), (String) session.get("platform"));
            db.update("""
                UPDATE refresh_tokens SET revoked_at=now(), revoked_reason='rotated',
                replaced_by_token_id=?, last_used_at=now() WHERE id=?
                """, replacement.sessionId(), session.get("id"));
            audit(replacement.userId(), replacement.sessionId(), "token_refresh", "success", null, requestId);
            return replacement;
        });
    }
    public void logout(String token, String requestId) {
        tx.executeWithoutResult(status -> {
            var owner=one("SELECT user_id FROM refresh_tokens WHERE token_hash=?",tokens.digest(token));
            if(owner==null)return;
            one("SELECT id FROM users WHERE id=? FOR UPDATE",owner.get("user_id"));
            var session = one("""
                UPDATE refresh_tokens SET revoked_at=COALESCE(revoked_at,now()),
                revoked_reason=COALESCE(revoked_reason,'logout') WHERE token_hash=?
                RETURNING user_id,id
                """, tokens.digest(token));
            if (session != null) audit((String) session.get("user_id"), (String) session.get("id"),
                "logout", "success", null, requestId);
        });
    }
    public AuthDtos.Principal authenticate(String token) {
        var principal = tokens.decode(token);
        var user = one("""
            SELECT u.status FROM refresh_tokens r JOIN users u ON u.id=r.user_id
            WHERE r.id=? AND r.user_id=? AND r.revoked_at IS NULL AND r.expires_at>now()
            """, principal.sessionId(), principal.userId());
        if (user == null) throw new ApiError(401, "AUTH_INVALID_TOKEN", "登录会话已失效");
        requireUser(user);
        return principal;
    }
    public Map<String,Object> me(AuthDtos.Principal principal) {
        var user = one("""
            SELECT id,phone,nickname,status,is_paid_member,membership_status,
            deactivation_requested_at,deactivation_due_at,deactivated_at,created_at
            FROM users WHERE id=?
            """, principal.userId());
        requireUser(user);
        return jsonRow(user);
    }
    public List<Map<String,Object>> sessions(AuthDtos.Principal principal) {
        return db.queryForList("""
            SELECT id,device_id,device_name,platform,id=? AS is_current,true AS is_active,
            last_used_at,expires_at,revoked_at,revoked_reason,created_at
            FROM refresh_tokens WHERE user_id=? AND revoked_at IS NULL AND expires_at>now()
            ORDER BY last_used_at DESC,created_at DESC LIMIT 30
            """, principal.sessionId(), principal.userId()).stream().map(this::jsonRow).toList();
    }
    public void revoke(AuthDtos.Principal principal, String sessionId, String requestId) {
        tx.executeWithoutResult(status -> {
            requireUser(one("SELECT status FROM users WHERE id=? FOR UPDATE",principal.userId()));
            if(one("SELECT id FROM refresh_tokens WHERE id=? AND user_id=? AND revoked_at IS NULL AND expires_at>now()",principal.sessionId(),principal.userId())==null)
                throw new ApiError(401,"AUTH_INVALID_TOKEN","登录会话已失效");
            int count = db.update("""
                UPDATE refresh_tokens SET revoked_at=COALESCE(revoked_at,now()),
                revoked_reason=COALESCE(revoked_reason,'device_removed') WHERE id=? AND user_id=?
                """, sessionId, principal.userId());
            if (count == 0) throw new ApiError(404, "AUTH_SESSION_NOT_FOUND", "登录设备不存在");
            audit(principal.userId(), sessionId, "device_session_revoke", "success", null, requestId);
        });
    }
    public Map<String,Integer> revokeOthers(AuthDtos.Principal principal, String requestId) {
        return tx.execute(status -> {
            requireUser(one("SELECT status FROM users WHERE id=? FOR UPDATE",principal.userId()));
            // Authentication can precede a concurrent revocation; recheck after taking the lock.
            if (one("SELECT id FROM refresh_tokens WHERE id=? AND user_id=? AND revoked_at IS NULL AND expires_at>now()",
                    principal.sessionId(),principal.userId()) == null)
                throw new ApiError(401,"AUTH_INVALID_TOKEN","登录会话已失效");
            int count=db.update("""
                UPDATE refresh_tokens SET revoked_at=now(),revoked_reason='other_devices_removed'
                WHERE user_id=? AND id<>? AND revoked_at IS NULL
                """,principal.userId(),principal.sessionId());
            audit(principal.userId(),principal.sessionId(),"device_sessions_revoke_others","success",null,requestId);
            return Map.of("revoked_count",count);
        });
    }
    AuthDtos.Tokens issue(String userId, String deviceId, String deviceName, String platform) {
        String sessionId = id("rt");
        String refresh = tokens.refresh();
        db.update("""
            INSERT INTO refresh_tokens(id,user_id,token_hash,expires_at,device_id,device_name,platform)
            VALUES (?,?,?,now()+interval '10 days',?,?,?)
            """, sessionId, userId, tokens.digest(refresh), deviceId, deviceName, platform);
        return new AuthDtos.Tokens(tokens.access(userId, sessionId), refresh, "bearer", userId, sessionId);
    }
    void audit(String userId, String sessionId, String event, String outcome, String code, String requestId) {
        db.update("""
            INSERT INTO auth_security_events(user_id,session_id,event_type,outcome,reason_code,request_id)
            VALUES (?,?,?,?,?,?)
            """, userId, sessionId, event, outcome, code, requestId);
    }
    Map<String,Object> one(String sql, Object... args) {
        var rows = db.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public Map<String,Object> jsonRow(Map<String,Object> row) {
        var result = new LinkedHashMap<String,Object>();
        row.forEach((key,value) -> result.put(key, value instanceof Timestamp time ? time.toInstant() : value));
        return result;
    }
    void requireUser(Map<String,Object> user) {
        if (user == null) throw new ApiError(404, "USER_NOT_FOUND", "用户不存在");
        switch ((String) user.get("status")) {
            case "active", "deactivation_pending" -> {}
            case "banned" -> throw new ApiError(403, "ACCOUNT_BANNED", "账号已被封禁");
            case "deactivated" -> throw new ApiError(403, "ACCOUNT_DEACTIVATED", "账号已注销");
            default -> throw new ApiError(403, "COMMON_BAD_REQUEST", "账号状态异常");
        }
    }
    String normalizePhone(String phone) {
        String normalized = phone.strip().replace(" ", "");
        if (normalized.length() < 5) throw new ApiError(422, "COMMON_VALIDATION_ERROR", "请求参数校验失败");
        return normalized;
    }
    String id(String prefix) { return prefix + "_" + UUID.randomUUID().toString().replace("-", ""); }
}
