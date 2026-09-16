package com.manliao.backend.identity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() {}
    public record Registration(
        @NotBlank @Size(min=5, max=32) String phone,
        @NotBlank @Size(min=6, max=128) String password,
        @NotBlank @Size(max=32) String nickname,
        @JsonProperty("device_id") @Size(min=8, max=128) String deviceId,
        @JsonProperty("device_name") @Size(max=120) String deviceName,
        @Size(max=32) String platform) {}
    public record Login(
        @NotBlank @Size(max=32) String phone,
        @NotBlank @Size(max=128) String password,
        @JsonProperty("device_id") @Size(min=8, max=128) String deviceId,
        @JsonProperty("device_name") @Size(max=120) String deviceName,
        @Size(max=32) String platform) {}
    public record Refresh(@JsonProperty("refresh_token") @NotBlank @Size(min=16, max=128) String token) {}
    public record Tokens(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("user_id") String userId,
        @JsonProperty("session_id") String sessionId) {}
    public record Principal(String userId, String sessionId) {}
}
