package com.homestay.platform;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class AdminControllerAuthorizationTest {
  @Test
  void customerSessionCannotReadAdminResources() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    SessionService session = mock(SessionService.class);
    when(jdbc.queryForList(anyString())).thenReturn(List.of());
    when(session.requirePermission("Bearer customer-token", "room-type:view"))
        .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN));

    AdminController controller = new AdminController(jdbc, session);

    assertThrows(ResponseStatusException.class,
        () -> controller.roomTypes("Bearer customer-token"));
  }
}
