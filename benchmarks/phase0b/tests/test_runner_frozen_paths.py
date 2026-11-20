import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import run_benchmark


MANIFEST_PATH = ROOT / "manifest.example.json"


class FrozenCodePathTest(unittest.TestCase):
    def test_every_frozen_code_path_exists(self):
        missing = {
            key: str(path)
            for key, path in run_benchmark.FROZEN_CODE_PATHS.items()
            if not path.exists()
        }
        self.assertEqual({}, missing)

    def test_real_manifest_verifies_frozen_code_hashes(self):
        manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        run_benchmark.verify_frozen_code_hashes(manifest)


if __name__ == "__main__":
    unittest.main()
