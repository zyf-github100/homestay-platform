package com.homestay.platform;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/audit-logs")
public class AuditController {
  private final SessionService session;
  private final AuditService audit;

  public AuditController(SessionService session, AuditService audit) {
    this.session = session;
    this.audit = audit;
  }

  @GetMapping
  public Map<String, Object> list(@RequestParam(defaultValue = "50") int limit,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    session.requirePermission(authorization, "audit:view");
    return Map.of("code", "0", "message", "success", "data", audit.list(limit));
  }
}
