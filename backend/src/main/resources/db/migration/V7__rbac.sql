CREATE TABLE IF NOT EXISTS sys_role (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  code VARCHAR(32) NOT NULL UNIQUE,
  name VARCHAR(80) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_permission (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  code VARCHAR(64) NOT NULL UNIQUE,
  name VARCHAR(120) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_user_role (
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, role_id),
  CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
  CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES sys_role(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_role_permission (
  role_id BIGINT NOT NULL,
  permission_id BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (role_id, permission_id),
  CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES sys_role(id),
  CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES sys_permission(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO sys_role(code, name)
SELECT x.code, x.name FROM (
  SELECT 'CUSTOMER' code, '普通用户' name
  UNION ALL SELECT 'FRONT_DESK', '前台员工'
  UNION ALL SELECT 'OPERATION_ADMIN', '运营管理员'
  UNION ALL SELECT 'SYSTEM_ADMIN', '系统管理员'
) x WHERE NOT EXISTS (SELECT 1 FROM sys_role r WHERE r.code=x.code);

INSERT INTO sys_permission(code, name)
SELECT x.code, x.name FROM (
  SELECT 'reservation:create' code, '创建订单' name
  UNION ALL SELECT 'reservation:view:self', '查看本人订单'
  UNION ALL SELECT 'reservation:cancel:self', '取消本人订单'
  UNION ALL SELECT 'payment:simulate', '使用模拟支付'
  UNION ALL SELECT 'guest:self', '管理本人房客资料'
  UNION ALL SELECT 'review:create', '提交评价'
  UNION ALL SELECT 'room-type:view', '查看房型'
  UNION ALL SELECT 'room-type:manage', '管理房型'
  UNION ALL SELECT 'room:view', '查看实体房间'
  UNION ALL SELECT 'room:manage', '管理实体房间'
  UNION ALL SELECT 'reservation:manage', '管理订单'
  UNION ALL SELECT 'reservation:assign-room', '分配房间'
  UNION ALL SELECT 'reservation:check-in', '办理入住'
  UNION ALL SELECT 'reservation:change-room', '换房'
  UNION ALL SELECT 'reservation:check-out', '办理退房'
  UNION ALL SELECT 'payment:collect', '登记收款'
  UNION ALL SELECT 'guest:view', '查看房客资料'
  UNION ALL SELECT 'guest:view-sensitive', '查看敏感证件信息'
  UNION ALL SELECT 'review:moderate', '审核评价'
  UNION ALL SELECT 'report:view', '查看经营统计'
  UNION ALL SELECT 'audit:view', '查看审计日志'
  UNION ALL SELECT 'system:user', '管理系统用户'
) x WHERE NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.code=x.code);

INSERT IGNORE INTO sys_user_role(user_id, role_id)
SELECT u.id, r.id FROM sys_user u JOIN sys_role r ON r.code=u.role_code;

INSERT IGNORE INTO sys_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p
  ON r.code='SYSTEM_ADMIN'
  OR (r.code='CUSTOMER' AND p.code IN ('reservation:create','reservation:view:self','reservation:cancel:self','payment:simulate','guest:self','review:create'))
  OR (r.code='FRONT_DESK' AND p.code IN ('room-type:view','room:view','reservation:manage','reservation:assign-room','reservation:check-in','reservation:change-room','reservation:check-out','payment:collect','guest:view','guest:view-sensitive'))
  OR (r.code='OPERATION_ADMIN' AND p.code IN ('room-type:view','room-type:manage','room:view','room:manage','reservation:manage','reservation:assign-room','reservation:check-in','reservation:change-room','reservation:check-out','payment:collect','guest:view','guest:view-sensitive','review:moderate','report:view','audit:view'));
