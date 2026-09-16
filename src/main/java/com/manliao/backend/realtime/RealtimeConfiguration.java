package com.manliao.backend.realtime;
import java.util.Map;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.http.server.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.web.util.UriComponentsBuilder;
import com.manliao.backend.identity.AuthService;
@Configuration
@EnableWebSocket
public class RealtimeConfiguration implements WebSocketConfigurer {
 private final RealtimeHandler handler;private final AuthService auth;
 public RealtimeConfiguration(RealtimeHandler handler,AuthService auth){this.handler=handler;this.auth=auth;}
 @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){
  registry.addHandler(handler,"/ws/messages","/ws/notifications").addInterceptors(new HandshakeInterceptor(){
   public boolean beforeHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler h,Map<String,Object> attributes){
    try{
     var query=UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
     String token=query.getFirst("token");String header=request.getHeaders().getFirst("Authorization");
     if(header!=null){if(!header.regionMatches(true,0,"Bearer ",0,7))throw new IllegalArgumentException();token=header.substring(7).strip();}
     if(token==null||token.isBlank())throw new IllegalArgumentException();
     auth.authenticate(token);attributes.put("token",token);
     String cursor=query.getFirst("cursor");
     if(cursor!=null){long value=Long.parseLong(cursor);if(value<0)throw new IllegalArgumentException();attributes.put("cursor",value);}
     attributes.put("reliable","true".equals(query.getFirst("reliable")));
     return true;
    }catch(RuntimeException failure){response.setStatusCode(HttpStatus.UNAUTHORIZED);return false;}
   }
   public void afterHandshake(ServerHttpRequest r,ServerHttpResponse s,WebSocketHandler h,Exception e){}
  }); // Default same-origin checks; native clients without Origin are supported.
 }
 @Bean ServletServerContainerFactoryBean websocketContainer(){
  var bean=new ServletServerContainerFactoryBean();
  bean.setMaxTextMessageBufferSize(8192);bean.setMaxBinaryMessageBufferSize(8192);
  bean.setAsyncSendTimeout(3000L);bean.setMaxSessionIdleTimeout(90000L);return bean;
 }
}
