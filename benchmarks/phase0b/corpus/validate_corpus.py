from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from typing import Iterable

from schema import CodeKnowledgeKind, KnowledgeItem

ALLOWED_KINDS = {
    CodeKnowledgeKind.API_FACT,
    CodeKnowledgeKind.PATTERN,
    CodeKnowledgeKind.PROCEDURE,
    CodeKnowledgeKind.REPAIR,
}
TARGET_COUNTS = {
    CodeKnowledgeKind.API_FACT: 80,
    CodeKnowledgeKind.PATTERN: 70,
    CodeKnowledgeKind.PROCEDURE: 30,
    CodeKnowledgeKind.REPAIR: 20,
}
MAX_TOKENISH = 150
MAX_MODULE_ITEMS = 6
TOKENISH_RE = re.compile(r"\w+|[^\w\s]", re.UNICODE)


def tokenish_count(text: str) -> int:
    return len(TOKENISH_RE.findall(text))


def module_from_title(title: str) -> str:
    if ":" not in title:
        raise ValueError(f"title must begin with '<module>:': {title!r}")
    module = title.split(":", 1)[0].strip()
    if not module:
        raise ValueError(f"blank module prefix in title: {title!r}")
    return module


def load_corpus(path: Path) -> list[KnowledgeItem]:
    items: list[KnowledgeItem] = []
    for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            raw = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"invalid JSON on line {line_no}: {exc}") from exc
        extra = set(raw) - {"item_id", "kind", "language", "title", "content", "verified"}
        if extra:
            raise ValueError(f"{raw.get('item_id', line_no)} has extra fields: {sorted(extra)}")
        item = KnowledgeItem.from_dict(raw)
        items.append(item)
    return items


def load_verification(path: Path) -> dict[str, dict]:
    records: dict[str, dict] = {}
    for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        raw = json.loads(line)
        item_id = str(raw["item_id"])
        if item_id in records:
            raise ValueError(f"duplicate verification record: {item_id}")
        records[item_id] = raw
    return records


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate(
    corpus_path: Path,
    verification_path: Path,
    *,
    exact_size: int = 200,
) -> dict:
    items = load_corpus(corpus_path)
    if len(items) != exact_size:
        raise ValueError(f"corpus must contain exactly {exact_size} items; found {len(items)}")

    ids = [item.item_id for item in items]
    if len(ids) != len(set(ids)):
        raise ValueError("corpus item IDs must be unique")

    counts = Counter(item.kind for item in items)
    if counts != Counter(TARGET_COUNTS):
        raise ValueError(
            "kind distribution mismatch: "
            + ", ".join(f"{kind.value}={counts[kind]}" for kind in TARGET_COUNTS)
        )

    modules = Counter()
    for item in items:
        if item.kind not in ALLOWED_KINDS:
            raise ValueError(f"{item.item_id}: unsupported corpus kind {item.kind.value}")
        if item.language != "python":
            raise ValueError(f"{item.item_id}: language must be python")
        if tokenish_count(item.content) > MAX_TOKENISH:
            raise ValueError(
                f"{item.item_id}: content exceeds {MAX_TOKENISH} token-like units "
                f"({tokenish_count(item.content)})"
            )
        module = module_from_title(item.title)
        modules[module] += 1

    overfull = {module: count for module, count in modules.items() if count > MAX_MODULE_ITEMS}
    if overfull:
        raise ValueError(f"breadth cap exceeded: {overfull}")

    verification = load_verification(verification_path)
    verified_ids = {item.item_id for item in items if item.verified}
    if set(verification) != verified_ids:
        missing = sorted(verified_ids - verification.keys())
        extra = sorted(verification.keys() - verified_ids)
        raise ValueError(f"verification IDs mismatch; missing={missing}, extra={extra}")

    for item in items:
        if not item.verified:
            continue
        record = verification[item.item_id]
        expected_sha = sha256_text(item.content)
        if record.get("content_sha256") != expected_sha:
            raise ValueError(f"{item.item_id}: verification content hash mismatch")
        if record.get("sandbox") != "docker":
            raise ValueError(f"{item.item_id}: verification was not recorded as docker")
        if record.get("passed") is not True or record.get("returncode") != 0:
            raise ValueError(f"{item.item_id}: verified=true but execution did not pass")

    return {
        "item_count": len(items),
        "kind_counts": {kind.value: counts[kind] for kind in TARGET_COUNTS},
        "module_counts": dict(sorted(modules.items())),
        "corpus_sha256": sha256_file(corpus_path),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("corpus", type=Path)
    parser.add_argument("verification", type=Path)
    args = parser.parse_args()
    print(json.dumps(validate(args.corpus, args.verification), indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
