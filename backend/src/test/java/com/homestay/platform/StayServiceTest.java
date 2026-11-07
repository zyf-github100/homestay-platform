package com.homestay.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class StayServiceTest {
  @Test
  void collectsCashAndMarksOrderPaid() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString(), eq("QY-1"))).thenReturn(List.of(
        Map.of("id", 1L, "order_no", "QY-1", "check_in", Date.valueOf("2027-01-01"),
            "check_out", Date.valueOf("2027-01-02"), "room_count", 1, "total_amount", new BigDecimal("100.00"),
            "status", "CONFIRMED", "payment_status", "UNPAID")));
    when(jdbc.queryForObject(contains("folio_charge"), eq(BigDecimal.class), eq(1L))).thenReturn(BigDecimal.ZERO);
    when(jdbc.queryForObject(contains("payment WHERE"), eq(BigDecimal.class), eq(1L))).thenReturn(BigDecimal.ZERO);

    Map<String, Object> response = new StayService(jdbc).collect("QY-1", 9L,
        new BigDecimal("100.00"), "cash", "到店收款");

    assertEquals("0", response.get("code"));
    verify(jdbc).update(contains("UPDATE reservation SET status=?, payment_status=?"), eq("CONFIRMED"), eq("PAID"), eq(1L));
  }

  @Test
  void checkoutRejectsOutstandingAmount() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString(), eq("QY-1"))).thenReturn(List.of(
        Map.of("id", 1L, "order_no", "QY-1", "check_in", Date.valueOf("2027-01-01"),
            "check_out", Date.valueOf("2027-01-02"), "room_count", 1, "total_amount", new BigDecimal("100.00"),
            "status", "CHECKED_IN", "payment_status", "PARTIAL")));
    when(jdbc.queryForList(contains("status='CHECKED_IN'"), eq(1L))).thenReturn(List.of(
        Map.of("id", 11L, "room_id", 101L)));
    when(jdbc.queryForObject(contains("folio_charge"), eq(BigDecimal.class), eq(1L))).thenReturn(BigDecimal.ZERO);
    when(jdbc.queryForObject(contains("payment WHERE"), eq(BigDecimal.class), eq(1L))).thenReturn(new BigDecimal("40.00"));

    assertThrows(ResponseStatusException.class, () -> new StayService(jdbc)
        .checkOut("QY-1", 9L, null));
  }
}
