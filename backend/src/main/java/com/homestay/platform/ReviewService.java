package com.homestay.platform;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewService {
  private final JdbcTemplate jdbc;
  private final AuditService audit;

  public ReviewService(JdbcTemplate jdbc, AuditService audit) {
    this.jdbc = jdbc;
    this.audit = audit;
  }

  @Transactional
  public Map<String, Object> create(Long userId, String orderNo, int score, String content) {
    if (score < 1 || score > 5) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "评分必须在 1 到 5 分之间");
    List<Map<String, Object>> reservations = jdbc.queryForList(
        "SELECT id, status FROM reservation WHERE order_no=? AND user_id=?", orderNo, userId);
    if (reservations.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
    Map<String, Object> reservation = reservations.getFirst();
    if (!"COMPLETED".equals(String.valueOf(reservation.get("status")))) throw new ResponseStatusException(HttpStatus.CONFLICT, "只有已完成订单可以评价");
    Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM review WHERE reservation_id=? AND user_id=?", Integer.class, reservation.get("id"), userId);
    if (existing != null && existing > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "该订单已经评价过");
    jdbc.update("INSERT INTO review(reservation_id,user_id,score,content) VALUES(?,?,?,?)", reservation.get("id"), userId, score, content);
    Long id = jdbc.queryForObject("SELECT id FROM review WHERE reservation_id=? AND user_id=?", Long.class, reservation.get("id"), userId);
    audit.record(userId, "REVIEW_CREATE", "REVIEW", String.valueOf(id), "SUCCESS", "提交评价");
    return Map.of("id", id, "orderNo", orderNo, "score", score, "content", content == null ? "" : content, "status", "PUBLISHED");
  }

  public List<Map<String, Object>> published() {
    return jdbc.queryForList("SELECT r.id, r.score, r.content, r.reply, r.created_at createdAt, u.display_name displayName FROM review r JOIN sys_user u ON u.id=r.user_id WHERE r.status='PUBLISHED' ORDER BY r.id DESC LIMIT 100");
  }

  public List<Map<String, Object>> all() {
    return jdbc.queryForList("SELECT r.id, r.reservation_id reservationId, r.user_id userId, u.display_name displayName, r.score, r.content, r.status, r.reply, r.created_at createdAt, r.replied_at repliedAt FROM review r JOIN sys_user u ON u.id=r.user_id ORDER BY r.id DESC LIMIT 200");
  }

  @Transactional
  public void updateStatus(Long operatorId, Long id, String status) {
    if (!List.of("PUBLISHED", "HIDDEN").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "评价状态不合法");
    int updated = jdbc.update("UPDATE review SET status=? WHERE id=?", status, id);
    if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "评价不存在");
    audit.record(operatorId, "REVIEW_MODERATE", "REVIEW", String.valueOf(id), "SUCCESS", "评价状态: " + status);
  }

  @Transactional
  public void reply(Long operatorId, Long id, String reply) {
    if (reply == null || reply.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "回复内容不能为空");
    int updated = jdbc.update("UPDATE review SET reply=?, replied_by=?, replied_at=NOW() WHERE id=?", reply, operatorId, id);
    if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "评价不存在");
    audit.record(operatorId, "REVIEW_REPLY", "REVIEW", String.valueOf(id), "SUCCESS", "回复评价");
  }
}
