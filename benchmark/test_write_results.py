#!/usr/bin/env python3
"""Selection and safety tests for write_results.py."""

from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import write_results


def _write(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload), encoding="utf-8")


def _sample(profile: str, scenario: str, n: int, success: int, suffix: str) -> dict:
    return {
        "profile": profile,
        "scenario": scenario,
        "concurrency": n,
        "round": 1,
        "success_201": success,
        "conflict_409": max(n - success, 0) if scenario == "correctness" else 0,
        "failed_other": 0,
        "total_observed": n,
        "throughput_rps": 10.0,
        "wall_seconds": 1.0,
        "latency_all": {"p50_ms": 1, "p95_ms": 2, "p99_ms": 3},
        "error_code_counts": {},
        "status_counts": {"201": success},
        "marker": suffix,
    }


class WriteResultsSelectionTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.bench = Path(self.tmp.name) / "benchmark"
        self.bench.mkdir()
        _write(
            self.bench / "results" / "published-h2.json",
            _sample("h2-local", "correctness", 50, 1, "published"),
        )
        _write(
            self.bench / "machine.json",
            {"collected_at_utc": "2026-08-14T07:03:52Z", "cpu_name": "published-cpu"},
        )
        (self.bench / "RESULTS.md").write_text("# curated published report\n", encoding="utf-8")

        run_root = self.bench / "runs" / "20260814T999999Z"
        _write(
            run_root / "results" / "rerun-h2.json",
            _sample("h2-local", "correctness", 50, 0, "rerun"),
        )
        _write(
            run_root / "machine.json",
            {"collected_at_utc": "2026-08-14T09:00:00Z", "cpu_name": "rerun-cpu"},
        )
        (self.bench / "runs" / "CURRENT").write_text("20260814T999999Z", encoding="utf-8")

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def test_published_source_ignores_rerun_directory(self) -> None:
        selected = write_results.resolve_source(str(self.bench), source="published")
        runs = write_results.load_runs(selected["results_dir"])
        self.assertEqual(selected["source"], "published")
        self.assertEqual([r["marker"] for r in runs], ["published"])

    def test_run_source_does_not_mix_published_json(self) -> None:
        selected = write_results.resolve_source(
            str(self.bench), source="run", run_id="20260814T999999Z"
        )
        runs = write_results.load_runs(selected["results_dir"])
        self.assertEqual(selected["source"], "run")
        self.assertEqual([r["marker"] for r in runs], ["rerun"])

    def test_auto_uses_current_pointer(self) -> None:
        selected = write_results.resolve_source(str(self.bench), source="auto")
        self.assertEqual(selected["source"], "run")
        self.assertEqual(selected["run_id"], "20260814T999999Z")

    def test_auto_without_current_uses_published(self) -> None:
        (self.bench / "runs" / "CURRENT").unlink()
        selected = write_results.resolve_source(str(self.bench), source="auto")
        self.assertEqual(selected["source"], "published")
        runs = write_results.load_runs(selected["results_dir"])
        self.assertEqual([r["marker"] for r in runs], ["published"])

    def test_refuses_to_overwrite_curated_results(self) -> None:
        out = self.bench / "RESULTS.md"
        rc = write_results.main(
            ["--bench-dir", str(self.bench), "--out", str(out), "--source", "published"]
        )
        self.assertEqual(rc, 2)
        self.assertEqual(out.read_text(encoding="utf-8"), "# curated published report\n")

    def test_generation_is_deterministic(self) -> None:
        first = self.bench / "gen-a.md"
        second = self.bench / "gen-b.md"
        args = ["--bench-dir", str(self.bench), "--source", "published"]
        self.assertEqual(write_results.main(args + ["--out", str(first)]), 0)
        self.assertEqual(write_results.main(args + ["--out", str(second)]), 0)
        self.assertEqual(first.read_text(encoding="utf-8"), second.read_text(encoding="utf-8"))
        text = first.read_text(encoding="utf-8")
        self.assertIn("2026-08-14T07:03:52Z", text)
        self.assertIn("published-cpu", text)
        self.assertNotIn("rerun-cpu", text)
        self.assertNotIn("rerun-h2.json", text)

    def test_zero_success_is_reported_as_bug(self) -> None:
        out = self.bench / "rerun.md"
        rc = write_results.main(
            [
                "--bench-dir",
                str(self.bench),
                "--out",
                str(out),
                "--source",
                "run",
                "--run-id",
                "20260814T999999Z",
            ]
        )
        self.assertEqual(rc, 0)
        self.assertIn("0 个 201", out.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
