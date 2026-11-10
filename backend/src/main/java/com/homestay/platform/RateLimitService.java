package com.homestay.platform;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Small Redis-backed fixed-window limiter for high-risk endpoints. */
@Service
public class RateLimitService {
  private final StringRedisTemplate redis;

  public RateLimitService(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public void check(String bucket, String subject, int limit, Duration window) {
    String safeSubject = subject == null || subject.isBlank() ? "unknown" : subject.trim();
    String key = "homestay:rate:" + bucket + ":" + safeSubject.replaceAll("[^A-Za-z0-9_.:-]", "_");
    Long count = redis.opsForValue().increment(key);
    if (count != null && count == 1L) redis.expire(key, window);
    if (count != null && count > limit) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
          "操作过于频繁，请稍后再试");
    }
  }

  public String clientAddress(jakarta.servlet.http.HttpServletRequest request) {
    String realIp = request.getHeader("X-Real-IP");
    return realIp == null || realIp.isBlank() ? request.getRemoteAddr() : realIp;
  }
}
