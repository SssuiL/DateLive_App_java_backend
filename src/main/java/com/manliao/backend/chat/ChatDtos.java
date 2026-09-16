package com.manliao.backend.chat;
import jakarta.validation.constraints.*;
public final class ChatDtos {
 private ChatDtos(){}
 public record Settings(Boolean pinned,Boolean muted){}
 public record Message(
  @Size(min=8,max=64) String client_message_id,
  @Size(min=1,max=64) String reply_to_message_id,
  String type,
  @NotNull @Size(min=1,max=2000) String content,
  String media_asset_id,String media_kind,
  @Min(0) @Max(60) Integer duration_seconds){}
}
