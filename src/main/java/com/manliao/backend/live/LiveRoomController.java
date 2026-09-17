package com.manliao.backend.live;
import java.time.Instant;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.manliao.backend.identity.AuthDtos.Principal;
import com.manliao.backend.admin.AdminDtos;
@RestController
public class LiveRoomController {
 public record Create(@NotBlank @Size(max=100) String title,@NotBlank @Size(max=32) String category,@Size(max=8) List<@NotNull @Size(max=32) String> tags,@Size(max=800) String cover_url,@DecimalMin("0") @DecimalMax("1000000") Double distance_km){public Create{tags=tags==null?List.of():tags;}}
 public record Start(@Size(min=16,max=512) String preflight_ticket){}
 public record Probe(@NotNull @Size(min=1024,max=262144) String payload){}
 public record Preflight(@NotBlank @Size(min=4,max=128) String device_id,@Size(max=32) String app_version,@NotNull Boolean camera_permission,@NotNull Boolean microphone_permission,@NotNull Boolean camera_available,@NotNull Boolean microphone_available,@NotNull @Pattern(regexp="wifi|mobile|ethernet|vpn|unknown|none") String network_type,@Min(0) @Max(60000) Integer latency_ms,@DecimalMin("0") @DecimalMax("100000") Double upload_mbps,Boolean user_confirmed_warnings){public Preflight{user_confirmed_warnings=Boolean.TRUE.equals(user_confirmed_warnings);}}
 private final LiveRoomService rooms;private final LivePreflightService preflights;
 public LiveRoomController(LiveRoomService rooms,LivePreflightService preflights){this.rooms=rooms;this.preflights=preflights;}
 @GetMapping("/live/categories") public Map<String,Object> categories(){return Map.of("primary",List.of("精选","推荐","附近的人","颜值"),"more",List.of("运动","宠物","户外","唱歌","跳舞"));}
 @PostMapping("/live/rooms") public Map<String,Object> create(@AuthenticationPrincipal Principal actor,@Valid @RequestBody Create input){return rooms.create(actor,input);}
 @GetMapping("/live/rooms") public List<Map<String,Object>> list(@RequestParam(required=false) String category){return rooms.list(category);}
 @GetMapping("/live/rooms/{id}") public Map<String,Object> get(@AuthenticationPrincipal Principal actor,@PathVariable String id){return rooms.get(actor,id);}
 @GetMapping("/live/rooms/{id}/stream-session") public Map<String,Object> session(@AuthenticationPrincipal Principal actor,@PathVariable String id){return rooms.session(actor,id);}
 @PostMapping("/live/rooms/{id}/preflight") public Map<String,Object> preflight(@AuthenticationPrincipal Principal actor,@PathVariable String id,@Valid @RequestBody Preflight input){return preflights.check(actor,id,input);}
 @PostMapping("/live/rooms/{id}/start") public Map<String,Object> start(@AuthenticationPrincipal Principal actor,@PathVariable String id,@Valid @RequestBody(required=false) Start input){return preflights.start(actor,id,input==null?null:input.preflight_ticket());}
 @PostMapping("/live/rooms/{id}/end") public Map<String,Object> end(@AuthenticationPrincipal Principal actor,@PathVariable String id){return rooms.end(actor,id);}
 @PostMapping("/live/rooms/{id}/participants/me") public Map<String,Object> join(@AuthenticationPrincipal Principal actor,@PathVariable String id){return rooms.join(actor,id);}
 @DeleteMapping("/live/rooms/{id}/participants/me") public Map<String,Object> leave(@AuthenticationPrincipal Principal actor,@PathVariable String id){return rooms.leave(actor,id);}
 @PostMapping("/live/network-probe") public Map<String,Object> probe(@Valid @RequestBody Probe input){return Map.of("received_bytes",input.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,"server_time",Instant.now());}
 @GetMapping("/admin/live-preflight") public List<Map<String,Object>> preflights(@AuthenticationPrincipal AdminDtos.Principal actor,@RequestParam(required=false) String result,@RequestParam(required=false) String host_user_id,@RequestParam(required=false) String room_id){return preflights.list(actor,result,host_user_id,room_id);}
}
