package com.manliao.backend.live;
import java.util.*;
import java.time.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import com.manliao.backend.common.*;
import com.manliao.backend.identity.AuthDtos.Principal;
import com.manliao.backend.billing.*;
import com.manliao.backend.media.MediaService;
@Service
public class LiveRoomService {
 private final JdbcTemplate db;private final TransactionTemplate tx;private final WalletService users;private final LedgerService ledger;private final HostVerificationService hosts;private final DatabaseRows rows;private final ObjectMapper json;private final MediaService media;
 @Value("${app.live.provider:disabled}") private String provider;
 @Value("${app.live.mock-enabled:false}") private boolean mock;
 @Value("${app.environment:production}") private String environment;
 public LiveRoomService(JdbcTemplate db,TransactionTemplate tx,WalletService users,LedgerService ledger,HostVerificationService hosts,DatabaseRows rows,ObjectMapper json,MediaService media){this.db=db;this.tx=tx;this.users=users;this.ledger=ledger;this.hosts=hosts;this.rows=rows;this.json=json;this.media=media;}
 static String id(String prefix){return prefix+"_"+UUID.randomUUID().toString().replace("-","");}
 static ApiError state(String message){return new ApiError(400,"LIVE_ROOM_STATE_INVALID",message);}
 void available(String provider){if(!"mock".equals(provider)||!mock||"production".equalsIgnoreCase(environment))throw new ApiError(503,"LIVE_PROVIDER_UNAVAILABLE","直播服务商尚未配置");}
 Map<String,Object> room(String id,boolean lock){var found=db.queryForList("SELECT * FROM live_rooms WHERE id=?"+(lock?" FOR UPDATE":""),id);if(found.isEmpty())throw new ApiError(404,"LIVE_ROOM_NOT_FOUND","直播间不存在");return found.getFirst();}
 Map<String,Object> lock(Principal actor,String id){var before=room(id,false);ledger.lockUsers(List.of(actor.userId(),(String)before.get("host_user_id")));users.lock(actor);return room(id,true);}
 void owner(Principal actor,Map<String,Object> room){if(!actor.userId().equals(room.get("host_user_id")))throw new ApiError(403,"LIVE_ROOM_MANAGE_FORBIDDEN","只有主播可以管理该直播间");}
 Map<String,Object> output(Map<String,Object> room){var out=rows.output(room,"tags");for(String key:List.of("provider_room_id","announcement","slow_mode_seconds","allow_cohost","close_reason","closed_by_user_id","created_at","updated_at"))out.remove(key);out.put("online_count",db.queryForObject("SELECT count(*) FROM live_room_participants p JOIN users u ON u.id=p.user_id WHERE p.room_id=? AND p.presence_status='active' AND u.status='active'",Integer.class,room.get("id")));out.put("cover_url",media.profileUrl((String)room.get("cover_url")));return out;}
 public Map<String,Object> create(Principal actor,LiveRoomController.Create input){available(provider);return tx.execute(s->{users.lock(actor);String cover=media.canonicalOwned(actor.userId(),input.cover_url());if(cover!=null&&!cover.isBlank()&&db.queryForObject("SELECT count(*) FROM media_assets WHERE owner_user_id=? AND url=? AND media_type='image' AND source='profile' AND status<>'deleted'",Integer.class,actor.userId(),cover)==0)throw new ApiError(422,"MEDIA_NOT_FOUND","封面必须是本人上传的图片");var tags=input.tags().stream().map(String::strip).filter(t->!t.isEmpty()).distinct().toList();String id=id("live");db.update("INSERT INTO live_rooms(id,host_user_id,title,category,tags,cover_url,distance_km,stream_provider,provider_room_id) VALUES(?,?,?,?,?::jsonb,?,?,?,?)",id,actor.userId(),input.title().strip(),input.category().strip(),json.writeValueAsString(tags),cover,input.distance_km(),provider,"mock_"+id);return output(room(id,false));});}
 public List<Map<String,Object>> list(String category){
  String c=category==null?"":category;var found=db.queryForList("""
   SELECT r.* FROM live_rooms r JOIN users u ON u.id=r.host_user_id WHERE r.status='live' AND u.status='active'
    AND (? IN ('','精选','推荐') OR (?='附近的人' AND r.distance_km IS NOT NULL) OR (?='颜值' AND r.tags @> '["颜值"]'::jsonb) OR (? NOT IN ('附近的人','颜值') AND (r.category=? OR r.tags @> jsonb_build_array(?::text))))
   ORDER BY CASE WHEN ?='附近的人' THEN r.distance_km END ASC,r.heat DESC,r.id
   """,c,c,c,c,c,c,c);return found.stream().map(this::output).toList();
 }
 public Map<String,Object> get(Principal actor,String id){return tx.execute(s->{var r=lock(actor,id);if(!"live".equals(r.get("status"))&&!actor.userId().equals(r.get("host_user_id")))throw new ApiError(404,"LIVE_ROOM_NOT_FOUND","直播间不存在或已结束");return output(r);});}
 public Map<String,Object> session(Principal actor,String id){return tx.execute(s->{var r=lock(actor,id);owner(actor,r);available((String)r.get("stream_provider"));var out=new LinkedHashMap<String,Object>();out.put("room_id",id);out.put("provider",r.get("stream_provider"));out.put("provider_room_id",r.get("provider_room_id"));out.put("push_url",null);out.put("playback_url",null);out.put("expires_at",null);out.put("is_mock",true);return out;});}
 Map<String,Object> member(String room,String user){var found=db.queryForList("SELECT room_id,user_id,role,presence_status,is_muted,is_manager,joined_at,left_at FROM live_room_participants WHERE room_id=? AND user_id=?",room,user);if(found.isEmpty())throw new ApiError(404,"LIVE_ROOM_PARTICIPANT_NOT_FOUND","直播间参与者不存在");return rows.output(found.getFirst());}
 private void notKicked(Map<String,Object> p){if("kicked".equals(p.get("presence_status")))throw new ApiError(403,"LIVE_ROOM_PARTICIPANT_KICKED","已被移出该直播间");}
 public Map<String,Object> join(Principal actor,String id){return tx.execute(s->{var r=lock(actor,id);if(!"live".equals(r.get("status")))throw state("当前直播间未开播");var existing=db.queryForList("SELECT presence_status FROM live_room_participants WHERE room_id=? AND user_id=?",id,actor.userId());if(!existing.isEmpty())notKicked(existing.getFirst());db.update("INSERT INTO live_room_participants(id,room_id,user_id,role) VALUES(?,?,?,?) ON CONFLICT(room_id,user_id) DO UPDATE SET presence_status='active',joined_at=clock_timestamp(),left_at=NULL",id("live_member"),id,actor.userId(),actor.userId().equals(r.get("host_user_id"))?"host":"viewer");return member(id,actor.userId());});}
 public Map<String,Object> leave(Principal actor,String id){return tx.execute(s->{var r=lock(actor,id);if(actor.userId().equals(r.get("host_user_id")))throw new ApiError(400,"LIVE_ROOM_MANAGE_FORBIDDEN","主播请结束直播后再离开");notKicked(member(id,actor.userId()));db.update("UPDATE live_room_participants SET presence_status='left',left_at=clock_timestamp() WHERE room_id=? AND user_id=?",id,actor.userId());return member(id,actor.userId());});}
 public Map<String,Object> end(Principal actor,String id){return tx.execute(s->{var r=lock(actor,id);owner(actor,r);if(!"live".equals(r.get("status")))throw state("只有直播中的房间可以结束");db.update("UPDATE live_rooms SET status='ended',stream_state='ended',ended_at=clock_timestamp(),updated_at=clock_timestamp() WHERE id=?",id);db.update("UPDATE live_room_participants SET presence_status='left',left_at=clock_timestamp() WHERE room_id=? AND presence_status='active'",id);return output(room(id,false));});}
 void activate(Principal actor,String id){hosts.assertCanStart(actor.userId());if(db.queryForObject("SELECT count(*) FROM live_rooms WHERE host_user_id=? AND status='live'",Integer.class,actor.userId())>0)throw new ApiError(409,"LIVE_ROOM_STATE_INVALID","已有直播中的房间");db.update("UPDATE live_rooms SET status='live',stream_state='live',started_at=clock_timestamp(),updated_at=clock_timestamp() WHERE id=?",id);db.update("INSERT INTO live_room_participants(id,room_id,user_id,role) VALUES(?,?,?,'host')",id("live_member"),id,actor.userId());}
}
