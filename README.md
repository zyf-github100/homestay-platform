# 民宿服务信息管理系统

面向单体/多门店民宿的预订与运营管理系统，覆盖 **用户预订端 → 前台接待 → 住中服务 → 房务保洁 → 结算售后 → 消息提醒** 的完整业务闭环。

> 当前状态：核心业务闭环、用户端、管理端和部署工程均已完成，后续以测试、部署和验收维护为主。

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
7. **消息中心**覆盖订单、支付、入住、退房和评价回复，支持分页、未读数和全部已读。
8. **经营统计**按有效附加费用、折扣、退款和冲正计算净收入与待收金额。

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
├─ web-admin/      # Vue 3 运营管理端（房源、房态、订单、评价、审计与 RBAC）
├─ deploy/         # Docker Compose、MySQL、Redis
└─ docs/           # 设计文档与接口契约
```

## 本地开发与部署

复制开发环境变量并启动完整 Compose 环境：

```bash
cp .env.example .env
PROXY_HOST_IP="$(getent hosts proxy 2>/dev/null | awk '{print $1}')" \
  docker compose --env-file .env -f deploy/docker-compose.yml up -d --build --wait
```

网关默认监听 `http://localhost:8088`：

- 用户端：`http://localhost:8088/`
- 管理端：`http://localhost:8088/admin/`
- 后端 API：`http://localhost:8088/api/v1/`
- 健康检查：`http://localhost:8088/health`

用户端和管理端生产构建使用同源 `/api/v1`，由 Nginx 将 `/api/` 反向代理到后端，不依赖浏览器所在机器的 `localhost`。Vite 开发服务器仍可用 `VITE_DEV_API_PROXY_TARGET` 指向本地后端。

Compose 要求数据库密码、Redis 密码和 `GUEST_ID_ENCRYPTION_KEY` 通过环境变量提供；`.env.example` 中的值只用于开发，不能带入生产。生产环境应设置 `APP_ENV=production`、`SEED_ADMIN_ENABLED=false`，并使用独立的 32 字节身份证加密密钥。开发管理员只有在 `SEED_ADMIN_ENABLED=true` 且显式提供 `ADMIN_INITIAL_PASSWORD` 时才会创建。

停止并清理容器：

```bash
docker compose --env-file .env -f deploy/docker-compose.yml down
```

本地单独启动前端仍可使用 `npm ci && npm run dev`；此时 `/api` 会通过 Vite 代理转发到 `VITE_DEV_API_PROXY_TARGET`（默认 `http://localhost:8080`）。

## CI

`.github/workflows/ci.yml` 在干净的 GitHub Actions runner 中执行：

1. 后端 `mvn test`；
2. 用户端和管理端 `npm ci && npm run build`；
3. 使用随机测试密码和加密密钥启动完整 Compose；
4. 运行 API smoke、运营接口 smoke；
5. 安装 Chromium 后运行 Playwright E2E。

任一 job 或集成检查失败都会阻断工作流。CI 只生成临时凭据，不提交真实密钥。

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
