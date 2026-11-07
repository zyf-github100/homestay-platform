package com.homestay.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PermissionServiceTest {
  @Test
  void resolvesDatabasePermissionsForUser() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString(), eq(String.class), eq(7L))).thenReturn(List.of("room-type:view", "report:view"));

    PermissionService permissions = new PermissionService(jdbc);

    assertTrue(permissions.has(7L, "room-type:view"));
    assertFalse(permissions.has(7L, "room-type:manage"));
  }
}
