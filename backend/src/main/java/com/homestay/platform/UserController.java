package com.homestay.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {
  private final JdbcTemplate jdbc;
  private final SessionService session;
  private final AuditService audit;

  public UserController(JdbcTemplate jdbc, SessionService session, AuditService audit) {
    this.jdbc = jdbc;
    this.session = session;
    this.audit = audit;
  }

  public record UpdateRequest(@NotBlank String displayName, String phone) {}

  @PutMapping("/me")
  public Map<String, Object> update(@Valid @RequestBody UpdateRequest body,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    SessionService.SessionUser user = session.requireUser(authorization);
    if (body.phone() != null && !body.phone().isBlank()) {
      Integer duplicate = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE phone=? AND id<>?",
          Integer.class, body.phone().trim(), user.id());
      if (duplicate != null && duplicate > 0) {
        throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.CONFLICT, "手机号已存在");
      }
    }
    jdbc.update("UPDATE sys_user SET display_name=?, phone=? WHERE id=?",
        body.displayName().trim(), body.phone() == null || body.phone().isBlank() ? null : body.phone().trim(), user.id());
    audit.record(user.id(), "USER_PROFILE_UPDATE", "SYS_USER", String.valueOf(user.id()), "SUCCESS", "修改个人资料");
    return Map.of("code", "0", "message", "个人资料已更新", "data", Map.of(
        "id", user.id(), "username", user.username(), "displayName", body.displayName().trim(),
        "phone", body.phone() == null ? "" : body.phone().trim(), "role", user.role()));
  }
}
