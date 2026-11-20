import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "corpus"))

from validate_corpus import (
    MAX_TOKENISH,
    TARGET_COUNTS,
    module_from_title,
    tokenish_count,
    validate,
)


class CorpusValidatorTest(unittest.TestCase):
    def test_tokenish_cap_helper(self):
        self.assertEqual(3, tokenish_count("a + b"))
        self.assertLessEqual(tokenish_count("x = 1"), MAX_TOKENISH)

    def test_module_prefix_is_required(self):
        self.assertEqual("json", module_from_title("json: loads"))
        with self.assertRaises(ValueError):
            module_from_title("missing module")

    def test_distribution_is_exactly_preregistered(self):
        self.assertEqual(
            {"api_fact": 80, "pattern": 70, "procedure": 30, "repair": 20},
            {kind.value: count for kind, count in TARGET_COUNTS.items()},
        )

    def test_validator_rejects_unexecuted_verified_item(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            corpus = root / "corpus.jsonl"
            verification = root / "verification.jsonl"
            rows = []
            n = 0
            for kind, count in TARGET_COUNTS.items():
                for i in range(count):
                    rows.append({
                        "item_id": f"{kind.value}-{i}",
                        "kind": kind.value,
                        "language": "python",
                        "title": f"m{n}: x",
                        "content": "assert True\n",
                        "verified": True,
                    })
                    n += 1
            corpus.write_text(
                "".join(json.dumps(row) + "\n" for row in rows),
                encoding="utf-8",
            )
            verification.write_text("", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "verification IDs mismatch"):
                validate(corpus, verification)

    def test_validator_rejects_content_hash_mismatch(self):
        import hashlib
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            corpus = root / "corpus.jsonl"
            verification = root / "verification.jsonl"
            rows = []
            reports = []
            n = 0
            for kind, count in TARGET_COUNTS.items():
                for i in range(count):
                    item_id = f"{kind.value}-{i}"
                    content = "assert True\n"
                    rows.append({
                        "item_id": item_id,
                        "kind": kind.value,
                        "language": "python",
                        "title": f"m{n}: x",
                        "content": content,
                        "verified": True,
                    })
                    reports.append({
                        "item_id": item_id,
                        "content_sha256": "0" * 64,
                        "sandbox": "docker",
                        "passed": True,
                        "returncode": 0,
                    })
                    n += 1
            corpus.write_text("".join(json.dumps(x) + "\n" for x in rows), encoding="utf-8")
            verification.write_text("".join(json.dumps(x) + "\n" for x in reports), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "content hash mismatch"):
                validate(corpus, verification)


if __name__ == "__main__":
    unittest.main()
