package com.homestay.platform;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class OperationsAuthorizationTest {
  @Test
  void customerCannotReadPropertySettings() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    SessionService session = mock(SessionService.class);
    when(session.requirePermission("Bearer customer-token", "property:view"))
        .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN));

    AdminController controller = new AdminController(jdbc, session);
    assertThrows(ResponseStatusException.class, () -> controller.property("Bearer customer-token"));
  }

  @Test
  void customerCannotOperateRoomStatus() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    SessionService session = mock(SessionService.class);
    when(session.requirePermission("Bearer customer-token", "room:operate"))
        .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN));

    AdminController controller = new AdminController(jdbc, session);
    assertThrows(ResponseStatusException.class,
        () -> controller.roomOperation("Bearer customer-token", 1L,
            new AdminController.RoomOperationRequest("MAINTENANCE", "test")));
  }
}
