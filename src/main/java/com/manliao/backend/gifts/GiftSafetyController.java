package com.manliao.backend.gifts;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import tools.jackson.databind.JsonNode;
import com.manliao.backend.admin.AdminDtos;
import com.manliao.backend.identity.AuthDtos.Principal;
import com.manliao.backend.common.RequestContextFilter;
@RestController
public class GiftSafetyController {
 public record Age(@NotNull @Pattern(regexp="unverified|adult|minor") String age_status,@NotBlank @Size(min=2,max=500) String reason){}
 public record Resolve(@NotNull @Pattern(regexp="reviewed|dismissed") String decision,@NotBlank @Size(min=2,max=500) String resolution){}
 private final GiftSafetyService safety;public GiftSafetyController(GiftSafetyService safety){this.safety=safety;}
 @GetMapping("/live/gift-safety/me") public Map<String,Object> get(@AuthenticationPrincipal Principal actor){return safety.get(actor);}
 @PatchMapping("/live/gift-safety/me") public Map<String,Object> update(@AuthenticationPrincipal Principal actor,@RequestBody JsonNode input){return safety.update(actor,input);}
 @GetMapping("/admin/gift-risk/events") public List<Map<String,Object>> list(@AuthenticationPrincipal AdminDtos.Principal actor,@RequestParam(required=false) String status,@RequestParam(required=false) String event_type,@RequestParam(required=false) String user_id,@RequestParam(defaultValue="100") int limit){return safety.list(actor,status,event_type,user_id,limit);}
 @PostMapping("/admin/gift-risk/events/{id}/resolve") public Map<String,Object> resolve(@AuthenticationPrincipal AdminDtos.Principal actor,@PathVariable String id,@Valid @RequestBody Resolve input,HttpServletRequest request){return safety.resolve(actor,id,input,RequestContextFilter.requestId(request));}
 @PatchMapping("/admin/gift-risk/users/{user}/age-status") public Map<String,Object> age(@AuthenticationPrincipal AdminDtos.Principal actor,@PathVariable String user,@Valid @RequestBody Age input,HttpServletRequest request){return safety.age(actor,user,input,RequestContextFilter.requestId(request));}
}
