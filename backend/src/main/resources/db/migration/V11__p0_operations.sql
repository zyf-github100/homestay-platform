ALTER TABLE reservation ADD COLUMN special_request VARCHAR(500) NULL;
ALTER TABLE reservation ADD COLUMN cancellation_reason VARCHAR(255) NULL;
ALTER TABLE reservation ADD COLUMN cancelled_by BIGINT NULL;
ALTER TABLE reservation ADD COLUMN no_show_at TIMESTAMP NULL;
ALTER TABLE reservation ADD COLUMN no_show_by BIGINT NULL;

ALTER TABLE reservation_item ADD COLUMN room_type_name_snapshot VARCHAR(120) NULL;
ALTER TABLE reservation_item ADD COLUMN bed_type_snapshot VARCHAR(80) NULL;
ALTER TABLE reservation_item ADD COLUMN cancellation_policy_snapshot VARCHAR(255) NULL;

ALTER TABLE folio_charge ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE folio_charge ADD COLUMN voided_by BIGINT NULL;
ALTER TABLE folio_charge ADD COLUMN voided_at TIMESTAMP NULL;
ALTER TABLE folio_charge ADD COLUMN void_reason VARCHAR(255) NULL;

CREATE TABLE IF NOT EXISTS payment_adjustment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  reservation_id BIGINT NOT NULL,
  payment_no VARCHAR(40) NULL,
  type VARCHAR(24) NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  reason VARCHAR(255) NOT NULL,
  operator_id BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_payment_adjustment_reservation FOREIGN KEY (reservation_id) REFERENCES reservation(id),
  CONSTRAINT fk_payment_adjustment_operator FOREIGN KEY (operator_id) REFERENCES sys_user(id),
  INDEX idx_payment_adjustment_reservation (reservation_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS reservation_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  reservation_id BIGINT NOT NULL,
  event_type VARCHAR(40) NOT NULL,
  from_status VARCHAR(30) NULL,
  to_status VARCHAR(30) NULL,
  reason VARCHAR(255) NULL,
  operator_id BIGINT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_reservation_event_reservation FOREIGN KEY (reservation_id) REFERENCES reservation(id),
  CONSTRAINT fk_reservation_event_operator FOREIGN KEY (operator_id) REFERENCES sys_user(id),
  INDEX idx_reservation_event_reservation (reservation_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS inventory_override (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  room_id BIGINT NOT NULL,
  stay_date DATE NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'BLOCKED',
  reason VARCHAR(255) NOT NULL,
  operator_id BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_inventory_override_room FOREIGN KEY (room_id) REFERENCES room(id),
  CONSTRAINT fk_inventory_override_operator FOREIGN KEY (operator_id) REFERENCES sys_user(id),
  UNIQUE KEY uk_inventory_override_room_date (room_id, stay_date),
  INDEX idx_inventory_override_date (stay_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS room_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  room_id BIGINT NOT NULL,
  reservation_id BIGINT NULL,
  task_type VARCHAR(24) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
  priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
  reason VARCHAR(255) NULL,
  assigned_to BIGINT NULL,
  opened_by BIGINT NULL,
  completed_by BIGINT NULL,
  opened_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at TIMESTAMP NULL,
  CONSTRAINT fk_room_task_room FOREIGN KEY (room_id) REFERENCES room(id),
  CONSTRAINT fk_room_task_reservation FOREIGN KEY (reservation_id) REFERENCES reservation(id),
  CONSTRAINT fk_room_task_assigned FOREIGN KEY (assigned_to) REFERENCES sys_user(id),
  CONSTRAINT fk_room_task_opened FOREIGN KEY (opened_by) REFERENCES sys_user(id),
  CONSTRAINT fk_room_task_completed FOREIGN KEY (completed_by) REFERENCES sys_user(id),
  INDEX idx_room_task_status (status, task_type, opened_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO sys_permission(code, name)
SELECT 'inventory:manage', '人工房态与库存' WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code='inventory:manage');
INSERT INTO sys_permission(code, name)
SELECT 'housekeeping:manage', '保洁维修任务' WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code='housekeeping:manage');

INSERT IGNORE INTO sys_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p
  ON p.code IN ('inventory:manage','housekeeping:manage')
 WHERE r.code IN ('SYSTEM_ADMIN','OPERATION_ADMIN','FRONT_DESK');

