CREATE TABLE IF NOT EXISTS sys_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL UNIQUE,
  phone VARCHAR(32) UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  display_name VARCHAR(80) NOT NULL,
  role_code VARCHAR(32) NOT NULL DEFAULT 'CUSTOMER',
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS property (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(120) NOT NULL,
  description TEXT,
  address VARCHAR(255) NOT NULL,
  phone VARCHAR(32),
  check_in_time TIME NOT NULL DEFAULT '14:00:00',
  check_out_time TIME NOT NULL DEFAULT '12:00:00',
  status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS room_type (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  property_id BIGINT NOT NULL,
  name VARCHAR(120) NOT NULL,
  description TEXT,
  bed_type VARCHAR(80) NOT NULL,
  area_sqm DECIMAL(8,2) NOT NULL DEFAULT 0,
  max_guests INT NOT NULL DEFAULT 2,
  base_price DECIMAL(12,2) NOT NULL,
  cover_image VARCHAR(500),
  status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_room_type_property FOREIGN KEY (property_id) REFERENCES property(id),
  INDEX idx_room_type_property_status (property_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS room (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  property_id BIGINT NOT NULL,
  room_type_id BIGINT NOT NULL,
  room_no VARCHAR(32) NOT NULL,
  floor_no VARCHAR(16),
  status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
  remark VARCHAR(255),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_room_property FOREIGN KEY (property_id) REFERENCES property(id),
  CONSTRAINT fk_room_room_type FOREIGN KEY (room_type_id) REFERENCES room_type(id),
  UNIQUE KEY uk_room_property_no (property_id, room_no),
  INDEX idx_room_type_status (room_type_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO property (name, description, address, phone)
SELECT '栖野民宿', '把日子住进风景里。山与溪之间的慢生活民宿。', '浙江省湖州市德清县莫干山镇', '+86 572 806 8899'
WHERE NOT EXISTS (SELECT 1 FROM property WHERE name = '栖野民宿');

INSERT INTO room_type (property_id, name, description, bed_type, area_sqm, max_guests, base_price, cover_image)
SELECT p.id, x.name, x.description, x.bed_type, x.area_sqm, x.max_guests, x.base_price, x.cover_image
FROM property p
JOIN (SELECT '松风大床房' name, '窗外是松林与晨雾。' description, '1 张大床' bed_type, 32.00 area_sqm, 2 max_guests, 688.00 base_price, '/assets/room-warm.svg' cover_image
      UNION ALL SELECT '云岚双床房', '溪流和山景相伴，适合朋友同行。', '2 张单人床', 38.00, 2, 758.00, '/assets/room-stream.svg'
      UNION ALL SELECT '栖迟整栋小院', '独享一方院落，适合家庭和小型聚会。', '3 室 1 厅', 128.00, 6, 2280.00, '/assets/courtyard.svg') x
WHERE p.name = '栖野民宿'
AND NOT EXISTS (SELECT 1 FROM room_type rt WHERE rt.property_id = p.id AND rt.name = x.name);

INSERT INTO room (property_id, room_type_id, room_no, floor_no)
SELECT p.id, rt.id, x.room_no, x.floor_no
FROM property p
JOIN room_type rt ON rt.property_id = p.id
JOIN (SELECT '101' room_no, '1F' floor_no, '松风大床房' type_name UNION ALL SELECT '102', '1F', '松风大床房'
      UNION ALL SELECT '201', '2F', '云岚双床房' UNION ALL SELECT '202', '2F', '云岚双床房'
      UNION ALL SELECT 'A01', '独栋', '栖迟整栋小院') x ON x.type_name = rt.name
WHERE NOT EXISTS (SELECT 1 FROM room r WHERE r.property_id = p.id AND r.room_no = x.room_no);
