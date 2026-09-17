package com.manliao.backend.groups;
import java.util.List;
import jakarta.validation.constraints.*;
public final class GroupDtos {
 private GroupDtos(){}
 public record Create(@NotBlank @Size(max=48) String name,@NotEmpty @Size(max=12) List<@NotBlank @Size(max=32) String> tags,
  @Pattern(regexp="manual|open|password|question|invite_only") String join_rule_type,@Size(max=240) String join_question,
  @Size(max=120) String join_answer,@Size(max=120) String join_password,@Min(2) @Max(500) Integer max_members){}
 public record Target(@NotBlank @Size(max=64) String target_user_id){}
 public record Join(@Size(max=120) String answer,@Size(max=120) String password,@Pattern(regexp="search|link_code|invite") String source){}
 public record Role(@NotNull @Pattern(regexp="admin|member") String role){}
}
