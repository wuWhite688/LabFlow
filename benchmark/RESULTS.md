# LabFlow 并发预约压测结果

生成时间（UTC）：`2026-08-14T07:05:03Z`（随后人工补全了环境说明与对比解读；**表内数字未改，全部来自对应 JSON**）

本文档中的每一个数字都来自本机实际跑出来的原始日志 / JSON。没有估算，没有从代码推吞吐。

## 机器与运行环境

| 项 | 值 |
| --- | --- |
| CPU | 13th Gen Intel(R) Core(TM) i9-13980HX |
| 物理核 / 逻辑核 | 24 / 32 |
| 内存 | 31.63 GB |
| OS | Microsoft Windows 11 家庭版 中文版 10.0.26200 64 位 |
| Java | java version "25.0.2" 2026-01-20 LTS（`JAVA_HOME=C:\Program Files\Java\jdk-25.0.2`） |
| Python 压测客户端 | 3.14.0，`benchmark/bench_reservation.py`（仅 stdlib `urllib` + 线程；绕过系统 HTTP 代理直连 `127.0.0.1`） |
| 默认 profile | 未指定 `spring.profiles.active`，回落到 `default`：H2 in-memory + `labops.reservation-lock.mode=local`（`LocalReservationLock`） |
| production profile | `--spring.profiles.active=production`：MySQL 8.4 + Redis 7.4 + `labops.reservation-lock.mode=redis`（`RedisReservationLock`，`wait=2s` / `lease=10s`） |
| 机器信息原始文件 | `benchmark/machine.json` |

Java `-version` 原文：

```
java version "25.0.2" 2026-01-20 LTS
Java(TM) SE Runtime Environment (build 25.0.2+10-LTS-69)
Java HotSpot(TM) 64-Bit Server VM (build 25.0.2+10-LTS-69, mixed mode, sharing)
```

服务按仓库默认配置启动：**没有**调大 Tomcat 线程数或 Hikari 连接池。

## 测了什么

- **场景 1 正确性**：N 个线程用 `threading.Barrier` 对齐后，对**同一设备、同一时段**同时 `POST /api/reservations`。期望恰好 1 个 `201`，其余全部 `409`。N = 50 / 100 / 200，每档 3 轮。
- **场景 2 性能**：每个 worker 独占一台设备，持续提交互不重叠的预约（无区间冲突、无锁争用），测吞吐与延迟。并发 50 / 100，各 500 个请求。
- **场景 3 锁对比**：同一套场景 1（以及场景 2）分别在上述两个 profile 下跑。

分类：成功 = HTTP 201；冲突 = HTTP 409（含 `RESERVATION_CONFLICT` 与 Redis `RESERVATION_LOCK_TIMEOUT`）；失败 = 其它状态或客户端异常。

延迟百分位按该轮**全部已完成请求**计算。吞吐 = `total_observed / wall_seconds`（从发令到最后一条响应）。场景 1 的吞吐会被锁排队 / `wait=2s` 超时抬高墙钟，**不能**当成「成功预约吞吐」来读。

认证：先 `POST /api/auth/login` 拿 JWT，再带 `Authorization: Bearer` 打预约接口。账号为演示用户 `student` / `admin`。

## 环境启动情况

### h2-local（默认 profile）

- 状态：**up，场景 1 + 2 全部跑完**
- 启动：`java -jar … --server.port=18080`（无 profile 参数）
- 日志原文：`No active profile set, falling back to 1 default profile: "default"`
- 后端完整日志：`benchmark/logs/20260814T065933Z-backend-h2.stdout.log`
- 编排日志：`benchmark/logs/20260814T065933Z-orchestrator.stdout.log`

### mysql-redis（production profile）

- 状态：**up，场景 1 + 2 全部跑完**
- 中间件：WSL Ubuntu 内 Docker Engine（Windows PATH 无 `docker`）。`docker compose up -d` 后 mysql / redis / rabbitmq 均 `healthy`。
- 启动：`java -jar … --spring.profiles.active=production --server.port=18080`
- 日志原文：`The following 1 profile is active: "production"`；Flyway 连上 `MySQL 8.4`；Hikari 拿到 `com.mysql.cj.jdbc.ConnectionImpl`。
- Redis 锁证据：后端日志中 `Redis reservation lock acquired` **1792** 次，`released` **1792** 次（与「拿到锁后进入临界区」的请求数一致；`RESERVATION_LOCK_TIMEOUT` 的请求不会打 acquired）。
- 中间件 / 编排 / 后端日志：
  - `benchmark/logs/20260814T070350Z-start-middleware.stdout.log`
  - `benchmark/logs/20260814T070350Z-orchestrator.stdout.log`
  - `benchmark/logs/20260814T070350Z-backend-mysql-redis.stdout.log`

跑完后已停止：H2 Java 进程、production Java 进程、compose 栈、WSL keepalive。复测时 WSL 状态为 `Stopped`，`18080` 无监听。

第一次拉 production 时，`start-middleware.ps1` 在 `$ErrorActionPreference=Stop` 下把 `wsl docker compose` 的 stderr 进度行（`Network labflow-prod_default Creating`）当成了终止错误。这是**压测编排**问题，不是业务 bug。原始失败摘录：`benchmark/logs/20260814T070159Z-start-middleware.stderr.log`。随后改为在编排器里 `ErrorAction=Continue` 调 `wsl-docker-lib.ps1`，中间件起来并完成压测。

## 场景结果 — 默认 profile（H2 + LocalReservationLock）

### 场景 1 — 正确性

| N | 轮次 | 201 | 409 | 失败 | P50 ms | P95 ms | P99 ms | 吞吐 req/s | 墙钟 s | 原始 JSON | 原始日志 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| 50 | 1 | 1 | 49 | 0 | 151.805 | 216.066 | 225.755 | 218.719 | 0.229 | `benchmark/results/20260814T065933Z-h2-local-correctness-n50-r1.json` | `benchmark/results/20260814T065933Z-h2-local-correctness-n50-r1.stdout.log` |
| 50 | 2 | 1 | 49 | 0 | 79.887 | 139.612 | 143.568 | 336.073 | 0.149 | `benchmark/results/20260814T065933Z-h2-local-correctness-n50-r2.json` | `benchmark/results/20260814T065933Z-h2-local-correctness-n50-r2.stdout.log` |
| 50 | 3 | 1 | 49 | 0 | 80.064 | 125.538 | 129.065 | 374.546 | 0.133 | `benchmark/results/20260814T065933Z-h2-local-correctness-n50-r3.json` | `benchmark/results/20260814T065933Z-h2-local-correctness-n50-r3.stdout.log` |
| 100 | 1 | 1 | 99 | 0 | 103.135 | 185.048 | 192.089 | 500.749 | 0.200 | `benchmark/results/20260814T065933Z-h2-local-correctness-n100-r1.json` | `benchmark/results/20260814T065933Z-h2-local-correctness-n100-r1.stdout.log` |
| 100 | 2 | 1 | 99 | 0 | 84.749 | 142.953 | 151.629 | 624.810 | 0.160 | `benchmark/results/20260814T065933Z-h2-local-correctness-n100-r2.json` | `benchmark/results/20260814T065933Z-h2-local-correctness-n100-r2.stdout.log` |
| 100 | 3 | 1 | 99 | 0 | 77.144 | 116.704 | 120.297 | 794.450 | 0.126 | `benchmark/results/20260814T065933Z-h2-local-correctness-n100-r3.json` | `benchmark/results/20260814T065933Z-h2-local-correctness-n100-r3.stdout.log` |
| 200 | 1 | 1 | 199 | 0 | 140.923 | 243.779 | 248.048 | 758.767 | 0.264 | `benchmark/results/20260814T065933Z-h2-local-correctness-n200-r1.json` | `benchmark/results/20260814T065933Z-h2-local-correctness-n200-r1.stdout.log` |
| 200 | 2 | 1 | 199 | 0 | 117.224 | 197.061 | 206.414 | 917.038 | 0.218 | `benchmark/results/20260814T065933Z-h2-local-correctness-n200-r2.json` | `benchmark/results/20260814T065933Z-h2-local-correctness-n200-r2.stdout.log` |
| 200 | 3 | 1 | 199 | 0 | 105.346 | 173.955 | 186.201 | 1021.972 | 0.196 | `benchmark/results/20260814T065933Z-h2-local-correctness-n200-r3.json` | `benchmark/results/20260814T065933Z-h2-local-correctness-n200-r3.stdout.log` |

每轮 409 全部是 `RESERVATION_CONFLICT`（进程内锁无等待超时）。**9/9 轮均为恰好 1 个 201，其余全部 409，0 失败。**

### 场景 2 — 性能（互不冲突）

| 并发 | 总请求 | 201 | 409 | 失败 | P50 ms | P95 ms | P99 ms | 吞吐 req/s | 墙钟 s | 原始 JSON | 原始日志 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| 50 | 500 | 500 | 0 | 0 | 22.842 | 35.563 | 41.439 | 1838.716 | 0.272 | `benchmark/results/20260814T065933Z-h2-local-performance-c50-t500-r1.json` | `benchmark/results/20260814T065933Z-h2-local-performance-c50-t500-r1.stdout.log` |
| 100 | 500 | 500 | 0 | 0 | 44.730 | 72.634 | 85.063 | 1887.344 | 0.265 | `benchmark/results/20260814T065933Z-h2-local-performance-c100-t500-r1.json` | `benchmark/results/20260814T065933Z-h2-local-performance-c100-t500-r1.stdout.log` |

## 场景结果 — production profile（MySQL + RedisReservationLock）

### 场景 1 — 正确性

| N | 轮次 | 201 | 409 | 失败 | P50 ms | P95 ms | P99 ms | 吞吐 req/s | 墙钟 s | 原始 JSON | 原始日志 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| 50 | 1 | 1 | 49 | 0 | 771.454 | 1456.787 | 1581.046 | 31.017 | 1.612 | `benchmark/results/20260814T070350Z-mysql-redis-correctness-n50-r1.json` | `benchmark/results/20260814T070350Z-mysql-redis-correctness-n50-r1.stdout.log` |
| 50 | 2 | 1 | 49 | 0 | 1489.126 | 2068.939 | 2069.578 | 24.144 | 2.071 | `benchmark/results/20260814T070350Z-mysql-redis-correctness-n50-r2.json` | `benchmark/results/20260814T070350Z-mysql-redis-correctness-n50-r2.stdout.log` |
| 50 | 3 | 1 | 49 | 0 | 1404.230 | 2052.097 | 2053.126 | 24.340 | 2.054 | `benchmark/results/20260814T070350Z-mysql-redis-correctness-n50-r3.json` | `benchmark/results/20260814T070350Z-mysql-redis-correctness-n50-r3.stdout.log` |
| 100 | 1 | 1 | 99 | 0 | 1549.713 | 2075.403 | 2078.110 | 48.032 | 2.082 | `benchmark/results/20260814T070350Z-mysql-redis-correctness-n100-r1.json` | `benchmark/results/20260814T070350Z-mysql-redis-correctness-n100-r1.stdout.log` |
| 100 | 2 | 1 | 99 | 0 | 2075.416 | 2082.746 | 2084.182 | 47.936 | 2.086 | `benchmark/results/20260814T070350Z-mysql-redis-correctness-n100-r2.json` | `benchmark/results/20260814T070350Z-mysql-redis-correctness-n100-r2.stdout.log` |
| 100 | 3 | 1 | 99 | 0 | 1691.193 | 2067.647 | 2071.014 | 48.154 | 2.077 | `benchmark/results/20260814T070350Z-mysql-redis-correctness-n100-r3.json` | `benchmark/results/20260814T070350Z-mysql-redis-correctness-n100-r3.stdout.log` |
| 200 | 1 | 1 | 199 | 0 | 958.259 | 2086.318 | 2093.546 | 95.232 | 2.100 | `benchmark/results/20260814T070350Z-mysql-redis-correctness-n200-r1.json` | `benchmark/results/20260814T070350Z-mysql-redis-correctness-n200-r1.stdout.log` |
| 200 | 2 | 1 | 199 | 0 | 794.730 | 1992.103 | 2040.572 | 95.398 | 2.096 | `benchmark/results/20260814T070350Z-mysql-redis-correctness-n200-r2.json` | `benchmark/results/20260814T070350Z-mysql-redis-correctness-n200-r2.stdout.log` |
| 200 | 3 | 1 | 199 | 0 | 960.228 | 2078.988 | 2090.618 | 95.195 | 2.101 | `benchmark/results/20260814T070350Z-mysql-redis-correctness-n200-r3.json` | `benchmark/results/20260814T070350Z-mysql-redis-correctness-n200-r3.stdout.log` |

409 拆分（同一 JSON 的 `error_code_counts`）：

| N | 轮次 | RESERVATION_CONFLICT | RESERVATION_LOCK_TIMEOUT | 201 |
| ---: | ---: | ---: | ---: | ---: |
| 50 | 1 | 49 | 0 | 1 |
| 50 | 2 | 33 | 16 | 1 |
| 50 | 3 | 34 | 15 | 1 |
| 100 | 1 | 59 | 40 | 1 |
| 100 | 2 | 32 | 67 | 1 |
| 100 | 3 | 59 | 40 | 1 |
| 200 | 1 | 160 | 39 | 1 |
| 200 | 2 | 190 | 9 | 1 |
| 200 | 3 | 165 | 34 | 1 |

**9/9 轮均为恰好 1 个 201，其余全部 409，0 失败。** 没有出现「超过 1 个 201」。P99 贴着默认 `wait=2s`，是锁等待超时，不是服务挂掉。

### 场景 2 — 性能（互不冲突）

| 并发 | 总请求 | 201 | 409 | 失败 | P50 ms | P95 ms | P99 ms | 吞吐 req/s | 墙钟 s | 原始 JSON | 原始日志 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| 50 | 500 | 500 | 0 | 0 | 274.956 | 352.341 | 375.849 | 174.032 | 2.873 | `benchmark/results/20260814T070350Z-mysql-redis-performance-c50-t500-r1.json` | `benchmark/results/20260814T070350Z-mysql-redis-performance-c50-t500-r1.stdout.log` |
| 100 | 500 | 500 | 0 | 0 | 534.235 | 582.186 | 614.726 | 184.343 | 2.712 | `benchmark/results/20260814T070350Z-mysql-redis-performance-c100-t500-r1.json` | `benchmark/results/20260814T070350Z-mysql-redis-performance-c100-t500-r1.stdout.log` |

## 场景 3 — 两种实现对比

同一套场景 1，按 N 对三轮做算术平均。平均值**只用于对照数量级**，分轮原始数字以上表为准。

| N | Local 平均 P99 ms | Redis 平均 P99 ms | Local 平均吞吐 req/s | Redis 平均吞吐 req/s | 两侧 201 合计（3 轮） |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 50 | 166.129 | 1901.250 | 309.779 | 26.500 | 3 / 3 |
| 100 | 154.672 | 2077.769 | 640.003 | 48.041 | 3 / 3 |
| 200 | 213.554 | 2074.912 | 899.259 | 95.275 | 3 / 3 |

场景 2（互不冲突，更能代表「一次预约有多贵」）：

| 并发 | H2+Local P99 ms | MySQL+Redis P99 ms | H2+Local req/s | MySQL+Redis req/s |
| ---: | ---: | ---: | ---: | ---: |
| 50 | 41.439 | 375.849 | 1838.716 | 174.032 |
| 100 | 85.063 | 614.726 | 1887.344 | 184.343 |

### 怎么读这些数字（面试时别说错）

1. **正确性两边都过了。** 50 / 100 / 200 × 3 轮，H2 与 MySQL+Redis 都是恰好 1 个 `201`。双层防重（锁 + 设备行 `PESSIMISTIC_WRITE` + 区间重叠判定）在这次压测里没有被打穿。
2. **场景 1 的 Redis P99 ≈ 2s，主要是锁等待超时，不是 MySQL 慢。** `RedisReservationLock` 默认最多抢 2 秒；抢不到返回 409 `RESERVATION_LOCK_TIMEOUT`。Local 锁没有超时，同一设备上排队后进事务做冲突校验，409 全是 `RESERVATION_CONFLICT`，P99 在 120–250 ms。
3. **场景 1 的「吞吐」随 N 变大而变大，是因为墙钟被 2s 超时钉住。** 200 个请求 / ~2.1s ≈ 95 req/s，并不表示 production 能每秒成功落 95 条重叠预约。成功预约永远是每轮 1 条。
4. **场景 2 的差距不能全部记在 Redis 锁头上。** 对比的是两套完整栈：H2 进程内库 + 进程内 `ReentrantLock`，对 MySQL（WSL Docker，经 `127.0.0.1:3306`）+ Redis `SET NX PX`。未争用路径上 MySQL+Redis 大约 **10× 延迟、约 1/10 吞吐**。其中有跨进程数据库、Docker/WSL 网络、Redis 往返，不只是锁。若要单独量化「Redis 锁比本地锁贵多少」，需要同一数据库上只切换 `labops.reservation-lock.mode`——这次**没有**做这个对照。
5. **Redis 锁做了它该做的事：** 把一部分冲突拦在事务外（`RESERVATION_LOCK_TIMEOUT`），其余拿到锁的请求在库里变成 `RESERVATION_CONFLICT`。N=50 第 1 轮 50 个请求全部进了临界区（0 次锁超时）；更高并发时超时比例上升，符合 `wait=2s` + 50ms 重试的实现。

## 发现的问题 / Bug

- **业务正确性：** 未发现「超过 1 个 201」。18 轮场景 1 全部是 1 / (N−1) / 0。
- **未改业务代码。** 压测只往 `benchmark/` 写文件。
- Flyway 启动时有一条 `MySQL 8.4 is newer than this version of Flyway` 的 WARN，不影响本次压测。
- 编排器第一次调用 `start-middleware.ps1` 因 PowerShell 把 docker 进度 stderr 当错误而失败，已在上面写明；重试后跑通。

## 如何复现

```powershell
# 仅 H2 / LocalReservationLock
.\benchmark\run-all.ps1 -SkipProduction

# 仅 production / RedisReservationLock（需 WSL Docker）
.\benchmark\run-all.ps1 -SkipH2

# 汇总 RESULTS.md（读取 benchmark/results/*.json）
py -3 .\benchmark\write_results.py --bench-dir .\benchmark --out .\benchmark\RESULTS.md
```

单次场景：

```powershell
py -3 .\benchmark\bench_reservation.py `
  --base-url http://127.0.0.1:18080 `
  --scenario correctness --concurrency 50 --round 1 `
  --profile h2-local --lock-impl LocalReservationLock `
  --out-dir .\benchmark\results --run-id demo-n50
```

## 产出文件

脚本：

- `benchmark/bench_reservation.py`
- `benchmark/run-all.ps1`
- `benchmark/write_results.py`
- `benchmark/machine.json`
- `benchmark/run-notes.json`
- `benchmark/RESULTS.md`

结构化结果与完整客户端 stdout/stderr：`benchmark/results/`（每个 run 一份 `.json` + `.jsonl` + `.stdout.log` + `.stderr.log`）。

后端与编排原始日志：`benchmark/logs/`（文件名带场景/profile 和时间戳）。

复核：打开对应 JSON 看 `success_201` / `conflict_409` / `latency_all` / `throughput_rps` / `error_code_counts`，再对照同名 `.stdout.log`、`.jsonl` 和后端 `Redis reservation lock acquired` 行。
