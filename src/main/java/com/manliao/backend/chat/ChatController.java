package com.manliao.backend.chat;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.manliao.backend.identity.AuthDtos.Principal;
@RestController
@RequestMapping("/conversations")
public class ChatController {
 private final ChatService chat;
 public ChatController(ChatService chat){this.chat=chat;}
 @GetMapping({"","/"})
 public List<Map<String,Object>> list(@AuthenticationPrincipal Principal u){return chat.list(u.userId());}
 @GetMapping("/page")
 public Map<String,Object> page(@AuthenticationPrincipal Principal u,@RequestParam(defaultValue="30") String limit,
  @RequestParam(required=false) String cursor){return chat.page(u.userId(),limit,cursor);}
 @GetMapping("/{id}")
 public Map<String,Object> get(@AuthenticationPrincipal Principal u,@PathVariable String id){return chat.get(u.userId(),id);}
 @GetMapping("/{id}/messages")
 public List<Map<String,Object>> messages(@AuthenticationPrincipal Principal u,@PathVariable String id){return chat.messages(u.userId(),id);}
 @GetMapping("/{id}/messages/page")
 public Map<String,Object> history(@AuthenticationPrincipal Principal u,@PathVariable String id,
  @RequestParam(defaultValue="30") String limit,@RequestParam(required=false) String cursor){return chat.history(u.userId(),id,limit,cursor);}
 @PostMapping("/{id}/messages")
 public Map<String,Object> send(@AuthenticationPrincipal Principal u,@PathVariable String id,@Valid @RequestBody ChatDtos.Message body){return chat.send(u,id,body);}
 @PostMapping("/{id}/read")
 public Map<String,Object> read(@AuthenticationPrincipal Principal u,@PathVariable String id){return chat.read(u,id);}
 @PatchMapping("/{id}/settings")
 public Map<String,Object> settings(@AuthenticationPrincipal Principal u,@PathVariable String id,@Valid @RequestBody ChatDtos.Settings body){return chat.settings(u,id,body);}
 @PostMapping("/{id}/messages/{messageId}/recall")
 public Map<String,Object> recall(@AuthenticationPrincipal Principal u,@PathVariable String id,@PathVariable String messageId){return chat.recall(u,id,messageId);}
 @GetMapping("/{id}/messages/search")
 public Map<String,Object> search(@AuthenticationPrincipal Principal u,@PathVariable String id,
  @RequestParam(required=false) String q,@RequestParam(name="message_type",required=false) String type,
  @RequestParam(defaultValue="30") String limit,@RequestParam(required=false) String cursor){return chat.search(u.userId(),id,q,type,limit,cursor);}
 @DeleteMapping("/{id}/messages/{messageId}")
 public Map<String,String> hide(@AuthenticationPrincipal Principal u,@PathVariable String id,@PathVariable String messageId){return chat.hide(u,id,messageId);}
 @DeleteMapping("/{id}/messages")
 public Map<String,Object> clear(@AuthenticationPrincipal Principal u,@PathVariable String id){return chat.clear(u,id);}

}
