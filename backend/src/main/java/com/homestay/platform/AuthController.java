package com.homestay.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
  private final AuditService audit;
  private final SessionService session;
  public AuthController(JdbcTemplate jdbc, StringRedisTemplate redis, PasswordEncoder encoder, AuditService audit, SessionService session) {
    this.jdbc = jdbc; this.redis = redis; this.encoder = encoder; this.audit = audit; this.session = session;
  }

  public record RegisterRequest(@NotBlank String username, String phone,
      @NotBlank @Size(min = 8, max = 72) @Pattern(regexp = ".*[A-Za-z].*", message = "密码必须包含字母")
      @Pattern(regexp = ".*\\d.*", message = "密码必须包含数字") String password,
      @NotBlank String displayName) {}
  public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

  @PostMapping("/register")
  public Map<String, Object> register(@Valid @RequestBody RegisterRequest body) {
    String username = body.username().trim();
    String displayName = body.displayName().trim();
    String phone = body.phone() == null || body.phone().isBlank() ? null : body.phone().trim();
    if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username = ?", Integer.class, username) > 0)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
    if (phone != null && jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE phone = ?", Integer.class, phone) > 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "手机号已存在");
    }
    jdbc.update("INSERT INTO sys_user(username, phone, password_hash, display_name) VALUES(?,?,?,?)", username, phone, encoder.encode(body.password()), displayName);
    Long userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, username);
    Long customerRoleId = jdbc.queryForObject("SELECT id FROM sys_role WHERE code='CUSTOMER'", Long.class);
    jdbc.update("INSERT IGNORE INTO sys_user_role(user_id,role_id) VALUES(?,?)", userId, customerRoleId);
    audit.record(userId, "REGISTER", "SYS_USER", String.valueOf(userId), "SUCCESS", "注册账号");
    return Map.of("code", "0", "message", "注册成功");
  }

  @PostMapping("/login")
  public Map<String, Object> login(@Valid @RequestBody LoginRequest body) {
    String username = body.username().trim();
    var users = jdbc.queryForList("SELECT id, username, phone, password_hash, display_name, role_code, status FROM sys_user WHERE username = ?", username);
    if (users.isEmpty()
        || !"ACTIVE".equals(String.valueOf(users.getFirst().get("status")))
        || !encoder.matches(body.password(), (String) users.getFirst().get("password_hash"))) {
      audit.record(null, "LOGIN", "SYS_USER", username, "FAILURE", "登录失败");
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
    }
    var user = users.getFirst();
    String token = UUID.randomUUID().toString().replace("-", "");
    redis.opsForValue().set("homestay:session:" + token, String.valueOf(user.get("id")), Duration.ofHours(12));
    jdbc.update("UPDATE sys_user SET last_login_at = ? WHERE id = ?", java.sql.Timestamp.from(Instant.now()), user.get("id"));
    audit.record(((Number) user.get("id")).longValue(), "LOGIN", "SYS_USER", String.valueOf(user.get("id")), "SUCCESS", "登录成功");
    return Map.of("code", "0", "message", "登录成功", "data", Map.of("token", token, "user", Map.of(
        "id", user.get("id"), "username", user.get("username"),
        "phone", user.get("phone") == null ? "" : user.get("phone"),
        "displayName", user.get("display_name"), "role", user.get("role_code"))));
  }

  @GetMapping("/me")
  public Map<String, Object> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
    SessionService.SessionUser user = session.requireUser(authorization);
    Map<String, Object> profile = jdbc.queryForMap(
        "SELECT id, username, phone, display_name displayName, role_code role, status, created_at createdAt, last_login_at lastLoginAt FROM sys_user WHERE id=?",
        user.id());
    return Map.of("code", "0", "message", "success", "data", profile);
  }

  @PostMapping("/logout")
  public Map<String, Object> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
    if (authorization != null && authorization.startsWith("Bearer ")) redis.delete("homestay:session:" + authorization.substring(7));
    audit.record(null, "LOGOUT", "SYS_USER", null, "SUCCESS", "退出登录");
    return Map.of("code", "0", "message", "已退出");
  }
}
