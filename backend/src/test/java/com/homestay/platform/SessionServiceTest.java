package com.homestay.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class SessionServiceTest {
  @Test
  void customerCannotAccessStaffSession() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("homestay:session:customer-token")).thenReturn("42");
    when(jdbc.queryForList(anyString(), eq("42"))).thenReturn(List.of(
        Map.of("id", 42L, "username", "customer", "role_code", "CUSTOMER", "status", "ACTIVE")));

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> new SessionService(jdbc, redis).requireStaff("Bearer customer-token"));

    assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
  }

  @Test
  void activeStaffSessionIsAccepted() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("homestay:session:staff-token")).thenReturn("7");
    when(jdbc.queryForList(anyString(), eq("7"))).thenReturn(List.of(
        Map.of("id", 7L, "username", "staff", "role_code", "OPERATION_ADMIN", "status", "ACTIVE")));

    SessionService.SessionUser user = new SessionService(jdbc, redis)
        .requireStaff("Bearer staff-token");

    assertEquals(7L, user.id());
    assertEquals("OPERATION_ADMIN", user.role());
  }
}
