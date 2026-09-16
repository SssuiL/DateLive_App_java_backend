package com.manliao.backend.identity;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
@Configuration
@EnableScheduling
public class RateWindowMaintenance {
 private final JdbcTemplate db;
 public RateWindowMaintenance(JdbcTemplate db) { this.db=db; }
 @Scheduled(initialDelay=60000,fixedDelay=60000)
 public void removeExpiredWindows() {
   db.update("""
      DELETE FROM auth_verification_codes WHERE id IN (
        SELECT id FROM auth_verification_codes WHERE created_at < now()-interval '1 day' LIMIT 1000
      )
      """);
   db.update("""
      DELETE FROM auth_rate_windows WHERE bucket_key IN (
        SELECT bucket_key FROM auth_rate_windows
        WHERE expires_at < now()-interval '1 day' LIMIT 1000
      )
      """);
 }
}
