package com.manliao.backend.posts;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.manliao.backend.identity.AuthDtos.Principal;
@RestController
@RequestMapping("/posts")
public class PostController {
 private final PostService posts;
 public PostController(PostService posts){this.posts=posts;}
 @PostMapping("/") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> create(@AuthenticationPrincipal Principal u,@Valid @RequestBody PostDtos.Create input){return posts.create(u,input);}
 @GetMapping("/feed") public List<Map<String,Object>> feed(@AuthenticationPrincipal Principal u,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset){return posts.list(u.userId(),null,limit,offset,null,false);}
 @GetMapping("/feed/page") public Map<String,Object> page(@AuthenticationPrincipal Principal u,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor){return posts.page(u.userId(),limit,cursor);}
 @GetMapping("/user/{user}") public List<Map<String,Object>> user(@AuthenticationPrincipal Principal u,@PathVariable String user,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset){return posts.list(u.userId(),user,limit,offset,null,false);}
 @GetMapping("/{id}") public Map<String,Object> get(@AuthenticationPrincipal Principal u,@PathVariable String id){return posts.get(u.userId(),id);}
 @PostMapping("/{id}/likes") public Map<String,Object> like(@AuthenticationPrincipal Principal u,@PathVariable String id){return posts.like(u,id,true);}
 @DeleteMapping("/{id}/likes") public Map<String,Object> unlike(@AuthenticationPrincipal Principal u,@PathVariable String id){return posts.like(u,id,false);}
 @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@AuthenticationPrincipal Principal u,@PathVariable String id){posts.delete(u,id);}
 @GetMapping("/{id}/comments") public List<Map<String,Object>> comments(@AuthenticationPrincipal Principal u,@PathVariable String id){return posts.comments(u.userId(),id);}
 @GetMapping("/{id}/comments/page") public Map<String,Object> commentsPage(@AuthenticationPrincipal Principal u,@PathVariable String id,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="3") int reply_preview_limit){return posts.commentPage(u.userId(),id,limit,cursor,reply_preview_limit);}
 @GetMapping("/{id}/comments/{comment}/replies") public List<Map<String,Object>> replies(@AuthenticationPrincipal Principal u,@PathVariable String id,@PathVariable String comment,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor){return posts.replies(u.userId(),id,comment,limit,cursor);}
 @PostMapping("/{id}/comments") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> comment(@AuthenticationPrincipal Principal u,@PathVariable String id,@Valid @RequestBody PostDtos.Comment input){return posts.comment(u,id,input);}
 @PostMapping("/{id}/comments/{comment}/likes") public Map<String,Object> likeComment(@AuthenticationPrincipal Principal u,@PathVariable String id,@PathVariable String comment){return posts.likeComment(u,id,comment,true);}
 @DeleteMapping("/{id}/comments/{comment}/likes") public Map<String,Object> unlikeComment(@AuthenticationPrincipal Principal u,@PathVariable String id,@PathVariable String comment){return posts.likeComment(u,id,comment,false);}
 @DeleteMapping("/{id}/comments/{comment}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteComment(@AuthenticationPrincipal Principal u,@PathVariable String id,@PathVariable String comment){posts.deleteComment(u,id,comment);}
 @GetMapping("/{id}/media/{asset}/reactions") public Map<String,Object> reactions(@AuthenticationPrincipal Principal u,@PathVariable String id,@PathVariable String asset){return posts.reactions(u.userId(),id,asset);}
 @PutMapping("/{id}/media/{asset}/reaction") public Map<String,Object> react(@AuthenticationPrincipal Principal u,@PathVariable String id,@PathVariable String asset,@Valid @RequestBody PostDtos.Reaction input){return posts.react(u,id,asset,input.emoji());}
 @DeleteMapping("/{id}/media/{asset}/reaction") public Map<String,Object> unreact(@AuthenticationPrincipal Principal u,@PathVariable String id,@PathVariable String asset){return posts.react(u,id,asset,null);}
 @GetMapping("/{id}/media/{asset}/download") public Map<String,Object> download(@AuthenticationPrincipal Principal u,@PathVariable String id,@PathVariable String asset){return posts.download(u,id,asset);}
}