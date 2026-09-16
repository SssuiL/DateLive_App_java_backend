package com.manliao.backend.notifications;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.manliao.backend.identity.AuthDtos.Principal;
@RestController
@RequestMapping("/notifications")
public class NotificationController {
 private final NotificationService notifications;
 public NotificationController(NotificationService notifications) { this.notifications=notifications; }
 @GetMapping("/preferences")
 public Map<String,Object> preferences(@AuthenticationPrincipal Principal user) { return notifications.preferences(user.userId()); }
 @PatchMapping("/preferences")
 public Map<String,Object> update(@AuthenticationPrincipal Principal user,@Valid @RequestBody NotificationPreferencesUpdate input) {
   return notifications.updatePreferences(user.userId(),input);
 }
 @GetMapping("/events")
 public List<Map<String,Object>> events(@AuthenticationPrincipal Principal user) { return notifications.events(user.userId()); }
 @GetMapping("/unread-count")
 public Map<String,Long> unread(@AuthenticationPrincipal Principal user) { return Map.of("unread_count",notifications.unread(user.userId())); }
 @PostMapping("/events/{eventId}/read")
 public Map<String,Object> read(@AuthenticationPrincipal Principal user,@PathVariable String eventId) {
   return notifications.read(user.userId(),eventId);
 }
 @PostMapping("/events/read-all")
 public Map<String,Integer> readAll(@AuthenticationPrincipal Principal user) { return Map.of("marked_count",notifications.readAll(user.userId())); }
}
