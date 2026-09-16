package com.manliao.backend.social;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.manliao.backend.identity.AuthDtos.Principal;
@RestController
public class SocialController {
 private final SocialService social;
 public SocialController(SocialService social){this.social=social;}
 @GetMapping({"/users","/users/"})
 public List<Map<String,Object>> search(@AuthenticationPrincipal Principal u,@RequestParam(required=false) String q,
   @RequestParam(defaultValue="20") String limit){return social.search(u.userId(),q,limit);}
 @GetMapping("/users/{id}")
 public Map<String,Object> user(@AuthenticationPrincipal Principal u,@PathVariable String id){return social.user(u.userId(),id);}
 @PostMapping("/friends/requests")
 public Map<String,Object> request(@AuthenticationPrincipal Principal u,@Valid @RequestBody SocialDtos.FriendRequest body){return social.request(u,body);}
 @GetMapping("/friends/requests")
 public List<Map<String,Object>> requests(@AuthenticationPrincipal Principal u){return social.requests(u.userId());}
 @PostMapping("/friends/requests/{id}/accept")
 public Map<String,Object> accept(@AuthenticationPrincipal Principal u,@PathVariable String id){return social.resolve(u,id,true);}
 @PostMapping("/friends/requests/{id}/reject")
 public Map<String,Object> reject(@AuthenticationPrincipal Principal u,@PathVariable String id){return social.resolve(u,id,false);}
 @GetMapping({"/friends","/friends/"})
 public List<Map<String,Object>> friends(@AuthenticationPrincipal Principal u){return social.friends(u.userId());}
 @DeleteMapping("/friends/{id}")
 public Map<String,String> deleteFriend(@AuthenticationPrincipal Principal u,@PathVariable String id){social.deleteFriend(u,id);return Map.of("status","deleted");}
 @PostMapping("/safety/blocks")
 public Map<String,Object> block(@AuthenticationPrincipal Principal u,@Valid @RequestBody SocialDtos.Block body){return social.block(u,body);}
 @GetMapping("/safety/blocks")
 public List<Map<String,Object>> blocks(@AuthenticationPrincipal Principal u){return social.blocks(u.userId());}
 @DeleteMapping("/safety/blocks/{id}")
 public ResponseEntity<Void> unblock(@AuthenticationPrincipal Principal u,@PathVariable String id){social.unblock(u,id);return ResponseEntity.noContent().build();}
}
