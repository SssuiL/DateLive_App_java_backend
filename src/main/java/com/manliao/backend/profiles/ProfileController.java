package com.manliao.backend.profiles;
import java.util.Map;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.manliao.backend.identity.AuthDtos.Principal;
@RestController
@RequestMapping("/profiles")
public class ProfileController {
 private final ProfileService profiles;
 public ProfileController(ProfileService profiles) { this.profiles=profiles; }
 @GetMapping("/me")
 public Map<String,Object> me(@AuthenticationPrincipal Principal user) { return profiles.get(user.userId(),user.userId()); }
 @PatchMapping("/me")
 public Map<String,Object> update(@AuthenticationPrincipal Principal user,@Valid @RequestBody ProfileUpdate input) {
   return profiles.update(user.userId(),input);
 }
 @GetMapping("/{userId}")
 public Map<String,Object> get(@AuthenticationPrincipal Principal user,@PathVariable String userId) {
   return profiles.get(user.userId(),userId);
 }
}
