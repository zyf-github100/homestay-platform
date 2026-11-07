package com.homestay.platform;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SessionService {
  private static final String SESSION_PREFIX = "homestay:session:";
  private final JdbcTemplate jdbc;
  private final StringRedisTemplate redis;
  private final PermissionService permissions;

  public SessionService(JdbcTemplate jdbc, StringRedisTemplate redis) {
    this(jdbc, redis, null);
  }

  @Autowired
  public SessionService(JdbcTemplate jdbc, StringRedisTemplate redis, PermissionService permissions) {
    this.jdbc = jdbc;
    this.redis = redis;
    this.permissions = permissions;
  }

  public SessionUser requireUser(String authorization) {
    String token = tokenFrom(authorization);
    String userId = redis.opsForValue().get(SESSION_PREFIX + token);
    if (userId == null) throw unauthorized();

    List<Map<String, Object>> users = jdbc.queryForList(
        "SELECT id, username, role_code, status FROM sys_user WHERE id = ?", userId);
    if (users.isEmpty()) throw unauthorized();
    Map<String, Object> user = users.getFirst();
    if (!"ACTIVE".equals(String.valueOf(user.get("status")))) throw unauthorized();
    return new SessionUser(
        ((Number) user.get("id")).longValue(),
        String.valueOf(user.get("username")),
        String.valueOf(user.get("role_code")));
  }

  public SessionUser requireStaff(String authorization) {
    SessionUser user = requireUser(authorization);
    if (!user.isStaff()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号没有管理端权限");
    }
    return user;
  }

  public SessionUser requirePermission(String authorization, String permission) {
    SessionUser user = requireUser(authorization);
    if (permissions == null || (!permissions.has(user.id(), permission) && !permissions.has(user.id(), "*"))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号没有该操作权限");
    }
    return user;
  }

  private String tokenFrom(String authorization) {
    if (authorization == null || !authorization.startsWith("Bearer ")
        || authorization.length() <= 7) throw unauthorized();
    return authorization.substring(7).trim();
  }

  private ResponseStatusException unauthorized() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
  }

  public record SessionUser(Long id, String username, String role) {
    public boolean isStaff() {
      return switch (role) {
        case "FRONT_DESK", "OPERATOR", "OPERATION_ADMIN", "ADMIN", "SYSTEM_ADMIN" -> true;
        default -> false;
      };
    }
  }
}
