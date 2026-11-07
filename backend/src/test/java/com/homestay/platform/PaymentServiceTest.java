package com.homestay.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

class PaymentServiceTest {
  @Test
  void createsPaymentForPendingOrder() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(jdbc.queryForList(anyString(), eq("QY-1"), eq(7L))).thenReturn(List.of(
        Map.of("id", 1L, "order_no", "QY-1", "total_amount", new BigDecimal("1376.00"),
            "status", "PENDING_PAYMENT", "payment_status", "UNPAID")));
    when(jdbc.queryForList(contains("SELECT payment_no"), eq(1L))).thenReturn(List.of());

    Map<String, Object> response = new PaymentService(jdbc, redis).createPayment("QY-1", 7L);
    Map<?, ?> data = (Map<?, ?>) response.get("data");

    assertEquals("0", response.get("code"));
    assertEquals("PENDING", data.get("status"));
    assertTrue(String.valueOf(data.get("paymentNo")).startsWith("P"));
    verify(jdbc).update(contains("INSERT INTO payment"), any(), any(), any(), any(), any());
  }

  @Test
  void expiredSimulationCancelsOrderAndReleasesInventory() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    Timestamp expired = Timestamp.from(Instant.now().minusSeconds(60));
    Map<String, Object> payment = new HashMap<>();
    payment.put("id", 1L);
    payment.put("payment_no", "P-1");
    payment.put("reservation_id", 9L);
    payment.put("amount", new BigDecimal("1376.00"));
    payment.put("method", "SIMULATED");
    payment.put("status", "PENDING");
    payment.put("expires_at", expired);
    payment.put("order_no", "QY-1");
    payment.put("user_id", 7L);
    payment.put("reservation_status", "PENDING_PAYMENT");
    payment.put("payment_status", "UNPAID");
    when(jdbc.queryForList(contains("SELECT p.id"), eq("P-1"), eq(7L))).thenReturn(List.of(
        payment));
    when(jdbc.queryForList(contains("SELECT room_id"), eq("QY-1"))).thenReturn(List.of(
        Map.of("room_id", 101L, "stay_date", Date.valueOf("2027-01-10"))));
    when(jdbc.queryForList(contains("SELECT payment_no paymentNo"), eq("P-1"))).thenReturn(List.of(
        Map.of("paymentNo", "P-1", "amount", new BigDecimal("1376.00"), "method", "SIMULATED",
            "status", "EXPIRED", "expiresAt", expired)));

    Map<String, Object> response = new PaymentService(jdbc, redis).simulate("P-1", 7L, "SUCCESS");
    Map<?, ?> data = (Map<?, ?>) response.get("data");

    assertEquals("EXPIRED", data.get("status"));
    verify(jdbc).update(contains("UPDATE payment SET status=?"), eq("EXPIRED"), eq("P-1"));
    verify(jdbc).update(contains("UPDATE reservation SET status='CANCELLED'"), eq("EXPIRED"), eq(9L));
    verify(jdbc).update(contains("DELETE FROM inventory_lock"), eq("QY-1"));
    verify(redis).delete("homestay:inventory-lock:101:2027-01-10");
  }

  @Test
  void expiryJobCancelsExpiredPendingPayments() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(jdbc.queryForList(contains("SELECT p.payment_no"))).thenReturn(List.of(
        Map.of("payment_no", "P-2", "reservation_id", 10L, "order_no", "QY-2")));
    when(jdbc.queryForList(contains("SELECT room_id"), eq("QY-2"))).thenReturn(List.of());

    int expired = new PaymentService(jdbc, redis).expirePendingPayments();

    assertEquals(1, expired);
    verify(jdbc).update(contains("UPDATE payment SET status='EXPIRED'"), eq("P-2"));
    verify(jdbc).update(contains("UPDATE reservation SET status='CANCELLED'"), eq(10L));
  }
}
