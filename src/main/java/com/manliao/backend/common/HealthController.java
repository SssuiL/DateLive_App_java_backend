package com.manliao.backend.common;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final JdbcTemplate db;
    public HealthController(JdbcTemplate db) { this.db = db; }
    @GetMapping("/health")
    public Map<String,String> health() { return Map.of("status","ok","service","manliao-java-backend"); }
    @GetMapping("/health/ready")
    public ResponseEntity<?> ready() {
        try {
            db.queryForObject("SELECT 1",Integer.class);
            return ResponseEntity.ok(Map.of("status","ready","database",Map.of("available",true)));
        } catch (org.springframework.dao.DataAccessException error) {
            return ResponseEntity.status(503).body(Map.of("status","not_ready","database",Map.of("available",false)));
        }
    }
}
