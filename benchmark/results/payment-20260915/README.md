# 支付并发压测原始结果（2026-09-15）

`benchmark/bench_payment_concurrency.py` 在同一台机器上跑了两轮：一轮在修复前，一轮在 PR #18 的并发修复之后。两轮脚本、并发档位、轮次完全相同。本目录保存两轮的原始输出，PR #18 正文里的前后对比表即来自这里。

## 代码版本

| 目录 | 代码 | 说明 |
| --- | --- | --- |
| `before-fix/` | `d091901` + `ae4023b` | `ae4023b` 只修生产启动失败（`RedisReservationLock` 注入构造器），不含并发修复 |
| `after-fix/` | `24b3727` | 重叠预约改锁定当前读；支付回调先读只读投影再开写事务 |

## 环境

| 项 | 值 |
| --- | --- |
| 机器 | Windows 11，i9-13980HX，24 物理核 / 32 逻辑核 |
| 后端 | production profile，仅监听 `127.0.0.1:28080` |
| 中间件 | WSL Docker：MySQL 8.4（REPEATABLE READ）、Redis 7.4、RabbitMQ 3.13 |
| 未调参 | Redis 等锁 2 s、租约 10 s、每用户 20 条活动预约上限、连接池默认值 |
| 支付网关 | 项目内 `SimulatedPaymentChannel`，不是真实支付提供商 |

## 规模

10 / 25 / 50 并发，每档 3 轮，5 个场景，共 45 个 burst、1275 个计时 HTTP 请求。登录、造数、查询与清理不计入。

## 结果

每个场景 255 个请求。「非预期响应率」指脚本白名单之外的响应所占比例，预期内的 409（配额、锁等待、已审批、已付款）不算。

| 场景 | HTTP 分布（修复前 → 修复后） | 平均 ms（前 → 后） | 非预期响应率（前 → 后） |
| --- | --- | --- | --- |
| 并发提交预约 | `{201:78, 409:177}` → `{201:84, 409:171}` | 648.6 → 1052.5 | 0.00% → 0.00% |
| 审批重叠预约 | `{200:66, 409:189}` → `{200:9, 409:246}` | 165.1 → 78.2 | 0.00% → 0.00% |
| 重复审批同一预约 | `{200:9, 409:246}` → 不变 | 76.3 → 51.4 | 0.00% → 0.00% |
| 相同支付回调重放 | `{200:174, 409:81}` → `{200:255}` | 112.9 → 89.4 | **31.76% → 0.00%** |
| 重复发起支付 | `{200:251, 409:4}` → `{200:255}` | 71.5 → 57.0 | 0.00% → 0.00% |

**支付回调**：修复前 81/255 返回 409 `RESOURCE_BUSY`，根因是 `PaymentOrder` 的 `ObjectOptimisticLockingFailureException`，堆栈见 `before-fix/callback-error-excerpt.txt`。修复后 255 次全部 200。

**重叠预约审批**：审批重叠预约的 HTTP 分布全是预期码，所以非预期响应率为 0。问题要看数据库：修复前 9 轮共批准 66 次，应为 9 次。每轮清理前的占位行记录在 `before-fix/violations.json`。修复后 `after-fix/violations.json` 为空。

**资金不变量**：两轮的每个订单都只有 1 条 PAYMENT 流水、1 条支付审计，且 `paid_cents = amount_cents`、`refunded_cents = 0`。逐单记录见两边的 `database-evidence.json`。

**死锁**：修复后一轮压测结束时 `lock_deadlocks = 2`、`lock_timeouts = 0`。这 2 次来自压测前人为构造的死锁自证，最近一次死锁发生在 12:48:26 UTC，涉及表 `probe_selfcheck.t`；压测运行于 20:56–20:58（UTC+8）。所以 1275 个请求期间 InnoDB 死锁为 0。这组计数当时是手工查询的，本目录没有单独的原始输出文件。

## 限制

- 这是同用户、同资源的争抢测试，不是吞吐或容量测试。
- 修复前一轮跑在已累积数据的库，修复后一轮跑在全新空库。延迟对比只能看方向，HTTP 分布和不变量结论不受影响。
- 并发提交预约变慢是修复的代价：锁定当前读持锁更久，同设备排队更长。
- 各场景 P95 / P99 请看 `summary.json` 中每个 burst 的 `latency` 字段。

## 文件

| 文件 | 内容 |
| --- | --- |
| `summary.json` | 45 个 burst 的汇总：HTTP 分布、业务码、延迟分位、非预期响应率 |
| `responses.jsonl` | 1275 条逐请求记录 |
| `violations.json` | 脚本发现的不变量违例 |
| `database-evidence.json` | 每个订单的数据库不变量核验 |
| `before-fix/initial-failure-db.txt` | 首次 10 并发试跑时，同一时段 10 条预约全部被批准的数据库快照。该试跑不计入 1275 |
| `before-fix/final-db-audit.txt` | 修复前一轮结束时的已付订单汇总与隔离级别 |
| `before-fix/startup-error-excerpt.txt` | `ae4023b` 修复的生产启动失败摘录 |
| `after-fix/regression-*` | `MysqlCriticalConcurrencyRegressionTest` 在 Testcontainers MySQL 8.4 上的运行记录，3 项全过。本机工作目录已替换为 `<workdir>` |

## 复现

需在 Windows 本机运行，脚本通过 `wsl` 调用 MySQL 做只读核验。先起隔离的 MySQL / Redis / RabbitMQ 与 `127.0.0.1:28080` 上的 production 后端，再执行：

```powershell
py -3 .\benchmark\bench_payment_concurrency.py `
  --output .\benchmark\results\payment-<date> `
  --query-script <只读 mysql 查询脚本> `
  --secret-file <本地文件，内容为 KEY=回调 token>
```

在当前 main 上重跑只能得到修复后的结果。要复现 31.76%，需要检出 `ae4023b`。
