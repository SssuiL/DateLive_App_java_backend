package com.manliao.backend.posts;
import java.util.List;
import jakarta.validation.constraints.*;
public final class PostDtos {
 public record Create(@Pattern(regexp="text|image|voice|video") String type,@Size(max=1000) String text,
  @Size(max=9) List<@NotBlank @Size(max=64) String> media_asset_ids,@Pattern(regexp="public|friends|private") String visibility,Boolean allow_media_save){}
 public record Comment(@Size(max=500) String content,@Size(max=64) String media_asset_id,
  @Pattern(regexp="image|gif|sticker") String media_kind,@Size(max=64) String parent_comment_id){}
 public record Reaction(@NotBlank @Size(max=32) String emoji){}
}