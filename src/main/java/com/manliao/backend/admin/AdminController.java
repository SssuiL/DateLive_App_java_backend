package com.manliao.backend.admin;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.manliao.backend.common.RequestContextFilter;
@RestController
@RequestMapping("/admin")
public class AdminController {
 private final AdminService admin;
 public AdminController(AdminService admin){this.admin=admin;}
 private String request(HttpServletRequest r){return RequestContextFilter.requestId(r);}
 @PostMapping("/auth/login")
 public Map<String,Object> login(@Valid @RequestBody AdminDtos.Login input,HttpServletRequest r){return admin.login(input,request(r));}
 @GetMapping("/auth/me")
 public Map<String,Object> me(@AuthenticationPrincipal AdminDtos.Principal actor){return admin.me(actor);}
 @PostMapping("/auth/logout")
 public Map<String,String> logout(@AuthenticationPrincipal AdminDtos.Principal actor,HttpServletRequest r){admin.logout(actor,request(r));return Map.of("status","ok");}
 @PatchMapping("/auth/password")
 public Map<String,Object> password(@AuthenticationPrincipal AdminDtos.Principal actor,@Valid @RequestBody AdminDtos.SelfPassword input,HttpServletRequest r){return admin.ownPassword(actor,input,request(r));}
 @PostMapping("/admin-users")
 public Map<String,Object> create(@AuthenticationPrincipal AdminDtos.Principal actor,@Valid @RequestBody AdminDtos.Create input,HttpServletRequest r){return admin.create(actor,input,request(r));}
 @GetMapping("/admin-users")
 public List<Map<String,Object>> accounts(@AuthenticationPrincipal AdminDtos.Principal actor,@RequestParam(required=false) String keyword,
   @RequestParam(required=false) String status,@RequestParam(required=false) String role){return admin.accounts(actor,keyword,status,role);}
 @PatchMapping("/admin-users/{id}/status")
 public Map<String,Object> status(@AuthenticationPrincipal AdminDtos.Principal actor,@PathVariable String id,
   @Valid @RequestBody AdminDtos.Status input,HttpServletRequest r){return admin.changeStatus(actor,id,input,request(r));}
 @PatchMapping("/admin-users/{id}/password")
 public Map<String,Object> reset(@AuthenticationPrincipal AdminDtos.Principal actor,@PathVariable String id,
   @Valid @RequestBody AdminDtos.Password input,HttpServletRequest r){return admin.resetPassword(actor,id,input,request(r));}
 @GetMapping("/operation-logs")
 public List<Map<String,Object>> logs(@AuthenticationPrincipal AdminDtos.Principal actor,@RequestParam(name="admin_user_id",required=false) String id,
   @RequestParam(required=false) String action,@RequestParam(name="target_type",required=false) String type,
   @RequestParam(name="target_id",required=false) String target){return admin.logs(actor,id,action,type,target);}
}
