package com.homestay.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/rbac")
public class RbacController {
  private final SessionService session;
  private final RbacService rbac;

  public RbacController(SessionService session, RbacService rbac) {
    this.session = session;
    this.rbac = rbac;
  }

  public record RoleRequest(@NotBlank String roleCode) {}

  @GetMapping("/roles")
  public Map<String, Object> roles(@RequestHeader(value = "Authorization", required = false) String authorization) {
    session.requirePermission(authorization, "system:user");
    return Map.of("code", "0", "message", "success", "data", rbac.roles());
  }

  @GetMapping("/permissions")
  public Map<String, Object> permissions(@RequestHeader(value = "Authorization", required = false) String authorization) {
    session.requirePermission(authorization, "system:user");
    return Map.of("code", "0", "message", "success", "data", rbac.permissions());
  }

  @GetMapping("/users")
  public Map<String, Object> users(@RequestHeader(value = "Authorization", required = false) String authorization) {
    session.requirePermission(authorization, "system:user");
    return Map.of("code", "0", "message", "success", "data", rbac.users());
  }

  @PutMapping("/users/{userId}/role")
  public Map<String, Object> assignRole(@PathVariable Long userId, @Valid @RequestBody RoleRequest body,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    Long operatorId = session.requirePermission(authorization, "system:user").id();
    rbac.assignRole(operatorId, userId, body.roleCode().trim().toUpperCase());
    return Map.of("code", "0", "message", "角色已更新");
  }
}
