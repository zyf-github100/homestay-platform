package com.homestay.platform;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** P0 operational workflows: settlement corrections, no-shows, manual inventory, and room tasks. */
@Service
public class P0OperationsService {
  private final JdbcTemplate jdbc;
  private final StringRedisTemplate redis;
  private final AuditService audit;

  public P0OperationsService(JdbcTemplate jdbc, StringRedisTemplate redis, AuditService audit) {
    this.jdbc = jdbc;
    this.redis = redis;
    this.audit = audit;
  }

  @Transactional
  public Map<String, Object> adjustSettlement(String orderNo, Long operatorId, String type,
      BigDecimal amount, String reason, String paymentNo) {
    Map<String, Object> reservation = reservationForUpdate(orderNo);
    String normalized = type == null ? "" : type.trim().toUpperCase();
    if (!List.of("REFUND", "REVERSAL", "DISCOUNT").contains(normalized)) {
      throw badRequest("调整类型只能是 REFUND、REVERSAL 或 DISCOUNT");
    }
    if (amount == null || amount.signum() <= 0) throw badRequest("调整金额必须大于 0");
    if (reason == null || reason.isBlank()) throw badRequest("调整原因不能为空");
    BigDecimal safeAmount = amount.setScale(2);
    Long reservationId = longValue(reservation.get("id"));
    if ("DISCOUNT".equals(normalized)) {
      BigDecimal dueBeforeDiscount = roomAndCharges(reservationId);
      BigDecimal discounts = adjustmentSum(reservationId, "DISCOUNT");
      if (safeAmount.compareTo(dueBeforeDiscount.subtract(discounts).max(BigDecimal.ZERO)) > 0) {
        throw conflict("优惠金额不能超过当前应收");
      }
    } else {
      BigDecimal available = netCollected(reservationId);
      if (safeAmount.compareTo(available) > 0) throw conflict("退款或冲正金额不能超过已收金额");
    }
    if (paymentNo != null && !paymentNo.isBlank()) {
      Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE payment_no=? AND reservation_id=?", Integer.class, paymentNo.trim(), reservationId);
      if (exists == null || exists == 0) throw notFound("关联支付记录不存在");
    }
    jdbc.update("INSERT INTO payment_adjustment(reservation_id,payment_no,type,amount,reason,operator_id) VALUES(?,?,?,?,?,?)",
        reservationId, blankToNull(paymentNo), normalized, safeAmount, reason.trim(), operatorId);
    refreshPaymentStatus(reservation);
    audit.record(operatorId, "SETTLEMENT_ADJUST", "RESERVATION", orderNo, "SUCCESS", normalized + " " + safeAmount + ": " + reason.trim());
    return Map.of("code", "0", "message", "结算调整已登记", "data", Map.of(
        "orderNo", orderNo, "type", normalized, "amount", safeAmount,
        "collected", netCollected(reservationId), "outstanding", outstanding(reservation)));
  }

  @Transactional
  public Map<String, Object> voidCharge(String orderNo, Long operatorId, Long chargeId, String reason) {
    Map<String, Object> reservation = reservationForUpdate(orderNo);
    if (reason == null || reason.isBlank()) throw badRequest("冲正原因不能为空");
    List<Map<String, Object>> charges = jdbc.queryForList(
        "SELECT id, amount, status FROM folio_charge WHERE id=? AND reservation_id=? FOR UPDATE", chargeId, reservation.get("id"));
    if (charges.isEmpty()) throw notFound("费用记录不存在");
    if (!"ACTIVE".equals(String.valueOf(charges.getFirst().get("status")))) throw conflict("费用已经被冲正");
    jdbc.update("UPDATE folio_charge SET status='VOID', voided_by=?, voided_at=NOW(), void_reason=? WHERE id=?",
        operatorId, reason.trim(), chargeId);
    audit.record(operatorId, "FOLIO_CHARGE_VOID", "RESERVATION", orderNo, "SUCCESS", reason.trim());
    return Map.of("code", "0", "message", "附加费用已冲正", "data", Map.of("orderNo", orderNo, "chargeId", chargeId));
  }

  @Transactional
  public Map<String, Object> markNoShow(String orderNo, Long operatorId, String reason) {
    Map<String, Object> reservation = reservationForUpdate(orderNo);
    String status = String.valueOf(reservation.get("status"));
    if (!List.of("CONFIRMED", "ASSIGNED").contains(status)) throw conflict("当前订单不能标记未入住");
    LocalDate checkIn = dateValue(reservation.get("check_in"));
    if (checkIn != null && LocalDate.now(ZoneOffset.UTC).isBefore(checkIn)) throw conflict("入住日期尚未到达");
    String detail = reason == null || reason.isBlank() ? "超过入住时间未到店" : reason.trim();
    jdbc.update("UPDATE reservation SET status='NO_SHOW', no_show_at=NOW(), no_show_by=?, cancellation_reason=? WHERE id=?",
        operatorId, detail, reservation.get("id"));
    releaseInventory(orderNo);
    event(reservation, "NO_SHOW", status, "NO_SHOW", detail, operatorId);
    audit.record(operatorId, "RESERVATION_NO_SHOW", "RESERVATION", orderNo, "SUCCESS", detail);
    return Map.of("code", "0", "message", "订单已标记未入住", "data", Map.of("orderNo", orderNo, "status", "NO_SHOW"));
  }

  @Transactional
  public Map<String, Object> updatePreArrival(String orderNo, Long operatorId, String contactName,
      String contactPhone, String specialRequest) {
    Map<String, Object> reservation = reservationForUpdate(orderNo);
    String status = String.valueOf(reservation.get("status"));
    if (!List.of("PENDING_PAYMENT", "CONFIRMED").contains(status)) throw conflict("当前订单不能修改入住信息");
    if (contactName == null || contactName.isBlank() || contactPhone == null || contactPhone.isBlank()) {
      throw badRequest("联系人和联系电话不能为空");
    }
    jdbc.update("UPDATE reservation SET contact_name=?, contact_phone=?, special_request=? WHERE id=?",
        contactName.trim(), contactPhone.trim(), specialRequest == null ? null : specialRequest.trim(), reservation.get("id"));
    event(reservation, "PRE_ARRIVAL_UPDATE", status, status, "更新联系人和入住备注", operatorId);
    audit.record(operatorId, "RESERVATION_PRE_ARRIVAL_UPDATE", "RESERVATION", orderNo, "SUCCESS", "更新入住前信息");
    return Map.of("code", "0", "message", "入住信息已更新", "data", Map.of("orderNo", orderNo,
        "contactName", contactName.trim(), "contactPhone", contactPhone.trim(), "specialRequest", specialRequest == null ? "" : specialRequest.trim()));
  }

  @Transactional
  public Map<String, Object> blockInventory(Long roomId, LocalDate stayDate, Long operatorId, String reason) {
    if (stayDate == null || stayDate.isBefore(LocalDate.now(ZoneOffset.UTC))) throw badRequest("封房日期不能早于今天");
    if (reason == null || reason.isBlank()) throw badRequest("封房原因不能为空");
    Integer roomCount = jdbc.queryForObject("SELECT COUNT(*) FROM room WHERE id=?", Integer.class, roomId);
    if (roomCount == null || roomCount == 0) throw notFound("房间不存在");
    Integer occupied = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_lock WHERE room_id=? AND stay_date=?", Integer.class, roomId, stayDate);
    if (occupied != null && occupied > 0) throw conflict("该日期已有订单占用，不能封房");
    jdbc.update("INSERT INTO inventory_override(room_id,stay_date,status,reason,operator_id) VALUES(?,?, 'BLOCKED',?,?) "
        + "ON DUPLICATE KEY UPDATE status='BLOCKED', reason=VALUES(reason), operator_id=VALUES(operator_id)", roomId, stayDate, reason.trim(), operatorId);
    audit.record(operatorId, "INVENTORY_BLOCK", "ROOM", String.valueOf(roomId), "SUCCESS", stayDate + ": " + reason.trim());
    return Map.of("code", "0", "message", "房晚已封锁", "data", Map.of("roomId", roomId, "stayDate", stayDate.toString(), "status", "BLOCKED"));
  }

  @Transactional
  public Map<String, Object> unblockInventory(Long roomId, LocalDate stayDate, Long operatorId) {
    int updated = jdbc.update("DELETE FROM inventory_override WHERE room_id=? AND stay_date=?", roomId, stayDate);
    if (updated == 0) throw notFound("没有找到该日期的封房记录");
    audit.record(operatorId, "INVENTORY_UNBLOCK", "ROOM", String.valueOf(roomId), "SUCCESS", stayDate.toString());
    return Map.of("code", "0", "message", "房晚封锁已解除", "data", Map.of("roomId", roomId, "stayDate", stayDate.toString(), "status", "AVAILABLE"));
  }

  public List<Map<String, Object>> tasks(String status, String type) {
    StringBuilder sql = new StringBuilder("SELECT t.id, t.room_id roomId, r.room_no roomNo, rt.name roomTypeName, t.reservation_id reservationId, t.task_type taskType, t.status, t.priority, t.reason, t.assigned_to assignedTo, t.opened_at openedAt, t.completed_at completedAt FROM room_task t JOIN room r ON r.id=t.room_id JOIN room_type rt ON rt.id=r.room_type_id WHERE 1=1");
    List<Object> args = new java.util.ArrayList<>();
    if (status != null && !status.isBlank()) { sql.append(" AND t.status=?"); args.add(status.trim().toUpperCase()); }
    if (type != null && !type.isBlank()) { sql.append(" AND t.task_type=?"); args.add(type.trim().toUpperCase()); }
    sql.append(" ORDER BY FIELD(t.status,'OPEN','IN_PROGRESS','DONE','CANCELLED'), t.priority DESC, t.id DESC");
    return jdbc.queryForList(sql.toString(), args.toArray());
  }

  @Transactional
  public Map<String, Object> updateTask(Long taskId, Long operatorId, String status, Long assignedTo, String reason) {
    String normalized = status == null ? "" : status.trim().toUpperCase();
    if (!List.of("OPEN", "IN_PROGRESS", "DONE", "CANCELLED").contains(normalized)) throw badRequest("任务状态不合法");
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, room_id roomId, task_type taskType, status FROM room_task WHERE id=? FOR UPDATE", taskId);
    if (rows.isEmpty()) throw notFound("任务不存在");
    Map<String, Object> task = rows.getFirst();
    jdbc.update("UPDATE room_task SET status=?, assigned_to=COALESCE(?,assigned_to), reason=COALESCE(NULLIF(?,''),reason), completed_by=?, completed_at=? WHERE id=?",
        normalized, assignedTo, reason == null ? "" : reason.trim(), "DONE".equals(normalized) ? operatorId : null,
        "DONE".equals(normalized) ? java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()) : null, taskId);
    Long roomId = longValue(task.get("roomId"));
    if ("DONE".equals(normalized)) jdbc.update("UPDATE room SET status='AVAILABLE' WHERE id=? AND status IN ('DIRTY','MAINTENANCE')", roomId);
    else if ("MAINTENANCE".equals(String.valueOf(task.get("taskType"))) && !"CANCELLED".equals(normalized)) jdbc.update("UPDATE room SET status='MAINTENANCE' WHERE id=? AND status<>'OCCUPIED'", roomId);
    audit.record(operatorId, "ROOM_TASK_UPDATE", "ROOM_TASK", String.valueOf(taskId), "SUCCESS", normalized);
    return Map.of("code", "0", "message", "房务任务已更新", "data", Map.of("taskId", taskId, "status", normalized));
  }

  @Transactional
  public void openTask(Long roomId, Long reservationId, String type, String reason, Long operatorId) {
    Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM room_task WHERE room_id=? AND task_type=? AND status IN ('OPEN','IN_PROGRESS')", Integer.class, roomId, type);
    if (active != null && active > 0) return;
    jdbc.update("INSERT INTO room_task(room_id,reservation_id,task_type,status,reason,opened_by) VALUES(?,?,?,'OPEN',?,?)",
        roomId, reservationId, type, reason, operatorId);
  }

  public List<Map<String, Object>> overrides(LocalDate from, LocalDate to) {
    return jdbc.queryForList("SELECT o.id, o.room_id roomId, r.room_no roomNo, o.stay_date stayDate, o.status, o.reason, o.operator_id operatorId, o.created_at createdAt FROM inventory_override o JOIN room r ON r.id=o.room_id WHERE o.stay_date BETWEEN ? AND ? ORDER BY o.stay_date, r.room_no", from, to);
  }

  private Map<String, Object> reservationForUpdate(String orderNo) {
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, order_no, check_in, check_out, total_amount, status, payment_status FROM reservation WHERE order_no=? FOR UPDATE", orderNo);
    if (rows.isEmpty()) throw notFound("订单不存在");
    return rows.getFirst();
  }

  private void refreshPaymentStatus(Map<String, Object> reservation) {
    Long id = longValue(reservation.get("id"));
    BigDecimal due = outstanding(reservation).add(netCollected(id));
    BigDecimal collected = netCollected(id);
    String next = collected.signum() == 0 ? "UNPAID" : collected.compareTo(due) >= 0 ? "PAID" : "PARTIAL";
    jdbc.update("UPDATE reservation SET payment_status=? WHERE id=? AND status NOT IN ('CANCELLED','NO_SHOW','COMPLETED')", next, id);
  }

  private BigDecimal outstanding(Map<String, Object> reservation) {
    Long id = longValue(reservation.get("id"));
    BigDecimal due = roomAndCharges(id).subtract(adjustmentSum(id, "DISCOUNT"));
    return due.subtract(netCollected(id)).max(BigDecimal.ZERO);
  }

  private BigDecimal roomAndCharges(Long reservationId) {
    BigDecimal room = jdbc.queryForObject("SELECT COALESCE(total_amount,0) FROM reservation WHERE id=?", BigDecimal.class, reservationId);
    BigDecimal charges = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM folio_charge WHERE reservation_id=? AND status='ACTIVE'", BigDecimal.class, reservationId);
    return (room == null ? BigDecimal.ZERO : room).add(charges == null ? BigDecimal.ZERO : charges);
  }

  private BigDecimal netCollected(Long reservationId) {
    BigDecimal payments = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM payment WHERE reservation_id=? AND status='SUCCESS'", BigDecimal.class, reservationId);
    BigDecimal adjustments = jdbc.queryForObject("SELECT COALESCE(SUM(pa.amount),0) FROM payment_adjustment pa WHERE pa.reservation_id=? AND pa.type IN ('REFUND','REVERSAL')", BigDecimal.class, reservationId);
    return (payments == null ? BigDecimal.ZERO : payments).subtract(adjustments == null ? BigDecimal.ZERO : adjustments).max(BigDecimal.ZERO);
  }

  private BigDecimal adjustmentSum(Long reservationId, String type) {
    BigDecimal value = jdbc.queryForObject("SELECT COALESCE(SUM(pa.amount),0) FROM payment_adjustment pa WHERE pa.reservation_id=? AND pa.type=?", BigDecimal.class, reservationId, type);
    return value == null ? BigDecimal.ZERO : value;
  }

  private void event(Map<String, Object> reservation, String type, String from, String to, String reason, Long operatorId) {
    jdbc.update("INSERT INTO reservation_event(reservation_id,event_type,from_status,to_status,reason,operator_id) VALUES(?,?,?,?,?,?)",
        reservation.get("id"), type, from, to, reason, operatorId);
  }

  private void releaseInventory(String orderNo) {
    List<Map<String, Object>> locks = jdbc.queryForList("SELECT room_id, stay_date FROM inventory_lock WHERE order_no=?", orderNo);
    jdbc.update("DELETE FROM inventory_lock WHERE order_no=?", orderNo);
    for (Map<String, Object> lock : locks) redis.delete("homestay:inventory-lock:" + lock.get("room_id") + ":" + lock.get("stay_date"));
  }

  private LocalDate dateValue(Object value) {
    if (value == null) return null;
    if (value instanceof java.sql.Date date) return date.toLocalDate();
    if (value instanceof LocalDate date) return date;
    return LocalDate.parse(String.valueOf(value));
  }

  private Long longValue(Object value) { return value instanceof Number number ? number.longValue() : Long.valueOf(String.valueOf(value)); }
  private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
  private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
  private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
  private ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
}
