# LabFlow 实验室设备预约与故障工单平台

面向高校与研发实验室的设备运营协同平台。项目围绕“设备能否预约、谁来审批、发生故障后如何闭环”三条主线，将设备台账、预约排期、维修工单、角色权限、运营统计和审计日志整合为一套可运行的业务系统。

## 项目亮点

- 使用 Redis 分布式锁与数据库冲突校验，解决同一设备同一时段的并发重复预约问题
- 使用 RabbitMQ **每预约独立延迟队列 + 队列级 TTL + 死信** 处理待审批超时（无插件、无共享 FIFO 队头阻塞），并以 DB 补偿扫描兜底；本地环境提供无中间件降级实现
- 设计预约和故障工单状态机，限制非法状态跳转，并联动设备“可预约/维护中”状态
- 基于 Spring Security 和数据库用户实现学生、教师、维修员、管理员四级权限
- 服务端从认证上下文确定申请人和报修人，避免客户端伪造身份字段
- 记录关键业务操作审计日志，支持分页筛选与运营统计
- 提供完整 React 管理端、真实业务种子数据和一键启动脚本，可直接进行招聘或课程演示
- 提供未来 7 日设备排期视图，并针对手机端使用固定底部业务导航

## 典型业务流程

### 设备预约

学生选择设备与使用时段提交预约 → 系统进行并发锁和时间冲突校验 → 教师审批 → 到期未审批自动失效 → 使用结束后完成预约。

### 故障处理

用户提交故障 → 设备自动进入维护状态 → 管理员从维修员目录中派单 → 维修员开始处理 → 标记解决 → 关闭工单后设备恢复可预约。

## 技术栈

后端：Java 21、Spring Boot 3.5、Spring Security、Spring Data JPA、Flyway、H2/MySQL、Redis、RabbitMQ。

前端：React 19、TypeScript、Vinext/Vite，采用石墨与暖橙的实验室工业控制台视觉语言。

## 架构与工程设计

```text
frontend/app
├─ components          登录、导航、通用组件与五个业务视图
├─ lib                 API、类型、状态文案与日期工具
└─ page.tsx            页面状态和业务动作编排

src/main/java/com/arthur/labops
├─ auth                JWT 登录 / 刷新 / 退出与过滤器
├─ equipment           设备档案与状态
├─ reservation         预约、并发锁与自动过期
├─ workorder           故障工单状态机
├─ user                认证用户与维修员目录
├─ audit               操作审计
└─ dashboard           运营统计
```

本地环境使用 H2、本地锁和本地定时器，做到零外部依赖启动；生产 Profile 切换至 MySQL、Redis 与 RabbitMQ。正式环境会关闭体验账号和种子数据。

### 预约过期延迟任务（RabbitMQ）

**问题**：单条共享 FIFO 延迟队列 + 逐消息 `expiration` 时，队头长 TTL（如 15 分钟）会挡住后面的短 TTL（如 12 秒），短消息即使到期也不会进入死信。

**当前方案**（标准 RabbitMQ，**不依赖** delayed-message 插件，适配本仓库 Docker 镜像）：

1. 每条待审批预约声明**私有延迟队列** `labops.reservation.expiry.delay.{reservationId}.{expiresAtEpochMs}`  
2. 队列级 `x-message-ttl` = 剩余延迟；`x-dead-letter-exchange` 指向统一过期工作交换机  
3. 到期后消息进入 `labops.reservation.expiry.queue`，由监听器执行过期  
4. 私有队列带 `x-expires`，空闲后自动清理，避免队列泄漏  
5. **DB 补偿扫描**（`ReservationExpiryCompensationJob`）始终开启，消息丢失或 broker 抖动时仍能过期  

旧版共享延迟队列 `labops.reservation.expiry.delay.queue` 在 production 启动时由 `LegacySharedDelayQueueCleanup` 删除（不再接收新消息）。

## 认证（JWT，面试友好方案）

已从 HTTP Basic 升级为 **Access Token + 可撤销 Refresh Token**，故意不做 OAuth2/SSO，方便讲清取舍：

| 令牌 | 形态 | 有效期（默认） | 说明 |
| --- | --- | --- | --- |
| Access Token | JWT（HS256） | 15 分钟 | 放在 `Authorization: Bearer …`，内含 username/role |
| Refresh Token | 不透明随机串 | 7 天 | **仅存 SHA-256 哈希**于表 `refresh_tokens`，可撤销 |

接口：

- `POST /api/auth/login` `{username,password}` → access + refresh + 用户信息  
- `POST /api/auth/refresh` `{refreshToken}` → 轮换签发新一对令牌（旧 refresh 作废）  
- `POST /api/auth/logout` `{refreshToken}` → 撤销 refresh  

密码仍为 **BCrypt**。后端 `JwtAuthenticationFilter` 解析 Access Token 后填入 SecurityContext，**现有 RBAC 与数据隔离规则不变**。  
前端登录后保存 token，请求自动带 Bearer；Access 过期时用 Refresh 静默刷新，并通过 `subscribeTokens` **同步 React 内存中的 accessToken**（避免只更新 sessionStorage 导致后续请求仍带旧 token）；刷新失败会清理登录态并回到登录页。

演示账号仍为 student / teacher / technician / admin（密码见下表）。

配置项：`labops.jwt.secret`、`labops.jwt.access-token-ttl`、`labops.jwt.refresh-token-ttl`。  
**production 禁止代码内默认密钥**：必须在 `.env` 设置 `JWT_SECRET`（≥32 字节、非占位值），否则启动失败。`.env.example` 仅保留安全占位说明，不提交真实密钥。

## 一键运行（H2 演示模式，默认）

本机需要 JDK 25（编译目标 Java 21）和 Node.js 22+：

```powershell
.\start-fullstack.ps1
```

访问 `http://localhost:13000`。停止服务：

```powershell
.\stop-fullstack.ps1
```

此模式**不依赖** Docker / MySQL / Redis / RabbitMQ，使用：

- H2 内存库 + Flyway
- `labops.reservation-lock.mode=local`
- `labops.reservation-expiry.mode=local`

本地体验角色：

| 角色 | 用户名 | 密码 | 可演示功能 |
| --- | --- | --- | --- |
| 学生 | `student` | `student123` | 设备查询、预约、取消、故障上报 |
| 教师 | `teacher` | `teacher123` | 预约审批、设备建档 |
| 维修员 | `technician` | `tech123` | 接单、处理、解决工单 |
| 管理员 | `admin` | `admin123` | 全局管理、维修员派单、审计日志 |

启动后自动生成 8 台设备、4 条不同状态预约、3 条不同处理阶段工单和审计记录，便于完整演示。

## Docker Compose 本地生产环境（MySQL + Redis + RabbitMQ）

用于验证 **production profile**：真实中间件、Flyway 落库 MySQL、预约并发走 Redis 锁、预约过期走 RabbitMQ 延迟队列。

### 前置条件

- Docker Desktop 或 Docker Engine + Compose v2
- JDK 21+（推荐本机已有的 JDK 25）
- 已安装 PowerShell

### 1. 准备密钥

```powershell
Copy-Item .env.example .env
# 按需修改 .env 中的密码；.env 已加入 .gitignore，不要提交真实密码
```

### 2. 启动中间件

```powershell
.\scripts\start-middleware.ps1
```

将拉起：

| 服务 | 容器名 | 默认端口 |
| --- | --- | --- |
| MySQL 8.4 | `labflow-mysql` | `3306` |
| Redis 7.4 | `labflow-redis` | `6379` |
| RabbitMQ 3.13 | `labflow-rabbitmq` | `5672` / 管理台 `15672` |

若本机没有 Windows Docker Desktop，脚本会自动走 **WSL Ubuntu Docker Engine**，并创建隐藏的 **WSL keepalive** 进程（PID 写入 `.runtime/wsl-keepalive.pid`），防止 Ubuntu 空闲自动退出导致容器掉线。重复执行是幂等的，不会叠多个 keepalive。

### 3. 启动 production 后端

```powershell
.\scripts\start-production.ps1
```

等价于：

- `spring.profiles.active=production`
- 数据源 / Redis / RabbitMQ 全部从 `.env` 注入
- `labops.reservation-lock.mode=redis`
- `labops.reservation-expiry.mode=rabbit`

健康检查：`http://127.0.0.1:18080/actuator/health`  

```powershell
.\scripts\status-production.ps1      # 后端进程 / WSL keepalive / 三容器 / health
.\scripts\stop-production.ps1        # 停后端
.\scripts\stop-middleware.ps1        # compose down + 停 keepalive（-Volumes 清空数据卷）
.\scripts\stop-production.ps1 -AlsoMiddleware  # 一并停中间件
```

### 4. 一键集成验证（推荐）

```powershell
.\scripts\verify-production.ps1
```

脚本会：

1. 启动中间件 + production 后端（可用 `-SkipStart` 跳过）
2. 校验 actuator health 中 **db / redis / rabbit 均为 UP**
3. 在 MySQL 查询 `flyway_schema_history`，确认 V1–V6 迁移成功
4. 并发提交两条重叠预约，期望 `201 + 409`，并在后端日志中出现 `Redis reservation lock acquired`
5. 创建预约后确认日志出现 `RabbitMQ expiry scheduled`，并检查 Rabbit 队列 `labops.reservation.expiry*`

验证报告输出到 `.verify-logs/verify-*.log`。

### 生产 Profile 与默认 Profile 对比

| 项 | 默认（H2 演示） | production（Docker） |
| --- | --- | --- |
| 数据库 | H2 mem | MySQL |
| 预约锁 | local | Redis |
| 预约过期 | local scheduler | RabbitMQ TTL + DLX |
| 体验账号/种子 | 默认开启 | 由 `.env` 的 `LABOPS_DEMO_USERS/DATA` 控制 |

## 自动化验证

```powershell
.\build.ps1
# 等价语义：mvnw package → 跑测试并产出 target/lab-equipment-platform-*.jar
cd frontend
npm test
npm run build
```

`.\build.ps1` **必须产出可运行 JAR**（不只跑 test）。  
`scripts\start-production.ps1 -SkipBuild` 会校验 JAR 是否存在，且 **不早于** `src/main/java`、`src/main/resources`、Flyway migration；过期则明确拒绝启动。

后端集成测试覆盖权限、JWT 密钥约束、身份归属、并发预约、自动过期、预约生命周期和工单状态机；前端覆盖角色导航与 Token 同步契约。

Docker 生产栈额外验证（含 Flyway V7、JWT 登录/刷新轮换/旧 Refresh 401）：

```powershell
.\scripts\verify-production.ps1
```

## 面试介绍话术

> 我做的是一套实验室设备预约和故障工单平台。核心难点不是普通 CRUD，而是预约并发冲突、超时审批、工单状态机和多角色数据权限。我用 Redis 锁配合数据库时间区间校验避免重复预约，用 RabbitMQ 死信队列实现超时任务，并提供本地降级实现保证项目可以零依赖演示。故障工单和设备状态会联动，所有关键动作都会写入审计日志。前端则按业务域拆分组件，提供完整的设备档案、预约审批和维修派单体验。

## 本轮已落地的业务增强

- 预约时间合法性：禁止过去时段，限制单次最长 12 小时
- 审批时二次冲突校验，过期后拒绝审批
- 预约过期 DB 补偿扫描（不依赖消息中间件也能兜底）
- 设备状态联动：`AVAILABLE` / `IN_USE` / `MAINTENANCE` / `RETIRED`
- 报修自动取消该设备未完成预约，并进入维护态
- 派单必须指定真实维修员角色
- 设备退役 / 恢复接口；前端按角色展示不同运营面板文案与指标
- JWT 登录 / 刷新 / 退出；Refresh Token 落库可撤销；四角色首页与导航差异

## 可继续扩展

- 接入学校统一身份认证和组织架构
- 增加设备图片、附件、二维码和使用培训资质
- 使用日历视图展示设备排期
- 增加消息通知、维修 SLA 与统计报表导出
