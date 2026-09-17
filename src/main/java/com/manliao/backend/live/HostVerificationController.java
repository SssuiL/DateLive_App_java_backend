package com.manliao.backend.live;
import java.time.*;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.manliao.backend.identity.AuthDtos.Principal;
import com.manliao.backend.admin.AdminDtos;
import com.manliao.backend.common.RequestContextFilter;
@RestController
public class HostVerificationController {
 public record Submit(@NotBlank @Size(min=2,max=64) String legal_name,@NotBlank @Size(min=8,max=32) String id_number,@NotNull LocalDate date_of_birth,Boolean guardian_consent,@NotNull Boolean agreement_accepted){public Submit{guardian_consent=Boolean.TRUE.equals(guardian_consent);}}
 public record Resolve(@NotNull @Pattern(regexp="approved|rejected") String decision,@Size(max=500) String reason,Boolean allow_gifts){public Resolve{allow_gifts=Boolean.TRUE.equals(allow_gifts);}}
 public record Suspend(@NotBlank @Size(max=500) String reason,Instant restriction_until){}
 private final HostVerificationService service;
 public HostVerificationController(HostVerificationService service){this.service=service;}
 @GetMapping("/live/host-verification/me") public Map<String,Object> me(@AuthenticationPrincipal Principal actor){return service.me(actor);}
 @PostMapping("/live/host-verification/applications") public Map<String,Object> submit(@AuthenticationPrincipal Principal actor,@Valid @RequestBody Submit input){return service.submit(actor,input);}
 @GetMapping("/admin/host-verification/applications") public List<Map<String,Object>> list(@AuthenticationPrincipal AdminDtos.Principal actor,@RequestParam(required=false) String status,@RequestParam(required=false) String user_id){return service.list(actor,status,user_id);}
 @PostMapping("/admin/host-verification/applications/{id}/resolve") public Map<String,Object> resolve(@AuthenticationPrincipal AdminDtos.Principal actor,@PathVariable String id,@Valid @RequestBody Resolve input,HttpServletRequest request){return service.resolve(actor,id,input,RequestContextFilter.requestId(request));}
 @PostMapping("/admin/host-verification/qualifications/{user}/suspend") public Map<String,Object> suspend(@AuthenticationPrincipal AdminDtos.Principal actor,@PathVariable String user,@Valid @RequestBody Suspend input,HttpServletRequest request){return service.suspend(actor,user,input,RequestContextFilter.requestId(request));}
 @PostMapping("/admin/host-verification/qualifications/{user}/restore") public Map<String,Object> restore(@AuthenticationPrincipal AdminDtos.Principal actor,@PathVariable String user,HttpServletRequest request){return service.restore(actor,user,RequestContextFilter.requestId(request));}
}
