package com.manliao.backend.social;
import jakarta.validation.constraints.*;
public final class SocialDtos {
 private SocialDtos(){}
 public record FriendRequest(@NotBlank @Size(max=64) String target_user_id,@Size(max=120) String message){}
 public record Block(@NotBlank @Size(max=64) String target_user_id,
   @Pattern(regexp="block|hide_from_explore") String block_type){}
}
