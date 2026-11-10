CREATE TABLE IF NOT EXISTS notification (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  notification_type VARCHAR(32) NOT NULL,
  title VARCHAR(120) NOT NULL,
  content VARCHAR(500) NOT NULL,
  related_order_no VARCHAR(32) NULL,
  read_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
  INDEX idx_notification_user_created (user_id, created_at),
  INDEX idx_notification_user_read (user_id, read_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
