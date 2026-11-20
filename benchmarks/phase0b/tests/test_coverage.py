import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from coverage import split_coverage


class CoverageTest(unittest.TestCase):
    def test_uncovered_tasks_remain_in_overall_denominator(self):
        split = split_coverage(
            ["a", "b", "c"],
            {"a": ["item-1"], "b": [], "c": []},
        )
        self.assertEqual(("a", "b", "c"), split.overall_task_ids)
        self.assertEqual(("a",), split.covered_task_ids)
        self.assertEqual(("b", "c"), split.uncovered_task_ids)
        self.assertAlmostEqual(1 / 3, split.coverage_rate)

    def test_oracle_mapping_cannot_introduce_unknown_tasks(self):
        with self.assertRaises(ValueError):
            split_coverage(["a"], {"unknown": ["item-1"]})


if __name__ == "__main__":
    unittest.main()
