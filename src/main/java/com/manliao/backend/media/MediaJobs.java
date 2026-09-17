package com.manliao.backend.media;
import java.io.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import com.manliao.backend.common.ApiError;
import com.manliao.backend.identity.AuthDtos.Principal;
@Service
public class MediaJobs {
 private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(MediaJobs.class);
 private final JdbcTemplate db;private final TransactionTemplate tx;private final MediaService media;private final MediaStorage storage;
 @Value("${app.media.jobs-worker-enabled:true}") private boolean enabled;
 @Value("${app.media.draft-worker-enabled:true}") private boolean draftsEnabled;
 @Value("${app.media.draft-retention-hours:24}") private int retentionHours;
 public MediaJobs(JdbcTemplate db,TransactionTemplate tx,MediaService media,MediaStorage storage){this.db=db;this.tx=tx;this.media=media;this.storage=storage;}
 public Map<String,Object> enqueue(Principal user,String type,String source,String conversation,MultipartFile file)throws IOException{
  media.validateUpload(user,type,source,conversation,file);
  String mime=Optional.ofNullable(file.getContentType()).orElse("application/octet-stream");
  if(mime.length()>80||(!type.equals("file")&&!switch(type){
   case "image"->Set.of("image/png","image/jpeg","image/gif").contains(mime);
   case "voice"->Set.of("audio/mp4","audio/x-m4a").contains(mime);
   case "video"->Set.of("video/mp4","video/quicktime","video/x-m4v").contains(mime);
   default->false;}))throw new ApiError(400,"MEDIA_TYPE_INVALID","不支持的媒体类型");
  int limit=(Set.of("file","video").contains(type)?32:8)*1024*1024;
  if(file.getSize()>limit)throw new ApiError(413,"MEDIA_FILE_TOO_LARGE","媒体超过大小限制");
  byte[] bytes;try(var input=file.getInputStream()){bytes=input.readNBytes(limit+1);}
  if(bytes.length==0)throw new ApiError(400,"MEDIA_TYPE_INVALID","文件不能为空");
  if(bytes.length>limit)throw new ApiError(413,"MEDIA_FILE_TOO_LARGE","媒体超过大小限制");
  String id="media_"+UUID.randomUUID().toString().replace("-","");
  String extension=switch(type){case "voice"->".wav";case "video"->".mp4";case "file"->".bin";default->mime.equals("image/gif")?".gif":".png";};
  tx.executeWithoutResult(status->{
   // Serialize quota decisions across instances. Processing never takes this admission lock.
   db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended('media-queue-admission',0))");
   media.lockUpload(user,source,conversation);
   long count=db.queryForObject("SELECT count(*) FROM media_processing_jobs",Long.class);
   long own=db.queryForObject("SELECT count(*) FROM media_processing_jobs j JOIN media_assets a ON a.id=j.media_id WHERE a.owner_user_id=?",Long.class,user.userId());
   long size=db.queryForObject("SELECT coalesce(sum(octet_length(payload)),0) FROM media_processing_jobs",Long.class);
   if(count>=20||own>=4||size+bytes.length>256L*1024*1024)throw new ApiError(429,"MEDIA_QUEUE_FULL","媒体队列繁忙，请稍后重试");
   db.update("""
    INSERT INTO media_assets(id,owner_user_id,url,media_type,status,storage_key,original_filename,content_type,file_size,
      width,height,duration_ms,source,conversation_id,processing_status,moderation_reason)
    VALUES(?,?,?,?,'uploaded',?,?,?,?,0,0,0,?,?,'queued','等待媒体处理')
    """,id,user.userId(),"media:"+id,type,id+extension,media.safeName(file.getOriginalFilename()),mime,bytes.length,source,conversation);
   db.update("INSERT INTO media_processing_jobs(media_id,payload,input_content_type) VALUES(?,?,?)",id,bytes,mime);
  });
  return media.owned(user,id);
 }
 @Scheduled(initialDelay=5000,fixedDelay=1000,scheduler="mediaProcessingScheduler")
 public void scheduled(){if(enabled)processNext();}
 /** One transaction owns the attempt through publication. A crash rolls it back for retry. */
 public boolean processNext(){
  return Boolean.TRUE.equals(tx.execute(status->{
   if(!Boolean.TRUE.equals(db.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended('media-processing-worker',0))",Boolean.class)))return false;
   var candidates=db.queryForList("""
    SELECT a.id,a.owner_user_id FROM media_processing_jobs j JOIN media_assets a ON a.id=j.media_id
    WHERE j.available_at<=now() AND a.processing_status='queued' AND a.status<>'deleted'
    ORDER BY j.available_at,j.media_id LIMIT 1
    """);
   if(candidates.isEmpty())return false;var first=candidates.getFirst();String id=(String)first.get("id");
   db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",first.get("owner_user_id"));
   var found=db.queryForList("SELECT * FROM media_assets WHERE id=? FOR UPDATE",id);
   if(found.isEmpty())return false;var asset=found.getFirst();
   if(!"queued".equals(asset.get("processing_status"))||"deleted".equals(asset.get("status")))return false;
   var jobs=db.queryForList("SELECT * FROM media_processing_jobs WHERE media_id=? FOR UPDATE",id);
   if(jobs.isEmpty())return false;var job=jobs.getFirst();String key=(String)asset.get("storage_key");
   int attempt=((Number)job.get("attempts")).intValue()+1;
   MediaStorage.Stored stored;
   try{
    // A prior process may have crashed after writing files but before committing publication.
    storage.delete(key);
    stored=media.store(id,(String)asset.get("media_type"),new QueuedUpload((byte[])job.get("payload"),(String)job.get("input_content_type"),(String)asset.get("original_filename")));
   }catch(IOException|RuntimeException failure){
    boolean permanent=failure instanceof ApiError e&&e.status()<500&&e.status()!=429;
    String code=failure instanceof ApiError e?e.code():"MEDIA_PROCESSING_FAILED";
    log.warn("Media processing failed: id={}, attempt={}, code={}, type={}",id,attempt,code,failure.getClass().getSimpleName());
    try{storage.delete(key);}catch(IOException cleanup){failure.addSuppressed(cleanup);}
    if(permanent||attempt>=3){
     db.update("UPDATE media_assets SET processing_status='failed',processing_error=?,status='rejected',moderation_reason='媒体处理失败' WHERE id=?",code,id);
     db.update("DELETE FROM media_processing_jobs WHERE media_id=?",id);
    }else{
     db.update("UPDATE media_processing_jobs SET attempts=?,available_at=now()+interval '30 seconds' WHERE media_id=?",attempt,id);
     db.update("UPDATE media_assets SET processing_error=? WHERE id=?",code,id);
    }
    return true;
   }
   db.update("""
    UPDATE media_assets SET processing_status='ready',processing_error=NULL,processed_at=now(),status='review_pending',
     content_type=?,file_size=?,sha256=?,width=?,height=?,duration_ms=?,image_derivatives_ready=?,
     moderation_reason=? WHERE id=?
    """,stored.contentType(),stored.size(),stored.sha256(),stored.width(),stored.height(),stored.durationMs(),"image".equals(asset.get("media_type")),
     "file".equals(asset.get("media_type"))?"原始文件等待人工审核，未执行病毒扫描":"媒体已重新编码，内容等待审核",id);
   db.update("DELETE FROM media_processing_jobs WHERE media_id=?",id);
   return true;
  }));
 }
 private static final String UNREFERENCED="""
  a.status<>'deleted' AND a.message_id IS NULL AND a.created_at<now()-(? * interval '1 hour')
  AND NOT EXISTS(SELECT 1 FROM messages m WHERE m.media_asset_id=a.id)
  AND NOT EXISTS(SELECT 1 FROM user_profiles p WHERE p.avatar_url=a.url OR p.pending_avatar_url=a.url
    OR p.photo_urls @> jsonb_build_array(a.url) OR p.pending_photo_urls @> jsonb_build_array(a.url))
  """;
 @Scheduled(initialDelay=60000,fixedDelay=60000,scheduler="mediaProcessingScheduler")
 public void scheduledDrafts(){if(draftsEnabled)reclaimDrafts();}
 public int reclaimDrafts(){
  if(retentionHours<1)throw new IllegalStateException("Media draft retention must be at least one hour");
  int completed=0;
  for(var candidate:db.queryForList("SELECT a.id,a.owner_user_id FROM media_assets a WHERE "+UNREFERENCED+" ORDER BY a.created_at,a.id LIMIT 50",retentionHours)){
   boolean deleted=Boolean.TRUE.equals(tx.execute(status->{
    db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",candidate.get("owner_user_id"));
    db.queryForList("SELECT user_id FROM user_profiles WHERE user_id=? FOR UPDATE",candidate.get("owner_user_id"));
    db.queryForList("SELECT id FROM media_assets WHERE id=? FOR UPDATE",candidate.get("id"));
    int changed=db.update("UPDATE media_assets a SET status='deleted' WHERE a.id=? AND "+UNREFERENCED,candidate.get("id"),retentionHours);
    if(changed==1)db.update("DELETE FROM media_processing_jobs WHERE media_id=?",candidate.get("id"));
    return changed==1;
   }));
   if(deleted)completed++;
  }
  media.cleanupDeleted();return completed;
 }
}
