package com.manliao.backend.identity;

import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.manliao.backend.common.RequestContextFilter;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }
    @PostMapping("/register")
    public AuthDtos.Tokens register(@Valid @RequestBody AuthDtos.Registration input, HttpServletRequest request) {
        return auth.register(input, RequestContextFilter.requestId(request));
    }
    @PostMapping("/login")
    public AuthDtos.Tokens login(@Valid @RequestBody AuthDtos.Login input, HttpServletRequest request) {
        return auth.login(input, RequestContextFilter.requestId(request));
    }
    @PostMapping("/refresh")
    public AuthDtos.Tokens refresh(@Valid @RequestBody AuthDtos.Refresh input, HttpServletRequest request) {
        return auth.refresh(input.token(), RequestContextFilter.requestId(request));
    }
    @PostMapping("/logout")
    public Map<String,String> logout(@Valid @RequestBody AuthDtos.Refresh input, HttpServletRequest request) {
        auth.logout(input.token(), RequestContextFilter.requestId(request));
        return Map.of("status", "ok");
    }
    @PostMapping("/sessions/revoke-others")
    public Map<String,Integer> revokeOthers(@AuthenticationPrincipal AuthDtos.Principal principal,
            HttpServletRequest request) {
        return auth.revokeOthers(principal,RequestContextFilter.requestId(request));
    }
    @GetMapping("/me")
    public Map<String,Object> me(@AuthenticationPrincipal AuthDtos.Principal principal) { return auth.me(principal); }
    @GetMapping("/sessions")
    public List<Map<String,Object>> sessions(@AuthenticationPrincipal AuthDtos.Principal principal) { return auth.sessions(principal); }
    @DeleteMapping("/sessions/{sessionId}")
    public Map<String,String> revoke(@AuthenticationPrincipal AuthDtos.Principal principal,
            @PathVariable String sessionId, HttpServletRequest request) {
        auth.revoke(principal, sessionId, RequestContextFilter.requestId(request));
        return Map.of("status", "ok", "session_id", sessionId);
    }
}
