ALTER TABLE inventory_lock MODIFY COLUMN expires_at TIMESTAMP NULL;

CREATE TABLE IF NOT EXISTS payment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  payment_no VARCHAR(40) NOT NULL UNIQUE,
  reservation_id BIGINT NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  method VARCHAR(20) NOT NULL DEFAULT 'SIMULATED',
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  expires_at TIMESTAMP NOT NULL,
  paid_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_payment_reservation FOREIGN KEY (reservation_id) REFERENCES reservation(id),
  INDEX idx_payment_reservation_status (reservation_id, status),
  INDEX idx_payment_expiry (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
