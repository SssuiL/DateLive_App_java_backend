package com.manliao.backend.identity;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
public final class SmsDtos {
 private SmsDtos() {}
 public record Send(@NotBlank @Size(min=5,max=32) String phone,
   @NotBlank @Pattern(regexp="register|login|password_reset") String purpose) {}
 public record Login(@NotBlank @Size(min=5,max=32) String phone,
   @JsonProperty("request_id") @NotBlank @Size(min=8,max=64) String requestId,
   @NotBlank @Pattern(regexp="[0-9]{6}") String code,
   @JsonProperty("device_id") @Size(min=8,max=128) String deviceId,
   @JsonProperty("device_name") @Size(max=120) String deviceName, @Size(max=32) String platform) {}
 public record Register(@NotBlank @Size(min=5,max=32) String phone,
   @NotBlank @Size(min=8,max=128) String password, @NotBlank @Size(max=32) String nickname,
   @JsonProperty("request_id") @NotBlank @Size(min=8,max=64) String requestId,
   @NotBlank @Pattern(regexp="[0-9]{6}") String code,
   @JsonProperty("device_id") @Size(min=8,max=128) String deviceId,
   @JsonProperty("device_name") @Size(max=120) String deviceName, @Size(max=32) String platform) {}
 public record Reset(@NotBlank @Size(min=5,max=32) String phone,
   @JsonProperty("request_id") @NotBlank @Size(min=8,max=64) String requestId,
   @NotBlank @Pattern(regexp="[0-9]{6}") String code,
   @JsonProperty("new_password") @NotBlank @Size(min=8,max=128) String newPassword) {}
 public record Dispatch(@JsonProperty("request_id") String requestId,
   @JsonProperty("expires_in_seconds") int expires,
   @JsonProperty("retry_after_seconds") int retry,
   @JsonProperty("dev_code") String devCode) {}
}
