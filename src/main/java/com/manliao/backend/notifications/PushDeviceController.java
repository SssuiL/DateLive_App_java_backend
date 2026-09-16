package com.manliao.backend.notifications;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.manliao.backend.identity.*;
import com.manliao.backend.common.ApiError;

@RestController
@RequestMapping("/notifications/devices")
public class PushDeviceController {
 public record Registration(
   @JsonProperty("device_id") @NotBlank @Size(min=8,max=128) String deviceId,
   @Pattern(regexp="android|ios|web|windows|macos|linux|unknown") String platform,
   @JsonProperty("push_token") @NotBlank @Size(min=8,max=512) String pushToken,
   @Size(max=32) String provider, Boolean enabled) {
   public Registration {
     if(platform==null) platform="unknown";
     if(provider==null) provider="development";
     if(enabled==null) enabled=true;
   }
 }
 private final org.springframework.transaction.support.TransactionTemplate tx;
 private final UserWriteGuard guard;
 private final JdbcTemplate db;
 private final AuthService auth;
 public PushDeviceController(JdbcTemplate db,AuthService auth,org.springframework.transaction.support.TransactionTemplate tx,UserWriteGuard guard) { this.db=db; this.auth=auth;this.tx=tx;this.guard=guard; }
 @PostMapping
 public Map<String,Object> register(@AuthenticationPrincipal AuthDtos.Principal principal,
       @Valid @RequestBody Registration input) {
   // One atomic upsert handles simultaneous device registration.
   return tx.execute(status->{guard.lock(principal.userId());return auth.jsonRow(db.queryForMap("""
     INSERT INTO push_devices(id,user_id,device_id,platform,push_token,provider,enabled)
     VALUES (?,?,?,?,?,?,?)
     ON CONFLICT(user_id,device_id) DO UPDATE SET platform=EXCLUDED.platform,
       push_token=EXCLUDED.push_token,provider=EXCLUDED.provider,enabled=EXCLUDED.enabled,last_registered_at=now()
     RETURNING id,user_id,device_id,platform,provider,enabled,last_registered_at,created_at
     ""","pushdev_"+UUID.randomUUID(),principal.userId(),input.deviceId(),input.platform(),
       input.pushToken(),input.provider(),input.enabled()));});
 }
 @GetMapping
 public List<Map<String,Object>> list(@AuthenticationPrincipal AuthDtos.Principal principal) {
   return db.queryForList("""
     SELECT id,user_id,device_id,platform,provider,enabled,last_registered_at,created_at
     FROM push_devices WHERE user_id=? ORDER BY last_registered_at DESC,id
     """,principal.userId()).stream().map(auth::jsonRow).toList();
 }
 @DeleteMapping("/{deviceId}")
 public Map<String,String> disable(@AuthenticationPrincipal AuthDtos.Principal principal,@PathVariable String deviceId) {
   return tx.execute(status->{guard.lock(principal.userId());
   if(db.update("UPDATE push_devices SET enabled=false WHERE user_id=? AND device_id=?",principal.userId(),deviceId)==0)
     throw new ApiError(404,"COMMON_NOT_FOUND","推送设备不存在");
   return Map.of("status","disabled");});
 }
}
