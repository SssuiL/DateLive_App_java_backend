package com.manliao.backend.gifts;
import java.util.*;
import java.io.IOException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;
import com.manliao.backend.admin.AdminDtos;
@RestController
public class GiftController {
 private final GiftCatalogService catalog;private final GiftAssets assets;
 public GiftController(GiftCatalogService catalog,GiftAssets assets){this.catalog=catalog;this.assets=assets;}
 private static String request(HttpServletRequest r){return Objects.toString(r.getAttribute("request_id"),"gift_request");}
 @GetMapping("/live/gifts") public List<Map<String,Object>> gifts(){return catalog.catalog();}
 @GetMapping("/admin/live-gifts") public List<Map<String,Object>> list(@AuthenticationPrincipal AdminDtos.Principal actor){return catalog.list(actor);}
 @PostMapping("/admin/live-gifts") public Map<String,Object> create(@AuthenticationPrincipal AdminDtos.Principal actor,@RequestBody JsonNode body,HttpServletRequest r){return catalog.create(actor,body,request(r));}
 @PatchMapping("/admin/live-gifts/{id}") public Map<String,Object> update(@AuthenticationPrincipal AdminDtos.Principal actor,@PathVariable String id,@RequestBody JsonNode body,HttpServletRequest r){return catalog.update(actor,id,body,request(r));}
 @PostMapping(value="/admin/live-gifts/{id}/assets/{kind}",consumes="multipart/form-data") public Map<String,Object> upload(@AuthenticationPrincipal AdminDtos.Principal actor,@PathVariable String id,@PathVariable String kind,@RequestPart("file") MultipartFile file,HttpServletRequest r)throws IOException{return assets.upload(actor,id,kind,file,request(r));}
 @GetMapping("/gift-assets/{id}") public ResponseEntity<byte[]> asset(@PathVariable String id){var a=assets.read(id);return ResponseEntity.ok().contentType(MediaType.parseMediaType(a.type())).header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","default-src 'none'; sandbox").cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(24)).cachePublic().immutable()).eTag(a.checksum()).body(a.bytes());}
}
