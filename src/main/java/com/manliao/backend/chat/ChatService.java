package com.manliao.backend.chat;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import com.manliao.backend.common.*;
import com.manliao.backend.identity.AuthDtos.Principal;
import com.manliao.backend.notifications.ChatNotifications;
@Service
public class ChatService {
 private static final String CONVERSATION_COLUMNS="""
  c.id,NULL::text AS group_id,c.type,c.title,s.unread_count,s.pinned,s.muted,
  (SELECT CASE WHEN lm.recalled_at IS NULL THEN left(lm.content,500) ELSE '消息已撤回' END FROM messages lm
   JOIN message_receipts lr ON lr.message_id=lm.id AND lr.user_id=s.user_id AND lr.hidden_at IS NULL
   WHERE lm.conversation_id=c.id ORDER BY lm.created_at DESC,lm.id DESC LIMIT 1) AS last_message,c.last_active_at,c.expires_at,
  (SELECT jsonb_agg(m.user_id ORDER BY m.user_id) FROM conversation_member_states m WHERE m.conversation_id=c.id) AS participant_ids
  """;
 private static final String MESSAGE_COLUMNS="""
  m.id,m.conversation_id,m.sender_id,m.client_message_id,m.reply_to_message_id,m.type,CASE WHEN m.recalled_at IS NULL THEN m.content ELSE '消息已撤回' END AS content,m.created_at,
  CASE WHEN m.recalled_at IS NULL AND EXISTS(SELECT 1 FROM media_assets a WHERE a.id=m.media_asset_id AND a.status='approved' AND a.storage_key IS NOT NULL) THEN m.media_asset_id ELSE NULL END AS media_asset_id,
  CASE WHEN m.recalled_at IS NULL AND EXISTS(SELECT 1 FROM media_assets a WHERE a.id=m.media_asset_id AND a.status='approved' AND a.storage_key IS NOT NULL) THEN m.media_kind ELSE NULL END AS media_kind,CASE WHEN m.recalled_at IS NULL THEN m.duration_seconds ELSE 0 END AS duration_seconds,
  m.recalled_at,m.recalled_by_user_id,(m.recalled_at IS NOT NULL) AS is_recalled,
  CASE WHEN r.id IS NULL OR reply_visibility.hidden_at IS NOT NULL THEN NULL ELSE jsonb_build_object('id',r.id,'sender_id',r.sender_id,'type',r.type,'content',CASE WHEN r.recalled_at IS NULL THEN r.content ELSE '原消息已撤回' END,'recalled',r.recalled_at IS NOT NULL) END AS reply_preview,
  COALESCE((SELECT jsonb_agg(p.user_id ORDER BY p.user_id) FROM message_receipts p WHERE p.message_id=m.id AND p.read_at IS NOT NULL),'[]'::jsonb) AS read_by,
  COALESCE((SELECT jsonb_agg(p.user_id ORDER BY p.user_id) FROM message_receipts p WHERE p.message_id=m.id AND p.delivered_at IS NOT NULL),'[]'::jsonb) AS delivered_to_user_ids
  """;
 private static final String MESSAGE_FROM=" FROM messages m JOIN message_receipts visibility ON visibility.message_id=m.id AND visibility.user_id=? LEFT JOIN messages r ON r.id=m.reply_to_message_id LEFT JOIN message_receipts reply_visibility ON reply_visibility.message_id=r.id AND reply_visibility.user_id=visibility.user_id ";
 private static final String LIST_FROM="""
  FROM conversations c JOIN conversation_member_states s ON s.conversation_id=c.id
  WHERE s.user_id=? AND c.type='friend'
   AND EXISTS(SELECT 1 FROM friendships f WHERE f.conversation_id=c.id AND (f.user_a_id=s.user_id OR f.user_b_id=s.user_id))
   AND (SELECT count(*) FROM conversation_member_states m WHERE m.conversation_id=c.id)=2
   AND NOT EXISTS(SELECT 1 FROM conversation_member_states m JOIN users u ON u.id=m.user_id
     WHERE m.conversation_id=c.id AND m.user_id<>s.user_id AND u.status<>'active')
   AND NOT EXISTS(SELECT 1 FROM blocks b JOIN conversation_member_states m ON m.conversation_id=c.id AND m.user_id<>s.user_id
     WHERE (b.actor_user_id=s.user_id AND b.target_user_id=m.user_id) OR (b.target_user_id=s.user_id AND b.actor_user_id=m.user_id))
  """;
 private final JdbcTemplate db;private final TransactionTemplate tx,readTx;
 private final DatabaseRows rows;private final ObjectMapper json;private final ChatCursor cursors;private final ChatNotifications notifications;
 public ChatService(JdbcTemplate db,TransactionTemplate tx,DatabaseRows rows,ObjectMapper json,ChatCursor cursors,ChatNotifications notifications){
  this.db=db;this.tx=tx;this.rows=rows;this.json=json;this.cursors=cursors;this.notifications=notifications;
  this.readTx=new TransactionTemplate(tx.getTransactionManager());
  this.readTx.setReadOnly(true);this.readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
 }
 public List<Map<String,Object>> list(String viewer){
  return readTx.execute(status->db.queryForList("SELECT "+CONVERSATION_COLUMNS+LIST_FROM+" ORDER BY s.pinned DESC,c.last_active_at DESC,c.id DESC",viewer).stream().map(this::conversationOutput).toList());
 }
 public Map<String,Object> page(String viewer,String rawLimit,String cursor){
  int limit=cursors.limit(rawLimit,100);var position=cursor==null||cursor.isEmpty()?null:cursors.decode(cursor,true);
  return readTx.execute(status->{
   var args=new ArrayList<Object>();args.add(viewer);
   String after="";
   if(position!=null){after=" AND (s.pinned,c.last_active_at,c.id)<(?,?,?)";args.add(position.pinned());args.add(position.time());args.add(position.id());}
   args.add(limit+1);
   var found=db.queryForList("SELECT "+CONVERSATION_COLUMNS+LIST_FROM+after+" ORDER BY s.pinned DESC,c.last_active_at DESC,c.id DESC LIMIT ?",args.toArray());
   boolean more=found.size()>limit;var selected=found.subList(0,Math.min(limit,found.size())).stream().map(this::conversationOutput).toList();
   return pageOutput(selected,more,more?cursors.encode(selected.getLast(),true):null);
  });
 }
 public Map<String,Object> get(String viewer,String id){
  return readTx.execute(status->{authorize(viewer,id);return conversation(viewer,id);});
 }
 public List<Map<String,Object>> messages(String viewer,String id){
  return readTx.execute(status->{
   authorize(viewer,id);
   return db.queryForList("SELECT "+MESSAGE_COLUMNS+MESSAGE_FROM+" WHERE m.conversation_id=? AND visibility.hidden_at IS NULL ORDER BY m.created_at,m.id",viewer,id).stream().map(this::messageOutput).toList();
  });
 }
 public Map<String,Object> history(String viewer,String id,String rawLimit,String cursor){
  int limit=cursors.limit(rawLimit,50);var position=cursor==null||cursor.isEmpty()?null:cursors.decode(cursor,false);
  return readTx.execute(status->{
   authorize(viewer,id);
   var args=new ArrayList<Object>();args.add(viewer);args.add(id);String after="";
   if(position!=null){after=" AND (m.created_at,m.id)<(?,?)";args.add(position.time());args.add(position.id());}
   args.add(limit+1);
   var found=db.queryForList("SELECT "+MESSAGE_COLUMNS+MESSAGE_FROM+" WHERE m.conversation_id=? AND visibility.hidden_at IS NULL"+after+" ORDER BY m.created_at DESC,m.id DESC LIMIT ?",args.toArray());
   boolean more=found.size()>limit;
   var selected=new ArrayList<>(found.subList(0,Math.min(limit,found.size())).stream().map(this::messageOutput).toList());
   String next=more?cursors.encode(selected.getLast(),false):null;
   Collections.reverse(selected);return pageOutput(selected,more,next);
  });
 }
 public Map<String,Object> send(Principal caller,String id,ChatDtos.Message input){
  String type=input.type()==null?"text":input.type();
  String kind=type.equals("image")?(input.media_kind()==null?"image":input.media_kind()):null;
  if(!Set.of("text","image","voice","video","file").contains(type)||(type.equals("text")&&(input.media_asset_id()!=null||input.media_kind()!=null))||
    (type.equals("image")&&(input.media_asset_id()==null||!Set.of("image","sticker","gif").contains(kind)))||
    (Set.of("voice","video","file").contains(type)&&(input.media_asset_id()==null||input.media_kind()!=null))||
    (!Set.of("voice","video").contains(type)&&input.duration_seconds()!=null&&input.duration_seconds()!=0))
   throw new ApiError(422,"COMMON_VALIDATION_ERROR","当前支持文本、图片、语音、视频和文件；媒体消息须绑定相应附件");
  String key=input.client_message_id()==null?null:input.client_message_id().strip();
  if(key!=null&&key.length()<8)throw new ApiError(400,"COMMON_VALIDATION_ERROR","client_message_id 格式无效",Map.of("field","client_message_id"));
  return tx.execute(status->{
   String peer=lock(caller,id,true);
   if(db.queryForObject("SELECT count(*) FROM friendships WHERE conversation_id=? AND (user_a_id=? OR user_b_id=?)",Integer.class,id,caller.userId(),caller.userId())==0)
    throw new ApiError(403,"CONVERSATION_ACCESS_FORBIDDEN","你们已不是好友，不能继续发送消息");
   if(key!=null){
    var existing=db.queryForList("SELECT id,content,reply_to_message_id,type,media_asset_id,media_kind FROM messages WHERE conversation_id=? AND sender_id=? AND client_message_id=?",id,caller.userId(),key);
    if(!existing.isEmpty()){
     var previous=existing.getFirst();
     if(!Objects.equals(previous.get("content"),input.content())||!Objects.equals(previous.get("reply_to_message_id"),input.reply_to_message_id())||
       !Objects.equals(previous.get("type"),type)||!Objects.equals(previous.get("media_asset_id"),input.media_asset_id())||!Objects.equals(previous.get("media_kind"),kind))
      throw new ApiError(409,"COMMON_CONFLICT","client_message_id 已用于另一条消息",Map.of("field","client_message_id"));
     return message((String)previous.get("id"),caller.userId());
    }
   }
   if(input.reply_to_message_id()!=null&&db.queryForObject("SELECT count(*) FROM messages m JOIN message_receipts p ON p.message_id=m.id WHERE m.id=? AND m.conversation_id=? AND m.recalled_at IS NULL AND p.user_id=? AND p.hidden_at IS NULL",Integer.class,input.reply_to_message_id(),id,caller.userId())==0)
    throw new ApiError(400,"MESSAGE_REPLY_INVALID","被引用的消息不存在或不可用",Map.of("field","reply_to_message_id"));
   int duration=type.equals("text")?0:validateMedia(caller.userId(),id,input.media_asset_id(),type,kind);
   admit(caller.userId());
   String message="msg_"+UUID.randomUUID().toString().replace("-","");
   var created=db.queryForMap("INSERT INTO messages(id,conversation_id,sender_id,client_message_id,reply_to_message_id,content,type,media_asset_id,media_kind,duration_seconds) VALUES(?,?,?,?,?,?,?,?,?,?) RETURNING created_at",
     message,id,caller.userId(),key,input.reply_to_message_id(),input.content(),type,input.media_asset_id(),kind,duration);
   if(!type.equals("text"))db.update("UPDATE media_assets SET message_id=? WHERE id=?",message,input.media_asset_id());
   db.update("INSERT INTO message_receipts(message_id,conversation_id,user_id,read_at) VALUES(?,?,?,clock_timestamp()),(?,?,?,NULL)",
     message,id,caller.userId(),message,id,peer);
   String preview=input.content().substring(0,input.content().offsetByCodePoints(0,Math.min(500,input.content().codePointCount(0,input.content().length()))));
   db.update("UPDATE conversations SET last_message=?,last_active_at=? WHERE id=?",preview,created.get("created_at"),id);
   db.update("UPDATE conversation_member_states SET unread_count=unread_count+1,updated_at=clock_timestamp() WHERE conversation_id=? AND user_id=?",id,peer);
   db.update("INSERT INTO chat_change_outbox(conversation_id,actor_user_id,message_id,event_type) VALUES(?,?,?,'message.created')",id,caller.userId(),message);
   notifications.created(message,id,caller.userId(),peer);
   return message(message,caller.userId());
  });
 }
 public Map<String,Object> read(Principal caller,String id){
  return tx.execute(status->{
   lock(caller,id,false);
   var changed=db.queryForList("""
    UPDATE message_receipts p SET read_at=clock_timestamp(),delivered_at=COALESCE(delivered_at,clock_timestamp())
    FROM messages m WHERE p.message_id=m.id AND p.conversation_id=? AND p.user_id=? AND m.sender_id<>?
     AND p.read_at IS NULL AND p.hidden_at IS NULL RETURNING p.message_id
    """,id,caller.userId(),caller.userId());
   db.update("UPDATE conversation_member_states SET unread_count=0,updated_at=clock_timestamp() WHERE conversation_id=? AND user_id=?",id,caller.userId());
   if(!changed.isEmpty()){
    var ids=changed.stream().map(row->(String)row.get("message_id")).sorted().toList();
    db.update("INSERT INTO chat_change_outbox(conversation_id,actor_user_id,event_type,payload) VALUES(?,?,'message.read',?::jsonb)",
      id,caller.userId(),json.writeValueAsString(Map.of("message_ids",ids,"reader_user_id",caller.userId())));
   }
   // Notification-center read state is separate, matching the existing client contract.
   return conversation(caller.userId(),id);
  });
 }
 public Map<String,Object> settings(Principal caller,String id,ChatDtos.Settings input){
  return tx.execute(status->{
   lock(caller,id,false);
   db.update("UPDATE conversation_member_states SET pinned=COALESCE(?,pinned),muted=COALESCE(?,muted),updated_at=clock_timestamp() WHERE conversation_id=? AND user_id=?",
    input.pinned(),input.muted(),id,caller.userId());
   return conversation(caller.userId(),id);
  });
 }
 public Map<String,Object> realtimeMessage(String viewer,String conversationId,String messageId){
  return readTx.execute(status->{authorize(viewer,conversationId);var result=message(messageId,viewer);if(!conversationId.equals(result.get("conversation_id")))throw missing();return result;});
 }
 public void realtimeDelivered(Principal caller,String conversationId,String messageId){
  tx.executeWithoutResult(status->{
   lock(caller,conversationId,false);
   int changed=db.update("""
    UPDATE message_receipts p SET delivered_at=clock_timestamp() FROM messages m
    WHERE p.message_id=m.id AND p.message_id=? AND p.conversation_id=? AND p.user_id=?
      AND p.hidden_at IS NULL AND p.delivered_at IS NULL AND m.sender_id<>? AND m.recalled_at IS NULL
    """,messageId,conversationId,caller.userId(),caller.userId());
   if(changed>0)db.update("INSERT INTO chat_change_outbox(conversation_id,actor_user_id,message_id,event_type) VALUES(?,?,?,'message.delivered')",conversationId,caller.userId(),messageId);
  });
 }
 public Map<String,Object> search(String viewer,String id,String query,String type,String rawLimit,String cursor){
  int limit=cursors.limit(rawLimit,50);
  if(query!=null&&query.codePointCount(0,query.length())>100)throw new ApiError(422,"COMMON_VALIDATION_ERROR","关键词过长");
  String q=query==null?"":query.strip();String filter=type==null||type.isEmpty()?null:type;
  if(filter!=null&&!Set.of("text","image","emoji","video","voice","file").contains(filter))
   throw new ApiError(400,"COMMON_VALIDATION_ERROR","消息类型筛选值无效",Map.of("field","message_type"));
  if(q.isEmpty()&&filter==null)throw new ApiError(400,"COMMON_VALIDATION_ERROR","请输入搜索关键词或选择消息类型");
  var position=cursor==null||cursor.isEmpty()?null:cursors.decode(cursor,false);
  return readTx.execute(status->{
   authorize(viewer,id);
   var args=new ArrayList<Object>();args.add(viewer);args.add(id);
   String condition=" AND visibility.hidden_at IS NULL AND m.recalled_at IS NULL";
   if(!q.isEmpty()){condition+=" AND strpos(lower(m.content),lower(?))>0";args.add(q);}
   if(filter!=null){condition+=" AND m.type=?";args.add(filter);}
   if(position!=null){condition+=" AND (m.created_at,m.id)<(?,?)";args.add(position.time());args.add(position.id());}
   args.add(limit+1);
   var found=db.queryForList("SELECT "+MESSAGE_COLUMNS+MESSAGE_FROM+" WHERE m.conversation_id=?"+condition+" ORDER BY m.created_at DESC,m.id DESC LIMIT ?",args.toArray());
   boolean more=found.size()>limit;
   var selected=found.subList(0,Math.min(limit,found.size())).stream().map(this::messageOutput).toList();
   return pageOutput(selected,more,more?cursors.encode(selected.getLast(),false):null);
  });
 }
 public Map<String,Object> recall(Principal caller,String id,String messageId){
  return tx.execute(status->{
   lock(caller,id,false);
   var found=db.queryForList("""
    SELECT m.*,clock_timestamp()-m.created_at>interval '2 minutes' AS expired FROM messages m
    JOIN message_receipts p ON p.message_id=m.id AND p.user_id=? AND p.hidden_at IS NULL
    WHERE m.id=? AND m.conversation_id=? FOR UPDATE OF m
    """,caller.userId(),messageId,id);
   if(found.isEmpty())throw new ApiError(404,"MESSAGE_NOT_FOUND","消息不存在");
   var message=found.getFirst();
   if(!caller.userId().equals(message.get("sender_id")))throw new ApiError(403,"MESSAGE_RECALL_FORBIDDEN","只能撤回自己的消息");
   if(message.get("recalled_at")!=null)return message(messageId,caller.userId());
   if(Boolean.TRUE.equals(message.get("expired")))throw new ApiError(409,"MESSAGE_RECALL_EXPIRED","消息发送超过 2 分钟，无法撤回",Map.of("recall_window_seconds",120));
   db.update("UPDATE messages SET recalled_at=clock_timestamp(),recalled_by_user_id=? WHERE id=?",caller.userId(),messageId);
   db.update("""
    UPDATE conversations SET last_message='消息已撤回' WHERE id=? AND ?=(
     SELECT id FROM messages WHERE conversation_id=? ORDER BY created_at DESC,id DESC LIMIT 1)
    """,id,messageId,id);
   db.update("DELETE FROM chat_change_outbox WHERE message_id=? AND event_type='message.created' AND delivered_at IS NULL",messageId);
   db.update("INSERT INTO chat_change_outbox(conversation_id,actor_user_id,message_id,event_type) VALUES(?,?,?,'message.recalled')",id,caller.userId(),messageId);
   db.update("""
    UPDATE notification_events SET status='suppressed',suppress_reason='message_recalled',delivery_channel='none',updated_at=clock_timestamp()
    WHERE source_type='message' AND source_id=? AND status='pending'
    """,messageId);
   return message(messageId,caller.userId());
  });
 }
 public Map<String,String> hide(Principal caller,String id,String messageId){
  return tx.execute(status->{
   lock(caller,id,false);
   if(db.queryForObject("SELECT count(*) FROM messages WHERE id=? AND conversation_id=?",Integer.class,messageId,id)==0)
    throw new ApiError(404,"MESSAGE_NOT_FOUND","消息不存在");
   int changed=db.update("UPDATE message_receipts SET hidden_at=clock_timestamp() WHERE conversation_id=? AND message_id=? AND user_id=? AND hidden_at IS NULL",id,messageId,caller.userId());
   if(changed>0){
    refreshVisibleUnread(caller.userId(),id);
    suppressHiddenNotifications(caller.userId(),id);
    db.update("INSERT INTO chat_change_outbox(conversation_id,actor_user_id,message_id,event_type) VALUES(?,?,?,'message.hidden')",id,caller.userId(),messageId);
   }
   return Map.of("status","hidden");
  });
 }
 public Map<String,Object> clear(Principal caller,String id){
  return tx.execute(status->{
   lock(caller,id,false);
   int changed=db.update("UPDATE message_receipts SET hidden_at=clock_timestamp() WHERE conversation_id=? AND user_id=? AND hidden_at IS NULL",id,caller.userId());
   if(changed>0){
    refreshVisibleUnread(caller.userId(),id);
    suppressHiddenNotifications(caller.userId(),id);
    db.update("INSERT INTO chat_change_outbox(conversation_id,actor_user_id,event_type) VALUES(?,?,'conversation.cleared')",id,caller.userId());
   }
   return conversation(caller.userId(),id);
  });
 }
 private void refreshVisibleUnread(String user,String id){
  db.update("""
   UPDATE conversation_member_states SET unread_count=(
    SELECT count(*) FROM message_receipts p JOIN messages m ON m.id=p.message_id
    WHERE p.conversation_id=? AND p.user_id=? AND p.hidden_at IS NULL AND p.read_at IS NULL AND m.sender_id<>?
   ),updated_at=clock_timestamp() WHERE conversation_id=? AND user_id=?
   """,id,user,user,id,user);
 }
 private void suppressHiddenNotifications(String user,String id){
  db.update("""
   UPDATE notification_events n SET status='suppressed',suppress_reason='message_hidden',delivery_channel='none',updated_at=clock_timestamp()
   WHERE n.recipient_user_id=? AND n.conversation_id=? AND n.source_type='message' AND n.status='pending'
    AND EXISTS(SELECT 1 FROM message_receipts p WHERE p.message_id=n.source_id AND p.user_id=? AND p.hidden_at IS NOT NULL)
   """,user,id,user);
 }

 public void lockMediaUpload(Principal caller,String conversation){
  lock(caller,conversation,true);
  if(db.queryForObject("SELECT count(*) FROM friendships WHERE conversation_id=?",Integer.class,conversation)==0)
   throw new ApiError(403,"CONVERSATION_ACCESS_FORBIDDEN","已不是好友，不能上传聊天图片");
 }
 private int validateMedia(String owner,String conversation,String id,String type,String kind){
  var found=db.queryForList("SELECT * FROM media_assets WHERE id=? FOR UPDATE",id);
  if(found.isEmpty()||!owner.equals(found.getFirst().get("owner_user_id")))
   throw new ApiError(404,"MEDIA_NOT_FOUND","媒体不存在");
  var asset=found.getFirst();
  if(!"chat".equals(asset.get("source"))||!conversation.equals(asset.get("conversation_id"))||!type.equals(asset.get("media_type")))
   throw new ApiError(400,"MEDIA_TYPE_INVALID","媒体必须为当前会话上传且类型匹配");
  if("gif".equals(kind)&&!"image/gif".equals(asset.get("content_type")))throw new ApiError(400,"MEDIA_TYPE_INVALID","GIF 消息须使用 GIF 附件");
  if(!"approved".equals(asset.get("status"))||asset.get("storage_key")==null)
   throw new ApiError(409,"MEDIA_NOT_APPROVED","媒体尚未通过审核或已不可用");
  if(asset.get("message_id")!=null)throw new ApiError(409,"COMMON_CONFLICT","媒体已绑定消息，请重新上传");
  int millis=((Number)asset.get("duration_ms")).intValue();
  if(Set.of("voice","video").contains(type)&&millis<=0)throw new ApiError(409,"MEDIA_NOT_APPROVED","音视频尚未完成处理");
  return Set.of("voice","video").contains(type)?Math.min(60,(millis+999)/1000):0;
 }
 private String lock(Principal caller,String id,boolean sending){
  var members=authorize(caller.userId(),id);
  // Same order as social writes: users, conversation, member states, messages/receipts/outbox.
  for(String member:members){
   var state=db.queryForList("SELECT status FROM users WHERE id=? FOR UPDATE",member);
   if(state.isEmpty()||(sending&&!state.getFirst().get("status").equals("active")))throw missing();
  }
  if(db.queryForList("SELECT id FROM conversations WHERE id=? FOR UPDATE",id).isEmpty())throw missing();
  members=authorize(caller.userId(),id);
  if(db.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id=? AND id=? AND revoked_at IS NULL AND expires_at>now()",Integer.class,caller.userId(),caller.sessionId())==0)
   throw new ApiError(401,"AUTH_INVALID_TOKEN","登录会话已失效");
  return members.stream().filter(member->!member.equals(caller.userId())).findFirst().orElseThrow(ChatService::missing);
 }
 private List<String> authorize(String viewer,String id){
  var members=db.queryForList("""
   SELECT m.user_id,u.status,c.type FROM conversation_member_states m
   JOIN users u ON u.id=m.user_id JOIN conversations c ON c.id=m.conversation_id WHERE c.id=? ORDER BY m.user_id
   """,id);
  if(members.size()!=2||members.stream().noneMatch(m->viewer.equals(m.get("user_id")))||
    members.stream().anyMatch(m->!m.get("type").equals("friend")||!(viewer.equals(m.get("user_id"))?Set.of("active","deactivation_pending").contains(m.get("status")):m.get("status").equals("active"))))
   throw missing();
  String peer=members.stream().map(m->(String)m.get("user_id")).filter(user->!user.equals(viewer)).findFirst().orElseThrow(ChatService::missing);
  if(db.queryForObject("SELECT count(*) FROM blocks WHERE (actor_user_id=? AND target_user_id=?) OR (actor_user_id=? AND target_user_id=?)",Integer.class,viewer,peer,peer,viewer)>0)throw missing();
  return members.stream().map(m->(String)m.get("user_id")).toList();
 }
 private Map<String,Object> conversation(String viewer,String id){
  return conversationOutput(db.queryForMap("SELECT "+CONVERSATION_COLUMNS+" FROM conversations c JOIN conversation_member_states s ON s.conversation_id=c.id WHERE c.id=? AND s.user_id=?",id,viewer));
 }
 private Map<String,Object> conversationOutput(Map<String,Object> row){
  var out=rows.output(row,"participant_ids");
  var online=new LinkedHashMap<String,Object>();var seen=new LinkedHashMap<String,Object>();
  for(var member:db.queryForList("""
   SELECT u.id,u.last_seen_at,EXISTS(SELECT 1 FROM realtime_connections r JOIN refresh_tokens t ON t.id=r.session_id
    WHERE r.user_id=u.id AND r.channel='messages' AND r.expires_at>clock_timestamp() AND t.revoked_at IS NULL AND t.expires_at>clock_timestamp()) AS online
   FROM users u JOIN conversation_member_states m ON m.user_id=u.id WHERE m.conversation_id=?
   """,out.get("id"))){
    String user=(String)member.get("id");online.put(user,member.get("online"));
    Object time=member.get("last_seen_at");seen.put(user,time instanceof java.sql.Timestamp t?t.toInstant():null);
  }
  out.put("participant_online",online);out.put("participant_last_seen_at",seen);return out;
 }
 private Map<String,Object> message(String id,String viewer){
  var found=db.queryForList("SELECT "+MESSAGE_COLUMNS+MESSAGE_FROM+" WHERE m.id=? AND visibility.hidden_at IS NULL",viewer,id);
  if(found.isEmpty())throw new ApiError(404,"MESSAGE_NOT_FOUND","消息不存在或已隐藏");
  return messageOutput(found.getFirst());
 }
 private Map<String,Object> messageOutput(Map<String,Object> row){return rows.output(row,"read_by","delivered_to_user_ids","reply_preview");}
 private Map<String,Object> pageOutput(List<Map<String,Object>> items,boolean more,String next){
  var out=new LinkedHashMap<String,Object>();out.put("items",items);out.put("next_cursor",next);out.put("has_more",more);return out;
 }
 private void admit(String user){
  int hits=db.queryForObject("""
   INSERT INTO auth_rate_windows(bucket_key,hits,expires_at) VALUES(?,1,now()+interval '60 seconds')
   ON CONFLICT(bucket_key) DO UPDATE SET
    hits=CASE WHEN auth_rate_windows.expires_at<=now() THEN 1 ELSE auth_rate_windows.hits+1 END,
    expires_at=CASE WHEN auth_rate_windows.expires_at<=now() THEN now()+interval '60 seconds' ELSE auth_rate_windows.expires_at END RETURNING hits
   """,Integer.class,"chat-send:"+user);
  if(hits>120)throw new ApiError(429,"AUTH_RATE_LIMITED","发送消息过于频繁，请稍后再试");
 }
 private static ApiError missing(){return new ApiError(404,"CONVERSATION_NOT_FOUND","会话不存在或无权访问");}
}
