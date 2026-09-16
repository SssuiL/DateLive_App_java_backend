package com.manliao.backend.realtime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.*;
import tools.jackson.databind.ObjectMapper;
@Component
public class RealtimeHandler extends TextWebSocketHandler {
 private final RealtimeStore store;private final ObjectMapper json;
 private final ConcurrentMap<String,Connection> connections=new ConcurrentHashMap<>();
 private final Semaphore slots=new Semaphore(200);
 private final ExecutorService pumps=Executors.newVirtualThreadPerTaskExecutor();
 private static final class Pending {final long first=System.currentTimeMillis();long last=first;}
 private static final class Connection {
  final WebSocketSession socket;final String token,user,channel;final boolean reliable;
  final AtomicBoolean running=new AtomicBoolean();final LinkedHashMap<Long,Pending> pending=new LinkedHashMap<>();
  Map<String,Boolean> presence=Map.of();long cursor,lastAck,lastActivity=System.currentTimeMillis(),lastHeartbeat,lastTyping,window=System.currentTimeMillis();int frames;
  Connection(WebSocketSession socket,String token,String user,String channel,long cursor,boolean reliable){
   this.socket=socket;this.token=token;this.user=user;this.channel=channel;this.cursor=cursor;this.lastAck=cursor;this.reliable=reliable;
  }
 }
 public RealtimeHandler(RealtimeStore store,ObjectMapper json){this.store=store;this.json=json;}
 @Override public void afterConnectionEstablished(WebSocketSession raw)throws Exception{
  if(!slots.tryAcquire()){raw.close(new CloseStatus(1013,"connection_limit"));return;}
  try{
   String token=(String)raw.getAttributes().get("token");String user=store.authenticate(token).userId();
   String channel=raw.getUri().getPath().endsWith("/notifications")?"notifications":"messages";
   long cursor=store.connect(token,raw.getId(),channel,(Long)raw.getAttributes().get("cursor"));
   var c=new Connection(new ConcurrentWebSocketSessionDecorator(raw,3000,65536),token,user,channel,cursor,Boolean.TRUE.equals(raw.getAttributes().get("reliable")));
   connections.put(raw.getId(),c);
   synchronized(c){
    send(c,Map.of("type","realtime.ready","channel",channel,"cursor",Long.toString(cursor),"protocol_version",1));
    if(channel.equals("notifications"))send(c,Map.of("type","notification.unread_count","unread_count",store.unread(user)));
   }
  }catch(Exception failure){
   if(connections.containsKey(raw.getId()))remove(raw.getId());else slots.release();
   try{store.disconnect(raw.getId());}catch(RuntimeException ignored){}
   raw.close(new CloseStatus(1008,"connection_rejected"));
  }
 }
 @Override protected void handleTextMessage(WebSocketSession raw,TextMessage message)throws Exception{
  var c=connections.get(raw.getId());if(c==null)return;
  synchronized(c){
   try{
    store.authenticate(c.token);long now=System.currentTimeMillis();c.lastActivity=now;
    if(now-c.window>=60000){c.window=now;c.frames=0;}
    if(++c.frames>1200||message.getPayloadLength()>8192)throw new IllegalArgumentException();
    var frame=json.readTree(message.getPayload());String type=frame.path("type").asString("");
    if(type.equals("ping"))send(c,Map.of("type","pong"));
    else if(type.equals("ack")){
     String value=frame.path("event_id").asString();long id=Long.parseLong(value);
     if(id<=c.lastAck){send(c,Map.of("type","ack.confirmed","event_id",Long.toString(id)));return;}
     if(c.pending.isEmpty()||c.pending.keySet().iterator().next()!=id)throw new IllegalArgumentException();
     store.ack(c.token,c.channel,id);c.pending.remove(id);c.lastAck=id;
     send(c,Map.of("type","ack.confirmed","event_id",Long.toString(id)));
    }else if(c.channel.equals("messages")&&(type.equals("typing.start")||type.equals("typing.stop"))){
     if(now-c.lastTyping>=500){
      String id=frame.path("conversation_id").asString();if(id.isBlank()||id.length()>64)throw new IllegalArgumentException();
      store.typing(c.token,id,type);c.lastTyping=now;
     }
    }else send(c,Map.of("type","error","code","REALTIME_UNSUPPORTED_FRAME"));
   }catch(RuntimeException failure){close(c,1008,"invalid_frame_or_session");}
  }
 }
 @Override protected void handlePongMessage(WebSocketSession raw,PongMessage message){
  var c=connections.get(raw.getId());if(c!=null)synchronized(c){c.lastActivity=System.currentTimeMillis();}
 }
 @Scheduled(initialDelay=1000,fixedDelay=500)
 public void pump(){
  for(var c:connections.values())if(c.running.compareAndSet(false,true))pumps.submit(()->{
   try{synchronized(c){flush(c);}}finally{c.running.set(false);}
  });
 }
 private void flush(Connection c){
  try{
   if(!c.socket.isOpen())return;
   long now=System.currentTimeMillis();store.authenticate(c.token);
   if(now-c.lastActivity>60000){close(c,1001,"heartbeat_timeout");return;}
   if(now-c.lastHeartbeat>=15000){store.heartbeat(c.token,c.socket.getId());c.lastHeartbeat=now;}
   if(c.channel.equals("messages")){
    var presence=store.presence(c.user);
    for(var item:presence.entrySet())if(!Objects.equals(c.presence.get(item.getKey()),item.getValue())){
     var frame=new LinkedHashMap<String,Object>();frame.put("type","presence.updated");frame.put("user_id",item.getKey());frame.put("online",item.getValue());frame.put("last_seen_at",store.lastSeen(item.getKey()));send(c,frame);
    }
    c.presence=presence;
   }
   if(c.reliable&&!c.pending.isEmpty()){
    long id=c.pending.keySet().iterator().next();Pending pending=c.pending.get(id);
    if(now-pending.first>30000){close(c,1013,"ack_timeout_resume_required");return;}
    if(now-pending.last>3000){
     var event=store.event(c.user,c.channel,id);var frame=event==null?null:store.frame(c.user,event);
     if(frame==null)c.pending.remove(id);else{send(c,frame);pending.last=now;}
    }
   }
   if(c.reliable&&c.pending.size()>=64)return;
   for(var event:store.events(c.user,c.channel,c.cursor)){
    if(c.reliable&&c.pending.size()>=64)break;
    long id=((Number)event.get("id")).longValue();var frame=store.frame(c.user,event);
    if(frame!=null){send(c,frame);c.pending.put(id,new Pending());}
    c.cursor=id;
    while(!c.reliable&&c.pending.size()>256)c.pending.remove(c.pending.keySet().iterator().next());
   }
  }catch(com.manliao.backend.common.ApiError invalid){close(c,1008,"session_invalid");}
   catch(Exception unavailable){close(c,1011,"retry_with_saved_cursor");}
 }
 private void send(Connection c,Object frame)throws java.io.IOException{c.socket.sendMessage(new TextMessage(json.writeValueAsString(frame)));}
 private void close(Connection c,int code,String reason){try{c.socket.close(new CloseStatus(code,reason));}catch(Exception ignored){}finally{remove(c.socket.getId());}}
 private void remove(String id){
  if(connections.remove(id)!=null){slots.release();try{store.disconnect(id);}catch(RuntimeException ignored){}}
 }
 @Override public void afterConnectionClosed(WebSocketSession socket,CloseStatus status){remove(socket.getId());}
 @Override public void handleTransportError(WebSocketSession socket,Throwable error){var c=connections.get(socket.getId());if(c!=null)close(c,1011,"transport_error");}
 @PreDestroy public void shutdown(){for(var c:connections.values())close(c,1001,"server_restart");pumps.shutdownNow();}
}
