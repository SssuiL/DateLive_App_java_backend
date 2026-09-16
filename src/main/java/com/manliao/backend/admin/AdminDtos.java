package com.manliao.backend.admin;
import jakarta.validation.constraints.*;
public final class AdminDtos {
 public record Login(@NotBlank @Size(min=3,max=64) String username,@NotBlank @Size(min=8,max=128) String password) {}
 public record Create(@NotBlank @Size(min=3,max=64) String username,@NotBlank @Size(min=12,max=128) String password,
   @NotBlank @Size(max=64) String display_name,@NotBlank @Pattern(regexp="owner|operator|auditor") String role) {}
 public record SelfPassword(@NotBlank @Size(min=8,max=128) String current_password,@NotBlank @Size(min=12,max=128) String new_password) {}
 public record Password(@NotBlank @Size(min=12,max=128) String new_password) {}
 public record Status(@NotBlank @Pattern(regexp="active|disabled") String status,@Size(max=240) String reason) {}
 public record Review(@Size(max=240) String reason,@Size(max=64) String reviewer_id,Boolean portrait_manual_approved) {}
 public record Principal(String id,long version,String fingerprint) {}
}
