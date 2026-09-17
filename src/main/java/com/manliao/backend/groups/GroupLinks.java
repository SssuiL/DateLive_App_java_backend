package com.manliao.backend.groups;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
@Service
public class GroupLinks {
 private final String base;
 public GroupLinks(@Value("${app.groups.public-base-url:http://127.0.0.1:8200}") String configured){
  URI uri;
  try{uri=URI.create(configured.strip());}catch(IllegalArgumentException e){throw new IllegalArgumentException("Group public URL must be an HTTP(S) origin");}
  if(uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||
    !("https".equals(uri.getScheme())||("http".equals(uri.getScheme())&&Set.of("localhost","127.0.0.1","[::1]").contains(uri.getHost())))||
    !(uri.getRawPath().isEmpty()||uri.getRawPath().equals("/")))throw new IllegalArgumentException("Group public URL requires HTTPS origin (HTTP only for localhost)");
  base=configured.strip().replaceAll("/+$","");
 }
 public Map<String,Object> output(Map<String,Object> group){
  var result=new LinkedHashMap<>(group);String url=base+"/g/"+group.get("link_code");
  result.put("share_url",url);result.put("share_title","加入「"+group.get("name")+"」");
  result.put("share_text","邀请你加入漫聊群组「"+group.get("name")+"」："+url);return result;
 }
}