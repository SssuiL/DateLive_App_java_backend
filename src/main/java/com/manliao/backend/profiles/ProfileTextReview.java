package com.manliao.backend.profiles;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
/** Port of the legacy local text rules, not a third-party moderation provider. */
@Component
public class ProfileTextReview {
 private final Map<String,Pattern> rules=new LinkedHashMap<>();
 public ProfileTextReview() {
   rules.put("external_contact",Pattern.compile("(微信|v信|扣扣)|(?<![a-z0-9])(wechat|weixin|vx|qq|telegram|tg|whatsapp)(?![a-z0-9])|1[3-9]\\d[\\s\\-_.]?\\d{4}[\\s\\-_.]?\\d{4}",Pattern.CASE_INSENSITIVE));
   rules.put("sexual",Pattern.compile("(裸聊|约炮|色情|情色|成人视频|援交)"));
   rules.put("violence",Pattern.compile("(杀人|砍人|血腥|暴力威胁)"));
   rules.put("gambling",Pattern.compile("(赌博|博彩|盘口|下注)"));
 }
 public List<String> labels(String text) {
   return rules.entrySet().stream().filter(e->e.getValue().matcher(text).find()).map(Map.Entry::getKey).toList();
 }
}
