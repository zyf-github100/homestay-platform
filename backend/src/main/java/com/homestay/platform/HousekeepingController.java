package com.homestay.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/room-tasks")
public class HousekeepingController {
  private final SessionService session;
  private final P0OperationsService operations;

  public HousekeepingController(SessionService session, P0OperationsService operations) {
    this.session = session;
    this.operations = operations;
  }

  public record TaskUpdateRequest(@NotBlank String status, Long assignedTo, String reason) {}

  @GetMapping
  public Map<String, Object> list(@RequestParam(required = false) String status,
      @RequestParam(required = false) String type,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    session.requirePermission(authorization, "housekeeping:manage");
    return Map.of("code", "0", "message", "success", "data", operations.tasks(status, type));
  }

  @PatchMapping("/{id}")
  public Map<String, Object> update(@PathVariable Long id, @Valid @RequestBody TaskUpdateRequest body,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return operations.updateTask(id, session.requirePermission(authorization, "housekeeping:manage").id(),
        body.status(), body.assignedTo(), body.reason());
  }
}
