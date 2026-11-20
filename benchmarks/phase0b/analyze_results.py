from __future__ import annotations

import argparse
import json
from dataclasses import asdict
from pathlib import Path

from decision import decide_adoption, paired_bootstrap_difference
from harness import load_tasks, sha256_file
from schema import HeldOutTask, KnowledgeDependency


CONDITIONS = (
    "A_raw",
    "B_fts",
    "C_hybrid",
    "D_oracle",
    "E_wrong",
    "F_1_5b_raw",
)
GATE_CONDITIONS = {"A_raw", "C_hybrid", "D_oracle"}
FAILURE_CATEGORIES = (
    "retrieval_failure",
    "ranking_failure",
    "adaptation_failure",
    "verification_failure",
)
FROZEN_CODE_PATHS = {
    "decision_sha256": Path(__file__).resolve().with_name("decision.py"),
    "runner_sha256": Path(__file__).resolve().with_name("run_benchmark.py"),
    "retrieval_sha256": Path(__file__).resolve().with_name("retrieval.py"),
    "harness_sha256": Path(__file__).resolve().with_name("harness.py"),
    "pins_sha256": Path(__file__).resolve().with_name("pins.py"),
}


def load_oracle(path: Path) -> dict[str, dict]:
    return {
        row["task_id"]: row
        for row in (
            json.loads(line)
            for line in path.read_text(encoding="utf-8").splitlines()
            if line.strip()
        )
    }


def load_summary(logs_dir: Path) -> list[dict]:
    path = logs_dir / "summary.json"
    if not path.exists():
        raise ValueError(f"missing run summary: {path}")
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, list):
        raise ValueError("summary.json must contain a list")
    return data


def verify_frozen_code_hashes(manifest: dict) -> None:
    for key, path in FROZEN_CODE_PATHS.items():
        expected = str(manifest.get(key, "")).strip().lower()
        actual = sha256_file(path)
        if not expected:
            raise ValueError(f"manifest {key} is not frozen")
        if expected != actual:
            raise ValueError(
                f"{key} mismatch: manifest={expected!r}, actual={actual!r}"
            )


def _condition_map(records: list[dict], condition: str) -> dict[str, dict]:
    out: dict[str, dict] = {}
    for row in records:
        if row.get("condition") != condition:
            continue
        task_id = str(row.get("task_id", ""))
        if not task_id:
            raise ValueError(f"{condition} row missing task_id")
        if task_id in out:
            raise ValueError(f"duplicate {condition} result for {task_id}")
        out[task_id] = row
    return out


def _passes(rows: dict[str, dict], task_ids: list[str], condition: str) -> list[bool]:
    missing = [task_id for task_id in task_ids if task_id not in rows]
    if missing:
        raise ValueError(f"missing {condition} results: {missing}")
    return [bool(rows[task_id].get("passed", False)) for task_id in task_ids]


def _pass_rate(rows: dict[str, dict], expected_ids: list[str]) -> dict:
    present = [rows[task_id] for task_id in expected_ids if task_id in rows]
    if not present:
        return {
            "available": False,
            "passed": 0,
            "total": 0,
            "pass_rate": None,
        }
    passed = sum(bool(row.get("passed", False)) for row in present)
    return {
        "available": True,
        "passed": passed,
        "total": len(present),
        "pass_rate": passed / len(present),
    }


def _failure_counts(rows: dict[str, dict]) -> dict[str, int]:
    counts = {name: 0 for name in FAILURE_CATEGORIES}
    for row in rows.values():
        taxonomy = row.get("failure_taxonomy") or {}
        for name in FAILURE_CATEGORIES:
            if taxonomy.get(name):
                counts[name] += 1
    return counts


def _paired_report(
    raw_rows: dict[str, dict],
    other_rows: dict[str, dict],
    ids: list[str],
    label: str,
    *,
    repetitions: int,
    seed: int,
) -> dict:
    raw = _passes(raw_rows, ids, f"A_raw {label}")
    other = _passes(other_rows, ids, label)
    return asdict(
        paired_bootstrap_difference(
            raw,
            other,
            repetitions=repetitions,
            seed=seed,
        )
    )


def analyze_records(
    records: list[dict],
    tasks: list[HeldOutTask],
    oracle: dict[str, dict],
    *,
    repetitions: int = 10_000,
    seed: int = 0,
) -> dict:
    task_by_id = {task.task_id: task for task in tasks}
    task_ids = [task.task_id for task in tasks]
    if len(task_by_id) != len(tasks):
        raise ValueError("held-out tasks contain duplicate IDs")

    if set(oracle) != set(task_ids):
        missing = sorted(set(task_ids) - set(oracle))
        extra = sorted(set(oracle) - set(task_ids))
        raise ValueError(f"oracle/task IDs mismatch; missing={missing}, extra={extra}")

    rows_by_condition = {
        condition: _condition_map(records, condition)
        for condition in CONDITIONS
    }
    raw_rows = rows_by_condition["A_raw"]
    fts_rows = rows_by_condition["B_fts"]
    hybrid_rows = rows_by_condition["C_hybrid"]
    oracle_rows = rows_by_condition["D_oracle"]
    wrong_rows = rows_by_condition["E_wrong"]

    covered_ids = [
        task_id
        for task_id in task_ids
        if oracle[task_id]["coverage"] == "covered"
    ]
    uncovered_ids = [
        task_id
        for task_id in task_ids
        if oracle[task_id]["coverage"] == "uncovered"
    ]
    direct_ids = [
        task_id
        for task_id in covered_ids
        if oracle[task_id]["strength"] == "direct"
    ]
    partial_ids = [
        task_id
        for task_id in covered_ids
        if oracle[task_id]["strength"] == "partial"
    ]
    reasoning_ids = [
        task_id
        for task_id in task_ids
        if task_by_id[task_id].knowledge_dependency
        is KnowledgeDependency.REASONING_ONLY
    ]

    # The primary populations must be complete even before deciding validity.
    _passes(raw_rows, task_ids, "A_raw")
    _passes(hybrid_rows, task_ids, "C_hybrid")
    _passes(oracle_rows, covered_ids, "D_oracle covered")

    model_errors = [
        {
            "task_id": row.get("task_id"),
            "condition": row.get("condition"),
            "model_error": row.get("model_error"),
        }
        for row in records
        if row.get("model_error")
    ]
    gate_model_errors = [
        row for row in model_errors if row["condition"] in GATE_CONDITIONS
    ]

    condition_pass_rates = {}
    for condition in CONDITIONS:
        expected_ids = covered_ids if condition == "D_oracle" else task_ids
        condition_pass_rates[condition] = _pass_rate(
            rows_by_condition[condition],
            expected_ids,
        )

    report_only = {
        "condition_pass_rates": condition_pass_rates,
        "B_vs_A_covered": _paired_report(
            raw_rows,
            fts_rows,
            covered_ids,
            "B_fts covered",
            repetitions=repetitions,
            seed=seed,
        ),
        "E_vs_A_covered": _paired_report(
            raw_rows,
            wrong_rows,
            covered_ids,
            "E_wrong covered",
            repetitions=repetitions,
            seed=seed,
        ),
        "failure_category_counts": {
            "C_hybrid": _failure_counts(hybrid_rows),
            "D_oracle": _failure_counts(oracle_rows),
        },
    }

    counts = {
        "tasks": len(task_ids),
        "covered": len(covered_ids),
        "uncovered": len(uncovered_ids),
        "direct": len(direct_ids),
        "partial": len(partial_ids),
        "reasoning_only": len(reasoning_ids),
        "model_errors": len(model_errors),
        "gate_model_errors": len(gate_model_errors),
    }

    if gate_model_errors:
        return {
            "valid_run": False,
            "adopt": None,
            "invalid_reasons": [
                "model_error present in one or more gate conditions (A_raw, C_hybrid, D_oracle)"
            ],
            "model_errors": model_errors,
            "gate_model_errors": gate_model_errors,
            "report_only": report_only,
            "counts": counts,
        }

    raw_all = _passes(raw_rows, task_ids, "A_raw")
    hybrid_all = _passes(hybrid_rows, task_ids, "C_hybrid")
    raw_covered = _passes(raw_rows, covered_ids, "A_raw covered")
    hybrid_covered = _passes(hybrid_rows, covered_ids, "C_hybrid covered")
    raw_uncovered = _passes(raw_rows, uncovered_ids, "A_raw uncovered")
    hybrid_uncovered = _passes(hybrid_rows, uncovered_ids, "C_hybrid uncovered")
    raw_reasoning = _passes(raw_rows, reasoning_ids, "A_raw reasoning-only")
    hybrid_reasoning = _passes(
        hybrid_rows,
        reasoning_ids,
        "C_hybrid reasoning-only",
    )
    raw_direct = _passes(raw_rows, direct_ids, "A_raw direct")
    hybrid_direct = _passes(hybrid_rows, direct_ids, "C_hybrid direct")
    raw_partial = _passes(raw_rows, partial_ids, "A_raw partial")
    hybrid_partial = _passes(hybrid_rows, partial_ids, "C_hybrid partial")
    oracle_covered = _passes(oracle_rows, covered_ids, "D_oracle covered")

    decision = decide_adoption(
        raw_all,
        hybrid_all,
        raw_covered,
        hybrid_covered,
        raw_uncovered,
        hybrid_uncovered,
        raw_reasoning,
        hybrid_reasoning,
        raw_direct,
        hybrid_direct,
        raw_partial,
        hybrid_partial,
        oracle_covered,
        repetitions=repetitions,
        seed=seed,
    )

    report_only.update(
        {
            "overall": asdict(decision.overall_report),
            "direct": asdict(decision.direct_report),
            "partial": asdict(decision.partial_report),
        }
    )

    return {
        "valid_run": True,
        "adopt": decision.adopt,
        "reasons": list(decision.reasons),
        "covered_primary": asdict(decision.covered_primary),
        "uncovered_net_tasks_worse": decision.uncovered_net_tasks_worse,
        "reasoning_only_worse_count": decision.reasoning_only_worse_count,
        "oracle_covered_pass_rate": decision.oracle_covered_pass_rate,
        "report_only": report_only,
        "counts": counts,
        "model_errors": model_errors,
        "gate_model_errors": [],
    }


def analyze_run(
    logs_dir: Path,
    tasks_path: Path,
    oracle_path: Path,
    manifest_path: Path,
) -> dict:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

    expected_script_hash = str(
        manifest.get("analysis_script_sha256", "")
    ).strip().lower()
    actual_script_hash = sha256_file(Path(__file__).resolve())
    if expected_script_hash != actual_script_hash:
        raise ValueError(
            "analysis_script_sha256 mismatch: "
            f"manifest={expected_script_hash!r}, actual={actual_script_hash!r}"
        )
    verify_frozen_code_hashes(manifest)

    tasks = load_tasks(tasks_path)
    oracle = load_oracle(oracle_path)
    records = load_summary(logs_dir)
    return analyze_records(
        records,
        tasks,
        oracle,
        repetitions=int(manifest["bootstrap_repetitions"]),
        seed=int(manifest["bootstrap_seed"]),
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Apply the frozen Phase 0B adoption gate to run logs."
    )
    parser.add_argument("--logs", required=True, type=Path)
    parser.add_argument("--tasks", required=True, type=Path)
    parser.add_argument("--oracle", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    result = analyze_run(
        args.logs,
        args.tasks,
        args.oracle,
        args.manifest,
    )
    rendered = json.dumps(result, indent=2, sort_keys=True)
    print(rendered)
    if args.output:
        args.output.write_text(rendered + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
