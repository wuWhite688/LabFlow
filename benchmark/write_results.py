#!/usr/bin/env python3
"""Assemble benchmark/RESULTS.md from machine.json + result JSON files."""

from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime, timezone
from typing import Any


def load_json(path: str) -> Any:
    with open(path, encoding="utf-8-sig") as fh:
        return json.load(fh)


def md_num(value: Any, digits: int = 3) -> str:
    if value is None:
        return "—"
    if isinstance(value, int):
        return str(value)
    try:
        return f"{float(value):.{digits}f}"
    except (TypeError, ValueError):
        return str(value)


def rel(path: str, root: str) -> str:
    try:
        return os.path.relpath(path, root).replace("\\", "/")
    except ValueError:
        return path.replace("\\", "/")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bench-dir", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    bench_dir = os.path.abspath(args.bench_dir)
    machine_path = os.path.join(bench_dir, "machine.json")
    notes_path = os.path.join(bench_dir, "run-notes.json")
    results_dir = os.path.join(bench_dir, "results")

    machine = load_json(machine_path) if os.path.exists(machine_path) else {}
    notes = load_json(notes_path) if os.path.exists(notes_path) else {}

    runs: list[dict[str, Any]] = []
    if os.path.isdir(results_dir):
        for name in sorted(os.listdir(results_dir)):
            if name.endswith(".json") and not name.endswith(".meta.json"):
                path = os.path.join(results_dir, name)
                data = load_json(path)
                data["_path"] = path
                runs.append(data)

    generated = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    lines: list[str] = []
    lines.append("# LabFlow 并发预约压测结果")
    lines.append("")
    lines.append(f"生成时间（UTC）：`{generated}`")
    lines.append("")
    lines.append("本文档中的**每一个数字都来自本机实际跑出来的原始日志/JSON**。未跑通的场景会明确写失败原因，没有用估算值填空。")
    lines.append("")
    lines.append("## 机器与运行环境")
    lines.append("")
    lines.append("| 项 | 值 |")
    lines.append("| --- | --- |")
    lines.append(f"| CPU | {machine.get('cpu_name', '—')} |")
    lines.append(f"| 物理核 / 逻辑核 | {machine.get('physical_cores', '—')} / {machine.get('logical_processors', '—')} |")
    lines.append(f"| 内存 | {machine.get('memory_gb', '—')} GB |")
    lines.append(f"| OS | {machine.get('os', '—')} |")
    lines.append(f"| Java | {machine.get('java_version', '—')} |")
    lines.append(f"| JAVA_HOME | `{machine.get('java_home', '—')}` |")
    lines.append(f"| Python | {machine.get('python_version', '—')} |")
    lines.append(f"| 压测客户端 | `benchmark/bench_reservation.py`（stdlib `urllib` + 线程） |")
    lines.append(f"| 默认 profile | H2 in-memory + `labops.reservation-lock.mode=local`（`LocalReservationLock`） |")
    lines.append(f"| production profile | MySQL + Redis + `labops.reservation-lock.mode=redis`（`RedisReservationLock`） |")
    if machine.get("source"):
        lines.append(f"| 机器信息原始文件 | `{rel(machine_path, os.path.dirname(bench_dir))}` |")
    lines.append("")
    if machine.get("java_version_raw"):
        lines.append("Java `-version` 原文：")
        lines.append("")
        lines.append("```")
        lines.append(str(machine["java_version_raw"]).strip())
        lines.append("```")
        lines.append("")

    lines.append("## 场景说明")
    lines.append("")
    lines.append("- **场景 1 正确性**：N 个线程用 `threading.Barrier` 对齐后，对**同一设备、同一时段**同时 `POST /api/reservations`。期望恰好 1 个 `201`，其余全部 `409`。N = 50 / 100 / 200，每档 3 轮。")
    lines.append("- **场景 2 性能**：每个 worker 独占一台设备，持续提交互不重叠的预约，测吞吐与延迟。")
    lines.append("- **场景 3 锁对比**：同一套场景 1（以及能跑通时的场景 2）分别在 H2/`LocalReservationLock` 与 MySQL/`RedisReservationLock` 下测量。")
    lines.append("- 成功 = HTTP 201；冲突 = HTTP 409（含 `RESERVATION_CONFLICT` 与 Redis 锁等待超时 `RESERVATION_LOCK_TIMEOUT`）；失败 = 其它状态或客户端异常。")
    lines.append("- 延迟百分位按该轮**全部已完成请求**计算；另外给出 201 / 409 子集。吞吐 = `total_observed / wall_seconds`。")
    lines.append("- 服务按仓库默认配置启动，**没有**调大 Tomcat 线程数或 Hikari 连接池。")
    lines.append("")

    env_notes = notes.get("environments") or {}
    lines.append("## 环境启动情况")
    lines.append("")
    if not env_notes:
        lines.append("没有写入 `run-notes.json` 的环境记录。")
        lines.append("")
    else:
        for key, info in env_notes.items():
            status = info.get("status", "unknown")
            lines.append(f"### {key}")
            lines.append("")
            lines.append(f"- 状态：**{status}**")
            if info.get("detail"):
                lines.append(f"- 说明：{info['detail']}")
            for log_key in ("stdout_log", "stderr_log", "backend_log"):
                if info.get(log_key):
                    lines.append(f"- {log_key}：`{info[log_key]}`")
            if info.get("error_excerpt"):
                lines.append("")
                lines.append("失败摘录：")
                lines.append("")
                lines.append("```")
                lines.append(str(info["error_excerpt"]).rstrip())
                lines.append("```")
            lines.append("")

    def section_for(profile: str, title: str) -> None:
        subset = [r for r in runs if r.get("profile") == profile]
        lines.append(f"## {title}")
        lines.append("")
        if not subset:
            lines.append("本 profile **没有成功落盘的压测 JSON**（未跑或启动失败）。")
            lines.append("")
            return

        corr = sorted(
            [r for r in subset if r.get("scenario") == "correctness"],
            key=lambda r: (int(r.get("concurrency") or 0), int(r.get("round") or 0)),
        )
        if corr:
            lines.append("### 场景 1 — 正确性")
            lines.append("")
            lines.append("| N | 轮次 | 201 | 409 | 失败 | P50 ms | P95 ms | P99 ms | 吞吐 req/s | 墙钟 s | 原始 JSON | 原始日志 |")
            lines.append("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |")
            for r in corr:
                path = r["_path"]
                log_path = os.path.splitext(path)[0] + ".stdout.log"
                if not os.path.exists(log_path):
                    # stdout may live under logs/
                    alt = os.path.join(bench_dir, "logs", os.path.basename(path).replace(".json", ".stdout.log"))
                    log_path = alt if os.path.exists(alt) else log_path
                lat = r.get("latency_all") or {}
                lines.append(
                    "| {n} | {rnd} | {s} | {c} | {f} | {p50} | {p95} | {p99} | {rps} | {wall} | `{json}` | `{log}` |".format(
                        n=r.get("concurrency"),
                        rnd=r.get("round"),
                        s=r.get("success_201"),
                        c=r.get("conflict_409"),
                        f=r.get("failed_other"),
                        p50=md_num(lat.get("p50_ms")),
                        p95=md_num(lat.get("p95_ms")),
                        p99=md_num(lat.get("p99_ms")),
                        rps=md_num(r.get("throughput_rps"), 3),
                        wall=md_num(r.get("wall_seconds"), 3),
                        json=rel(path, os.path.dirname(bench_dir)),
                        log=rel(log_path, os.path.dirname(bench_dir)),
                    )
                )
            lines.append("")
            lines.append("每轮错误码分布：")
            lines.append("")
            lines.append("| N | 轮次 | error_code_counts | status_counts |")
            lines.append("| ---: | ---: | --- | --- |")
            for r in corr:
                lines.append(
                    f"| {r.get('concurrency')} | {r.get('round')} | `{json.dumps(r.get('error_code_counts', {}), ensure_ascii=False)}` | `{json.dumps(r.get('status_counts', {}), ensure_ascii=False)}` |"
                )
            lines.append("")
            bad = [r for r in corr if r.get("success_201") != 1 or r.get("failed_other", 0) > 0]
            if any(r.get("success_201", 0) > 1 for r in corr):
                lines.append("**严重：出现超过 1 个 201，双层防重被穿透。**")
                lines.append("")
            elif not bad and all(r.get("success_201") == 1 and r.get("conflict_409") == r.get("concurrency", 0) - 1 for r in corr):
                lines.append("本 profile 已跑轮次均满足「恰好 1 个 201，其余全部 409」。")
                lines.append("")
            elif not any(r.get("success_201", 0) > 1 for r in corr):
                lines.append("没有出现「超过 1 个 201」。若某轮 201 ≠ 1 或存在失败，见上表。")
                lines.append("")

        perf = sorted(
            [r for r in subset if r.get("scenario") == "performance"],
            key=lambda r: (int(r.get("concurrency") or 0), int(r.get("total_observed") or 0)),
        )
        if perf:
            lines.append("### 场景 2 — 性能（互不冲突）")
            lines.append("")
            lines.append("| 并发 | 总请求 | 201 | 409 | 失败 | P50 ms | P95 ms | P99 ms | 吞吐 req/s | 墙钟 s | 原始 JSON | 原始日志 |")
            lines.append("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |")
            for r in perf:
                path = r["_path"]
                log_path = os.path.splitext(path)[0] + ".stdout.log"
                alt = os.path.join(bench_dir, "logs", os.path.basename(path).replace(".json", ".stdout.log"))
                if not os.path.exists(log_path) and os.path.exists(alt):
                    log_path = alt
                lat = r.get("latency_all") or {}
                lines.append(
                    "| {n} | {t} | {s} | {c} | {f} | {p50} | {p95} | {p99} | {rps} | {wall} | `{json}` | `{log}` |".format(
                        n=r.get("concurrency"),
                        t=r.get("total_observed"),
                        s=r.get("success_201"),
                        c=r.get("conflict_409"),
                        f=r.get("failed_other"),
                        p50=md_num(lat.get("p50_ms")),
                        p95=md_num(lat.get("p95_ms")),
                        p99=md_num(lat.get("p99_ms")),
                        rps=md_num(r.get("throughput_rps"), 3),
                        wall=md_num(r.get("wall_seconds"), 3),
                        json=rel(path, os.path.dirname(bench_dir)),
                        log=rel(log_path, os.path.dirname(bench_dir)),
                    )
                )
            lines.append("")

    section_for("h2-local", "场景结果 — 默认 profile（H2 + LocalReservationLock）")
    section_for("mysql-redis", "场景结果 — production profile（MySQL + RedisReservationLock）")

    h2 = [r for r in runs if r.get("profile") == "h2-local" and r.get("scenario") == "correctness"]
    rd = [r for r in runs if r.get("profile") == "mysql-redis" and r.get("scenario") == "correctness"]
    lines.append("## 场景 3 — 两种锁实现对比")
    lines.append("")
    if h2 and rd:
        lines.append("同一套场景 1，按 N 聚合（算术平均；分轮原始数字见上表，禁止把平均值当成单次测量）。")
        lines.append("")
        lines.append("| N | Local 平均 P99 ms | Redis 平均 P99 ms | Local 平均吞吐 | Redis 平均吞吐 | Local 201 合计 | Redis 201 合计 |")
        lines.append("| ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
        for n in (50, 100, 200):
            a = [r for r in h2 if r.get("concurrency") == n]
            b = [r for r in rd if r.get("concurrency") == n]
            if not a or not b:
                continue

            def avg(items: list[dict[str, Any]], fn) -> float | None:
                vals = [fn(x) for x in items]
                vals = [v for v in vals if v is not None]
                if not vals:
                    return None
                return sum(vals) / len(vals)

            lines.append(
                "| {n} | {lp} | {rp} | {lt} | {rt} | {ls} | {rs} |".format(
                    n=n,
                    lp=md_num(avg(a, lambda r: (r.get("latency_all") or {}).get("p99_ms"))),
                    rp=md_num(avg(b, lambda r: (r.get("latency_all") or {}).get("p99_ms"))),
                    lt=md_num(avg(a, lambda r: r.get("throughput_rps"))),
                    rt=md_num(avg(b, lambda r: r.get("throughput_rps"))),
                    ls=sum(r.get("success_201") or 0 for r in a),
                    rs=sum(r.get("success_201") or 0 for r in b),
                )
            )
        lines.append("")
        lines.append("解读要点：")
        lines.append("")
        lines.append("- `LocalReservationLock` 无等待超时，同一设备上的请求会在进程内公平排队，然后在事务里撞上区间冲突，409 多为 `RESERVATION_CONFLICT`。")
        lines.append("- `RedisReservationLock` 默认 `wait=2s` / `lease=10s`。高并发抢同一把锁时，拿不到锁的请求会在约 2 秒后以 `RESERVATION_LOCK_TIMEOUT` 返回 409。这是设计行为，不是正确性失败。")
        lines.append("- Redis 锁把冲突尽量拦在事务外；对比数字反映的是「多一次 Redis 往返 + 可能的锁等待超时」相对进程内锁的代价。")
        lines.append("")
    else:
        missing = []
        if not h2:
            missing.append("H2/Local 场景 1")
        if not rd:
            missing.append("production/Redis 场景 1")
        lines.append(f"无法做完整对比：缺少 {', '.join(missing)} 的真实数据。")
        lines.append("")
        if notes.get("environments", {}).get("mysql-redis", {}).get("detail"):
            lines.append(f"production 侧说明：{notes['environments']['mysql-redis']['detail']}")
            lines.append("")

    lines.append("## 发现的问题 / Bug")
    lines.append("")
    bugs = notes.get("bugs") or []
    auto_bugs = []
    for r in runs:
        if r.get("scenario") == "correctness" and (r.get("success_201") or 0) > 1:
            auto_bugs.append(
                f"场景 1 `{r.get('profile')}` N={r.get('concurrency')} round={r.get('round')} 出现 {r.get('success_201')} 个 201（期望 1）。原始文件：`{rel(r['_path'], os.path.dirname(bench_dir))}`"
            )
        if r.get("scenario") == "correctness" and (r.get("success_201") or 0) == 0:
            auto_bugs.append(
                f"场景 1 `{r.get('profile')}` N={r.get('concurrency')} round={r.get('round')} **0 个 201**（全部被 409/失败吃掉）。原始文件：`{rel(r['_path'], os.path.dirname(bench_dir))}`"
            )
        if (r.get("failed_other") or 0) > 0:
            auto_bugs.append(
                f"`{r.get('profile')}` {r.get('scenario')} N={r.get('concurrency')} round={r.get('round')} 出现 {r.get('failed_other')} 个非 201/409 失败，status={r.get('status_counts')} codes={r.get('error_code_counts')}。原始文件：`{rel(r['_path'], os.path.dirname(bench_dir))}`"
            )
    all_bugs = bugs + auto_bugs
    if not all_bugs:
        lines.append("已跑通的轮次中，没有观察到「超过 1 个 201」的正确性穿透，也没有把未证实的问题写成 bug。")
        lines.append("")
    else:
        for item in all_bugs:
            lines.append(f"- {item}")
        lines.append("")

    lines.append("## 产出文件")
    lines.append("")
    lines.append("```")
    for dirpath, _, filenames in os.walk(bench_dir):
        for name in sorted(filenames):
            full = os.path.join(dirpath, name)
            lines.append(rel(full, os.path.dirname(bench_dir)))
    lines.append("```")
    lines.append("")
    lines.append("复核方式：打开对应 JSON 看 `success_201` / `conflict_409` / `latency_all` / `throughput_rps`，再对照同名 `.stdout.log` 与 `.jsonl`。")
    lines.append("")

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))
    print(f"WROTE {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
