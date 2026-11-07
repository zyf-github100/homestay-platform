package com.homestay.platform;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class DevDataInitializer {
  @Bean
  CommandLineRunner seedAdmin(JdbcTemplate jdbc, PasswordEncoder encoder) {
    return args -> {
      Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username='admin'", Integer.class);
      if (count != null && count == 0) {
        jdbc.update("INSERT INTO sys_user(username,phone,password_hash,display_name,role_code) VALUES(?,?,?,?,?)", "admin", "13800000001", encoder.encode("admin123"), "栖野管理员", "SYSTEM_ADMIN");
      }
      Long userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username='admin'", Long.class);
      Long roleId = jdbc.queryForObject("SELECT id FROM sys_role WHERE code='SYSTEM_ADMIN'", Long.class);
      jdbc.update("INSERT IGNORE INTO sys_user_role(user_id,role_id) VALUES(?,?)", userId, roleId);
    };
  }
}
