CREATE TABLE IF NOT EXISTS guest_profile (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  name VARCHAR(80) NOT NULL,
  gender VARCHAR(16),
  phone VARCHAR(32),
  id_type VARCHAR(24) NOT NULL DEFAULT 'ID_CARD',
  id_no_ciphertext VARCHAR(512) NOT NULL,
  id_no_hash CHAR(64) NOT NULL,
  id_no_masked VARCHAR(64) NOT NULL,
  remark VARCHAR(255),
  deleted_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_guest_profile_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
  INDEX idx_guest_profile_user (user_id, deleted_at),
  INDEX idx_guest_profile_hash (user_id, id_no_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS reservation_guest (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  reservation_room_id BIGINT NOT NULL,
  guest_profile_id BIGINT NULL,
  name VARCHAR(80) NOT NULL,
  gender VARCHAR(16),
  phone VARCHAR(32),
  id_type VARCHAR(24) NOT NULL DEFAULT 'ID_CARD',
  id_no_ciphertext VARCHAR(512) NOT NULL,
  id_no_hash CHAR(64) NOT NULL,
  id_no_masked VARCHAR(64) NOT NULL,
  is_primary BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_reservation_guest_room FOREIGN KEY (reservation_room_id) REFERENCES reservation_room(id),
  CONSTRAINT fk_reservation_guest_profile FOREIGN KEY (guest_profile_id) REFERENCES guest_profile(id),
  INDEX idx_reservation_guest_room (reservation_room_id, is_primary)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS audit_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  operator_id BIGINT NULL,
  action VARCHAR(64) NOT NULL,
  resource_type VARCHAR(64) NOT NULL,
  resource_id VARCHAR(64),
  result VARCHAR(24) NOT NULL,
  detail TEXT,
  source_ip VARCHAR(64),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_audit_operator FOREIGN KEY (operator_id) REFERENCES sys_user(id),
  INDEX idx_audit_created (created_at),
  INDEX idx_audit_operator (operator_id, created_at),
  INDEX idx_audit_resource (resource_type, resource_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
