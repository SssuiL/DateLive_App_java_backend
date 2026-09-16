package com.manliao.backend.media;
import java.io.IOException;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.manliao.backend.identity.AuthDtos.Principal;
@RestController
@RequestMapping("/media")
public class MediaController {
 private final MediaService media;
 public MediaController(MediaService media){this.media=media;}
 @PostMapping(value="/upload",consumes="multipart/form-data")
 public Map<String,Object> upload(@AuthenticationPrincipal Principal user,@RequestParam("media_type") String type,
   @RequestParam(defaultValue="profile") String source,@RequestParam(name="conversation_id",required=false) String conversation,
   @RequestParam MultipartFile file)throws IOException{return media.upload(user,type,source,conversation,file);}
 @GetMapping("/me")
 public List<Map<String,Object>> list(@AuthenticationPrincipal Principal user){return media.list(user);}
 @PostMapping("/{id}/access-url")
 public Map<String,Object> access(@AuthenticationPrincipal Principal user,@PathVariable String id,
   @RequestParam(required=false) String variant){return media.access(user,id,variant);}
 @GetMapping("/access/{token}")
 public ResponseEntity<org.springframework.core.io.InputStreamResource> read(@PathVariable String token,
   @RequestParam(required=false) String variant,@RequestHeader(value="Range",required=false) String range)throws IOException {
   return MediaResponse.build(media.read(token,variant),range);
 }
 @DeleteMapping("/{id}")
 public ResponseEntity<Void> delete(@AuthenticationPrincipal Principal user,@PathVariable String id){
   media.delete(user,id);return ResponseEntity.noContent().build();
 }
}
