package com.manliao.backend.admin;
import java.io.IOException;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.InputStreamResource;
import com.manliao.backend.common.RequestContextFilter;
@RestController
@RequestMapping("/admin/moderation")
public class AdminModerationController {
 private final AdminModerationService moderation;
 public AdminModerationController(AdminModerationService moderation){this.moderation=moderation;}
 @GetMapping("/media")
 public List<Map<String,Object>> list(@AuthenticationPrincipal AdminDtos.Principal actor,@RequestParam(required=false) String status,
   @RequestParam(name="media_type",required=false) String type,@RequestParam(name="owner_user_id",required=false) String owner,
   @RequestParam(required=false) String source,@RequestParam(name="conversation_id",required=false) String conversation,
   @RequestParam(name="post_id",required=false) String post,@RequestParam(name="message_id",required=false) String message){
   return moderation.list(actor,status,type,owner,source,conversation,post,message);
 }
 @GetMapping("/media/pending")
 public List<Map<String,Object>> pending(@AuthenticationPrincipal AdminDtos.Principal actor){return moderation.list(actor,"review_pending",null,null,null,null,null,null);}
 @GetMapping("/media/{id}")
 public Map<String,Object> get(@AuthenticationPrincipal AdminDtos.Principal actor,@PathVariable String id){return moderation.get(actor,id);}
 @PostMapping("/media/{id}/preview-url")
 public Map<String,Object> preview(@AuthenticationPrincipal AdminDtos.Principal actor,@PathVariable String id,@RequestParam(required=false) String variant,
   HttpServletRequest r){return moderation.preview(actor,id,variant,RequestContextFilter.requestId(r));}
 @GetMapping("/access/{token}")
 public ResponseEntity<InputStreamResource> read(@PathVariable String token,@RequestParam(required=false) String variant,
   @RequestHeader(value="Range",required=false) String range)throws IOException {
   return com.manliao.backend.media.MediaResponse.build(moderation.read(token,variant),range);
 }
 @PostMapping("/media/{id}/approve")
 public Map<String,Object> approve(@AuthenticationPrincipal AdminDtos.Principal actor,@PathVariable String id,@Valid @RequestBody AdminDtos.Review input,
   HttpServletRequest r){return moderation.review(actor,id,true,input,RequestContextFilter.requestId(r));}
 @PostMapping("/media/{id}/reject")
 public Map<String,Object> reject(@AuthenticationPrincipal AdminDtos.Principal actor,@PathVariable String id,@Valid @RequestBody AdminDtos.Review input,
   HttpServletRequest r){return moderation.review(actor,id,false,input,RequestContextFilter.requestId(r));}
 @GetMapping("/reviews")
 public List<Map<String,Object>> reviews(@AuthenticationPrincipal AdminDtos.Principal actor,
   @RequestParam(name="target_type",required=false) String type,@RequestParam(name="target_id",required=false) String id){return moderation.reviews(actor,type,id);}
}
