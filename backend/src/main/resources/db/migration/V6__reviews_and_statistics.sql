CREATE TABLE IF NOT EXISTS review (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  reservation_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  score INT NOT NULL,
  content VARCHAR(2000),
  status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED',
  reply VARCHAR(2000),
  replied_by BIGINT NULL,
  replied_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_review_reservation FOREIGN KEY (reservation_id) REFERENCES reservation(id),
  CONSTRAINT fk_review_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
  CONSTRAINT fk_review_replier FOREIGN KEY (replied_by) REFERENCES sys_user(id),
  UNIQUE KEY uk_review_reservation_user (reservation_id, user_id),
  INDEX idx_review_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
