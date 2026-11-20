import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from analyze_results import analyze_records
from schema import HeldOutTask, KnowledgeDependency


def task(task_id: str, kind: KnowledgeDependency) -> HeldOutTask:
    return HeldOutTask(
        task_id=task_id,
        language="python",
        instruction=f"task {task_id}",
        prefix=f"def f_{task_id}():\n    ",
        suffix="\n",
        test_code="assert True\n",
        knowledge_dependency=kind,
    )


def fixture():
    tasks = [
        task("c1", KnowledgeDependency.REASONING_ONLY),
        task("c2", KnowledgeDependency.API_KNOWLEDGE),
        task("c3", KnowledgeDependency.API_KNOWLEDGE_PLUS_ADAPTATION),
        task("c4", KnowledgeDependency.ALGORITHMIC_REASONING),
        task("u1", KnowledgeDependency.REASONING_ONLY),
        task("u2", KnowledgeDependency.API_KNOWLEDGE),
        task("u3", KnowledgeDependency.API_KNOWLEDGE_PLUS_ADAPTATION),
        task("u4", KnowledgeDependency.ALGORITHMIC_REASONING),
    ]
    oracle = {
        "c1": {"task_id": "c1", "coverage": "covered", "strength": "direct", "item_ids": ["x"]},
        "c2": {"task_id": "c2", "coverage": "covered", "strength": "direct", "item_ids": ["x"]},
        "c3": {"task_id": "c3", "coverage": "covered", "strength": "direct", "item_ids": ["x"]},
        "c4": {"task_id": "c4", "coverage": "covered", "strength": "partial", "item_ids": ["x"]},
        "u1": {"task_id": "u1", "coverage": "uncovered", "item_ids": []},
        "u2": {"task_id": "u2", "coverage": "uncovered", "item_ids": []},
        "u3": {"task_id": "u3", "coverage": "uncovered", "item_ids": []},
        "u4": {"task_id": "u4", "coverage": "uncovered", "item_ids": []},
    }

    def taxonomy(**overrides):
        base = {
            "retrieval_failure": False,
            "ranking_failure": False,
            "adaptation_failure": False,
            "verification_failure": False,
        }
        base.update(overrides)
        return base

    records = []
    for t in tasks:
        covered = t.task_id.startswith("c")
        records.extend([
            {"task_id": t.task_id, "condition": "A_raw", "passed": False},
            {
                "task_id": t.task_id,
                "condition": "B_fts",
                "passed": covered and t.task_id in {"c1", "c2"},
            },
            {
                "task_id": t.task_id,
                "condition": "C_hybrid",
                "passed": covered,
                "failure_taxonomy": taxonomy(
                    retrieval_failure=t.task_id == "u1",
                    verification_failure=not covered,
                ),
            },
            {
                "task_id": t.task_id,
                "condition": "E_wrong",
                "passed": covered and t.task_id == "c1",
            },
            {
                "task_id": t.task_id,
                "condition": "F_1_5b_raw",
                "passed": t.task_id in {"c1", "c2", "u1"},
            },
        ])
    for task_id, passed in [("c1", True), ("c2", True), ("c3", True), ("c4", False)]:
        records.append({
            "task_id": task_id,
            "condition": "D_oracle",
            "passed": passed,
            "failure_taxonomy": taxonomy(
                adaptation_failure=task_id == "c4",
                verification_failure=not passed,
            ),
        })
    return tasks, oracle, records


class AnalyzeResultsTest(unittest.TestCase):
    def test_frozen_gate_passes_good_synthetic_logs(self):
        tasks, oracle, records = fixture()
        result = analyze_records(records, tasks, oracle, repetitions=1000, seed=7)
        self.assertTrue(result["valid_run"])
        self.assertTrue(result["adopt"], result["reasons"])
        self.assertGreaterEqual(result["covered_primary"]["point_estimate"], 0.25)
        self.assertGreater(result["covered_primary"]["lower_95"], 0.05)
        self.assertEqual(0, result["uncovered_net_tasks_worse"])
        self.assertGreaterEqual(result["oracle_covered_pass_rate"], 0.60)
        self.assertEqual(3, result["counts"]["direct"])
        self.assertEqual(1, result["counts"]["partial"])

    def test_gate_condition_model_error_invalidates_run_and_removes_verdict(self):
        tasks, oracle, records = fixture()
        row = next(
            r
            for r in records
            if r["condition"] == "C_hybrid" and r["task_id"] == "c2"
        )
        row["model_error"] = "timeout"
        row["passed"] = False

        result = analyze_records(records, tasks, oracle, repetitions=1000, seed=7)
        self.assertFalse(result["valid_run"])
        self.assertIsNone(result["adopt"])
        self.assertEqual(1, result["counts"]["gate_model_errors"])
        self.assertEqual("timeout", result["gate_model_errors"][0]["model_error"])
        self.assertNotIn("covered_primary", result)

    def test_non_gate_model_error_is_reported_but_does_not_invalidate_run(self):
        tasks, oracle, records = fixture()
        row = next(
            r
            for r in records
            if r["condition"] == "B_fts" and r["task_id"] == "c2"
        )
        row["model_error"] = "timeout"
        row["passed"] = False

        result = analyze_records(records, tasks, oracle, repetitions=1000, seed=7)
        self.assertTrue(result["valid_run"])
        self.assertEqual(1, result["counts"]["model_errors"])
        self.assertEqual(0, result["counts"]["gate_model_errors"])

    def test_report_only_contains_all_condition_rates_and_controls(self):
        tasks, oracle, records = fixture()
        result = analyze_records(records, tasks, oracle, repetitions=1000, seed=7)
        rates = result["report_only"]["condition_pass_rates"]
        self.assertEqual(
            {"A_raw", "B_fts", "C_hybrid", "D_oracle", "E_wrong", "F_1_5b_raw"},
            set(rates),
        )
        self.assertEqual(4, rates["D_oracle"]["total"])
        self.assertEqual(8, rates["A_raw"]["total"])
        self.assertIn("B_vs_A_covered", result["report_only"])
        self.assertIn("E_vs_A_covered", result["report_only"])

    def test_report_only_failure_counts_for_c_and_d(self):
        tasks, oracle, records = fixture()
        result = analyze_records(records, tasks, oracle, repetitions=1000, seed=7)
        failures = result["report_only"]["failure_category_counts"]
        self.assertEqual(1, failures["C_hybrid"]["retrieval_failure"])
        self.assertEqual(4, failures["C_hybrid"]["verification_failure"])
        self.assertEqual(1, failures["D_oracle"]["adaptation_failure"])
        self.assertEqual(1, failures["D_oracle"]["verification_failure"])

    def test_uncovered_control_blocks_four_net_losses(self):
        tasks, oracle, records = fixture()
        for row in records:
            if row["condition"] == "A_raw" and row["task_id"].startswith("u"):
                row["passed"] = True
        result = analyze_records(records, tasks, oracle, repetitions=1000, seed=7)
        self.assertFalse(result["adopt"])
        self.assertEqual(4, result["uncovered_net_tasks_worse"])

    def test_reasoning_guard_blocks_more_than_two_worse(self):
        tasks, oracle, records = fixture()
        for name in ("r2", "r3"):
            tasks.append(task(name, KnowledgeDependency.REASONING_ONLY))
            oracle[name] = {"task_id": name, "coverage": "uncovered", "item_ids": []}
            records.extend([
                {"task_id": name, "condition": "A_raw", "passed": True},
                {"task_id": name, "condition": "B_fts", "passed": False},
                {
                    "task_id": name,
                    "condition": "C_hybrid",
                    "passed": False,
                    "failure_taxonomy": {},
                },
                {"task_id": name, "condition": "E_wrong", "passed": False},
                {"task_id": name, "condition": "F_1_5b_raw", "passed": False},
            ])
        for row in records:
            if row["task_id"] == "u1" and row["condition"] == "A_raw":
                row["passed"] = True
        result = analyze_records(records, tasks, oracle, repetitions=1000, seed=7)
        self.assertFalse(result["adopt"])
        self.assertEqual(3, result["reasoning_only_worse_count"])

    def test_oracle_gate_blocks_below_sixty_percent(self):
        tasks, oracle, records = fixture()
        for row in records:
            if row["condition"] == "D_oracle":
                row["passed"] = row["task_id"] in {"c1", "c2"}
        result = analyze_records(records, tasks, oracle, repetitions=1000, seed=7)
        self.assertFalse(result["adopt"])
        self.assertEqual(0.5, result["oracle_covered_pass_rate"])

    def test_missing_primary_result_is_rejected(self):
        tasks, oracle, records = fixture()
        records = [
            r
            for r in records
            if not (
                r["condition"] == "C_hybrid"
                and r["task_id"] == "c1"
            )
        ]
        with self.assertRaisesRegex(ValueError, "missing C_hybrid"):
            analyze_records(records, tasks, oracle, repetitions=1000, seed=7)


if __name__ == "__main__":
    unittest.main()
