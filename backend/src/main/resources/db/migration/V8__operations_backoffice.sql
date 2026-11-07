ALTER TABLE property
  ADD COLUMN check_in_rule VARCHAR(500) NULL,
  ADD COLUMN check_out_rule VARCHAR(500) NULL;

ALTER TABLE reservation
  ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'ONLINE',
  ADD COLUMN created_by BIGINT NULL,
  ADD CONSTRAINT fk_reservation_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);

CREATE TABLE IF NOT EXISTS room_type_image (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  room_type_id BIGINT NOT NULL,
  image_url VARCHAR(500) NOT NULL,
  alt_text VARCHAR(160),
  sort_order INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_room_type_image_type FOREIGN KEY (room_type_id) REFERENCES room_type(id) ON DELETE CASCADE,
  INDEX idx_room_type_image_sort (room_type_id, sort_order, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS room_type_facility (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  room_type_id BIGINT NOT NULL,
  name VARCHAR(120) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_room_type_facility_type FOREIGN KEY (room_type_id) REFERENCES room_type(id) ON DELETE CASCADE,
  UNIQUE KEY uk_room_type_facility_name (room_type_id, name),
  INDEX idx_room_type_facility_sort (room_type_id, sort_order, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO sys_permission(code, name)
SELECT x.code, x.name FROM (
  SELECT 'property:view' code, '查看民宿资料' name
  UNION ALL SELECT 'property:manage', '维护民宿资料'
  UNION ALL SELECT 'inventory:view', '查看库存日历'
  UNION ALL SELECT 'room:operate', '执行房务状态操作'
) x WHERE NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.code=x.code);

INSERT IGNORE INTO sys_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p
  ON (r.code='SYSTEM_ADMIN')
  OR (r.code='OPERATION_ADMIN' AND p.code IN ('property:view','property:manage','inventory:view','room:operate'))
  OR (r.code='FRONT_DESK' AND p.code IN ('property:view','inventory:view','room:operate'));
