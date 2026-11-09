package com.homestay.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class GuestController {
  private final SessionService session;
  private final GuestService guests;
  private final RateLimitService rateLimits;

  public GuestController(SessionService session, GuestService guests, RateLimitService rateLimits) {
    this.session = session;
    this.guests = guests;
    this.rateLimits = rateLimits;
  }

  public record GuestRequest(@NotBlank String name, String gender, String phone, String idType, @NotBlank String idNo, String remark) {}
  public record GuestUpdateRequest(@NotBlank String name, String gender, String phone, String idType, String idNo, String remark) {}

  @GetMapping("/guests")
  public Map<String, Object> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
    return Map.of("code", "0", "message", "success", "data", guests.listMine(session.requirePermission(authorization, "guest:self").id()));
  }

  @PostMapping("/guests")
  public Map<String, Object> create(@Valid @RequestBody GuestRequest body,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    Long userId = session.requirePermission(authorization, "guest:self").id();
    return Map.of("code", "0", "message", "创建成功", "data", guests.create(userId, body.name(), body.gender(), body.phone(), body.idType(), body.idNo(), body.remark()));
  }

  @PutMapping("/guests/{id}")
  public Map<String, Object> update(@PathVariable Long id, @Valid @RequestBody GuestUpdateRequest body,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    Long userId = session.requirePermission(authorization, "guest:self").id();
    return Map.of("code", "0", "message", "更新成功", "data", guests.update(userId, id, body.name(), body.gender(), body.phone(), body.idType(), body.idNo(), body.remark()));
  }

  @DeleteMapping("/guests/{id}")
  public Map<String, Object> delete(@PathVariable Long id,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    Long userId = session.requirePermission(authorization, "guest:self").id();
    guests.delete(userId, id);
    return Map.of("code", "0", "message", "删除成功");
  }

  @GetMapping("/admin/guests")
  public Map<String, Object> listAll(@RequestHeader(value = "Authorization", required = false) String authorization) {
    session.requirePermission(authorization, "guest:view");
    return Map.of("code", "0", "message", "success", "data", guests.listAll());
  }

  @GetMapping("/admin/guests/{id}/sensitive")
  public Map<String, Object> sensitive(@PathVariable Long id,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    Long operatorId = session.requirePermission(authorization, "guest:view-sensitive").id();
    rateLimits.check("guest-sensitive", String.valueOf(operatorId), 10, Duration.ofMinutes(1));
    return Map.of("code", "0", "message", "success", "data", guests.sensitive(operatorId, id));
  }
}
