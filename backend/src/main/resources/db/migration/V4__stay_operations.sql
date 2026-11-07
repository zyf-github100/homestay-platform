CREATE TABLE IF NOT EXISTS reservation_room (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  reservation_id BIGINT NOT NULL,
  reservation_item_id BIGINT NOT NULL,
  room_id BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ASSIGNED',
  assigned_at TIMESTAMP NULL,
  assigned_by BIGINT NULL,
  check_in_at TIMESTAMP NULL,
  check_in_by BIGINT NULL,
  check_out_at TIMESTAMP NULL,
  check_out_by BIGINT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_reservation_room_reservation FOREIGN KEY (reservation_id) REFERENCES reservation(id),
  CONSTRAINT fk_reservation_room_item FOREIGN KEY (reservation_item_id) REFERENCES reservation_item(id),
  CONSTRAINT fk_reservation_room_room FOREIGN KEY (room_id) REFERENCES room(id),
  CONSTRAINT fk_reservation_room_assigned_by FOREIGN KEY (assigned_by) REFERENCES sys_user(id),
  CONSTRAINT fk_reservation_room_check_in_by FOREIGN KEY (check_in_by) REFERENCES sys_user(id),
  CONSTRAINT fk_reservation_room_check_out_by FOREIGN KEY (check_out_by) REFERENCES sys_user(id),
  INDEX idx_reservation_room_reservation_status (reservation_id, status),
  INDEX idx_reservation_room_room_status (room_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS room_change_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  reservation_id BIGINT NOT NULL,
  reservation_room_id BIGINT NOT NULL,
  from_room_id BIGINT NOT NULL,
  to_room_id BIGINT NOT NULL,
  reason VARCHAR(255) NOT NULL,
  fee_difference DECIMAL(12,2) NOT NULL DEFAULT 0,
  operator_id BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_room_change_reservation FOREIGN KEY (reservation_id) REFERENCES reservation(id),
  CONSTRAINT fk_room_change_reservation_room FOREIGN KEY (reservation_room_id) REFERENCES reservation_room(id),
  CONSTRAINT fk_room_change_from_room FOREIGN KEY (from_room_id) REFERENCES room(id),
  CONSTRAINT fk_room_change_to_room FOREIGN KEY (to_room_id) REFERENCES room(id),
  CONSTRAINT fk_room_change_operator FOREIGN KEY (operator_id) REFERENCES sys_user(id),
  INDEX idx_room_change_reservation (reservation_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS folio_charge (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  reservation_id BIGINT NOT NULL,
  type VARCHAR(32) NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  description VARCHAR(255),
  created_by BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_folio_charge_reservation FOREIGN KEY (reservation_id) REFERENCES reservation(id),
  CONSTRAINT fk_folio_charge_creator FOREIGN KEY (created_by) REFERENCES sys_user(id),
  INDEX idx_folio_charge_reservation (reservation_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
