package com.homestay.platform;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

class ReservationControllerAuthorizationTest {
  @Test
  void listFiltersOrdersByAuthenticatedUser() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    SessionService session = mock(SessionService.class);
    PaymentService payments = mock(PaymentService.class);
    when(session.requirePermission("Bearer user-token", "reservation:view:self"))
        .thenReturn(new SessionService.SessionUser(42L, "customer", "CUSTOMER"));
    when(jdbc.queryForList(anyString(), eq(42L))).thenReturn(List.of());

    new ReservationController(jdbc, redis, session, payments).list("Bearer user-token");

    verify(jdbc).queryForList(contains("WHERE user_id=?"), eq(42L));
  }
}
