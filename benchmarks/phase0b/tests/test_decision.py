import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from decision import decide_adoption, paired_bootstrap_difference


def decision_fixture(
    *,
    uncovered_raw=None,
    uncovered_hybrid=None,
    reasoning_raw=None,
    reasoning_hybrid=None,
    oracle=None,
):
    raw_covered = [False] * 29
    hybrid_covered = [True] * 15 + [False] * 14
    raw_uncovered = uncovered_raw if uncovered_raw is not None else [False] * 38
    hybrid_uncovered = uncovered_hybrid if uncovered_hybrid is not None else [False] * 38
    raw_reasoning = reasoning_raw if reasoning_raw is not None else [False] * 15
    hybrid_reasoning = reasoning_hybrid if reasoning_hybrid is not None else [True] * 15
    oracle_covered = oracle if oracle is not None else [True] * 20 + [False] * 9

    raw_all = raw_covered + raw_uncovered
    hybrid_all = hybrid_covered + hybrid_uncovered
    # Current mapping has 22 direct and 7 partial covered tasks.
    raw_direct = [False] * 22
    hybrid_direct = [True] * 12 + [False] * 10
    raw_partial = [False] * 7
    hybrid_partial = [True] * 3 + [False] * 4

    return decide_adoption(
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
        repetitions=1000,
        seed=3,
    )


class DecisionTest(unittest.TestCase):
    def test_paired_bootstrap_is_paired_and_deterministic(self):
        raw = [False] * 60
        hybrid = [True] * 60
        result = paired_bootstrap_difference(raw, hybrid, repetitions=1000, seed=7)
        self.assertEqual(1.0, result.point_estimate)
        self.assertEqual(1.0, result.lower_95)
        self.assertEqual(1.0, result.upper_95)

    def test_primary_gate_is_covered_subset_at_twenty_five_points(self):
        result = decision_fixture()
        self.assertTrue(result.adopt, result.reasons)
        self.assertGreaterEqual(result.covered_primary.point_estimate, 0.25)
        self.assertGreater(result.covered_primary.lower_95, 0.05)

    def test_uncovered_more_than_three_net_tasks_worse_blocks_adoption(self):
        raw = [True] * 4 + [False] * 34
        hybrid = [False] * 38
        result = decision_fixture(uncovered_raw=raw, uncovered_hybrid=hybrid)
        self.assertFalse(result.adopt)
        self.assertEqual(4, result.uncovered_net_tasks_worse)
        self.assertTrue(any("uncovered" in reason for reason in result.reasons))

    def test_three_net_uncovered_tasks_worse_is_allowed(self):
        raw = [True] * 3 + [False] * 35
        hybrid = [False] * 38
        result = decision_fixture(uncovered_raw=raw, uncovered_hybrid=hybrid)
        self.assertTrue(result.adopt, result.reasons)
        self.assertEqual(3, result.uncovered_net_tasks_worse)

    def test_more_than_two_reasoning_losses_blocks_adoption(self):
        raw = [True] * 15
        hybrid = [False] * 3 + [True] * 12
        result = decision_fixture(reasoning_raw=raw, reasoning_hybrid=hybrid)
        self.assertFalse(result.adopt)
        self.assertEqual(3, result.reasoning_only_worse_count)

    def test_oracle_below_sixty_percent_blocks_adoption(self):
        oracle = [True] * 17 + [False] * 12
        result = decision_fixture(oracle=oracle)
        self.assertFalse(result.adopt)
        self.assertLess(result.oracle_covered_pass_rate, 0.60)

    def test_overall_direct_partial_are_report_only(self):
        result = decision_fixture()
        self.assertIsNotNone(result.overall_report)
        self.assertIsNotNone(result.direct_report)
        self.assertIsNotNone(result.partial_report)
        self.assertTrue(result.adopt, result.reasons)

    def test_mismatched_pairs_are_rejected(self):
        with self.assertRaises(ValueError):
            paired_bootstrap_difference([True], [True, False], repetitions=1000)


if __name__ == "__main__":
    unittest.main()
