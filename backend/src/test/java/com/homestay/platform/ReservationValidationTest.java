package com.homestay.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class ReservationValidationTest {
  @Test
  void rejectsCheckInBeforeBusinessDate() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    SessionService session = mock(SessionService.class);
    PaymentService payments = mock(PaymentService.class);
    ReservationController controller = new ReservationController(jdbc, redis, session, payments);

    ReservationController.CreateRequest request = new ReservationController.CreateRequest(
        LocalDate.now().minusDays(1), LocalDate.now().plusDays(1), 2, "测试", "13800000000",
        List.of(new ReservationController.Item(1L, 1)), null);

    ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> controller.price(request));
    assertEquals(400, error.getStatusCode().value());
  }
}
