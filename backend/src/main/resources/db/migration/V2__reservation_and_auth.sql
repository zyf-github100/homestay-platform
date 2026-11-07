ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMP NULL;

CREATE TABLE IF NOT EXISTS reservation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_no VARCHAR(32) NOT NULL UNIQUE,
  user_id BIGINT NULL,
  contact_name VARCHAR(80) NOT NULL,
  contact_phone VARCHAR(32) NOT NULL,
  check_in DATE NOT NULL,
  check_out DATE NOT NULL,
  guests INT NOT NULL DEFAULT 1,
  room_count INT NOT NULL DEFAULT 1,
  total_amount DECIMAL(12,2) NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'PENDING_PAYMENT',
  payment_status VARCHAR(20) NOT NULL DEFAULT 'UNPAID',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_reservation_user_status (user_id, status),
  INDEX idx_reservation_stay (check_in, check_out, status),
  CONSTRAINT fk_reservation_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS reservation_item (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  reservation_id BIGINT NOT NULL,
  room_type_id BIGINT NOT NULL,
  quantity INT NOT NULL,
  unit_price DECIMAL(12,2) NOT NULL,
  nights INT NOT NULL,
  CONSTRAINT fk_reservation_item_order FOREIGN KEY (reservation_id) REFERENCES reservation(id),
  CONSTRAINT fk_reservation_item_type FOREIGN KEY (room_type_id) REFERENCES room_type(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS inventory_lock (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  room_id BIGINT NOT NULL,
  stay_date DATE NOT NULL,
  order_no VARCHAR(32) NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  UNIQUE KEY uk_inventory_room_date (room_id, stay_date),
  INDEX idx_inventory_order (order_no),
  CONSTRAINT fk_inventory_room FOREIGN KEY (room_id) REFERENCES room(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
