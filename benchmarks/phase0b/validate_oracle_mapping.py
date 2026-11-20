from __future__ import annotations

import argparse
import json
from pathlib import Path

from corpus.validate_corpus import load_corpus
from harness import load_tasks, sha256_file


VALID_STRENGTHS = {"direct", "partial"}


def load_mapping(path: Path) -> list[dict]:
    rows: list[dict] = []
    for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"invalid JSON on mapping line {line_no}: {exc}") from exc

        coverage = row.get("coverage")
        required = {"task_id", "item_ids", "coverage", "justification"}
        if coverage == "covered":
            required.add("strength")
        allowed = set(required)

        missing = required - set(row)
        extra = set(row) - allowed
        if missing or extra:
            raise ValueError(
                f"mapping line {line_no} fields invalid; missing={sorted(missing)}, extra={sorted(extra)}"
            )

        if coverage not in {"covered", "uncovered"}:
            raise ValueError(f"{row.get('task_id', line_no)}: coverage must be covered or uncovered")
        if not isinstance(row["item_ids"], list):
            raise ValueError(f"{row['task_id']}: item_ids must be a list")
        if len(row["item_ids"]) != len(set(row["item_ids"])):
            raise ValueError(f"{row['task_id']}: duplicate corpus item IDs")

        if coverage == "covered":
            if row["strength"] not in VALID_STRENGTHS:
                raise ValueError(
                    f"{row['task_id']}: covered strength must be one of {sorted(VALID_STRENGTHS)}"
                )
        elif "strength" in row:
            raise ValueError(f"{row['task_id']}: uncovered task must not carry strength")

        justification = str(row["justification"])
        if not justification.strip() or "\n" in justification or "\r" in justification:
            raise ValueError(f"{row['task_id']}: justification must be one nonblank line")
        rows.append(row)
    return rows


def validate_oracle_mapping(
    tasks_path: Path,
    corpus_path: Path,
    mapping_path: Path,
    manifest_path: Path,
    *,
    require_mapping_hash: bool,
) -> dict:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

    heldout_hash = sha256_file(tasks_path)
    if heldout_hash != str(manifest.get("heldout_sha256", "")).strip().lower():
        raise ValueError("heldout_sha256 mismatch")

    corpus_hash = sha256_file(corpus_path)
    if corpus_hash != str(manifest.get("corpus_sha256", "")).strip().lower():
        raise ValueError("corpus_sha256 mismatch")

    mapping_hash = sha256_file(mapping_path)
    if require_mapping_hash:
        recorded = str(manifest.get("oracle_mapping_sha256", "")).strip().lower()
        if mapping_hash != recorded:
            raise ValueError(
                f"oracle_mapping_sha256 mismatch: manifest={recorded!r}, actual={mapping_hash!r}"
            )

    tasks = load_tasks(tasks_path)
    task_ids = [task.task_id for task in tasks]
    mapping = load_mapping(mapping_path)
    mapped_ids = [row["task_id"] for row in mapping]

    if len(mapped_ids) != len(set(mapped_ids)):
        raise ValueError("oracle mapping contains duplicate task IDs")
    if set(mapped_ids) != set(task_ids):
        missing = sorted(set(task_ids) - set(mapped_ids))
        extra = sorted(set(mapped_ids) - set(task_ids))
        raise ValueError(f"oracle mapping task IDs mismatch; missing={missing}, extra={extra}")

    corpus = load_corpus(corpus_path)
    corpus_by_id = {item.item_id: item for item in corpus}

    covered = 0
    uncovered = 0
    direct = 0
    partial = 0
    for row in mapping:
        ids = row["item_ids"]
        if row["coverage"] == "covered":
            if not ids:
                raise ValueError(f"{row['task_id']}: covered task must list corpus items")
            covered += 1
            if row["strength"] == "direct":
                direct += 1
            else:
                partial += 1
        else:
            if ids:
                raise ValueError(f"{row['task_id']}: uncovered task must have [] item_ids")
            uncovered += 1

        for item_id in ids:
            item = corpus_by_id.get(item_id)
            if item is None:
                raise ValueError(f"{row['task_id']}: unknown corpus item {item_id}")
            if not item.verified:
                raise ValueError(f"{row['task_id']}: unverified corpus item {item_id} cannot be mapped")

    return {
        "task_count": len(tasks),
        "mapped_count": len(mapping),
        "covered": covered,
        "uncovered": uncovered,
        "direct": direct,
        "partial": partial,
        "heldout_sha256": heldout_hash,
        "corpus_sha256": corpus_hash,
        "oracle_mapping_sha256": mapping_hash,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tasks", required=True, type=Path)
    parser.add_argument("--corpus", required=True, type=Path)
    parser.add_argument("--mapping", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--require-manifest-hash", action="store_true")
    args = parser.parse_args()

    result = validate_oracle_mapping(
        args.tasks,
        args.corpus,
        args.mapping,
        args.manifest,
        require_mapping_hash=args.require_manifest_hash,
    )
    print(json.dumps(result, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
