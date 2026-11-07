package com.homestay.platform;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PermissionService {
  private final JdbcTemplate jdbc;

  public PermissionService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Set<String> permissions(Long userId) {
    List<String> values = jdbc.queryForList(
        "SELECT DISTINCT p.code FROM sys_user_role ur JOIN sys_role_permission rp ON rp.role_id=ur.role_id JOIN sys_permission p ON p.id=rp.permission_id WHERE ur.user_id=?",
        String.class, userId);
    if (!values.isEmpty()) return new HashSet<>(values);
    List<String> legacyRoles = jdbc.queryForList("SELECT role_code FROM sys_user WHERE id=?", String.class, userId);
    if (legacyRoles.isEmpty()) return Set.of();
    String role = legacyRoles.getFirst();
    return legacyPermissions(role);
  }

  public boolean has(Long userId, String permission) {
    return permissions(userId).contains(permission);
  }

  private Set<String> legacyPermissions(String role) {
    if ("SYSTEM_ADMIN".equals(role)) return Set.of("*");
    if ("OPERATION_ADMIN".equals(role)) return Set.of("room-type:view", "room-type:manage", "room:view", "room:manage", "room:operate", "property:view", "property:manage", "inventory:view", "reservation:manage", "reservation:assign-room", "reservation:check-in", "reservation:change-room", "reservation:check-out", "payment:collect", "guest:view", "guest:view-sensitive", "review:moderate", "report:view", "audit:view");
    if ("FRONT_DESK".equals(role) || "OPERATOR".equals(role) || "ADMIN".equals(role)) return Set.of("room-type:view", "room:view", "room:operate", "property:view", "inventory:view", "reservation:manage", "reservation:assign-room", "reservation:check-in", "reservation:change-room", "reservation:check-out", "payment:collect", "guest:view", "guest:view-sensitive");
    return Set.of("reservation:create", "reservation:view:self", "reservation:cancel:self", "payment:simulate", "guest:self", "review:create");
  }
}
