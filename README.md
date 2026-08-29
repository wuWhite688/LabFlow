# LabFlow 实验室设备预约与故障工单平台

[![CI](https://github.com/wuWhite688/LabFlow/actions/workflows/ci.yml/badge.svg)](https://github.com/wuWhite688/LabFlow/actions/workflows/ci.yml)

面向高校实验室的设备运营系统：管台账、管预约、管报修。学生提交预约，教师审批，维修员处理故障，管理员看全局与审计。适合作为可演示的后端工程项目。

## 界面预览

> 以下截图全部来自本机真实运行的服务，走的是默认 H2 profile（`.\start-fullstack.ps1`，零外部中间件），
> 数据是启动时写入的演示种子：8 台设备、4 条预约、3 条工单与对应审计记录。

**运营总览（管理员）**：设备在册数、待审批、开放工单与设备健康度四项指标，右侧汇聚异常与审计入口。

![运营总览](docs/screenshots/02-dashboard-admin.png)

**同一套 app shell，导航按角色收窄**。学生登录后只剩四个入口（没有「操作审计」），标题与文案也随角色切换，列表只展示与本人相关的预约和报修——权限不是靠前端藏按钮，服务端的申请人一律取登录用户。

![学生工作台](docs/screenshots/07-dashboard-student.png)

**操作审计**：谁、在什么角色下、对哪个对象做了什么，全部留痕，仅管理员可见。

![操作审计](docs/screenshots/06-audit.png)

<details>
<summary>登录页与其余业务页面</summary>

![登录页](docs/screenshots/01-login.png)

![设备中心](docs/screenshots/03-equipment.png)

![预约管理](docs/screenshots/04-reservations.png)

![故障工单](docs/screenshots/05-workorders.png)

![维修员工单视图](docs/screenshots/08-workorders-technician.png)

</details>

## 能跑通的主流程

**预约（免费设备）：** 选设备与时段 → 进入 `PENDING` → 教师批准为 `APPROVED` → 超时未批自动 `EXPIRED` → 用完后 `COMPLETED`。重叠的待审批可以同时存在；真正占住时段的只有已批准预约。

**预约（计价设备，`hourlyPriceCents > 0`）：** 批准后不是 `APPROVED` 而是 `AWAITING_PAYMENT`——占住时段，但只给一个很短的支付窗口；付了变 `PAID`，窗口内没付就关单、时段归还。取消已支付的预约先进 `REFUNDING`，等退款回调到账才落 `CANCELLED`。

**报修：** 提交工单 → 有权限的报修会取消冲突预约并让设备进入维护 → 管理员派单或维修员接单 → 处理、解决、关闭后设备恢复可预约。

**角色：** 学生 / 教师 / 维修员 / 管理员。申请人、报修人一律取服务端登录用户，不接受前端伪造。

## 技术栈

后端：Java 21、Spring Boot 3.5、Spring Security、JPA、Flyway。默认 H2；production 用 MySQL + Redis + RabbitMQ。

前端：React 19、TypeScript、Vinext/Vite。访问入口 `http://localhost:13000`（代理到后端 `18080`）。

## 预约并发

创建预约时锁在事务外面，避免锁先释放、事务后提交：

```text
ReservationApplicationService.create
└─ executeUser(userId)                 用户应用锁（配额）
   └─ execute(equipmentId)             设备应用锁
      └─ @Transactional ReservationService.create
         ├─ PlatformUser FOR UPDATE
         ├─ 统计 PENDING+APPROVED 是否超配额
         ├─ Equipment FOR UPDATE
         ├─ 时段冲突：只与 APPROVED 比较
         └─ insert
```

本地默认 `labops.reservation-lock.mode=local`（进程内公平锁）。production 切 Redis：`SET NX PX` 抢锁，Lua 比对 token 再 `DEL`，Redis 挂了直接 503，不降级成无锁。

状态变更（审批 / 取消 / 完成 / 过期 / 报修联动）固定 **Equipment → Reservation** 行锁，避免和创建路径形成 ABBA。创建路径是 **User → Equipment**，全项目没有反过来先锁设备再锁用户。

设备行锁是正确性底线：即使 Redis 租约（默认 10s）先过期，同一设备的写入仍在数据库串行。Redis 锁主要用来把冲突挡在事务前，减少排队。临界区没有外部 IO，所以没有做锁续期，也没有引入 Redisson。

## 预约语义

| 规则 | 行为 |
| --- | --- |
| 创建 | 多条时间重叠的 `PENDING` 可以并存 |
| 配额 | 所有未关闭状态计入上限（默认 20）：`PENDING`、`APPROVED`、`AWAITING_PAYMENT`、`PAID`、`REFUNDING` |
| 占时段 | `APPROVED`、`AWAITING_PAYMENT`、`PAID` 占用设备日历；`REFUNDING` **不占**——用户已经取消了，不该让别人为渠道的退款延迟买单 |
| 设备「使用中」 | 只有 `APPROVED` 和 `PAID` 会把设备推成 `IN_USE`；`AWAITING_PAYMENT` 占时段但不算在用 |
| 支付窗口 | `min(now + labops.payment.window, startTime)`，默认 10 分钟。远短于审批时限，因为它是真的把时段扣住了；也**绝不越过预约开始时间**——两分钟后就开始的预约不该拿到十分钟支付窗口 |
| 计价 | 按小时定价，**按开始的每一分钟**计费并向上取整到分。预约没有最短时长，所以截断会让 59 秒的预约算出 0 元、然后被当成免费设备直接放行 |
| 审批 | 已过审批截止时间或预约已结束时返回 409；已有重叠 `APPROVED` 时也返回 409；并发审批最多一条成功 |
| 其它 | 开始时间必须在未来；单次最长 12 小时；最多提前 30 天 |

压测数据在 [`benchmark/RESULTS.md`](benchmark/RESULTS.md)。那组「同一时段只成功一条」是当时创建也把 `PENDING` 当冲突时的结果；现在冲突发生在审批，不是创建。

## 超时过期（RabbitMQ）

production：`labops.reservation-expiry.mode=rabbit`。

1. 审批截止时间取「创建时间 + 审批时限」与预约结束时间的较早者；每条待审批预约一条私有 delay queue：`labops.reservation.expiry.delay.{id}.{expiresAtMs}`
2. 队列 TTL + DLX，到期进入共享工作队列
3. 监听器执行过期；非法 payload（如 `not-a-number`）记 warning 后 ack 丢掉，不重投
4. 空闲队列用 `x-expires` 自动删
5. `ReservationExpiryCompensationJob` 始终扫库兜底

**支付窗口复用同一套延时设施**，但用独立的队列命名空间 `labops.reservation.payment.delay.{id}.{deadlineMs}`，消息体带 `PAYMENT:` 前缀。审批截止时间仍然是裸数字，所以上一版留在 broker 里的消息升级后照样能解析。

**截止时间会被主动撤销。** 以前预约一旦被审批或取消，那条延时任务照样挂到原定时刻——触发时状态守卫会挡住，无害，但队列（或本地 scheduler 的任务表）是按「历史上所有预约」增长而不是按「还开着的预约」增长的，加上支付窗口会让这个面直接翻倍。现在预约离开 `PENDING` / `AWAITING_PAYMENT` 时会发 disarm 事件，Rabbit 侧删掉私有队列，本地侧 cancel 掉 future。

> `x-expires` 帮不上这个忙：它只回收**已经空掉**的队列，而待撤销的队列里正压着那条没投递的消息。

撤销是尽力而为的清理，不是正确性手段——触发路径上的状态守卫仍然是让迟到截止时间无害的那道防线。

本地默认 `expiry.mode=local`，不需要 broker。旧的共享 FIFO delay queue 启动时会清掉。真 broker 上的 HOL 验证见 `RabbitReservationExpiryOrderingIntegrationTest`（本机 `5672` 不通则跳过）。

## 支付与对账

钱这条链路全部在仓库内闭环，**不接任何真实支付渠道或沙箱**。`SimulatedPaymentChannel` 是一个同仓的假网关：自己记账、自己决定什么时候回调、能按天导出 T+1 账单文件。

### 为什么模拟渠道要这样写

真实集成里三件事是不受控的，这里都做成了参数，所以任何场景都能确定性重放：

| 不确定性 | 这里的开关 |
| --- | --- |
| 回调时机 | `labops.payment.channel.callback-mode`：`IMMEDIATE` 同线程投递 / `MANUAL` 攒着等 `deliverPending()` / `DELAYED` 按 `callback-delay` 走调度器 |
| 重复投递 | `redeliverAll()`——把已投递过的回调原样再发一遍 |
| 投递丢失 | `discardPending()`——渠道账上留着这笔，本地永远收不到 |

渠道流水号是单调序列不是随机数，也是为了可重放。`reset()` 会清账但**不回退序列号**：真实网关不会重发同一个交易号，回退会让新场景拿到本地流水已经存在的号，然后被幂等检查完全正确地、也非常费解地当成重放。

### 幂等：两层

```text
回调进来 → 查 idempotency_key 有没有  ──有──→ 原样吞掉，什么都不动
                    │无
                    ↓
              插入流水（唯一索引兜底）  ──违反──→ 整个事务回滚，PaymentCallbackIngest 在事务外面接住，回渠道「已记录」
```

查一次就够了吗？不够。**两条同一笔回调同时到达时，两边都会先读到「没见过」再各自写入**——`uk_payment_transaction_idempotency` 才是真正拦住第二条的东西。`PaymentCallbackIngest` 故意放在事务外：被标记 rollback-only 的事务在里面是劝不回来的。

### 出站：幂等键 + 持久化 + 补偿重试

回调幂等只管**进来**的方向。出去的方向有两个独立的坑，而且都能把钱搞错到对账查不出来：

**重复发起支付。** 从请求渠道扣款到回调回来之间，订单还是 `AWAITING_PAYMENT`，第二次点支付会通过同样的状态检查，造出**第二笔真实渠道交易**。两笔的交易号不同，所以回调幂等根本没机会匹配；更糟的是渠道收了两次、本地也记了两次，**对账会报"完全平账"**。

**请求根本没发出去。** 取消已支付预约的事务已经提交、预约停在 `REFUNDING`，如果这时渠道调用失败而只是 log 一下，它就永远卡在那里。「对账会发现」在这里**不成立**：请求没到渠道，所以渠道没有退款、本地也没有退款流水，双方账目一致；换一天对账，这张订单甚至进不了订单号并集。

两个坑是同一件事的两半，所以修法也是一件事——`payment_requests` 表：

| 机制 | 作用 |
| --- | --- |
| 稳定幂等键（`LF…:PAY` / `:REF:CANCEL` / `:REF:LATE`） | 命名的是**意图**不是尝试，所以重试是重试，不是第二笔 |
| `uk_payment_request_idempotency` 唯一索引 | 重复发起在**入队时**就被挡掉，还没碰到渠道 |
| 渠道认商户幂等键 | 万一还是重复发了，渠道返回原交易而不是新建一笔 |
| `PaymentRequestRetryJob` 定时补偿 + 指数退避 | 发不出去的请求会一直重试，而不是无声丢失 |
| 重试耗尽 → 差账工单 | 真钱卡住时交给人，而不是又一行没人看的日志 |

> 键必须同时覆盖退款。只给支付加键、然后给退款加重试，等于把「重复扣款」换成「重复退款」。

首次投递走线程池而不是在 `AFTER_COMMIT` 里同步跑，有两个原因：出站调用不该占着请求线程；以及 **`AFTER_COMMIT` 阶段线程上还绑着刚结束的 EntityManager**，在那里开新事务会复用它，等渠道同步回调进来写库时就炸 `no transaction is known to be in progress`。线程池线程是干净的。

### 支付晚于窗口到账

窗口关掉预约、时段归还之后，钱才到。预约**保持关闭**（时段可能已经被别人拿走，复活它会双重占用），但这笔钱是真的：记流水、订单转 `REFUND_DUE`（收了但欠退），并发起一笔幂等的补偿退款。

这一条也不能指望对账——渠道收了、本地记了，两边一致，账面完美而用户的钱没了。

### 对账：比什么、比谁

`ReconciliationService` 每天（默认 UTC 01:30）读前一天的渠道账单，和本地流水按订单号逐笔比。两个决定撑起整个类：

- **比「本地流水实际记了多少」，不是「订单应收多少」。** 这两个数只在「没出错且没退款」时相等——也就是恰好在对账没事可做的时候。拿应收去比，每一笔正常的部分退款都会报警，而最该抓的那类（渠道成功、本地没落库）反而静悄悄：那时候应收和渠道正好一致，缺的是流水本身。
- **比双方订单号的并集。** 只遍历本地看不见渠道独有的条目，只遍历账单看不见本地记了而渠道没结的钱。

差异分四类：`MISSING_LOCALLY`、`MISSING_IN_CHANNEL`、`AMOUNT_MISMATCH`、`STATUS_MISMATCH`（金额对得上但本地订单状态还停在未结）。

### 差账工单

复用现有 `fault_work_orders` 表，加 `category` 区分。两个刻意的选择：

- 差账工单**从不置 `equipment_taken_offline`**——账目对不平跟设备好不好用没关系，把设备推成维护会为了一个财务问题去取消一堆无辜预约。
- `discrepancy_key`（账期 + 订单号 + 差异类型）带唯一索引。对账任务本来就该能重跑，重跑不该堆出第二张单。
- 报修去重的那道 `ACTIVE_WORK_ORDER_EXISTS` 现在只看 `category = FAULT`，否则一张差账工单会挡住别人报「这机器坏了」。

### 锁顺序

回调路径按 **Equipment → Reservation → PaymentOrder** 加锁——把支付订单**追加**在现有顺序末尾，而不是先锁它。回调和取消预约是从系统两头去碰同样三行；这里先锁订单行就会把预约路径已经修掉的那个 ABBA 环重新引回来。

### 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/payments/callback` | 渠道回调。Spring Security 放行（网关没有平台账号），端点自己校 `X-Channel-Token`，代替真实集成里的验签 |
| `POST` | `/api/payments/orders/{orderNo}/pay` | 模拟「用户在渠道 App 里付了」。平台自己不动钱，只是请渠道动，然后照常等回调 |
| `GET` | `/api/payments/orders/{orderNo}` | 订单详情 |
| `POST` | `/api/reconciliation/run?settlementDate=YYYY-MM-DD` | 管理员手动重跑对账 |

## 认证与安全默认

- Access JWT（HS256，默认 15 分钟）只放前端内存；Refresh 是不透明随机串，HttpOnly Cookie，库里只存 SHA-256，事务内 `FOR UPDATE` 轮换
- 过滤器每次查库：用户删除/停用立即 401；权限用数据库角色，JWT 里的 role 不算数
- 登录限流：IP+用户名 与 IP 总量（默认 5 / 20 / 15 分钟）；BFF 优先取 Cloudflare/Vercel 的边缘地址并覆盖为 `X-BFF-Client-IP`，后端只在 loopback/私有服务网来源上信任它，所以后端仍须保持内网可达
- 默认 `server.address=127.0.0.1`；非 loopback 禁止 demo 账号/种子，并拒绝占位 JWT
- 分页 `size` 上限 100
- production 必须提供 `JWT_SECRET`（≥32 字节、非 placeholder），demo 默认关闭

接口：`POST /api/auth/login`、`/refresh`、`/logout`。密码 BCrypt。Refresh Cookie：`HttpOnly`、`SameSite=Lax`、路径 `/api/auth`（前端代理成 `/api/backend/api/auth`）；production 默认 `Secure`。

## 一键演示（H2，零中间件）

需要 JDK 21+、Node.js 22+。

```powershell
.\start-fullstack.ps1
# 浏览器 http://localhost:13000
.\stop-fullstack.ps1
```

| 角色 | 用户名 | 密码 |
| --- | --- | --- |
| 学生 | `student` | `student123` |
| 教师 | `teacher` | `teacher123` |
| 维修员 | `technician` | `tech123` |
| 管理员 | `admin` | `admin123` |

启动后写入 8 台设备、4 条预约、3 条工单和审计，便于演示。绑定 loopback，不要把这个密钥和体验账号暴露到公网。

## 本地 production（MySQL + Redis + RabbitMQ）

中间件端口只绑 `127.0.0.1`。

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }  # 填入 JWT_SECRET，不要提交 .env
.\scripts\start-middleware.ps1
.\scripts\start-production.ps1
# health: http://127.0.0.1:18080/actuator/health
.\scripts\verify-local.ps1
.\scripts\stop-production.ps1 -AlsoMiddleware
```

`verify-local.ps1` 会起栈、查 health（db/redis/rabbit）、确认 Flyway、打 Redis 锁日志和 Rabbit 过期调度日志。本机 HTTP 验证会临时关掉 Refresh Cookie 的 `Secure`，不改 `.env`。

没有 Docker Desktop 时，脚本会走 WSL Ubuntu Docker，并写 `.runtime/wsl-keepalive.pid` 防止发行版休眠。

JDK 解析顺序：`-JavaPath` → `JAVA_HOME` → `PATH`。`.\build.ps1` 必须打出可运行 JAR；`start-production.ps1 -SkipBuild` 会拒绝过期 JAR。

## 已知边界

- **账期切口硬编码 UTC 自然日。** 模拟渠道按 UTC 切账单，本地也按渠道给的 `occurredAt` 切，所以"23:59 扣款、00:01 回调"仍会落进正确账期。真接第三方后如果对方按北京时间、结算日或自定义 cut-off 切账，这个假设就要跟着改
- `labops.payment.callback-token` 的开发默认值与既有 `labops.jwt.secret` 同性质，受同一道 `server.address=127.0.0.1` + `PublicBindSafety` 约束，线上必须覆盖
- `STATUS_MISMATCH` 这类差异比其余三类窄：正常回调里流水落库和订单折算在同一个事务，所以"流水成功、订单状态没更新"很难留下。它是防御性的，不像前三类有红绿测试撑着

## 测试

```powershell
.\build.ps1          # mvnw package，含测试并产出 jar
cd frontend
npm test
npm run build
```

后端覆盖权限、JWT 查库与降权、登录限流、配额、重叠 PENDING 审批、锁顺序、过期与工单状态机。默认 `mvn test` 不需要 MySQL/Redis/Rabbit。

三类差账场景各有一个测试，都是**先写红测试复现、再修**：

| 场景 | 测试 | 修之前红在哪 |
| --- | --- | --- |
| 回调重复 | `PaymentCallbackIdempotencyIntegrationTest` | 同一条回调重发写出第二条流水、`paid_cents` 翻倍；并发重放下是 9 条 |
| 部分退款 | `PartialRefundReconciliationIntegrationTest` | 拿订单应收比渠道净额，退一半就被判不平、误开差账工单 |
| 渠道成功本地失败 | `ChannelSuccessLocalFailureReconciliationIntegrationTest` | 同一个比法反而算平——应收和渠道一致，缺的正是流水 |

第二轮 review 又打出三个 blocker，同样是先红后修：

| 场景 | 测试 | 修之前红在哪 |
| --- | --- | --- |
| 重复发起支付 | `DuplicatePaymentInitiationIntegrationTest` | 渠道账上出现 2 笔真实扣款，而对账报"平账" |
| 支付晚于窗口到账 | `LatePaymentAfterWindowIntegrationTest` | 预约 `EXPIRED`、订单 `PAID`、钱被收走，对账同样报"平账" |
| 退款请求发不出去 | `RefundRequestRecoveryIntegrationTest` | 预约永久卡在 `REFUNDING`，20 秒轮询也出不来 |

另外还有：支付窗口占时段与超时归还（`ReservationPaymentWindowIntegrationTest`）、截止时间不泄漏（`ReservationDeadlineLeakIntegrationTest` + `LocalReservationExpirySchedulerTest`）、报修下线设备时已支付预约照样退款（`FaultReportRefundsPaidReservationIntegrationTest`）、以及计费取整／窗口夹紧／付款权限／字段长度校验四道护栏（`PaymentGuardrailsIntegrationTest`，逐条用探针回退证明过不是恒真）。

## 面试时可以这样说

> 这是实验室设备预约和报修系统。难点在并发预约、超时审批、多角色权限，以及接上钱之后的幂等与对账。创建时按用户再按设备加锁，数据库再加悲观写锁；待审批可以重叠，批准时才占时段，并发审批最多过一条。超时用每预约一条 Rabbit 延迟队列，非法消息直接丢弃，数据库扫描兜底。JWT 每次查库，登录按 IP 限流，默认只绑 127.0.0.1，公网不会带着体验账号启动。付款走模拟渠道。幂等要分进出两个方向：回调幂等靠幂等键加唯一索引两层，只做应用层查重挡不住并发重投；出站还要有稳定幂等键加持久化重试，否则重复点支付会造出两笔真实交易，而退款请求发失败会让预约永远卡在退款中。对账刻意比的是本地流水而不是订单应收，因为「渠道成功、本地没落库」那种情况下应收和渠道恰好一致，用应收比会永远看不见它。

## 还可以做的

- 校园统一登录
- 设备图片 / 二维码 / 培训资质
- 日历排期
- 通知、维修 SLA、导出
- 登出后作废未过期的 Access Token（当前只吊销 Refresh）
