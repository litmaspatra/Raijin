from __future__ import annotations

import argparse
import json
from pathlib import Path

from harness import (
    load_reference_solutions,
    load_tasks,
    sha256_file,
    validate_distribution,
    validate_task_count,
    validate_vacuity,
)


def _require_manifest_hash(manifest: dict, key: str, path: Path) -> str:
    recorded = str(manifest.get(key, "")).strip().lower()
    if not recorded or recorded.startswith("fill_"):
        raise ValueError(f"manifest {key} is not frozen")
    actual = sha256_file(path)
    if actual != recorded:
        raise ValueError(f"{key} mismatch: manifest={recorded}, actual={actual}")
    return actual


def validate_heldout(tasks_path: Path, reference_path: Path, manifest_path: Path) -> dict:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

    task_hash = _require_manifest_hash(manifest, "heldout_sha256", tasks_path)
    reference_hash = _require_manifest_hash(
        manifest,
        "reference_solutions_sha256",
        reference_path,
    )

    tasks = load_tasks(tasks_path)
    validate_task_count(tasks, minimum=int(manifest.get("minimum_heldout_tasks", 60)))
    validate_distribution(tasks)

    references = load_reference_solutions(reference_path)
    vacuity = validate_vacuity(tasks, references)

    distribution: dict[str, int] = {}
    for task in tasks:
        distribution[task.knowledge_dependency.value] = (
            distribution.get(task.knowledge_dependency.value, 0) + 1
        )

    return {
        "task_count": len(tasks),
        "distribution": dict(sorted(distribution.items())),
        "heldout_sha256": task_hash,
        "reference_solutions_sha256": reference_hash,
        "null_failures": vacuity.null_failures,
        "reference_passes": vacuity.reference_passes,
        "docker_vacuity_validated": True,
    }


def main() -> None:
    parser = argparse.ArgumentParser(
        description=(
            "Validate the frozen Phase 0B held-out set against private reference "
            "solutions using the real Docker execution path."
        )
    )
    parser.add_argument("--tasks", required=True, type=Path)
    parser.add_argument("--reference", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    args = parser.parse_args()

    print(
        json.dumps(
            validate_heldout(args.tasks, args.reference, args.manifest),
            indent=2,
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
