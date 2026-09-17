package com.manliao.backend.live;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.manliao.backend.identity.AuthDtos.Principal;
import com.manliao.backend.admin.AdminDtos;
@RestController
public class LiveGiftController {
 public record Confirm(@NotBlank @Size(max=32) String gift_code,@NotNull @Min(1) @Max(99) Integer quantity){}
 public record Send(@NotBlank @Size(max=32) String gift_code,@Min(1) @Max(99) Integer quantity,@NotBlank @Size(min=8,max=64) String client_request_id,@Size(min=8,max=64) String combo_id,@Size(min=8,max=64) String risk_confirmation_id){public Send{quantity=quantity==null?1:quantity;}}
 private final LiveGiftService gifts;public LiveGiftController(LiveGiftService gifts){this.gifts=gifts;}
 @PostMapping("/live/rooms/{room}/gift-confirmations") public Map<String,Object> confirm(@AuthenticationPrincipal Principal actor,@PathVariable String room,@Valid @RequestBody Confirm input){return gifts.confirmation(actor,room,input);}
 @PostMapping("/live/rooms/{room}/gifts") public Map<String,Object> send(@AuthenticationPrincipal Principal actor,@PathVariable String room,@Valid @RequestBody Send input){return gifts.send(actor,room,input);}
 @GetMapping("/live/rooms/{room}/gifts") public List<Map<String,Object>> list(@AuthenticationPrincipal Principal actor,@PathVariable String room,@RequestParam(defaultValue="50") int limit){return gifts.list(actor,room,limit);}
 @GetMapping("/live/rooms/{room}/gift-ranking") public List<Map<String,Object>> ranking(@AuthenticationPrincipal Principal actor,@PathVariable String room,@RequestParam(defaultValue="10") int limit){return gifts.ranking(actor,room,limit);}
 @GetMapping("/live/income/summary") public Map<String,Object> summary(@AuthenticationPrincipal Principal actor){return gifts.summary(actor);}
 @GetMapping("/live/income/records") public List<Map<String,Object>> income(@AuthenticationPrincipal Principal actor,@RequestParam(required=false) String status){return gifts.income(actor,status);}
 @GetMapping("/admin/live/rooms/{room}/gifts") public List<Map<String,Object>> adminList(@AuthenticationPrincipal AdminDtos.Principal actor,@PathVariable String room){return gifts.adminList(actor,room);}
}
