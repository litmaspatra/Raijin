from __future__ import annotations

import random
from dataclasses import dataclass
from typing import Sequence


@dataclass(frozen=True)
class PairedDifference:
    point_estimate: float
    lower_95: float
    upper_95: float


@dataclass(frozen=True)
class AdoptionDecision:
    adopt: bool
    covered_primary: PairedDifference
    overall_report: PairedDifference
    direct_report: PairedDifference
    partial_report: PairedDifference
    uncovered_net_tasks_worse: int
    reasoning_only_worse_count: int
    oracle_covered_pass_rate: float
    reasons: tuple[str, ...]


def _rate(values: Sequence[bool]) -> float:
    if not values:
        raise ValueError("comparison requires at least one task")
    return sum(bool(value) for value in values) / len(values)


def paired_bootstrap_difference(
    raw_passes: Sequence[bool],
    retrieval_passes: Sequence[bool],
    *,
    repetitions: int = 10_000,
    seed: int = 0,
) -> PairedDifference:
    if len(raw_passes) != len(retrieval_passes):
        raise ValueError("paired conditions must contain the same number of tasks")
    if not raw_passes:
        raise ValueError("paired comparison requires at least one task")
    if repetitions < 1_000:
        raise ValueError("use at least 1000 bootstrap repetitions")

    paired = list(zip(raw_passes, retrieval_passes))
    point = _rate(retrieval_passes) - _rate(raw_passes)
    rng = random.Random(seed)
    diffs: list[float] = []
    n = len(paired)

    for _ in range(repetitions):
        sample = [paired[rng.randrange(n)] for _ in range(n)]
        raw_rate = sum(raw for raw, _ in sample) / n
        retrieval_rate = sum(retrieval for _, retrieval in sample) / n
        diffs.append(retrieval_rate - raw_rate)

    diffs.sort()
    lower_index = int(0.025 * (repetitions - 1))
    upper_index = int(0.975 * (repetitions - 1))
    return PairedDifference(
        point_estimate=point,
        lower_95=diffs[lower_index],
        upper_95=diffs[upper_index],
    )


def decide_adoption(
    raw_all: Sequence[bool],
    hybrid_all: Sequence[bool],
    raw_covered: Sequence[bool],
    hybrid_covered: Sequence[bool],
    raw_uncovered: Sequence[bool],
    hybrid_uncovered: Sequence[bool],
    raw_reasoning: Sequence[bool],
    hybrid_reasoning: Sequence[bool],
    raw_direct: Sequence[bool],
    hybrid_direct: Sequence[bool],
    raw_partial: Sequence[bool],
    hybrid_partial: Sequence[bool],
    oracle_covered: Sequence[bool],
    *,
    repetitions: int = 10_000,
    seed: int = 0,
) -> AdoptionDecision:
    covered_primary = paired_bootstrap_difference(
        raw_covered,
        hybrid_covered,
        repetitions=repetitions,
        seed=seed,
    )
    overall_report = paired_bootstrap_difference(
        raw_all,
        hybrid_all,
        repetitions=repetitions,
        seed=seed,
    )
    direct_report = paired_bootstrap_difference(
        raw_direct,
        hybrid_direct,
        repetitions=repetitions,
        seed=seed,
    )
    partial_report = paired_bootstrap_difference(
        raw_partial,
        hybrid_partial,
        repetitions=repetitions,
        seed=seed,
    )

    if len(raw_uncovered) != len(hybrid_uncovered):
        raise ValueError("uncovered conditions must be paired")
    if len(raw_reasoning) != len(hybrid_reasoning):
        raise ValueError("reasoning-only conditions must be paired")

    # Positive means hybrid passed fewer uncovered tasks than raw.
    uncovered_net_tasks_worse = sum(bool(x) for x in raw_uncovered) - sum(
        bool(x) for x in hybrid_uncovered
    )
    reasoning_only_worse_count = sum(
        raw and not hybrid
        for raw, hybrid in zip(raw_reasoning, hybrid_reasoning)
    )
    oracle_covered_pass_rate = _rate(oracle_covered)

    reasons: list[str] = []
    if covered_primary.point_estimate < 0.25:
        reasons.append("hybrid lift on covered tasks is below +25 percentage points")
    if covered_primary.lower_95 <= 0.05:
        reasons.append(
            "covered-task 95% paired-bootstrap CI lower bound is not above +5 percentage points"
        )
    if uncovered_net_tasks_worse > 3:
        reasons.append("hybrid is more than 3 net tasks worse than raw on uncovered tasks")
    if reasoning_only_worse_count > 2:
        reasons.append("hybrid is worse on more than 2 reasoning-only tasks")
    if oracle_covered_pass_rate < 0.60:
        reasons.append("oracle pass rate on covered tasks is below 60%")

    return AdoptionDecision(
        adopt=not reasons,
        covered_primary=covered_primary,
        overall_report=overall_report,
        direct_report=direct_report,
        partial_report=partial_report,
        uncovered_net_tasks_worse=uncovered_net_tasks_worse,
        reasoning_only_worse_count=reasoning_only_worse_count,
        oracle_covered_pass_rate=oracle_covered_pass_rate,
        reasons=tuple(reasons),
    )
