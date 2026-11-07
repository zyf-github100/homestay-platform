package com.homestay.platform;

import java.time.Instant;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {
  private final StringRedisTemplate redis;
  public HealthController(StringRedisTemplate redis) { this.redis = redis; }

  @GetMapping("/health")
  public Map<String, Object> health() {
    String redisStatus = "unavailable";
    try { redis.opsForValue().set("homestay:health:last-check", Instant.now().toString()); redisStatus = "ok"; }
    catch (Exception ignored) { }
    return Map.of("status", "ok", "service", "homestay-platform", "redis", redisStatus, "time", Instant.now().toString());
  }
}
