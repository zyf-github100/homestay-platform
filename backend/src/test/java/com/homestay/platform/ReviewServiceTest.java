package com.homestay.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ReviewServiceTest {
  @Test
  void completedOrderCanBeReviewedOnce() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    AuditService audit = mock(AuditService.class);
    when(jdbc.queryForList(anyString(), eq("QY-1"), eq(7L))).thenReturn(List.of(
        Map.of("id", 1L, "status", "COMPLETED")));
    when(jdbc.queryForObject(contains("COUNT(*) FROM review"), eq(Integer.class), eq(1L), eq(7L))).thenReturn(0);
    when(jdbc.queryForObject(contains("SELECT id FROM review"), eq(Long.class), eq(1L), eq(7L))).thenReturn(3L);

    Map<String, Object> response = new ReviewService(jdbc, audit).create(7L, "QY-1", 5, "很棒");

    assertEquals(3L, response.get("id"));
    assertEquals("PUBLISHED", response.get("status"));
  }
}
