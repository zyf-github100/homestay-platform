package com.homestay.platform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationService {
  private final JdbcTemplate jdbc;

  public NotificationService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public void create(Long userId, String type, String title, String content, String orderNo) {
    if (userId == null || title == null || title.isBlank() || content == null || content.isBlank()) return;
    jdbc.update("INSERT INTO notification(user_id,notification_type,title,content,related_order_no) VALUES(?,?,?,?,?)",
        userId, type == null || type.isBlank() ? "SYSTEM" : type.trim().toUpperCase(),
        title.trim(), content.trim(), orderNo == null || orderNo.isBlank() ? null : orderNo.trim());
  }

  public Map<String, Object> list(Long userId, int page, int size) {
    int safePage = Math.max(1, page);
    int safeSize = Math.min(Math.max(1, size), 50);
    Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id=?", Integer.class, userId);
    Integer unread = jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id=? AND read_at IS NULL", Integer.class, userId);
    List<Map<String, Object>> data = jdbc.queryForList(
        "SELECT id, notification_type notificationType, title, content, related_order_no relatedOrderNo, read_at readAt, created_at createdAt "
            + "FROM notification WHERE user_id=? ORDER BY id DESC LIMIT " + safeSize + " OFFSET " + ((long) (safePage - 1) * safeSize), userId);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("data", data);
    result.put("page", safePage);
    result.put("size", safeSize);
    result.put("total", total == null ? 0 : total);
    result.put("totalPages", total == null || total == 0 ? 0 : (total + safeSize - 1) / safeSize);
    result.put("unreadCount", unread == null ? 0 : unread);
    return result;
  }

  public int unreadCount(Long userId) {
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id=? AND read_at IS NULL", Integer.class, userId);
    return count == null ? 0 : count;
  }

  @Transactional
  public void markRead(Long userId, Long notificationId) {
    int updated = jdbc.update("UPDATE notification SET read_at=COALESCE(read_at,NOW()) WHERE id=? AND user_id=?",
        notificationId, userId);
    if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "消息不存在");
  }

  @Transactional
  public int markAllRead(Long userId) {
    return jdbc.update("UPDATE notification SET read_at=NOW() WHERE user_id=? AND read_at IS NULL", userId);
  }
}
