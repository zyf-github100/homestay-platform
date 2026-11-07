# 民宿服务信息管理系统

面向单体/多门店民宿的预订与运营管理系统，覆盖 **用户预订端 → 前台接待 → 住中服务 → 房务保洁 → 结算售后** 的完整业务闭环。

> 当前状态：**工程初始化完成**，已完成详细需求规格说明书 v2.0 和用户端首页首屏，后端正在按模块实现。

---

## 文档

| 文档 | 说明 |
|---|---|
| [民宿服务信息管理系统-详细开发文档](民宿服务信息管理系统-详细开发文档.md) | v2.0 开发基线：Spring Boot + Vue + MySQL + Redis 的需求、数据库、API、页面、测试与验收标准 |

文档共 19 章，主要包含：

- 课程设计首期范围与明确边界
- 角色权限模型（RBAC）与权限矩阵
- 预订、支付、房间运营和入住退房状态
- MySQL 核心表、Redis 缓存/锁/幂等设计
- REST API 设计与前端路由规划
- 关键业务规则（库存冲突、价格、取消、入住退房、证件加密）
- 测试方案、验收标准（AC-01 ~ AC-27）、部署设计与里程碑

## 核心设计要点

1. **民宿 / 房型 / 实体房间三段拆分**，不把房源信息堆在一张表里。
2. **按入住日期计算房晚库存**，用 MySQL 唯一约束和 Redis 短时锁防止重复预订。
3. **订单状态、支付状态和实体房间运营状态分离**，互不覆盖。
4. **价格、住客、房型和取消规则在下单时快照**，后续改价不影响历史订单。
5. **用户端与管理端独立构建**，后端按业务模块组织，首期范围可控。
6. **身份证应用层加密**（AES-256-GCM + 摘要去重 + 脱敏展示 + 权限审计）。

## 规划技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 21、Spring Boot 3.x、MyBatis-Plus、Sa-Token、Flyway |
| 数据库 | MySQL 8 |
| 缓存与并发控制 | Redis 7 |
| 前端 | Vue 3 + TypeScript + Vite + Pinia，用户端 / 管理端分离 |
| UI | Element Plus（管理端）、移动优先组件（用户端） |
| 测试 | JUnit 5、Testcontainers、Vitest、Playwright |
| 部署 | Docker Compose + Nginx |

## 规划目录结构

```text
homestay-platform/
├─ backend/        # Spring Boot + Redis/MySQL 后端
├─ web-user/       # Vue 3 用户端（移动优先）
├─ web-admin/      # Vue 3 运营管理端（基础看板与房型列表）
├─ deploy/         # Docker Compose、MySQL、Redis
└─ docs/           # 设计文档与接口契约
```

## 本地启动

```bash
cd web-user
npm install
npm run dev
```

用户端默认地址为 `http://localhost:5173`。后端需要 Java 21 和 Maven；基础服务可以使用 `docker compose -f deploy/docker-compose.yml up` 启动 MySQL、Redis 和后端容器。

管理端在另一个终端启动：

```bash
cd web-admin
npm install
npm run dev
```

管理端默认地址为 `http://localhost:5174`。首次使用可先调用 `/api/v1/auth/register` 注册账号，再从管理端登录。

## 里程碑（详见文档第 22 章）

| 阶段 | 内容 |
|---|---|
| 0 | 需求与工程基线 |
| 1 | 账号与权限 |
| 2 | 民宿与房源 |
| 3 | 库存与预订 |
| 4 | 支付与前台业务 |
| 5 | 分房、入住、换房、退房与收款 |
| 6 | 房客资料、评价、审计与经营统计 |
| 7 | 测试、部署与验收 |

首个可运行切片：注册登录 → 建立房型和房间 → 查询库存 → 一单多房下单 → 模拟支付 → 分房入住 → 退房结算 → 评价 → 查看入住统计。

## 许可

暂未选定开源许可证，默认保留所有权利。如计划开放使用，请先补充 `LICENSE`。
