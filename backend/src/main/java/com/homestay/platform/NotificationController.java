package com.homestay.platform;

import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
  private final SessionService session;
  private final NotificationService notifications;

  public NotificationController(SessionService session, NotificationService notifications) {
    this.session = session;
    this.notifications = notifications;
  }

  @GetMapping
  public Map<String, Object> list(@RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    Long userId = session.requireUser(authorization).id();
    return Map.of("code", "0", "message", "success", "data", notifications.list(userId, page, size));
  }

  @GetMapping("/unread-count")
  public Map<String, Object> unreadCount(@RequestHeader(value = "Authorization", required = false) String authorization) {
    Long userId = session.requireUser(authorization).id();
    return Map.of("code", "0", "message", "success", "data", Map.of("unreadCount", notifications.unreadCount(userId)));
  }

  @PatchMapping("/{id}/read")
  public Map<String, Object> markRead(@PathVariable Long id,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    notifications.markRead(session.requireUser(authorization).id(), id);
    return Map.of("code", "0", "message", "消息已读");
  }

  @PostMapping("/read-all")
  public Map<String, Object> markAllRead(@RequestHeader(value = "Authorization", required = false) String authorization) {
    int count = notifications.markAllRead(session.requireUser(authorization).id());
    return Map.of("code", "0", "message", "消息已全部读", "data", Map.of("updated", count));
  }
}
