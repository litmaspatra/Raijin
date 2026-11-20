from __future__ import annotations

from dataclasses import dataclass
from typing import Mapping, Sequence


@dataclass(frozen=True)
class CoverageSplit:
    overall_task_ids: tuple[str, ...]
    covered_task_ids: tuple[str, ...]
    uncovered_task_ids: tuple[str, ...]

    @property
    def coverage_rate(self) -> float:
        if not self.overall_task_ids:
            return 0.0
        return len(self.covered_task_ids) / len(self.overall_task_ids)


def split_coverage(
    task_ids: Sequence[str],
    oracle_mapping: Mapping[str, Sequence[str]],
) -> CoverageSplit:
    unique = tuple(dict.fromkeys(task_ids))
    if len(unique) != len(task_ids):
        raise ValueError("task_ids must be unique")

    unknown = sorted(set(oracle_mapping) - set(unique))
    if unknown:
        raise ValueError(f"oracle mapping contains unknown task IDs: {unknown}")

    covered = tuple(task_id for task_id in unique if oracle_mapping.get(task_id))
    uncovered = tuple(task_id for task_id in unique if not oracle_mapping.get(task_id))
    return CoverageSplit(
        overall_task_ids=unique,
        covered_task_ids=covered,
        uncovered_task_ids=uncovered,
    )
