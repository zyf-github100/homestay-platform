package com.homestay.platform;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaymentService {
  private static final Duration PAYMENT_TTL = Duration.ofMinutes(15);
  private final JdbcTemplate jdbc;
  private final StringRedisTemplate redis;
  private final AuditService audit;

  public PaymentService(JdbcTemplate jdbc, StringRedisTemplate redis) {
    this(jdbc, redis, null);
  }

  @Autowired
  public PaymentService(JdbcTemplate jdbc, StringRedisTemplate redis, AuditService audit) {
    this.jdbc = jdbc;
    this.redis = redis;
    this.audit = audit;
  }

  @Transactional
  public Map<String, Object> createPayment(String orderNo, Long userId) {
    Map<String, Object> reservation = reservationForUpdate(orderNo, userId);
    String status = String.valueOf(reservation.get("status"));
    String paymentStatus = String.valueOf(reservation.get("payment_status"));
    if (!"PENDING_PAYMENT".equals(status) || !"UNPAID".equals(paymentStatus)) {
      throw conflict("当前订单不能发起支付");
    }

    List<Map<String, Object>> existing = jdbc.queryForList(
        "SELECT p.payment_no, p.amount, p.method, p.status, p.expires_at, r.order_no "
            + "FROM payment p JOIN reservation r ON r.id=p.reservation_id "
            + "WHERE p.reservation_id=? AND p.status='PENDING' ORDER BY p.id DESC LIMIT 1",
        reservation.get("id"));
    if (!existing.isEmpty()) {
      Map<String, Object> payment = existing.getFirst();
      Timestamp expiresAt = (Timestamp) payment.get("expires_at");
      if (expiresAt != null && expiresAt.toInstant().isBefore(Instant.now())) {
        markCancelled(payment, "EXPIRED", "EXPIRED");
        throw conflict("支付单已过期，请重新下单");
      }
      return paymentResponse(payment, "支付单已存在");
    }

    String paymentNo = "P" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    Timestamp expiresAt = Timestamp.from(Instant.now().plus(PAYMENT_TTL));
    BigDecimal amount = (BigDecimal) reservation.get("total_amount");
    jdbc.update("INSERT INTO payment(payment_no,reservation_id,amount,method,status,expires_at) "
            + "VALUES(?,?,?,?, 'PENDING', ?)",
        paymentNo, reservation.get("id"), amount, "SIMULATED", expiresAt);
    recordAudit(userId, "PAYMENT_CREATE", "PAYMENT", paymentNo, "SUCCESS", "创建模拟支付单");
    return Map.of("code", "0", "message", "支付单创建成功", "data",
        Map.of("paymentNo", paymentNo, "amount", amount, "method", "SIMULATED",
            "status", "PENDING", "expiresAt", expiresAt));
  }

  @Transactional
  public Map<String, Object> simulate(String paymentNo, Long userId, String requestedResult) {
    String result = requestedResult == null ? "" : requestedResult.trim().toUpperCase();
    if (!List.of("SUCCESS", "FAILURE", "CANCEL", "TIMEOUT").contains(result)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "result 必须是 SUCCESS、FAILURE、CANCEL 或 TIMEOUT");
    }

    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT p.id, p.payment_no, p.reservation_id, p.amount, p.method, p.status, "
            + "p.expires_at, r.order_no, r.user_id, r.status reservation_status, "
            + "r.payment_status FROM payment p JOIN reservation r ON r.id=p.reservation_id "
            + "WHERE p.payment_no=? AND r.user_id=? FOR UPDATE",
        paymentNo, userId);
    if (rows.isEmpty()) throw notFound("支付单不存在");
    Map<String, Object> payment = rows.getFirst();
    String currentStatus = String.valueOf(payment.get("status"));
    if (!"PENDING".equals(currentStatus)) return paymentResponse(payment, "支付结果已处理");

    Timestamp expiresAt = (Timestamp) payment.get("expires_at");
    if (expiresAt != null && expiresAt.toInstant().isBefore(Instant.now())) result = "TIMEOUT";
    switch (result) {
      case "SUCCESS" -> markSuccess(payment);
      case "FAILURE" -> markCancelled(payment, "FAILED", "FAILED");
      case "CANCEL" -> markCancelled(payment, "CANCELLED", "CANCELLED");
      case "TIMEOUT" -> markCancelled(payment, "EXPIRED", "EXPIRED");
      default -> throw new IllegalStateException("unreachable result");
    }
    recordAudit(userId, "PAYMENT_SIMULATE", "PAYMENT", paymentNo, "SUCCESS", "模拟支付结果: " + result);
    return paymentResponseByNo(paymentNo, result.equals("SUCCESS") ? "支付成功" : "订单已取消");
  }

  public Map<String, Object> getPayment(String paymentNo, Long userId) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT p.payment_no paymentNo, p.amount, p.method, p.status, p.expires_at expiresAt, "
            + "p.paid_at paidAt, r.order_no orderNo FROM payment p "
            + "JOIN reservation r ON r.id=p.reservation_id "
            + "WHERE p.payment_no=? AND r.user_id=?",
        paymentNo, userId);
    if (rows.isEmpty()) throw notFound("支付单不存在");
    return Map.of("code", "0", "message", "success", "data", rows.getFirst());
  }

  @Transactional
  public Map<String, Object> cancelReservation(String orderNo, Long userId) {
    Map<String, Object> reservation = reservationForUpdate(orderNo, userId);
    return cancelReservationInternal(orderNo, userId, reservation);
  }

  public Map<String, Object> cancellationPreview(String orderNo, Long userId) {
    Map<String, Object> reservation = reservationForUser(orderNo, userId);
    String status = String.valueOf(reservation.get("status"));
    if (!"PENDING_PAYMENT".equals(status) && !"CONFIRMED".equals(status)) {
      return Map.of("code", "0", "message", "当前订单不能取消", "data", Map.of(
          "orderNo", orderNo, "allowed", false, "cancellationFee", BigDecimal.ZERO,
          "refundAmount", BigDecimal.ZERO));
    }
    CancellationBreakdown breakdown = cancellationBreakdown(reservation);
    return Map.of("code", "0", "message", "success", "data", Map.of(
        "orderNo", orderNo, "allowed", true, "cancellationFee", breakdown.fee(),
        "refundAmount", breakdown.refund()));
  }

  @Transactional
  public Map<String, Object> cancelReservationByOperator(String orderNo, Long operatorId) {
    Map<String, Object> reservation = reservationForOperatorUpdate(orderNo);
    return cancelReservationInternal(orderNo, operatorId, reservation);
  }

  private Map<String, Object> cancelReservationInternal(String orderNo, Long operatorId,
      Map<String, Object> reservation) {
    String status = String.valueOf(reservation.get("status"));
    if (!"PENDING_PAYMENT".equals(status) && !"CONFIRMED".equals(status)) {
      throw conflict("当前订单不能取消");
    }

    BigDecimal collected = collectedAmount(reservation.get("id"));
    CancellationBreakdown breakdown = cancellationBreakdown(reservation, collected);
    boolean paid = collected.signum() > 0;
    List<Map<String, Object>> payments = jdbc.queryForList(
        "SELECT payment_no, status FROM payment WHERE reservation_id=? AND status='PENDING' FOR UPDATE",
        reservation.get("id"));
    for (Map<String, Object> payment : payments) {
      jdbc.update("UPDATE payment SET status='CANCELLED' WHERE payment_no=?", payment.get("payment_no"));
    }
    if (paid) {
      jdbc.update("UPDATE payment SET status='REFUNDED' WHERE reservation_id=? AND status='SUCCESS'",
          reservation.get("id"));
    }
    jdbc.update("UPDATE reservation SET status='CANCELLED', payment_status=? WHERE id=?",
        paid ? "REFUNDED" : "CANCELLED", reservation.get("id"));
    releaseInventory(String.valueOf(reservation.get("order_no")));
    recordAudit(operatorId, "RESERVATION_CANCEL", "RESERVATION", orderNo, "SUCCESS", paid ? "取消并记录模拟退款" : "取消订单");
    return Map.of("code", "0", "message", paid ? "订单已取消，已记录模拟退款" : "订单已取消",
        "data", Map.of("orderNo", orderNo, "status", "CANCELLED", "refundAmount",
            breakdown.refund(), "cancellationFee", breakdown.fee()));
  }

  @Transactional
  public int expirePendingPayments() {
    List<Map<String, Object>> payments = jdbc.queryForList(
        "SELECT p.payment_no, p.reservation_id, r.order_no FROM payment p "
            + "JOIN reservation r ON r.id=p.reservation_id "
            + "WHERE p.status='PENDING' AND p.expires_at <= NOW() FOR UPDATE");
    for (Map<String, Object> payment : payments) {
      jdbc.update("UPDATE payment SET status='EXPIRED' WHERE payment_no=? AND status='PENDING'",
          payment.get("payment_no"));
      int cancelled = jdbc.update("UPDATE reservation SET status='CANCELLED', payment_status='EXPIRED' "
              + "WHERE id=? AND status='PENDING_PAYMENT'", payment.get("reservation_id"));
      if (cancelled > 0) releaseInventory(String.valueOf(payment.get("order_no")));
      recordAudit(null, "PAYMENT_EXPIRE", "PAYMENT", String.valueOf(payment.get("payment_no")), "SUCCESS", "支付超时释放库存");
    }
    List<Map<String, Object>> reservations = jdbc.queryForList(
        "SELECT r.id, r.order_no FROM reservation r "
            + "WHERE r.status='PENDING_PAYMENT' "
            + "AND EXISTS (SELECT 1 FROM inventory_lock l WHERE l.order_no=r.order_no "
            + "AND l.expires_at IS NOT NULL AND l.expires_at <= NOW()) "
            + "AND NOT EXISTS (SELECT 1 FROM payment p WHERE p.reservation_id=r.id AND p.status='PENDING')");
    for (Map<String, Object> reservation : reservations) {
      int cancelled = jdbc.update(
          "UPDATE reservation SET status='CANCELLED', payment_status='EXPIRED' "
              + "WHERE id=? AND status='PENDING_PAYMENT'",
          reservation.get("id"));
      if (cancelled > 0) {
        releaseInventory(String.valueOf(reservation.get("order_no")));
        recordAudit(null, "RESERVATION_EXPIRE", "RESERVATION",
            String.valueOf(reservation.get("order_no")), "SUCCESS", "未发起支付，库存锁超时");
      }
    }
    return payments.size() + reservations.size();
  }

  private Map<String, Object> reservationForUpdate(String orderNo, Long userId) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT id, order_no, user_id, total_amount, check_in, check_out, cancellation_policy, status, payment_status FROM reservation "
            + "WHERE order_no=? AND user_id=? FOR UPDATE", orderNo, userId);
    if (rows.isEmpty()) throw notFound("订单不存在");
    return rows.getFirst();
  }

  private Map<String, Object> reservationForOperatorUpdate(String orderNo) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT id, order_no, user_id, total_amount, check_in, check_out, cancellation_policy, status, payment_status FROM reservation "
            + "WHERE order_no=? FOR UPDATE", orderNo);
    if (rows.isEmpty()) throw notFound("订单不存在");
    return rows.getFirst();
  }

  private Map<String, Object> reservationForUser(String orderNo, Long userId) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT id, order_no, user_id, total_amount, check_in, check_out, cancellation_policy, status, payment_status FROM reservation "
            + "WHERE order_no=? AND user_id=?", orderNo, userId);
    if (rows.isEmpty()) throw notFound("订单不存在");
    return rows.getFirst();
  }

  private CancellationBreakdown cancellationBreakdown(Map<String, Object> reservation) {
    return cancellationBreakdown(reservation, collectedAmount(reservation.get("id")));
  }

  private CancellationBreakdown cancellationBreakdown(Map<String, Object> reservation, BigDecimal collected) {
    BigDecimal fee = BigDecimal.ZERO;
    LocalDate checkIn = dateValue(reservation.get("check_in"));
    if ("CONFIRMED".equals(String.valueOf(reservation.get("status"))) && checkIn != null) {
      LocalDate today = LocalDate.now(ZoneOffset.UTC);
      if (!today.isBefore(checkIn)) throw conflict("入住日当天或入住后不能由用户取消订单");
      if (!today.isBefore(checkIn.minusDays(1))) {
        BigDecimal firstNight = jdbc.queryForObject(
            "SELECT COALESCE(SUM(unit_price * quantity),0) FROM reservation_item WHERE reservation_id=?",
            BigDecimal.class, reservation.get("id"));
        fee = (firstNight == null ? BigDecimal.ZERO : firstNight).min(collected).max(BigDecimal.ZERO);
      }
    }
    return new CancellationBreakdown(fee, collected.subtract(fee).max(BigDecimal.ZERO));
  }

  private LocalDate dateValue(Object value) {
    if (value == null) return null;
    if (value instanceof java.sql.Date date) return date.toLocalDate();
    if (value instanceof LocalDate date) return date;
    return LocalDate.parse(String.valueOf(value));
  }

  private void markSuccess(Map<String, Object> payment) {
    jdbc.update("UPDATE payment SET status='SUCCESS', paid_at=NOW() WHERE payment_no=? AND status='PENDING'",
        payment.get("payment_no"));
    jdbc.update("UPDATE reservation SET status='CONFIRMED', payment_status='PAID' WHERE id=? AND status='PENDING_PAYMENT'",
        payment.get("reservation_id"));
    confirmInventory(String.valueOf(payment.get("order_no")));
  }

  private void markCancelled(Map<String, Object> payment, String paymentStatus, String reservationPaymentStatus) {
    jdbc.update("UPDATE payment SET status=? WHERE payment_no=? AND status='PENDING'",
        paymentStatus, payment.get("payment_no"));
    jdbc.update("UPDATE reservation SET status='CANCELLED', payment_status=? WHERE id=? AND status='PENDING_PAYMENT'",
        reservationPaymentStatus, payment.get("reservation_id"));
    releaseInventory(String.valueOf(payment.get("order_no")));
  }

  private void confirmInventory(String orderNo) {
    List<Map<String, Object>> locks = jdbc.queryForList(
        "SELECT room_id, stay_date FROM inventory_lock WHERE order_no=?", orderNo);
    jdbc.update("UPDATE inventory_lock SET expires_at=NULL WHERE order_no=?", orderNo);
    deleteRedisLocks(locks);
  }

  private void releaseInventory(String orderNo) {
    List<Map<String, Object>> locks = jdbc.queryForList(
        "SELECT room_id, stay_date FROM inventory_lock WHERE order_no=?", orderNo);
    jdbc.update("DELETE FROM inventory_lock WHERE order_no=?", orderNo);
    deleteRedisLocks(locks);
  }

  private void deleteRedisLocks(List<Map<String, Object>> locks) {
    for (Map<String, Object> lock : locks) {
      redis.delete("homestay:inventory-lock:" + lock.get("room_id") + ":" + lock.get("stay_date"));
    }
  }

  private Map<String, Object> paymentResponseByNo(String paymentNo, String message) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT payment_no paymentNo, amount, method, status, expires_at expiresAt, paid_at paidAt "
            + "FROM payment WHERE payment_no=?", paymentNo);
    return Map.of("code", "0", "message", message, "data", rows.getFirst());
  }

  private Map<String, Object> paymentResponse(Map<String, Object> payment, String message) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("paymentNo", payment.getOrDefault("paymentNo", payment.get("payment_no")));
    data.put("amount", payment.get("amount"));
    data.put("method", payment.get("method"));
    data.put("status", payment.get("status"));
    data.put("expiresAt", payment.getOrDefault("expiresAt", payment.get("expires_at")));
    data.put("paidAt", payment.getOrDefault("paidAt", payment.get("paid_at")));
    return Map.of("code", "0", "message", message, "data", data);
  }

  private BigDecimal collectedAmount(Object reservationId) {
    BigDecimal collected = jdbc.queryForObject(
        "SELECT COALESCE(SUM(amount),0) FROM payment WHERE reservation_id=? AND status='SUCCESS'",
        BigDecimal.class, reservationId);
    return collected == null ? BigDecimal.ZERO : collected;
  }

  private ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private void recordAudit(Long operatorId, String action, String resourceType, String resourceId, String result, String detail) {
    if (audit != null) audit.record(operatorId, action, resourceType, resourceId, result, detail);
  }

  private record CancellationBreakdown(BigDecimal fee, BigDecimal refund) {}
}
