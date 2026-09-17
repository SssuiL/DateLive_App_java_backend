package com.manliao.backend.explore;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.manliao.backend.identity.AuthDtos.Principal;
import com.manliao.backend.common.ApiError;
@RestController
public class ExploreController {
 private final ExploreService explore;
 public ExploreController(ExploreService explore){this.explore=explore;}
 @GetMapping("/explore/candidates") public List<Map<String,Object>> candidates(@AuthenticationPrincipal Principal u,@RequestParam(defaultValue="10") int limit){return explore.candidates(u.userId(),limit);}
 @GetMapping("/explore/liked-profiles") public List<Map<String,Object>> liked(@AuthenticationPrincipal Principal u){return explore.liked(u.userId());}
 @PostMapping("/explore/exposures/{target}") public Map<String,Object> exposure(@AuthenticationPrincipal Principal u,@PathVariable String target){return explore.act(u,target,"exposure");}
 @PostMapping("/explore/likes/{target}") public Map<String,Object> like(@AuthenticationPrincipal Principal u,@PathVariable String target){return explore.act(u,target,"like");}
 @PostMapping("/explore/skips/{target}") public Map<String,Object> skip(@AuthenticationPrincipal Principal u,@PathVariable String target){return explore.act(u,target,"skip");}
 @PostMapping("/explore/blocks/{target}") public Map<String,Object> block(@AuthenticationPrincipal Principal u,@PathVariable String target){return explore.act(u,target,"block");}
 @PostMapping("/explore/undo") public Map<String,Object> undo(@AuthenticationPrincipal Principal u){return explore.undo(u);}
 @PostMapping("/explore/super-likes/{target}") public void retired(@PathVariable String target){throw new ApiError(410,"EXPLORE_SUPER_LIKE_REMOVED","超级喜欢已取消，请使用普通喜欢");}
 @GetMapping("/matches/") public List<Map<String,Object>> matches(@AuthenticationPrincipal Principal u){return explore.matches(u.userId(),null);}
 @GetMapping("/matches/{id}") public Map<String,Object> match(@AuthenticationPrincipal Principal u,@PathVariable String id){var found=explore.matches(u.userId(),id);if(found.isEmpty())throw new ApiError(404,"MATCH_NOT_FOUND","配对不存在");return found.getFirst();}
}