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

### 并发预约锁：Redis 与数据库双层

同一设备同一时段的重复预约，由两层机制共同保证，**分布式锁不是唯一防线**：

```text
ReservationApplicationService
└─ reservationLock.execute(equipmentId, ...)        ← 第一层：Redis SET NX PX，租约 10s
   └─ ReservationService.create()   @Transactional  ← 锁在事务之外
      ├─ equipmentRepository.findByIdForUpdate()    ← 第二层：设备行 PESSIMISTIC_WRITE
      ├─ assertNoConflict()                         ← 区间重叠判定 start < reqEnd && end > reqStart
      └─ save()
```

**为什么锁加在事务外层**：若把加锁放进 `@Transactional` 方法内部，锁会在事务提交**之前**释放，中间窗口里另一个请求能拿到锁却读不到尚未提交的那条预约，冲突校验直接失效。锁的范围必须完整覆盖事务边界。

**锁实现的三个细节**（`RedisReservationLock`）：

- 获取用 `setIfAbsent(key, uuidToken, leaseTime)`，即 `SET NX PX`，一次往返完成"抢占 + 设置租约"，不存在先 SET 后 EXPIRE 之间宕机导致的永久死锁
- 释放走 **Lua 脚本先比对 token 再 DEL**，保证不会删掉其他请求的锁（自己的锁若已因租约过期被顶替，此时删除就是误删）
- Redis 不可用时抛 `REDIS_UNAVAILABLE` **503 快速失败**，不静默降级为无锁执行

**为什么没有做锁续期（watchdog）**：临界区是一次行锁查询 + 一次区间冲突查询 + 一次 insert 的纯本地数据库短事务，实际耗时远小于 10 秒租约。更关键的是——**即使租约提前过期，也不会产生重复预约**：`findByIdForUpdate` 对设备行持有悲观写锁，同一设备的并发请求在数据库层依然串行，冲突校验不会被穿透。Redis 锁在这里的职责是**把冲突拦在事务之前**，减少无谓的行锁排队与事务回滚，而不是充当正确性的最后一道防线。为一个不承担正确性的锁引入后台续期线程、线程生命周期绑定和续期失败处理，复杂度并不划算。

**这个取舍在什么情况下失效**（值得写下来的边界）：如果临界区里加入外部调用（推送通知、对接校方审批系统）使耗时逼近租约，或者去掉设备行锁改用乐观并发控制，那么租约过期就会真正导致两个请求同时进入临界区，此时**必须**补上续期机制——而那时正确的做法是换成 Redisson，不是自己手写续期线程。

**为什么当前不用 Redisson**：Redisson 的核心增量是 watchdog 自动续期、可重入锁和 RedLock，如上所述，本场景三者都用不上，引入它等于为用不到的能力多背一个重依赖。而 `spring-boot-starter-data-redis` 已在依赖里，三十行代码就能把"原子获取 / 租约兜底 / 防误删释放"三个关键点显式写出来。另外**刻意不做 RedLock**：本项目部署的是单 Redis 实例，RedLock 的多独立节点前提根本不成立，强行套用只会得到虚假的安全感。

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
| Access Token | JWT（HS256） | 15 分钟 | 仅保存在前端内存，放入 `Authorization: Bearer …`，内含 username/role |
| Refresh Token | 不透明随机串 | 7 天 | 浏览器仅持有 HttpOnly Cookie；数据库**仅存 SHA-256 哈希**，可撤销 |

接口：

- `POST /api/auth/login` `{username,password}` → access + 用户信息，并设置 Refresh Cookie
- `POST /api/auth/refresh`（无请求体）→ 从 Cookie 读取并轮换 Refresh Token，响应新的 access + 用户信息
- `POST /api/auth/logout`（无请求体）→ 撤销 Refresh Token 并清除 Cookie

密码仍为 **BCrypt**。后端 `JwtAuthenticationFilter` 解析 Access Token 后填入 SecurityContext，**现有 RBAC 与数据隔离规则不变**。  
Refresh Cookie 固定使用 `HttpOnly`、`SameSite=Lax`，后端路径为 `/api/auth`，前端代理会为浏览器重写为 `/api/backend/api/auth`；production 默认开启 `Secure`，仅本机 HTTP 验证脚本会临时覆盖为 `false`。Cookie `Max-Age` 与 `JWT_REFRESH_TTL` 一致。前端只在内存中保存 Access Token，请求自动带 Bearer；Access 过期时由 Cookie 静默刷新，刷新失败会清理登录态并回到登录页。
刷新与退出会在事务内用 `PESSIMISTIC_WRITE` 锁住旧 Refresh Token，确保同一个 Token 并发轮换时只允许一次成功。

演示账号仍为 student / teacher / technician / admin（密码见下表）。

配置项：`labops.jwt.secret`、`labops.jwt.access-token-ttl`、`labops.jwt.refresh-token-ttl`、`labops.jwt.refresh-cookie-secure`。
**production 禁止代码内默认密钥**：必须在 `.env` 设置 `JWT_SECRET`（≥32 字节、非占位值），否则启动失败。`.env.example` 仅保留安全占位说明，不提交真实密钥。

## 一键运行（H2 演示模式，默认）

本机需要 JDK 21+（本机以 JDK 25 验证，编译目标 Java 21）和 Node.js 22+：

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

PowerShell 入口按 `-JavaPath` → `JAVA_HOME` → `PATH` 查找 JDK，不依赖本机安装目录。例如：

```powershell
.\build.ps1 -JavaPath "D:\Java\jdk-21"
.\start-fullstack.ps1 -JavaPath "D:\Java\jdk-21"
.\scripts\start-production.ps1 -JavaPath "D:\Java\jdk-21"
```

解析器会实际运行同一 JDK `bin` 目录下的 `java -version` 与 `javac -version`，要求两者主版本一致且不低于 21。可单独探测当前选择结果：

```powershell
. .\scripts\resolve-java.ps1
Resolve-LabFlowJava | Format-List Executable, JavaHome, MajorVersion
```

`verify-production.ps1` 默认自行启动后端时，会通过命令行参数仅为该次本机 HTTP 验证关闭 Refresh Cookie 的 `Secure`；它不会修改 `.env`，production 的默认值仍为 `true`。若使用 `-SkipStart`，已有后端必须由下面的显式本地验证开关启动，否则脚本会拒绝在 HTTP 上误测 Secure Cookie：

```powershell
.\scripts\start-production.ps1 -AllowInsecureRefreshCookieForLocalHttp
.\scripts\verify-production.ps1 -SkipStart
```

### 1. 准备密钥

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
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

上述中间件端口只绑定宿主机 `127.0.0.1`，不会直接暴露到局域网。

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
