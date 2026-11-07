package com.homestay.platform;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/statistics")
public class StatisticsController {
  private final SessionService session;
  private final StatisticsService statistics;

  public StatisticsController(SessionService session, StatisticsService statistics) {
    this.session = session;
    this.statistics = statistics;
  }

  @GetMapping("/overview")
  public Map<String, Object> overview(@RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    session.requirePermission(authorization, "report:view");
    return Map.of("code", "0", "message", "success", "data", statistics.overview(from, to));
  }

  @GetMapping("/trends")
  public Map<String, Object> trends(@RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    session.requirePermission(authorization, "report:view");
    return Map.of("code", "0", "message", "success", "data", statistics.trends(from, to));
  }
}
