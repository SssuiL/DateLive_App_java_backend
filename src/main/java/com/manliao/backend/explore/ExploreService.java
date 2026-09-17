package com.manliao.backend.explore;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.manliao.backend.common.*;
import com.manliao.backend.identity.AuthDtos.Principal;
import com.manliao.backend.social.*;
import com.manliao.backend.profiles.ProfileService;
import com.manliao.backend.notifications.MatchNotifications;
@Service
public class ExploreService {
 private final JdbcTemplate db;private final TransactionTemplate tx;private final DatabaseRows rows;
 private final SocialService social;private final ProfileService profiles;private final MatchNotifications notifications;
 public ExploreService(JdbcTemplate db,TransactionTemplate tx,DatabaseRows rows,SocialService social,ProfileService profiles,MatchNotifications notifications){this.db=db;this.tx=tx;this.rows=rows;this.social=social;this.profiles=profiles;this.notifications=notifications;}
 private static String id(String prefix){return prefix+"_"+UUID.randomUUID().toString().replace("-","");}
 private void lock(Principal actor,String target){
  if(actor.userId().equals(target))throw new ApiError(422,"COMMON_VALIDATION_ERROR","不能对自己划卡");
  for(String user:new TreeSet<>(List.of(actor.userId(),target))){
   var found=db.queryForList("SELECT status FROM users WHERE id=? FOR UPDATE",user);
   if(found.isEmpty()||!"active".equals(found.getFirst().get("status")))throw new ApiError(404,"USER_NOT_FOUND","用户不存在或不可访问");
  }
  session(actor);
  if(db.queryForObject("SELECT count(*) FROM blocks WHERE (actor_user_id=? AND target_user_id=?) OR (actor_user_id=? AND target_user_id=?)",Integer.class,actor.userId(),target,target,actor.userId())>0)throw new ApiError(404,"USER_NOT_FOUND","用户不存在或不可访问");
 }
 private void session(Principal actor){
  if(db.queryForObject("SELECT count(*) FROM refresh_tokens WHERE id=? AND user_id=? AND revoked_at IS NULL AND expires_at>now()",Integer.class,actor.sessionId(),actor.userId())==0)throw new ApiError(401,"AUTH_INVALID_TOKEN","登录会话已失效");
 }
 public Map<String,Object> act(Principal actor,String target,String type){return tx.execute(status->{
  lock(actor,target);
  // No membership, daily-like, daily-undo or business quota check.
  var existing=type.equals("like")?db.queryForList("SELECT id FROM explore_actions WHERE actor_user_id=? AND target_user_id=? AND action_type='like' AND undone_at IS NULL",actor.userId(),target):List.<Map<String,Object>>of();
  String action=existing.isEmpty()?id("act"):(String)existing.getFirst().get("id");
  if(existing.isEmpty())db.update("INSERT INTO explore_actions(id,actor_user_id,target_user_id,action_type) VALUES(?,?,?,?)",action,actor.userId(),target,type);
  if(type.equals("block"))social.block(actor,new SocialDtos.Block(target,"hide_from_explore"));
  Map<String,Object> match=null;
  if(type.equals("like")&&db.queryForObject("SELECT count(*) FROM explore_actions WHERE actor_user_id=? AND target_user_id=? AND action_type='like' AND undone_at IS NULL",Integer.class,target,actor.userId())>0){
   var pair=new TreeSet<>(List.of(actor.userId(),target));String a=pair.first(),b=pair.last();
   var found=db.queryForList("SELECT id FROM matches WHERE user_a_id=? AND user_b_id=?",a,b);
   String conversation=social.ensureMatchedFriendship(actor,target);
   String matchId=found.isEmpty()?id("match"):(String)found.getFirst().get("id");
   if(found.isEmpty()){
    db.update("INSERT INTO matches(id,user_a_id,user_b_id,conversation_id) VALUES(?,?,?,?)",matchId,a,b,conversation);
    notifications.created(matchId,a,b,conversation);notifications.created(matchId,b,a,conversation);
   }
   match=matches(actor.userId(),matchId).getFirst();
  }
  return result(action,match);
 });}
 private Map<String,Object> result(String action,Map<String,Object> match){
  var result=new LinkedHashMap<String,Object>();
  result.put("action",rows.output(db.queryForMap("SELECT id,actor_user_id,target_user_id,action_type,created_at FROM explore_actions WHERE id=?",action)));
  result.put("match",match);result.put("conversation_id",match==null?null:match.get("conversation_id"));
  result.put("likes_unlimited",true);result.put("undo_unlimited",true);result.put("undo_remaining_today",null);return result;
 }
 public Map<String,Object> undo(Principal actor){
  // Snapshot only chooses lock order. Recheck the latest decision under the same sorted user locks.
  for(int attempt=0;attempt<5;attempt++){
   var latest=latest(actor.userId());if(latest.isEmpty())throw new ApiError(409,"EXPLORE_NOTHING_TO_UNDO","没有可以回退的操作");
   var snapshot=latest.getFirst();
   var result=tx.execute(status->{
    lock(actor,(String)snapshot.get("target_user_id"));var current=latest(actor.userId());
    if(current.isEmpty()||!snapshot.get("id").equals(current.getFirst().get("id")))return null;
    db.update("UPDATE explore_actions SET undone_at=clock_timestamp() WHERE id=?",snapshot.get("id"));
    String action=id("act");db.update("INSERT INTO explore_actions(id,actor_user_id,target_user_id,action_type) VALUES(?,?,?,'undo')",action,actor.userId(),snapshot.get("target_user_id"));
    // Existing friendships/matches persist; undo only retracts an unmatched like or skip.
    return result(action,null);
   });if(result!=null)return result;
  }
  throw new ApiError(409,"COMMON_CONFLICT","划卡状态已变化，请重试");
 }
 private List<Map<String,Object>> latest(String actor){return db.queryForList("SELECT id,target_user_id FROM explore_actions WHERE actor_user_id=? AND action_type IN ('like','skip') AND undone_at IS NULL ORDER BY created_at DESC,id DESC LIMIT 1",actor);}
 public List<Map<String,Object>> matches(String user,String id){
  var args=new ArrayList<Object>(List.of(user,user,user,user,user));
  String extra="";if(id!=null){extra=" AND m.id=?";args.add(id);}
  return db.queryForList("""
   SELECT m.id,jsonb_build_array(m.user_a_id,m.user_b_id) AS user_ids,m.conversation_id,m.created_at FROM matches m
   JOIN users peer ON peer.id=CASE WHEN m.user_a_id=? THEN m.user_b_id ELSE m.user_a_id END
   WHERE (m.user_a_id=? OR m.user_b_id=?) AND peer.status='active'
    AND NOT EXISTS(SELECT 1 FROM blocks b WHERE (b.actor_user_id=? AND b.target_user_id=peer.id) OR (b.target_user_id=? AND b.actor_user_id=peer.id))
   """+extra+" ORDER BY m.created_at DESC,m.id DESC",args.toArray()).stream().map(r->rows.output(r,"user_ids")).toList();
 }
 public List<Map<String,Object>> liked(String user){
  var result=new ArrayList<Map<String,Object>>();
  var found=db.queryForList("""
   SELECT e.target_user_id,e.created_at FROM explore_actions e WHERE e.actor_user_id=? AND e.action_type='like' AND e.undone_at IS NULL
   AND NOT EXISTS(SELECT 1 FROM friendships f WHERE (f.user_a_id=e.actor_user_id AND f.user_b_id=e.target_user_id) OR (f.user_b_id=e.actor_user_id AND f.user_a_id=e.target_user_id))
   ORDER BY e.created_at DESC,e.id DESC
   """,user);
  for(var action:found){try{
   var row=new LinkedHashMap<String,Object>();row.put("profile",profiles.get(user,(String)action.get("target_user_id")));
   row.put("liked_action_type","like");row.put("liked_at",action.get("created_at"));row.put("conversation_id",null);result.add(rows.output(row));
  }catch(ApiError hidden){if(hidden.status()!=404)throw hidden;}}
  return result;
 }
 public List<Map<String,Object>> candidates(String user,int limit){
  if(limit<1||limit>10)throw new ApiError(422,"COMMON_VALIDATION_ERROR","每页候选数量为 1 至 10");
  var found=db.queryForList("""
   WITH eligible AS (
    SELECT p.user_id,row_number() OVER(ORDER BY (p.avatar_url IS NOT NULL) DESC,(COALESCE(p.bio,'')<>'') DESC,(jsonb_array_length(p.tags)>0 OR jsonb_array_length(p.hobbies)>0) DESC,p.user_id DESC)-1 AS position,count(*) OVER() AS total
    FROM user_profiles p JOIN users u ON u.id=p.user_id WHERE p.user_id<>? AND u.status='active' AND p.moderation_status='approved'
    AND EXISTS(SELECT 1 FROM media_assets a WHERE a.owner_user_id=p.user_id AND a.source='profile' AND a.media_type='image' AND a.status='approved'
      AND p.photo_urls @> jsonb_build_array(a.url) AND a.portrait_manual_approved)
    AND NOT EXISTS(SELECT 1 FROM blocks b WHERE (b.actor_user_id=? AND b.target_user_id=p.user_id) OR (b.target_user_id=? AND b.actor_user_id=p.user_id))
   ), decisions AS (SELECT count(*) AS total FROM explore_actions WHERE actor_user_id=? AND action_type IN ('like','skip') AND undone_at IS NULL)
   SELECT e.user_id,(SELECT count(*) FROM explore_actions a WHERE a.actor_user_id=? AND a.target_user_id=e.user_id AND a.action_type='exposure') AS exposure_count
   FROM eligible e CROSS JOIN decisions d ORDER BY mod(e.position-mod(d.total,e.total)+e.total,e.total) LIMIT ?
   """,user,user,user,user,user,limit);
  if(found.isEmpty())return List.of();
  var counts=db.queryForMap("""
   SELECT count(*) AS total,count(*) FILTER(WHERE e.created_at>=(date_trunc('day',now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')) AS today
   FROM explore_actions e JOIN users u ON u.id=e.actor_user_id WHERE e.target_user_id=? AND e.action_type='like' AND e.undone_at IS NULL AND u.status='active'
   AND NOT EXISTS(SELECT 1 FROM blocks b WHERE (b.actor_user_id=? AND b.target_user_id=u.id) OR (b.target_user_id=? AND b.actor_user_id=u.id))
   """,user,user,user);
  var result=new ArrayList<Map<String,Object>>();
  for(var row:found){try{
   var item=new LinkedHashMap<String,Object>();item.put("profile",profiles.get(user,(String)row.get("user_id")));item.put("exposure_count",row.get("exposure_count"));
   item.put("total_liked_me_count",counts.get("total"));item.put("today_liked_me_count",counts.get("today"));
   item.put("likes_unlimited",true);item.put("undo_unlimited",true);item.put("undo_remaining_today",null);result.add(item);
  }catch(ApiError hidden){if(hidden.status()!=404)throw hidden;}}
  return result;
 }
}