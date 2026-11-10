package com.homestay.platform;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class StayService {
  private final JdbcTemplate jdbc;
  private final AuditService audit;
  private final StringRedisTemplate redis;
  private final P0OperationsService p0;
  private final NotificationService notifications;

  public StayService(JdbcTemplate jdbc) {
    this(jdbc, null, null, null, null);
  }

  public StayService(JdbcTemplate jdbc, AuditService audit) {
    this(jdbc, audit, null, null, null);
  }

  public StayService(JdbcTemplate jdbc, AuditService audit, StringRedisTemplate redis) {
    this(jdbc, audit, redis, null, null);
  }

  public StayService(JdbcTemplate jdbc, AuditService audit, StringRedisTemplate redis, P0OperationsService p0) {
    this(jdbc, audit, redis, p0, null);
  }

  @Autowired
  public StayService(JdbcTemplate jdbc, AuditService audit, StringRedisTemplate redis, P0OperationsService p0, NotificationService notifications) {
    this.jdbc = jdbc;
    this.audit = audit;
    this.redis = redis;
    this.p0 = p0;
    this.notifications = notifications;
  }

  @Transactional
  public Map<String, Object> assignRooms(String orderNo, Long operatorId, List<Long> roomIds) {
    Map<String, Object> reservation = reservationForUpdate(orderNo);
    requireStatus(reservation, "CONFIRMED");
    if (roomIds == null || roomIds.isEmpty()) throw badRequest("至少选择一个实体房间");
    int roomCount = number(reservation.get("room_count"));
    if (roomIds.size() != roomCount) throw badRequest("分配房间数量必须与订单房间数一致");
    if (new HashSet<>(roomIds).size() != roomIds.size()) throw badRequest("不能重复分配同一房间");
    Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM reservation_room WHERE reservation_id=?", Integer.class, reservation.get("id"));
    if (existing != null && existing > 0) throw conflict("该订单已经分配过房间");

    Map<Long, Integer> required = new HashMap<>();
    Map<Long, Long> itemByType = new HashMap<>();
    List<Map<String, Object>> items = jdbc.queryForList(
        "SELECT id, room_type_id, quantity FROM reservation_item WHERE reservation_id=?",
        reservation.get("id"));
    for (Map<String, Object> item : items) {
      Long typeId = longValue(item.get("room_type_id"));
      required.put(typeId, number(item.get("quantity")));
      itemByType.put(typeId, longValue(item.get("id")));
    }

    List<Long> assigned = new ArrayList<>();
    for (Long roomId : roomIds) {
      Map<String, Object> room = roomForUpdate(roomId);
      String status = String.valueOf(room.get("status"));
      if (!Set.of("AVAILABLE", "RESERVED").contains(status)) throw conflict("房间当前不可分配");
      Integer conflicts = jdbc.queryForObject(
          "SELECT COUNT(*) FROM inventory_lock WHERE room_id=? AND order_no<>? AND stay_date>=? AND stay_date<?",
          Integer.class, roomId, orderNo, reservation.get("check_in"), reservation.get("check_out"));
      if (conflicts != null && conflicts > 0) throw conflict("房间在订单日期内已被占用");
      Long typeId = longValue(room.get("room_type_id"));
      int left = required.getOrDefault(typeId, 0);
      if (left <= 0) throw badRequest("分配房间与订单房型不匹配");
      required.put(typeId, left - 1);
      jdbc.update("INSERT INTO reservation_room(reservation_id,reservation_item_id,room_id,status,assigned_at,assigned_by) VALUES(?,?,?,'ASSIGNED',NOW(),?)",
          reservation.get("id"), itemByType.get(typeId), roomId, operatorId);
      assigned.add(roomId);
    }
    if (required.values().stream().anyMatch(value -> value != 0)) throw badRequest("订单房型数量与分配结果不一致");
    event(reservation, "ROOM_ASSIGN", String.valueOf(reservation.get("status")), String.valueOf(reservation.get("status")), "分配房间", operatorId);
    recordAudit(operatorId, "ROOM_ASSIGN", "RESERVATION", orderNo, "SUCCESS", "分配房间");
    return Map.of("code", "0", "message", "分房成功", "data", Map.of("orderNo", orderNo, "roomIds", assigned, "status", "ASSIGNED"));
  }

  @Transactional
  public Map<String, Object> checkIn(String orderNo, Long operatorId, List<Long> reservationRoomIds) {
    Map<String, Object> reservation = reservationForUpdate(orderNo);
    requireStatus(reservation, "CONFIRMED", "PARTIALLY_CHECKED_IN");
    if (!"PAID".equals(String.valueOf(reservation.get("payment_status")))) throw conflict("订单尚未完成收款");
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    LocalDate checkIn = dateValue(reservation.get("check_in"));
    LocalDate checkOut = dateValue(reservation.get("check_out"));
    if (today.isBefore(checkIn) || !today.isBefore(checkOut)) throw conflict("当前日期不在入住日期范围内");

    List<Map<String, Object>> assigned = jdbc.queryForList(
        "SELECT id, room_id FROM reservation_room WHERE reservation_id=? AND status='ASSIGNED' FOR UPDATE",
        reservation.get("id"));
    List<Map<String, Object>> selected = selectRooms(assigned, reservationRoomIds);
    if (selected.isEmpty()) throw conflict("没有可办理入住的已分配房间");
    for (Map<String, Object> room : selected) {
      jdbc.update("UPDATE reservation_room SET status='CHECKED_IN', check_in_at=NOW(), check_in_by=? WHERE id=?",
          operatorId, room.get("id"));
      jdbc.update("UPDATE room SET status='OCCUPIED' WHERE id=?", room.get("room_id"));
    }
    int checkedIn = jdbc.queryForObject("SELECT COUNT(*) FROM reservation_room WHERE reservation_id=? AND status='CHECKED_IN'", Integer.class, reservation.get("id"));
    String nextStatus = checkedIn >= number(reservation.get("room_count")) ? "CHECKED_IN" : "PARTIALLY_CHECKED_IN";
    jdbc.update("UPDATE reservation SET status=? WHERE id=?", nextStatus, reservation.get("id"));
    event(reservation, "CHECK_IN", String.valueOf(reservation.get("status")), nextStatus, "办理入住", operatorId);
    recordAudit(operatorId, "CHECK_IN", "RESERVATION", orderNo, "SUCCESS", "办理入住");
    if (notifications != null) notifications.create(longValue(reservation.get("user_id")), "CHECK_IN", "入住已办理",
        "订单 " + orderNo + " 已完成入住登记。", orderNo);
    return Map.of("code", "0", "message", "入住办理成功", "data", Map.of("orderNo", orderNo, "status", nextStatus, "checkedInRoomCount", checkedIn));
  }

  @Transactional
  public Map<String, Object> changeRoom(String orderNo, Long operatorId, Long reservationRoomId,
      Long newRoomId, String reason, BigDecimal feeDifference) {
    Map<String, Object> reservation = reservationForUpdate(orderNo);
    requireStatus(reservation, "CHECKED_IN", "PARTIALLY_CHECKED_IN");
    if (reason == null || reason.isBlank()) throw badRequest("换房原因不能为空");
    Map<String, Object> current = jdbc.queryForMap(
        "SELECT rr.id, rr.reservation_item_id, rr.room_id, rr.status, r.room_type_id FROM reservation_room rr JOIN room r ON r.id=rr.room_id WHERE rr.id=? AND rr.reservation_id=? FOR UPDATE",
        reservationRoomId, reservation.get("id"));
    if (!"CHECKED_IN".equals(String.valueOf(current.get("status")))) throw conflict("只有入住中的房间可以换房");
    Map<String, Object> replacement = roomForUpdate(newRoomId);
    if (!longValue(current.get("room_type_id")).equals(longValue(replacement.get("room_type_id")))) throw badRequest("换房必须保持相同房型");
    if (!"AVAILABLE".equals(String.valueOf(replacement.get("status")))) throw conflict("新房间当前不可用");
    Integer conflicts = jdbc.queryForObject(
        "SELECT COUNT(*) FROM inventory_lock WHERE room_id=? AND order_no<>? AND stay_date>=? AND stay_date<?",
        Integer.class, newRoomId, orderNo, reservation.get("check_in"), reservation.get("check_out"));
    if (conflicts != null && conflicts > 0) throw conflict("新房间在剩余日期内已被占用");

    List<Map<String, Object>> oldLocks = jdbc.queryForList(
        "SELECT stay_date FROM inventory_lock WHERE order_no=? AND room_id=?",
        orderNo, current.get("room_id"));
    jdbc.update("DELETE FROM inventory_lock WHERE order_no=? AND room_id=?", orderNo, current.get("room_id"));
    for (Map<String, Object> lock : oldLocks) {
      jdbc.update("INSERT INTO inventory_lock(room_id,stay_date,order_no,expires_at) VALUES(?,?,?,NULL)",
          newRoomId, lock.get("stay_date"), orderNo);
    }
    jdbc.update("UPDATE reservation_room SET status='CHANGED', check_out_at=NOW(), check_out_by=? WHERE id=?", operatorId, reservationRoomId);
    jdbc.update("UPDATE room SET status='DIRTY' WHERE id=?", current.get("room_id"));
    jdbc.update("UPDATE room SET status='OCCUPIED' WHERE id=?", newRoomId);
    jdbc.update("INSERT INTO reservation_room(reservation_id,reservation_item_id,room_id,status,assigned_at,assigned_by,check_in_at,check_in_by) VALUES(?,?,?,'CHECKED_IN',NOW(),?,NOW(),?)",
        reservation.get("id"), current.get("reservation_item_id"), newRoomId, operatorId, operatorId);
    BigDecimal difference = feeDifference == null ? BigDecimal.ZERO : feeDifference;
    jdbc.update("INSERT INTO room_change_log(reservation_id,reservation_room_id,from_room_id,to_room_id,reason,fee_difference,operator_id) VALUES(?,?,?,?,?,?,?)",
        reservation.get("id"), reservationRoomId, current.get("room_id"), newRoomId, reason, difference, operatorId);
    if (difference.compareTo(BigDecimal.ZERO) != 0) {
      jdbc.update("INSERT INTO folio_charge(reservation_id,type,amount,description,created_by) VALUES(?,?,?,?,?)",
          reservation.get("id"), "ROOM_CHANGE", difference, reason, operatorId);
    }
    recordAudit(operatorId, "ROOM_CHANGE", "RESERVATION", orderNo, "SUCCESS", reason);
    event(reservation, "ROOM_CHANGE", String.valueOf(reservation.get("status")), String.valueOf(reservation.get("status")), reason, operatorId);
    return Map.of("code", "0", "message", "换房成功", "data", Map.of("orderNo", orderNo, "fromRoomId", current.get("room_id"), "toRoomId", newRoomId, "feeDifference", difference));
  }

  @Transactional
  public Map<String, Object> collect(String orderNo, Long operatorId, BigDecimal amount, String method, String remark) {
    Map<String, Object> reservation = reservationForUpdate(orderNo);
    requireStatus(reservation, "PENDING_PAYMENT", "CONFIRMED", "PARTIALLY_CHECKED_IN", "CHECKED_IN", "PARTIALLY_CHECKED_OUT");
    if (amount == null || amount.signum() <= 0) throw badRequest("收款金额必须大于 0");
    if (method == null || method.isBlank()) throw badRequest("收款方式不能为空");
    BigDecimal due = totalDue(reservation);
    BigDecimal collected = collectedAmount(reservation);
    BigDecimal outstanding = due.subtract(collected).max(BigDecimal.ZERO);
    if (amount.compareTo(outstanding) > 0) throw badRequest("收款金额不能超过应收金额");
    String paymentNo = "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    jdbc.update("INSERT INTO payment(payment_no,reservation_id,amount,method,status,expires_at,paid_at) VALUES(?,?,?,?, 'SUCCESS', NOW(), NOW())",
        paymentNo, reservation.get("id"), amount, method.trim().toUpperCase());
    BigDecimal nextCollected = collected.add(amount);
    String paymentStatus = nextCollected.compareTo(due) >= 0 ? "PAID" : "PARTIAL";
    String nextReservationStatus = "PENDING_PAYMENT".equals(String.valueOf(reservation.get("status"))) && "PAID".equals(paymentStatus)
        ? "CONFIRMED" : String.valueOf(reservation.get("status"));
    jdbc.update("UPDATE reservation SET status=?, payment_status=? WHERE id=?", nextReservationStatus, paymentStatus, reservation.get("id"));
    event(reservation, "PAYMENT_COLLECT", String.valueOf(reservation.get("status")), nextReservationStatus, "登记线下收款", operatorId);
    confirmInventoryLocks(orderNo);
    recordAudit(operatorId, "PAYMENT_COLLECT", "RESERVATION", orderNo, "SUCCESS", "登记线下收款");
    return Map.of("code", "0", "message", "收款成功", "data", Map.of("paymentNo", paymentNo, "orderNo", orderNo, "amount", amount, "method", method.trim().toUpperCase(), "remark", remark == null ? "" : remark, "collected", nextCollected, "outstanding", due.subtract(nextCollected).max(BigDecimal.ZERO)));
  }

  @Transactional
  public Map<String, Object> addCharge(String orderNo, Long operatorId, String type,
      BigDecimal amount, String description) {
    Map<String, Object> reservation = reservationForUpdate(orderNo);
    requireStatus(reservation, "PENDING_PAYMENT", "CONFIRMED", "CHECKED_IN", "PARTIALLY_CHECKED_IN", "PARTIALLY_CHECKED_OUT");
    if (type == null || type.isBlank()) throw badRequest("费用类型不能为空");
    if (amount == null || amount.signum() <= 0) throw badRequest("费用金额必须大于 0");
    jdbc.update("INSERT INTO folio_charge(reservation_id,type,amount,description,created_by) VALUES(?,?,?,?,?)",
        reservation.get("id"), type.trim().toUpperCase(), amount,
        description == null ? "" : description.trim(), operatorId);
    recordAudit(operatorId, "FOLIO_CHARGE_ADD", "RESERVATION", orderNo, "SUCCESS", type.trim().toUpperCase());
    return Map.of("code", "0", "message", "附加费用已登记", "data", Map.of(
        "orderNo", orderNo, "type", type.trim().toUpperCase(), "amount", amount,
        "description", description == null ? "" : description.trim()));
  }

  @Transactional
  public Map<String, Object> checkOut(String orderNo, Long operatorId, List<Long> reservationRoomIds) {
    Map<String, Object> reservation = reservationForUpdate(orderNo);
    requireStatus(reservation, "CHECKED_IN", "PARTIALLY_CHECKED_IN", "PARTIALLY_CHECKED_OUT");
    List<Map<String, Object>> checkedIn = jdbc.queryForList(
        "SELECT id, room_id FROM reservation_room WHERE reservation_id=? AND status='CHECKED_IN' FOR UPDATE",
        reservation.get("id"));
    List<Map<String, Object>> selected = selectRooms(checkedIn, reservationRoomIds);
    if (selected.isEmpty()) throw conflict("没有可退房的入住记录");
    BigDecimal outstanding = totalDue(reservation).subtract(collectedAmount(reservation));
    if (outstanding.signum() > 0) throw conflict("尚有未结清费用，请先完成收款");
    for (Map<String, Object> room : selected) {
      jdbc.update("UPDATE reservation_room SET status='CHECKED_OUT', check_out_at=NOW(), check_out_by=? WHERE id=?", operatorId, room.get("id"));
      jdbc.update("UPDATE room SET status='DIRTY' WHERE id=?", room.get("room_id"));
      jdbc.update("DELETE FROM inventory_lock WHERE order_no=? AND room_id=?", orderNo, room.get("room_id"));
      if (p0 != null) p0.openTask(longValue(room.get("room_id")), longValue(reservation.get("id")), "HOUSEKEEPING", "退房后待清洁", operatorId);
    }
    int checkedOut = jdbc.queryForObject("SELECT COUNT(*) FROM reservation_room WHERE reservation_id=? AND status='CHECKED_OUT'", Integer.class, reservation.get("id"));
    String nextStatus = checkedOut >= number(reservation.get("room_count")) ? "COMPLETED" : "PARTIALLY_CHECKED_OUT";
    jdbc.update("UPDATE reservation SET status=? WHERE id=?", nextStatus, reservation.get("id"));
    event(reservation, "CHECK_OUT", String.valueOf(reservation.get("status")), nextStatus, "办理退房", operatorId);
    recordAudit(operatorId, "CHECK_OUT", "RESERVATION", orderNo, "SUCCESS", "办理退房");
    if (notifications != null) notifications.create(longValue(reservation.get("user_id")), "CHECK_OUT", "退房已完成",
        "订单 " + orderNo + " 已完成退房结算，感谢入住。", orderNo);
    return Map.of("code", "0", "message", "退房结算成功", "data", Map.of("orderNo", orderNo, "status", nextStatus, "checkedOutRoomCount", checkedOut, "settledAmount", totalDue(reservation)));
  }

  private Map<String, Object> reservationForUpdate(String orderNo) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT id, order_no, user_id, check_in, check_out, room_count, total_amount, status, payment_status FROM reservation WHERE order_no=? FOR UPDATE",
        orderNo);
    if (rows.isEmpty()) throw notFound("订单不存在");
    return rows.getFirst();
  }

  private Map<String, Object> roomForUpdate(Long roomId) {
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, room_type_id, status FROM room WHERE id=? FOR UPDATE", roomId);
    if (rows.isEmpty()) throw notFound("房间不存在");
    return rows.getFirst();
  }

  private List<Map<String, Object>> selectRooms(List<Map<String, Object>> available, List<Long> requestedIds) {
    if (requestedIds == null || requestedIds.isEmpty()) return available;
    Set<Long> ids = new HashSet<>(requestedIds);
    if (ids.size() != requestedIds.size()) throw badRequest("房间记录不能重复");
    List<Map<String, Object>> selected = available.stream().filter(row -> ids.contains(longValue(row.get("id")))).toList();
    if (selected.size() != requestedIds.size()) throw conflict("指定的房间记录当前不可操作");
    return selected;
  }

  private BigDecimal totalDue(Map<String, Object> reservation) {
    BigDecimal roomTotal = (BigDecimal) reservation.get("total_amount");
    BigDecimal charges = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM folio_charge WHERE reservation_id=? AND status='ACTIVE'", BigDecimal.class, reservation.get("id"));
    BigDecimal discounts = jdbc.queryForObject("SELECT COALESCE(SUM(pa.amount),0) FROM payment_adjustment pa WHERE pa.reservation_id=? AND pa.type='DISCOUNT'", BigDecimal.class, reservation.get("id"));
    return roomTotal.add(charges == null ? BigDecimal.ZERO : charges).subtract(discounts == null ? BigDecimal.ZERO : discounts).max(BigDecimal.ZERO);
  }

  private BigDecimal collectedAmount(Map<String, Object> reservation) {
    BigDecimal collected = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM payment WHERE reservation_id=? AND status='SUCCESS'", BigDecimal.class, reservation.get("id"));
    BigDecimal adjustments = jdbc.queryForObject("SELECT COALESCE(SUM(pa.amount),0) FROM payment_adjustment pa WHERE pa.reservation_id=? AND pa.type IN ('REFUND','REVERSAL')", BigDecimal.class, reservation.get("id"));
    return (collected == null ? BigDecimal.ZERO : collected).subtract(adjustments == null ? BigDecimal.ZERO : adjustments).max(BigDecimal.ZERO);
  }

  private void requireStatus(Map<String, Object> reservation, String... allowed) {
    String status = String.valueOf(reservation.get("status"));
    for (String value : allowed) if (value.equals(status)) return;
    throw conflict("当前订单状态不允许执行此操作");
  }

  private int number(Object value) {
    return ((Number) value).intValue();
  }

  private Long longValue(Object value) {
    return ((Number) value).longValue();
  }

  private LocalDate dateValue(Object value) {
    if (value instanceof java.sql.Date date) return date.toLocalDate();
    if (value instanceof LocalDate date) return date;
    return LocalDate.parse(String.valueOf(value));
  }

  private ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private void recordAudit(Long operatorId, String action, String resourceType, String resourceId, String result, String detail) {
    if (audit != null) audit.record(operatorId, action, resourceType, resourceId, result, detail);
  }

  private void event(Map<String, Object> reservation, String type, String from, String to, String reason, Long operatorId) {
    jdbc.update("INSERT INTO reservation_event(reservation_id,event_type,from_status,to_status,reason,operator_id) VALUES(?,?,?,?,?,?)",
        reservation.get("id"), type, from, to, reason, operatorId);
  }

  private void confirmInventoryLocks(String orderNo) {
    List<Map<String, Object>> locks = jdbc.queryForList("SELECT room_id, stay_date FROM inventory_lock WHERE order_no=?", orderNo);
    jdbc.update("UPDATE inventory_lock SET expires_at=NULL WHERE order_no=?", orderNo);
    if (redis != null) {
      for (Map<String, Object> lock : locks) {
        redis.delete("homestay:inventory-lock:" + lock.get("room_id") + ":" + lock.get("stay_date"));
      }
    }
  }
}
