package com.homestay.platform;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class DevDataInitializer {
  @Value("${homestay.seed-admin:false}")
  private boolean seedAdmin;

  @Value("${homestay.environment:development}")
  private String environment;

  @Value("${homestay.admin-username:admin}")
  private String adminUsername;

  @Value("${homestay.admin-password:}")
  private String adminPassword;

  @Bean
  CommandLineRunner seedAdmin(JdbcTemplate jdbc, PasswordEncoder encoder) {
    return args -> {
      if (!seedAdmin) return;
      if (adminPassword == null || adminPassword.isBlank()) {
        throw new IllegalStateException("ADMIN_INITIAL_PASSWORD is required when SEED_ADMIN_ENABLED=true");
      }
      if ("production".equalsIgnoreCase(environment)
          && ("admin123".equals(adminPassword) || "admin_dev_only_change_me".equals(adminPassword))) {
        throw new IllegalStateException("The development admin password cannot be used in production");
      }
      Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username=?", Integer.class, adminUsername);
      if (count != null && count == 0) {
        jdbc.update("INSERT INTO sys_user(username,phone,password_hash,display_name,role_code) VALUES(?,?,?,?,?)", adminUsername, "13800000001", encoder.encode(adminPassword), "栖野管理员", "SYSTEM_ADMIN");
      }
      Long userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, adminUsername);
      Long roleId = jdbc.queryForObject("SELECT id FROM sys_role WHERE code='SYSTEM_ADMIN'", Long.class);
      jdbc.update("INSERT IGNORE INTO sys_user_role(user_id,role_id) VALUES(?,?)", userId, roleId);
    };
  }
}
