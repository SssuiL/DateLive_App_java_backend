package com.manliao.backend.common;
import java.util.Map;
public final class ApiError extends RuntimeException {
 private final int status;
 private final String code;
 private final Map<String,Object> details;
 public ApiError(int status,String code,String message) { this(status,code,message,null); }
 public ApiError(int status,String code,String message,Map<String,Object> details) {
   super(message); this.status=status; this.code=code; this.details=details==null?null:Map.copyOf(details);
 }
 public int status() { return status; }
 public String code() { return code; }
 public Map<String,Object> details() { return details; }
 public int retryAfterSeconds() {
   return details!=null && details.get("retry_after_seconds") instanceof Number seconds ? Math.max(1,seconds.intValue()) : 60;
 }
}
