import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from validate_oracle_mapping import load_mapping


class OracleMappingValidatorTest(unittest.TestCase):
    def write_rows(self, rows):
        tmp = tempfile.TemporaryDirectory()
        path = Path(tmp.name) / "mapping.jsonl"
        path.write_text(
            "".join(json.dumps(row) + "\n" for row in rows),
            encoding="utf-8",
        )
        return tmp, path

    def test_covered_row_requires_strength(self):
        tmp, path = self.write_rows([
            {
                "task_id": "x",
                "item_ids": ["api-001"],
                "coverage": "covered",
                "justification": "Direct API match.",
            }
        ])
        try:
            with self.assertRaisesRegex(ValueError, "strength"):
                load_mapping(path)
        finally:
            tmp.cleanup()

    def test_covered_strength_must_be_direct_or_partial(self):
        tmp, path = self.write_rows([
            {
                "task_id": "x",
                "item_ids": ["api-001"],
                "coverage": "covered",
                "strength": "weak",
                "justification": "Invalid strength.",
            }
        ])
        try:
            with self.assertRaisesRegex(ValueError, "strength"):
                load_mapping(path)
        finally:
            tmp.cleanup()

    def test_uncovered_row_must_not_have_strength(self):
        tmp, path = self.write_rows([
            {
                "task_id": "x",
                "item_ids": [],
                "coverage": "uncovered",
                "strength": "direct",
                "justification": "No match.",
            }
        ])
        try:
            with self.assertRaisesRegex(ValueError, "extra|must not carry strength"):
                load_mapping(path)
        finally:
            tmp.cleanup()

    def test_direct_and_partial_rows_load(self):
        tmp, path = self.write_rows([
            {
                "task_id": "a",
                "item_ids": ["api-001"],
                "coverage": "covered",
                "strength": "direct",
                "justification": "Direct match.",
            },
            {
                "task_id": "b",
                "item_ids": ["api-002"],
                "coverage": "covered",
                "strength": "partial",
                "justification": "Partial match.",
            },
            {
                "task_id": "c",
                "item_ids": [],
                "coverage": "uncovered",
                "justification": "No substantive match.",
            },
        ])
        try:
            rows = load_mapping(path)
            self.assertEqual(["direct", "partial"], [rows[0]["strength"], rows[1]["strength"]])
            self.assertNotIn("strength", rows[2])
        finally:
            tmp.cleanup()


if __name__ == "__main__":
    unittest.main()
