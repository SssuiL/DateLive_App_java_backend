package com.manliao.backend.admin;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.manliao.backend.identity.AccountLifecycleService;
@RestController
@RequestMapping("/admin")
public class AdminErasureController {
 private final AdminService admins;private final AccountLifecycleService accounts;
 public AdminErasureController(AdminService admins,AccountLifecycleService accounts){this.admins=admins;this.accounts=accounts;}
 @GetMapping("/users/{id}/erasure-preview")
 public Map<String,Object> preview(@AuthenticationPrincipal AdminDtos.Principal actor,@PathVariable String id){
   admins.require(actor,"users.erase",false);return accounts.preview(id);
 }
 @GetMapping("/users/{id}/erasure")
 public Map<String,Object> record(@AuthenticationPrincipal AdminDtos.Principal actor,@PathVariable String id){
   admins.require(actor,"users.erase",false);return accounts.record(id);
 }
 @GetMapping("/moderation/storage-deletions")
 public List<Map<String,Object>> jobs(@AuthenticationPrincipal AdminDtos.Principal actor){
   admins.require(actor,"users.erase",false);return accounts.storageJobs();
 }
}
