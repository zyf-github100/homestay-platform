package com.homestay.platform;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RbacService {
  private final JdbcTemplate jdbc;
  private final AuditService audit;

  public RbacService(JdbcTemplate jdbc, AuditService audit) {
    this.jdbc = jdbc;
    this.audit = audit;
  }

  public List<Map<String, Object>> roles() {
    return jdbc.queryForList("SELECT id, code, name FROM sys_role ORDER BY id");
  }

  public List<Map<String, Object>> permissions() {
    return jdbc.queryForList("SELECT id, code, name FROM sys_permission ORDER BY id");
  }

  @Transactional
  public void assignRole(Long operatorId, Long userId, String roleCode) {
    List<Map<String, Object>> role = jdbc.queryForList("SELECT id, code FROM sys_role WHERE code=?", roleCode);
    if (role.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "角色不存在");
    Integer userCount = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=?", Integer.class, userId);
    if (userCount == null || userCount == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
    Long roleId = ((Number) role.getFirst().get("id")).longValue();
    jdbc.update("UPDATE sys_user SET role_code=? WHERE id=?", roleCode, userId);
    jdbc.update("DELETE FROM sys_user_role WHERE user_id=?", userId);
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)", userId, roleId);
    audit.record(operatorId, "ROLE_ASSIGN", "SYS_USER", String.valueOf(userId), "SUCCESS", "分配角色: " + roleCode);
  }
}
