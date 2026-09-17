package com.manliao.backend.groups;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.manliao.backend.identity.AuthDtos.Principal;
@RestController
@RequestMapping("/groups")
public class GroupController {
 private final GroupService groups;
 public GroupController(GroupService groups){this.groups=groups;}
 @PostMapping("/") public Map<String,Object> create(@AuthenticationPrincipal Principal u,@Valid @RequestBody GroupDtos.Create p){return groups.create(u,p);}
 @GetMapping("/") public List<Map<String,Object>> list(@AuthenticationPrincipal Principal u){return groups.list(u.userId());}
 @GetMapping("/search") public List<Map<String,Object>> search(@AuthenticationPrincipal Principal u,@RequestParam(required=false) String q){return groups.search(u.userId(),q);}
 @GetMapping("/{id}") public Map<String,Object> get(@AuthenticationPrincipal Principal u,@PathVariable String id){return groups.get(u.userId(),id);}
 @PostMapping("/{id}/invite") public Map<String,Object> invite(@AuthenticationPrincipal Principal u,@PathVariable String id,@Valid @RequestBody GroupDtos.Target p){return groups.invite(u,id,p.target_user_id());}
 @PostMapping("/{id}/join-requests") public Map<String,Object> join(@AuthenticationPrincipal Principal u,@PathVariable String id,@Valid @RequestBody GroupDtos.Join p){return groups.join(u,id,p);}
 @PostMapping("/join-by-link-code") public Map<String,Object> link(@AuthenticationPrincipal Principal u,@RequestParam String link_code,@Valid @RequestBody GroupDtos.Join p){return groups.joinByCode(u,link_code,p);}
 @GetMapping("/{id}/members") public List<Map<String,Object>> members(@AuthenticationPrincipal Principal u,@PathVariable String id){return groups.members(u.userId(),id);}
 @GetMapping("/{id}/join-requests") public List<Map<String,Object>> requests(@AuthenticationPrincipal Principal u,@PathVariable String id){return groups.requests(u.userId(),id);}
 @PostMapping("/{id}/join-requests/{request}/approve") public Map<String,Object> approve(@AuthenticationPrincipal Principal u,@PathVariable String id,@PathVariable String request){return groups.resolve(u,id,request,true);}
 @PostMapping("/{id}/join-requests/{request}/reject") public Map<String,Object> reject(@AuthenticationPrincipal Principal u,@PathVariable String id,@PathVariable String request){return groups.resolve(u,id,request,false);}
 @PatchMapping("/{id}/members/{target}/role") public Map<String,Object> role(@AuthenticationPrincipal Principal u,@PathVariable String id,@PathVariable String target,@Valid @RequestBody GroupDtos.Role p){return groups.role(u,id,target,p.role());}
 @DeleteMapping("/{id}/members/{target}") public Map<String,String> remove(@AuthenticationPrincipal Principal u,@PathVariable String id,@PathVariable String target){return groups.remove(u,id,target,false);}
 @PostMapping("/{id}/leave") public Map<String,String> leave(@AuthenticationPrincipal Principal u,@PathVariable String id){return groups.remove(u,id,u.userId(),true);}
 @PostMapping("/{id}/transfer-owner") public Map<String,Object> transfer(@AuthenticationPrincipal Principal u,@PathVariable String id,@Valid @RequestBody GroupDtos.Target p){return groups.transfer(u,id,p.target_user_id());}
 @DeleteMapping("/{id}") public Map<String,String> dissolve(@AuthenticationPrincipal Principal u,@PathVariable String id){return groups.dissolve(u,id);}
}
