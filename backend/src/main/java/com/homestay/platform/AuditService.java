package com.homestay.platform;

import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class AuditService {
  private final JdbcTemplate jdbc;

  public AuditService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void record(Long operatorId, String action, String resourceType, String resourceId,
      String result, String detail) {
    jdbc.update("INSERT INTO audit_log(operator_id,action,resource_type,resource_id,result,detail,source_ip) VALUES(?,?,?,?,?,?,?)",
        operatorId, action, resourceType, resourceId, result, detail, sourceIp());
  }

  private String sourceIp() {
    if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return "-";
    HttpServletRequest request = attributes.getRequest();
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",", 2)[0].trim();
    String realIp = request.getHeader("X-Real-IP");
    return realIp == null || realIp.isBlank() ? request.getRemoteAddr() : realIp;
  }

  public List<Map<String, Object>> list(int limit) {
    int safeLimit = Math.max(1, Math.min(limit, 200));
    return jdbc.queryForList("SELECT id, operator_id operatorId, action, resource_type resourceType, resource_id resourceId, result, detail, source_ip sourceIp, created_at createdAt FROM audit_log ORDER BY id DESC LIMIT " + safeLimit);
  }
}
