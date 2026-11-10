package com.homestay.platform;

import java.time.Instant;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {
  private final StringRedisTemplate redis;
  private final JdbcTemplate jdbc;
  public HealthController(StringRedisTemplate redis, JdbcTemplate jdbc) { this.redis = redis; this.jdbc = jdbc; }

  @GetMapping("/health")
  public ResponseEntity<Map<String, Object>> health() {
    String redisStatus = "unavailable";
    String databaseStatus = "unavailable";
    try { redis.opsForValue().set("homestay:health:last-check", Instant.now().toString()); redisStatus = "ok"; }
    catch (Exception ignored) { }
    try { jdbc.queryForObject("SELECT 1", Integer.class); databaseStatus = "ok"; }
    catch (Exception ignored) { }
    boolean healthy = "ok".equals(redisStatus) && "ok".equals(databaseStatus);
    Map<String, Object> body = Map.of("status", healthy ? "ok" : "degraded", "service", "homestay-platform",
        "redis", redisStatus, "database", databaseStatus, "time", Instant.now().toString());
    return ResponseEntity.status(healthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
  }
}
