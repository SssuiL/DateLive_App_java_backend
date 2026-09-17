package com.manliao.backend.groups;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.util.HtmlUtils;
import com.manliao.backend.identity.AuthDtos.Principal;
@RestController
public class GroupLinkController {
 private final GroupService groups;private final GroupLinks links;
 public GroupLinkController(GroupService groups,GroupLinks links){this.groups=groups;this.links=links;}
 @GetMapping("/groups/{id}/share-link")
 public ResponseEntity<Map<String,Object>> share(@AuthenticationPrincipal Principal user,@PathVariable String id){
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(links.output(groups.share(user.userId(),id)));
 }
 @GetMapping("/group-links/{code}")
 public ResponseEntity<Map<String,Object>> resolve(@PathVariable String code){
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(links.output(groups.publicLink(code)));
 }
 @GetMapping(value="/g/{code}",produces=MediaType.TEXT_HTML_VALUE)
 public ResponseEntity<String> page(@PathVariable String code){
  var group=links.output(groups.publicLink(code));String name=escape(group.get("name")),url=escape(group.get("share_url")),key=escape(group.get("link_code"));
  String html="""
   <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
   <meta name="robots" content="noindex,nofollow"><title>加入群组 · 漫聊</title>
   <meta property="og:title" content="加入「%s」"><meta property="og:description" content="在漫聊 App 中使用群口令申请加入">
   <meta property="og:type" content="website"><meta property="og:url" content="%s">
   <style>body{font:18px/1.7 system-ui,sans-serif;background:#f3f5f8;color:#17202e;margin:0;padding:24px}main{max-width:520px;margin:8vh auto;background:white;padding:32px;border-radius:24px}h1{overflow-wrap:anywhere}code{display:block;overflow-wrap:anywhere;padding:16px;background:#edf1f7;border-radius:12px}small{color:#526071}</style></head>
   <body><main><small>漫聊 · 群组邀请</small><h1>%s</h1><p>在漫聊 App 中使用以下群口令申请加入：</p><code>%s</code>
   <p>入群需登录，并遵循群主设置的审核、密码或邀请规则。</p></main></body></html>
   """.formatted(name,url,name,key);
  return ResponseEntity.ok().contentType(new MediaType("text","html",java.nio.charset.StandardCharsets.UTF_8)).cacheControl(CacheControl.noStore())
   .header("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'")
   .header("Referrer-Policy","no-referrer").header("X-Robots-Tag","noindex, nofollow").body(html);
 }
 private static String escape(Object value){return HtmlUtils.htmlEscape(String.valueOf(value),"UTF-8");}
}