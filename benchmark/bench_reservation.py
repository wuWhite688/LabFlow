#!/usr/bin/env python3
"""LabFlow reservation concurrency / performance bench. Stdlib only."""

from __future__ import annotations

import argparse
import json
import math
import os
import statistics
import sys
import threading
import time
import traceback
import urllib.error
import urllib.request
from collections import Counter
from datetime import datetime, timedelta, timezone
from typing import Any

ISO_FMT = "%Y-%m-%dT%H:%M:%SZ"


def utc_now() -> datetime:
    return datetime.now(timezone.utc).replace(microsecond=0)


def iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime(ISO_FMT)


def eprint(*args: Any, **kwargs: Any) -> None:
    print(*args, file=sys.stderr, **kwargs)


def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return float(ordered[0])
    rank = (p / 100.0) * (len(ordered) - 1)
    lo = math.floor(rank)
    hi = math.ceil(rank)
    if lo == hi:
        return float(ordered[lo])
    weight = rank - lo
    return float(ordered[lo] * (1.0 - weight) + ordered[hi] * weight)


def summarize_latencies(latencies_ms: list[float]) -> dict[str, float | None]:
    if not latencies_ms:
        return {
            "count": 0,
            "min_ms": None,
            "avg_ms": None,
            "p50_ms": None,
            "p95_ms": None,
            "p99_ms": None,
            "max_ms": None,
        }
    return {
        "count": len(latencies_ms),
        "min_ms": round(min(latencies_ms), 3),
        "avg_ms": round(statistics.fmean(latencies_ms), 3),
        "p50_ms": round(percentile(latencies_ms, 50) or 0.0, 3),
        "p95_ms": round(percentile(latencies_ms, 95) or 0.0, 3),
        "p99_ms": round(percentile(latencies_ms, 99) or 0.0, 3),
        "max_ms": round(max(latencies_ms), 3),
    }


# Direct to the local app. A system HTTP_PROXY (e.g. clash on 127.0.0.1:7897)
# would otherwise intercept urllib even when NO_PROXY lists localhost.
_NO_PROXY_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def http_json(
    method: str,
    url: str,
    headers: dict[str, str] | None = None,
    body: Any = None,
    timeout: float = 180.0,
) -> tuple[int, Any, float, str]:
    raw_body = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=raw_body, method=method)
    req.add_header("Accept", "application/json")
    if raw_body is not None:
        req.add_header("Content-Type", "application/json; charset=utf-8")
    if headers:
        for key, value in headers.items():
            req.add_header(key, value)
    started = time.perf_counter()
    try:
        with _NO_PROXY_OPENER.open(req, timeout=timeout) as resp:
            raw = resp.read()
            elapsed_ms = (time.perf_counter() - started) * 1000.0
            text = raw.decode("utf-8", errors="replace")
            parsed = json.loads(text) if text else None
            return int(resp.status), parsed, elapsed_ms, text
    except urllib.error.HTTPError as exc:
        raw = exc.read()
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        text = raw.decode("utf-8", errors="replace")
        try:
            parsed = json.loads(text) if text else None
        except json.JSONDecodeError:
            parsed = None
        return int(exc.code), parsed, elapsed_ms, text
    except Exception as exc:  # noqa: BLE001 — record client-side failures as status 0
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        return 0, None, elapsed_ms, f"{type(exc).__name__}: {exc}"


class Api:
    def __init__(self, base_url: str, timeout: float = 180.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def url(self, path: str) -> str:
        return self.base_url + path

    def login(self, username: str, password: str) -> str:
        status, parsed, elapsed_ms, text = http_json(
            "POST",
            self.url("/api/auth/login"),
            body={"username": username, "password": password},
            timeout=self.timeout,
        )
        print(f"LOGIN {username} status={status} latency_ms={elapsed_ms:.1f}")
        if status != 200 or not isinstance(parsed, dict) or not parsed.get("accessToken"):
            raise RuntimeError(f"login failed for {username}: status={status} body={text[:500]}")
        return str(parsed["accessToken"])

    def create_equipment(self, token: str, code: str, name: str) -> int:
        status, parsed, elapsed_ms, text = http_json(
            "POST",
            self.url("/api/equipment"),
            headers={"Authorization": f"Bearer {token}"},
            body={
                "code": code,
                "name": name,
                "category": "benchmark",
                "location": "bench-lab",
            },
            timeout=self.timeout,
        )
        print(f"CREATE_EQUIPMENT code={code} status={status} latency_ms={elapsed_ms:.1f}")
        if status != 201 or not isinstance(parsed, dict) or parsed.get("id") is None:
            raise RuntimeError(f"create equipment failed: status={status} body={text[:500]}")
        return int(parsed["id"])


def classify(status: int) -> str:
    if status == 201:
        return "success"
    if status == 409:
        return "conflict"
    return "failed"


def error_code(parsed: Any) -> str | None:
    if isinstance(parsed, dict) and parsed.get("code"):
        return str(parsed["code"])
    return None


def run_correctness(args: argparse.Namespace, api: Api, tokens: dict[str, str]) -> dict[str, Any]:
    stamp = utc_now().strftime("%Y%m%dT%H%M%SZ")
    code = f"BM-C-{args.profile}-{stamp}-{args.concurrency}-R{args.round}"[:50]
    equipment_id = api.create_equipment(
        tokens["admin"],
        code,
        f"bench-correctness-{args.concurrency}-r{args.round}",
    )
    start = utc_now() + timedelta(days=4, hours=args.round)
    end = start + timedelta(hours=1)
    payload = {
        "equipmentId": equipment_id,
        "purpose": f"correctness N={args.concurrency} round={args.round} profile={args.profile}",
        "startTime": iso(start),
        "endTime": iso(end),
    }

    n = args.concurrency
    barrier = threading.Barrier(n + 1)
    results: list[dict[str, Any]] = [None] * n  # type: ignore[list-item]
    errors: list[str] = []

    def worker(idx: int) -> None:
        try:
            barrier.wait(timeout=60)
            status, parsed, elapsed_ms, text = http_json(
                "POST",
                api.url("/api/reservations"),
                headers={"Authorization": f"Bearer {tokens['student']}"},
                body=payload,
                timeout=args.timeout,
            )
            snippet = text if len(text) <= 400 else text[:400] + "..."
            results[idx] = {
                "idx": idx,
                "status": status,
                "class": classify(status),
                "code": error_code(parsed),
                "latency_ms": round(elapsed_ms, 3),
                "reservation_id": parsed.get("id") if isinstance(parsed, dict) else None,
                "body": snippet,
            }
        except Exception:  # noqa: BLE001
            errors.append(f"worker {idx}: {traceback.format_exc()}")
            results[idx] = {
                "idx": idx,
                "status": 0,
                "class": "failed",
                "code": "CLIENT_EXCEPTION",
                "latency_ms": None,
                "reservation_id": None,
                "body": traceback.format_exc()[-400:],
            }

    threads = [threading.Thread(target=worker, args=(i,), name=f"c-{i}", daemon=True) for i in range(n)]
    print(
        f"CORRECTNESS start profile={args.profile} N={n} round={args.round} "
        f"equipmentId={equipment_id} startTime={payload['startTime']} endTime={payload['endTime']}"
    )
    for t in threads:
        t.start()
    wall_start = time.perf_counter()
    barrier.wait(timeout=60)
    for t in threads:
        t.join(timeout=args.timeout + 30)
    wall_s = time.perf_counter() - wall_start

    alive = [t.name for t in threads if t.is_alive()]
    if alive:
        print(f"WARNING threads still alive after join: {alive[:10]} count={len(alive)}")

    records = [r for r in results if r is not None]
    return finish_run(
        args=args,
        scenario="correctness",
        wall_s=wall_s,
        records=records,
        extra={
            "equipment_id": equipment_id,
            "equipment_code": code,
            "start_time": payload["startTime"],
            "end_time": payload["endTime"],
            "worker_exceptions": errors,
            "threads_still_alive": alive,
        },
    )


def run_performance(args: argparse.Namespace, api: Api, tokens: dict[str, str]) -> dict[str, Any]:
    stamp = utc_now().strftime("%Y%m%dT%H%M%SZ")
    workers = args.concurrency
    total = args.total
    if total < workers:
        raise RuntimeError(f"total={total} must be >= concurrency={workers}")

    print(f"PERFORMANCE creating {workers} dedicated equipment (one per worker)...")
    equipment_ids: list[int] = []
    for i in range(workers):
        code = f"BM-P-{args.profile}-{stamp}-{i:04d}"[:50]
        eid = api.create_equipment(tokens["admin"], code, f"bench-perf-worker-{i}")
        equipment_ids.append(eid)

    per_worker = [total // workers] * workers
    for i in range(total % workers):
        per_worker[i] += 1

    base = utc_now() + timedelta(days=5)
    work_q: list[tuple[int, int, int, datetime, datetime]] = []
    seq = 0
    for worker_idx, eid in enumerate(equipment_ids):
        for slot in range(per_worker[worker_idx]):
            start = base + timedelta(hours=slot * 2)
            end = start + timedelta(hours=1)
            work_q.append((seq, worker_idx, eid, start, end))
            seq += 1

    ready = threading.Barrier(workers + 1)
    start_gate = threading.Event()
    records: list[dict[str, Any]] = []
    records_lock = threading.Lock()
    errors: list[str] = []

    def worker(worker_idx: int, jobs: list[tuple[int, int, int, datetime, datetime]]) -> None:
        try:
            ready.wait(timeout=120)
            start_gate.wait(timeout=60)
            for seq_i, _w, eid, start, end in jobs:
                payload = {
                    "equipmentId": eid,
                    "purpose": f"perf seq={seq_i} worker={worker_idx} profile={args.profile}",
                    "startTime": iso(start),
                    "endTime": iso(end),
                }
                status, parsed, elapsed_ms, text = http_json(
                    "POST",
                    api.url("/api/reservations"),
                    headers={"Authorization": f"Bearer {tokens['student']}"},
                    body=payload,
                    timeout=args.timeout,
                )
                snippet = text if len(text) <= 300 else text[:300] + "..."
                rec = {
                    "idx": seq_i,
                    "worker": worker_idx,
                    "equipment_id": eid,
                    "status": status,
                    "class": classify(status),
                    "code": error_code(parsed),
                    "latency_ms": round(elapsed_ms, 3),
                    "reservation_id": parsed.get("id") if isinstance(parsed, dict) else None,
                    "start_time": payload["startTime"],
                    "body": snippet,
                }
                with records_lock:
                    records.append(rec)
        except Exception:  # noqa: BLE001
            errors.append(f"worker {worker_idx}: {traceback.format_exc()}")

    jobs_by_worker: dict[int, list[tuple[int, int, int, datetime, datetime]]] = {i: [] for i in range(workers)}
    for job in work_q:
        jobs_by_worker[job[1]].append(job)

    threads = [
        threading.Thread(target=worker, args=(i, jobs_by_worker[i]), name=f"p-{i}", daemon=True)
        for i in range(workers)
    ]
    print(
        f"PERFORMANCE start profile={args.profile} concurrency={workers} total={total} "
        f"equipment={len(equipment_ids)} base_start={iso(base)}"
    )
    for t in threads:
        t.start()
    ready.wait(timeout=120)
    wall_start = time.perf_counter()
    start_gate.set()
    for t in threads:
        t.join(timeout=args.timeout * max(per_worker) + 60)
    wall_s = time.perf_counter() - wall_start
    alive = [t.name for t in threads if t.is_alive()]
    if alive:
        print(f"WARNING threads still alive after join: {alive[:10]} count={len(alive)}")

    return finish_run(
        args=args,
        scenario="performance",
        wall_s=wall_s,
        records=sorted(records, key=lambda r: r["idx"]),
        extra={
            "equipment_ids": equipment_ids,
            "per_worker": per_worker,
            "base_start": iso(base),
            "worker_exceptions": errors,
            "threads_still_alive": alive,
        },
    )


def finish_run(
    args: argparse.Namespace,
    scenario: str,
    wall_s: float,
    records: list[dict[str, Any]],
    extra: dict[str, Any],
) -> dict[str, Any]:
    counts = Counter(r["status"] for r in records)
    class_counts = Counter(r["class"] for r in records)
    code_counts = Counter(r.get("code") or f"HTTP_{r['status']}" for r in records)
    lat_all = [float(r["latency_ms"]) for r in records if r.get("latency_ms") is not None]
    lat_201 = [float(r["latency_ms"]) for r in records if r.get("status") == 201 and r.get("latency_ms") is not None]
    lat_409 = [float(r["latency_ms"]) for r in records if r.get("status") == 409 and r.get("latency_ms") is not None]

    created = class_counts.get("success", 0)
    conflicts = class_counts.get("conflict", 0)
    failed = class_counts.get("failed", 0)
    total = len(records)
    rps = (total / wall_s) if wall_s > 0 else None

    summary = {
        "scenario": scenario,
        "profile": args.profile,
        "lock_impl": args.lock_impl,
        "base_url": args.base_url,
        "concurrency": args.concurrency,
        "round": args.round,
        "total_planned": args.total if scenario == "performance" else args.concurrency,
        "total_observed": total,
        "success_201": created,
        "conflict_409": conflicts,
        "failed_other": failed,
        "status_counts": {str(k): v for k, v in sorted(counts.items(), key=lambda kv: str(kv[0]))},
        "error_code_counts": dict(code_counts),
        "wall_seconds": round(wall_s, 6),
        "throughput_rps": round(rps, 6) if rps is not None else None,
        "latency_all": summarize_latencies(lat_all),
        "latency_201": summarize_latencies(lat_201),
        "latency_409": summarize_latencies(lat_409),
        "started_at": extra.get("started_at") or utc_now().isoformat(),
        **{k: v for k, v in extra.items() if k != "started_at"},
        "requests": records,
    }

    print("---- SUMMARY ----")
    print(json.dumps({k: v for k, v in summary.items() if k != "requests"}, ensure_ascii=False, indent=2))
    print("---- PER-REQUEST ----")
    for rec in records:
        print(
            f"idx={rec.get('idx')} status={rec.get('status')} class={rec.get('class')} "
            f"code={rec.get('code')} latency_ms={rec.get('latency_ms')} "
            f"reservation_id={rec.get('reservation_id')}"
        )

    if scenario == "correctness" and created > 1:
        print(
            f"FATAL: correctness violation — expected exactly 1 HTTP 201, got {created}. "
            "Stopping further performance scenarios is the caller's responsibility.",
            file=sys.stderr,
        )
    return summary


def write_outputs(args: argparse.Namespace, summary: dict[str, Any]) -> None:
    os.makedirs(args.out_dir, exist_ok=True)
    json_path = args.json_out or os.path.join(args.out_dir, f"{args.run_id}.json")
    jsonl_path = os.path.splitext(json_path)[0] + ".jsonl"
    with open(json_path, "w", encoding="utf-8") as fh:
        json.dump(summary, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    with open(jsonl_path, "w", encoding="utf-8") as fh:
        for rec in summary.get("requests", []):
            fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
    print(f"WROTE {json_path}")
    print(f"WROTE {jsonl_path}")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="LabFlow reservation bench")
    p.add_argument("--base-url", required=True)
    p.add_argument("--scenario", choices=("correctness", "performance"), required=True)
    p.add_argument("--concurrency", type=int, required=True)
    p.add_argument("--round", type=int, default=1)
    p.add_argument("--total", type=int, default=500, help="performance total requests")
    p.add_argument("--profile", required=True, help="logical profile label, e.g. h2-local / mysql-redis")
    p.add_argument("--lock-impl", required=True, help="LocalReservationLock or RedisReservationLock")
    p.add_argument("--out-dir", required=True)
    p.add_argument("--run-id", required=True)
    p.add_argument("--json-out", default="")
    p.add_argument("--timeout", type=float, default=180.0)
    p.add_argument("--student-user", default="student")
    p.add_argument("--student-pass", default="student123")
    p.add_argument("--admin-user", default="admin")
    p.add_argument("--admin-pass", default="admin123")
    return p.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    args = parse_args(argv)
    print(f"BENCH begin {args.scenario} profile={args.profile} N={args.concurrency} round={args.round}")
    print(f"base_url={args.base_url} python={sys.version} pid={os.getpid()}")
    api = Api(args.base_url, timeout=args.timeout)
    tokens = {
        "student": api.login(args.student_user, args.student_pass),
        "admin": api.login(args.admin_user, args.admin_pass),
    }
    # cheap warmup so first measured request is not a cold Hikari/JPA hit
    if args.scenario == "performance":
        warm_code = f"BM-WARM-{args.profile}-{utc_now().strftime('%H%M%S')}"[:50]
        warm_id = api.create_equipment(tokens["admin"], warm_code, "bench-warmup")
        warm_start = utc_now() + timedelta(days=3)
        status, parsed, elapsed_ms, text = http_json(
            "POST",
            api.url("/api/reservations"),
            headers={"Authorization": f"Bearer {tokens['student']}"},
            body={
                "equipmentId": warm_id,
                "purpose": "warmup",
                "startTime": iso(warm_start),
                "endTime": iso(warm_start + timedelta(hours=1)),
            },
            timeout=args.timeout,
        )
        print(f"WARMUP reservation status={status} latency_ms={elapsed_ms:.1f} body={text[:160]}")

    if args.scenario == "correctness":
        summary = run_correctness(args, api, tokens)
    else:
        summary = run_performance(args, api, tokens)
    write_outputs(args, summary)

    if args.scenario == "correctness" and summary["success_201"] != 1:
        return 2
    if args.scenario == "performance" and summary["failed_other"] > 0:
        return 3
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception:
        traceback.print_exc()
        raise SystemExit(1)
