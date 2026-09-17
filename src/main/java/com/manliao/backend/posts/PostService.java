package com.manliao.backend.posts;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import com.manliao.backend.common.*;
import com.manliao.backend.identity.AuthDtos.Principal;
import com.manliao.backend.media.MediaService;
import com.manliao.backend.profiles.ProfileTextReview;
import com.manliao.backend.notifications.PostNotifications;
import com.manliao.backend.chat.ChatCursor;
@Service
public class PostService {
 private final JdbcTemplate db;private final TransactionTemplate tx,readTx;private final DatabaseRows rows;private final ObjectMapper json;
 private final PostAccess access;private final MediaService media;private final ProfileTextReview review;private final PostNotifications notifications;private final ChatCursor cursors;
 public PostService(JdbcTemplate db,TransactionTemplate tx,DatabaseRows rows,ObjectMapper json,PostAccess access,MediaService media,ProfileTextReview review,PostNotifications notifications,ChatCursor cursors){
  this.db=db;this.tx=tx;this.rows=rows;this.json=json;this.access=access;this.media=media;this.review=review;this.notifications=notifications;this.cursors=cursors;
  readTx=new TransactionTemplate(tx.getTransactionManager());readTx.setReadOnly(true);readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
 }
 private <T>T read(Supplier<T> action){return readTx.execute(s->action.get());}
 private static String id(String prefix){return prefix+"_"+UUID.randomUUID().toString().replace("-","");}
 private static ApiError invalid(String message){return new ApiError(400,"COMMON_VALIDATION_ERROR",message);}
 private static String clean(String text){return text==null||text.isBlank()?null:text.strip();}
 private void reviewText(String text){var labels=review.labels(text==null?"":text);if(!labels.isEmpty())throw new ApiError(400,"MODERATION_TEXT_REJECTED","内容未通过审核",Map.of("labels",labels));}
 private Map<String,Object> post(String id){return db.queryForMap("SELECT * FROM posts WHERE id=?",id);}
 private void pageLimit(int limit,int offset){if(limit<1||limit>50||offset<0)throw new ApiError(422,"COMMON_VALIDATION_ERROR","分页参数超出范围");}
 private Map<String,Object> asset(String id){var found=db.queryForList("SELECT * FROM media_assets WHERE id=?",id);if(found.isEmpty())throw new ApiError(404,"MEDIA_NOT_FOUND","媒体不存在");return found.getFirst();}
 private Map<String,Object> ownedAsset(String user,String id,String source,Set<String> types){
  var found=db.queryForList("SELECT * FROM media_assets WHERE id=? FOR UPDATE",id);
  if(found.isEmpty())throw new ApiError(404,"MEDIA_NOT_FOUND","媒体不存在");var item=found.getFirst();
  if(!user.equals(item.get("owner_user_id"))||!source.equals(item.get("source"))||item.get("post_id")!=null||item.get("message_id")!=null||
    db.queryForObject("SELECT count(*) FROM post_comments WHERE media_asset_id=?",Integer.class,id)>0)throw new ApiError(404,"MEDIA_NOT_FOUND","媒体不存在或已被使用");
  if(!types.contains(item.get("media_type")))throw new ApiError(400,"MEDIA_TYPE_INVALID","媒体类型不匹配");
  if(Set.of("rejected","deleted").contains(item.get("status"))||"failed".equals(item.get("processing_status")))throw new ApiError(409,"MEDIA_NOT_APPROVED","媒体不可发布");
  return item;
 }
 public Map<String,Object> create(Principal actor,PostDtos.Create input){
  String type=input.type()==null?"text":input.type(),text=clean(input.text());var ids=new ArrayList<>(new LinkedHashSet<>(input.media_asset_ids()==null?List.<String>of():input.media_asset_ids()));
  if(text==null&&ids.isEmpty())throw invalid("动态内容不能为空");
  if(Set.of("voice","video").contains(type)&&ids.size()!=1)throw invalid("语音或视频动态需要一个媒体");reviewText(text);
  return tx.execute(status->{
   access.lockUsers(actor,List.of());var assets=new HashMap<String,Map<String,Object>>();
   for(String id:new TreeSet<>(ids))assets.put(id,ownedAsset(actor.userId(),id,"post",Set.of(Set.of("text","image").contains(type)?"image":type)));
   boolean approved=assets.values().stream().allMatch(a->"approved".equals(a.get("status")));String id=id("post");
   db.update("INSERT INTO posts(id,author_id,type,text,media_asset_ids,visibility,allow_media_save,moderation_status) VALUES(?,?,?,?,?::jsonb,?,?,?)",id,actor.userId(),type,text,json.writeValueAsString(ids),input.visibility()==null?"friends":input.visibility(),input.allow_media_save()==null||input.allow_media_save(),approved?"approved":"review_pending");
   for(String asset:ids)db.update("UPDATE media_assets SET post_id=? WHERE id=?",id,asset);
   return output(actor.userId(),post(id));
  });
 }
 private String visibleSql(String viewer,List<Object> args){
  args.addAll(Collections.nCopies(5,viewer));
  return """
   p.deleted_at IS NULL AND u.status='active'
   AND NOT EXISTS(SELECT 1 FROM blocks b WHERE b.block_type='block' AND ((b.actor_user_id=? AND b.target_user_id=p.author_id) OR(b.target_user_id=? AND b.actor_user_id=p.author_id)))
   AND (p.author_id=? OR (p.moderation_status='approved' AND (p.visibility='public' OR (p.visibility='friends' AND EXISTS(SELECT 1 FROM friendships f WHERE (f.user_a_id=? AND f.user_b_id=p.author_id) OR(f.user_b_id=? AND f.user_a_id=p.author_id))))))
   """;
 }
 public List<Map<String,Object>> list(String user,String target,int limit,int offset,String cursor,boolean extra){
  pageLimit(limit,offset);return read(()->{
   var args=new ArrayList<Object>();String where=visibleSql(user,args);
   if(target==null){where+=" AND (p.author_id=? OR EXISTS(SELECT 1 FROM friendships f WHERE (f.user_a_id=? AND f.user_b_id=p.author_id) OR(f.user_b_id=? AND f.user_a_id=p.author_id)))";args.addAll(Collections.nCopies(3,user));}
   else{where+=" AND p.author_id=?";args.add(target);}
   if(cursor!=null&&!cursor.isEmpty()){
    ChatCursor.Position at;try{at=cursors.decode(cursor,false);}catch(ApiError bad){throw new ApiError(400,"POST_FEED_CURSOR_INVALID","动态分页游标无效");}
    where+=" AND (p.created_at,p.id)<(?,?)";args.add(at.time());args.add(at.id());
   }
   args.add(limit+(extra?1:0));args.add(offset);
   return db.queryForList("SELECT p.* FROM posts p JOIN users u ON u.id=p.author_id WHERE "+where+" ORDER BY p.created_at DESC,p.id DESC LIMIT ? OFFSET ?",args.toArray()).stream().map(p->output(user,p)).toList();
  });
 }
 public Map<String,Object> page(String user,int limit,String cursor){var list=list(user,null,limit,0,cursor,true);boolean more=list.size()>limit;var selected=list.subList(0,Math.min(limit,list.size()));var out=new LinkedHashMap<String,Object>();out.put("items",selected);out.put("has_more",more);out.put("next_cursor",more?cursors.encode(selected.getLast(),false):null);return out;}
 public Map<String,Object> get(String user,String id){return read(()->output(user,access.visible(user,id)));}
 public Map<String,Object> like(Principal actor,String id,boolean on){return tx.execute(status->{
  var post=access.lockPost(actor,id,List.of());int changed=on?db.update("INSERT INTO post_likes(post_id,user_id) VALUES(?,?) ON CONFLICT DO NOTHING",id,actor.userId()):db.update("DELETE FROM post_likes WHERE post_id=? AND user_id=?",id,actor.userId());
  if(on&&changed>0)notifications.event((String)post.get("author_id"),actor.userId(),"post_liked",id,"有人赞了你的动态","你的动态收到了点赞",Map.of(),null,true);
  return output(actor.userId(),post);
 });}
 public void delete(Principal actor,String id){tx.executeWithoutResult(status->{
  var post=access.lockPost(actor,id,List.of());if(!actor.userId().equals(post.get("author_id")))throw new ApiError(403,"POST_DELETE_FORBIDDEN","不能删除他人的动态");
  db.update("UPDATE posts SET deleted_at=clock_timestamp() WHERE id=?",id);
  db.update("UPDATE notification_events SET status='suppressed',suppress_reason='post_deleted',delivery_channel='none' WHERE source_type='post' AND source_id=? AND status='pending'",id);
 });}
 private String nickname(String id){if(id==null)return null;var found=db.queryForList("SELECT coalesce(p.nickname,u.nickname) AS nickname FROM users u LEFT JOIN user_profiles p ON p.user_id=u.id WHERE u.id=?",id);return found.isEmpty()?"已注销用户":(String)found.getFirst().get("nickname");}
 private Map<String,Object> output(String viewer,Map<String,Object> post){
  var out=new LinkedHashMap<String,Object>();for(String key:List.of("id","author_id","type","text","visibility","allow_media_save","moderation_status","created_at"))out.put(key,post.get(key));
  out.put("author_nickname",nickname((String)post.get("author_id")));
  var profile=db.queryForList("SELECT avatar_url FROM user_profiles WHERE user_id=?",post.get("author_id"));out.put("author_avatar_url",profile.isEmpty()?null:media.profileUrl((String)profile.getFirst().get("avatar_url")));
  var attachments=new ArrayList<Map<String,Object>>();
  for(String id:json.readValue(post.get("media_asset_ids").toString(),String[].class)){
   var found=db.queryForList("SELECT * FROM media_assets WHERE id=? AND status<>'deleted'",id);if(!found.isEmpty())attachments.add(mediaOut(viewer,found.getFirst(),true));
  }
  out.put("media",attachments);String id=(String)post.get("id");
  out.put("like_count",db.queryForObject("SELECT count(*) FROM post_likes WHERE post_id=?",Integer.class,id));
  out.put("comment_count",db.queryForObject("SELECT count(*) FROM post_comments WHERE post_id=? AND deleted_at IS NULL AND moderation_status='approved'",Integer.class,id));
  out.put("liked_by_me",db.queryForObject("SELECT count(*) FROM post_likes WHERE post_id=? AND user_id=?",Integer.class,id,viewer)>0);
  return rows.output(out);
 }
 private Map<String,Object> mediaOut(String viewer,Map<String,Object> asset,boolean reactions){
  var out=new LinkedHashMap<String,Object>();for(String key:List.of("id","media_type","content_type","status"))out.put(key,asset.get(key));
  out.put("url",Objects.toString(media.profileUrl((String)asset.get("url")),""));var summary=summary(viewer,(String)asset.get("post_id"),(String)asset.get("id"));
  for(String key:List.of("reaction_counts","reaction_total","my_reaction"))out.put(key,summary.get(key));return out;
 }
 public Map<String,Object> comment(Principal actor,String id,PostDtos.Comment input){
  String content=clean(input.content());if(content==null&&input.media_asset_id()==null)throw invalid("评论不能为空");reviewText(content);
  var snapshot=input.parent_comment_id()==null?null:access.comment(actor.userId(),id,input.parent_comment_id(),false);
  return tx.execute(status->{
   var post=access.lockPost(actor,id,snapshot==null?List.of():List.of((String)snapshot.get("author_id")));
   var parent=snapshot==null?null:access.comment(actor.userId(),id,input.parent_comment_id(),false);admit(actor.userId(),"post-comment",8);
   var asset=input.media_asset_id()==null?null:ownedAsset(actor.userId(),input.media_asset_id(),"post_comment",Set.of("image"));
   String kind=input.media_kind();if(asset==null&&kind!=null)throw invalid("媒体标记必须绑定图片");
   if(asset!=null){boolean gif="image/gif".equals(asset.get("content_type"));if(kind==null)kind=gif?"gif":"image";if((gif&&!kind.equals("gif"))||(!gif&&kind.equals("gif")))throw invalid("媒体类型标记不匹配");}
   String comment=id("comment"),root=parent==null?null:(String)(parent.get("root_comment_id")==null?parent.get("id"):parent.get("root_comment_id"));
   String moderation=asset==null||"approved".equals(asset.get("status"))?"approved":"review_pending";
   db.update("INSERT INTO post_comments(id,post_id,author_id,parent_comment_id,root_comment_id,reply_to_user_id,content,media_asset_id,media_kind,moderation_status) VALUES(?,?,?,?,?,?,?,?,?,?)",
    comment,id,actor.userId(),parent==null?null:parent.get("id"),root,parent==null?null:parent.get("author_id"),content,input.media_asset_id(),kind,moderation);
   var row=db.queryForMap("SELECT * FROM post_comments WHERE id=?",comment);if(moderation.equals("approved"))notifications.comment(post,row);return commentOut(actor.userId(),row);
  });
 }
 private String commentVisible(){return "(c.moderation_status='approved' OR c.author_id=?)";}
 private List<Map<String,Object>> replyRows(String viewer,String root){return db.queryForList("SELECT c.* FROM post_comments c WHERE c.root_comment_id=? AND c.deleted_at IS NULL AND "+commentVisible()+" ORDER BY c.created_at,c.id",root,viewer);}
 private List<Map<String,Object>> roots(String viewer,String post){return db.queryForList("SELECT c.* FROM post_comments c WHERE c.post_id=? AND c.root_comment_id IS NULL AND "+commentVisible()+" AND (c.deleted_at IS NULL OR EXISTS(SELECT 1 FROM post_comments r WHERE r.root_comment_id=c.id AND r.deleted_at IS NULL AND (r.moderation_status='approved' OR r.author_id=?))) ORDER BY c.created_at,c.id",post,viewer,viewer);}
 public List<Map<String,Object>> comments(String viewer,String id){return read(()->{
  access.visible(viewer,id);var result=new ArrayList<Map<String,Object>>();
  for(var root:roots(viewer,id)){result.add(commentOut(viewer,root));for(var reply:replyRows(viewer,(String)root.get("id")))result.add(commentOut(viewer,reply));}return result;
 });}
 private Map<String,Object> cursorComment(String viewer,String post,String id,String root){
  var found=db.queryForList("SELECT * FROM post_comments WHERE id=? AND post_id=?",id,post);
  if(found.isEmpty()||!Objects.equals(root,found.getFirst().get("root_comment_id")))throw new ApiError(400,"COMMON_BAD_REQUEST","评论分页游标无效");return found.getFirst();
 }
 public Map<String,Object> commentPage(String viewer,String id,int limit,String cursor,int preview){
  pageLimit(limit,0);if(preview<0||preview>10)throw new ApiError(422,"COMMON_VALIDATION_ERROR","回复预览数量超出范围");
  return read(()->{access.visible(viewer,id);var args=new ArrayList<Object>(List.of(id,viewer,viewer));String after="";
   if(cursor!=null&&!cursor.isEmpty()){var at=cursorComment(viewer,id,cursor,null);after=" AND (c.created_at,c.id)<(?,?)";args.add(at.get("created_at"));args.add(cursor);}
   args.add(limit+1);
   var found=db.queryForList("SELECT c.* FROM post_comments c WHERE c.post_id=? AND c.root_comment_id IS NULL AND "+commentVisible()+" AND (c.deleted_at IS NULL OR EXISTS(SELECT 1 FROM post_comments r WHERE r.root_comment_id=c.id AND r.deleted_at IS NULL AND (r.moderation_status='approved' OR r.author_id=?)))"+after+" ORDER BY c.created_at DESC,c.id DESC LIMIT ?",args.toArray());
   boolean more=found.size()>limit;var selected=found.subList(0,Math.min(limit,found.size()));var items=new ArrayList<Map<String,Object>>();
   for(var root:selected){
    var replies=db.queryForList("SELECT c.* FROM post_comments c WHERE c.root_comment_id=? AND c.deleted_at IS NULL AND "+commentVisible()+" ORDER BY c.created_at,c.id LIMIT ?",root.get("id"),viewer,preview);
    int count=db.queryForObject("SELECT count(*) FROM post_comments c WHERE c.root_comment_id=? AND c.deleted_at IS NULL AND "+commentVisible(),Integer.class,root.get("id"),viewer);
    items.add(Map.of("root",commentOut(viewer,root),"replies",replies.stream().map(r->commentOut(viewer,r)).toList(),"reply_count",count));
   }
   var out=new LinkedHashMap<String,Object>();out.put("items",items);out.put("has_more",more);out.put("next_cursor",more?selected.getLast().get("id"):null);return out;
  });
 }
 public List<Map<String,Object>> replies(String viewer,String id,String comment,int limit,String cursor){
  pageLimit(limit,0);return read(()->{
   var parent=access.comment(viewer,id,comment,true);String root=(String)(parent.get("root_comment_id")==null?parent.get("id"):parent.get("root_comment_id"));
   var args=new ArrayList<Object>(List.of(root,viewer));String after="";
   if(cursor!=null&&!cursor.isEmpty()){var at=cursorComment(viewer,id,cursor,root);after=" AND (c.created_at,c.id)>(?,?)";args.add(at.get("created_at"));args.add(cursor);}
   args.add(limit);return db.queryForList("SELECT c.* FROM post_comments c WHERE c.root_comment_id=? AND c.deleted_at IS NULL AND "+commentVisible()+after+" ORDER BY c.created_at,c.id LIMIT ?",args.toArray()).stream().map(c->commentOut(viewer,c)).toList();
  });
 }
 private Map<String,Object> commentOut(String viewer,Map<String,Object> comment){
  var out=new LinkedHashMap<String,Object>();for(String key:List.of("id","post_id","author_id","parent_comment_id","root_comment_id","reply_to_user_id","moderation_status","created_at"))out.put(key,comment.get(key));
  boolean deleted=comment.get("deleted_at")!=null;String id=(String)comment.get("id");
  out.put("is_deleted",deleted);out.put("content",deleted?null:comment.get("content"));out.put("media_kind",deleted?null:comment.get("media_kind"));
  out.put("media",deleted||comment.get("media_asset_id")==null?null:mediaOut(viewer,asset((String)comment.get("media_asset_id")),false));
  out.put("author_nickname",nickname((String)comment.get("author_id")));out.put("reply_to_nickname",nickname((String)comment.get("reply_to_user_id")));
  out.put("like_count",db.queryForObject("SELECT count(*) FROM post_comment_likes WHERE comment_id=?",Integer.class,id));
  out.put("liked_by_me",db.queryForObject("SELECT count(*) FROM post_comment_likes WHERE comment_id=? AND user_id=?",Integer.class,id,viewer)>0);
  out.put("reply_count",comment.get("root_comment_id")==null?db.queryForObject("SELECT count(*) FROM post_comments WHERE root_comment_id=? AND deleted_at IS NULL AND moderation_status='approved'",Integer.class,id):0);
  return rows.output(out);
 }
 public Map<String,Object> likeComment(Principal actor,String id,String comment,boolean on){
  var snapshot=access.comment(actor.userId(),id,comment,false);
  return tx.execute(status->{
   access.lockPost(actor,id,List.of((String)snapshot.get("author_id")));var row=access.comment(actor.userId(),id,comment,false);
   int changed=on?db.update("INSERT INTO post_comment_likes(comment_id,user_id) VALUES(?,?) ON CONFLICT DO NOTHING",comment,actor.userId()):db.update("DELETE FROM post_comment_likes WHERE comment_id=? AND user_id=?",comment,actor.userId());
   if(on&&changed>0)notifications.event((String)row.get("author_id"),actor.userId(),"post_comment_liked",id,"有人赞了你的评论","你的评论收到了点赞",Map.of("comment_id",comment),null,true);
   return commentOut(actor.userId(),row);
  });
 }
 public void deleteComment(Principal actor,String id,String comment){
  var snapshot=access.manageableComment(actor.userId(),id,comment);
  tx.executeWithoutResult(status->{var post=access.lockPost(actor,id,List.of((String)snapshot.get("author_id")));var row=access.manageableComment(actor.userId(),id,comment);
   if(!actor.userId().equals(row.get("author_id"))&&!actor.userId().equals(post.get("author_id")))throw new ApiError(403,"COMMON_FORBIDDEN","无权删除评论");
   db.update("UPDATE post_comments SET deleted_at=clock_timestamp() WHERE id=?",comment);
  });
 }
 private Map<String,Object> usable(String viewer,String post,String asset){
  access.visible(viewer,post);var item=asset(asset);
  if(!post.equals(item.get("post_id"))||!Set.of("image","video").contains(item.get("media_type"))||!"approved".equals(item.get("status"))||!"ready".equals(item.get("processing_status")))throw new ApiError(404,"POST_MEDIA_NOT_AVAILABLE","动态媒体不可操作");
  access.media(viewer,item);return item;
 }
 private Map<String,Object> summary(String viewer,String post,String asset){
  var counts=new LinkedHashMap<String,Object>();long total=0;
  for(var row:db.queryForList("SELECT emoji,count(*) AS count FROM post_media_reactions WHERE media_asset_id=? GROUP BY emoji ORDER BY emoji",asset)){counts.put((String)row.get("emoji"),row.get("count"));total+=((Number)row.get("count")).longValue();}
  var mine=db.queryForList("SELECT emoji FROM post_media_reactions WHERE media_asset_id=? AND user_id=?",asset,viewer);
  var out=new LinkedHashMap<String,Object>();out.put("post_id",post);out.put("media_asset_id",asset);out.put("reaction_counts",counts);out.put("reaction_total",total);out.put("my_reaction",mine.isEmpty()?null:mine.getFirst().get("emoji"));return out;
 }
 public Map<String,Object> reactions(String viewer,String post,String asset){return read(()->{usable(viewer,post,asset);return summary(viewer,post,asset);});}
 public Map<String,Object> react(Principal actor,String post,String asset,String raw){
  String emoji=raw==null?null:emoji(raw);return tx.execute(status->{
   var owner=access.lockPost(actor,post,List.of());var item=usable(actor.userId(),post,asset);admit(actor.userId(),"post-media-reaction",12);
   String key="post-reaction:"+asset+":"+actor.userId();
   if(emoji==null){db.update("DELETE FROM post_media_reactions WHERE media_asset_id=? AND user_id=?",asset,actor.userId());db.update("DELETE FROM notification_events WHERE deduplication_key=?",key);notifications.changed((String)owner.get("author_id"));}
   else{
    var old=db.queryForList("SELECT emoji FROM post_media_reactions WHERE media_asset_id=? AND user_id=?",asset,actor.userId());
    db.update("INSERT INTO post_media_reactions(post_id,media_asset_id,user_id,emoji) VALUES(?,?,?,?) ON CONFLICT(media_asset_id,user_id) DO UPDATE SET emoji=excluded.emoji,updated_at=clock_timestamp()",post,asset,actor.userId(),emoji);
    if(old.isEmpty()||!emoji.equals(old.getFirst().get("emoji"))){
     db.update("DELETE FROM notification_events WHERE deduplication_key=?",key);
     notifications.event((String)owner.get("author_id"),actor.userId(),"post_media_reacted",post,"你的动态收到 Emoji 点评",emoji,Map.of("media_asset_id",asset,"media_type",item.get("media_type"),"emoji",emoji),key,false);
    }
   }return summary(actor.userId(),post,asset);
  });
 }
 private String emoji(String raw){
  String value=raw.strip();boolean base=false,keycap=value.indexOf('\u20e3')>=0;
  if(value.isEmpty()||value.codePointCount(0,value.length())>16)throw new ApiError(400,"POST_MEDIA_REACTION_INVALID","请选择有效 Emoji");
  for(int point:value.codePoints().toArray()){
   if((point>=0x1f000&&point<=0x1faff)||(point>=0x2300&&point<=0x23ff)||(point>=0x2600&&point<=0x27bf)||(point>=0x2b00&&point<=0x2bff)||Set.of(0xa9,0xae,0x203c,0x2049,0x2122,0x2139,0x3030,0x303d,0x3297,0x3299).contains(point)){base=true;continue;}
   if(point==0x200d||point==0xfe0e||point==0xfe0f||point==0x20e3||(point>=0x1f3fb&&point<=0x1f3ff)||(point>=0xe0020&&point<=0xe007f)||(keycap&&(point==35||point==42||(point>=48&&point<=57))))continue;
   throw new ApiError(400,"POST_MEDIA_REACTION_INVALID","请选择有效 Emoji");
  }if(!base&&!keycap)throw new ApiError(400,"POST_MEDIA_REACTION_INVALID","请选择有效 Emoji");return value;
 }
 public Map<String,Object> download(Principal actor,String post,String asset){return read(()->{
  var item=usable(actor.userId(),post,asset);var owner=access.visible(actor.userId(),post);
  if(!actor.userId().equals(owner.get("author_id"))&&!Boolean.TRUE.equals(owner.get("allow_media_save")))throw new ApiError(403,"POST_MEDIA_SAVE_FORBIDDEN","作者未允许保存媒体");
  String suffix="video".equals(item.get("media_type"))?".mp4":"image/gif".equals(item.get("content_type"))?".gif":".png";
  return Map.of("post_id",post,"media_asset_id",asset,"media_type",item.get("media_type"),"content_type",item.get("content_type"),"filename","datelive_"+asset+suffix,"url",media.access(actor,asset,"original").get("url"));
 });}
 private void admit(String user,String category,int max){
  int hits=db.queryForObject("""
   INSERT INTO auth_rate_windows(bucket_key,hits,expires_at) VALUES(?,1,now()+interval '10 seconds')
   ON CONFLICT(bucket_key) DO UPDATE SET hits=CASE WHEN auth_rate_windows.expires_at<=now() THEN 1 ELSE auth_rate_windows.hits+1 END,
   expires_at=CASE WHEN auth_rate_windows.expires_at<=now() THEN now()+interval '10 seconds' ELSE auth_rate_windows.expires_at END RETURNING hits
   """,Integer.class,category+":"+user);
  if(hits>max)throw new ApiError(429,category.equals("post-comment")?"POST_COMMENT_RATE_LIMITED":"POST_MEDIA_REACTION_RATE_LIMITED","操作过于频繁");
 }
}