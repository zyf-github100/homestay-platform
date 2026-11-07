package com.homestay.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final JdbcTemplate jdbc;
  private final StringRedisTemplate redis;
  private final PasswordEncoder encoder;
  public AuthController(JdbcTemplate jdbc, StringRedisTemplate redis, PasswordEncoder encoder) { this.jdbc = jdbc; this.redis = redis; this.encoder = encoder; }

  public record RegisterRequest(@NotBlank String username, String phone, @NotBlank String password, @NotBlank String displayName) {}
  public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

  @PostMapping("/register")
  public Map<String, Object> register(@Valid @RequestBody RegisterRequest body) {
    if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username = ?", Integer.class, body.username()) > 0)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
    jdbc.update("INSERT INTO sys_user(username, phone, password_hash, display_name) VALUES(?,?,?,?)", body.username(), body.phone(), encoder.encode(body.password()), body.displayName());
    return Map.of("code", "0", "message", "注册成功");
  }

  @PostMapping("/login")
  public Map<String, Object> login(@Valid @RequestBody LoginRequest body) {
    var users = jdbc.queryForList("SELECT id, username, password_hash, display_name, role_code, status FROM sys_user WHERE username = ?", body.username());
    if (users.isEmpty() || !encoder.matches(body.password(), (String) users.getFirst().get("password_hash"))) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
    var user = users.getFirst();
    String token = UUID.randomUUID().toString().replace("-", "");
    redis.opsForValue().set("homestay:session:" + token, String.valueOf(user.get("id")), Duration.ofHours(12));
    jdbc.update("UPDATE sys_user SET last_login_at = ? WHERE id = ?", java.sql.Timestamp.from(Instant.now()), user.get("id"));
    return Map.of("code", "0", "message", "登录成功", "data", Map.of("token", token, "user", Map.of("id", user.get("id"), "username", user.get("username"), "displayName", user.get("display_name"), "role", user.get("role_code"))));
  }

  @PostMapping("/logout")
  public Map<String, Object> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
    if (authorization != null && authorization.startsWith("Bearer ")) redis.delete("homestay:session:" + authorization.substring(7));
    return Map.of("code", "0", "message", "已退出");
  }
}
