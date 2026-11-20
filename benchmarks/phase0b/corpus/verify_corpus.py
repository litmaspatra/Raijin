from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import tempfile
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from validate_corpus import load_corpus
from pins import PYTHON_SANDBOX_IMAGE


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def build_command(workdir: Path) -> list[str]:
    return [
        "docker", "run", "--rm",
        "--network", "none",
        "--memory", "128m",
        "--cpus", "0.5",
        "--pids-limit", "64",
        "--read-only",
        "--cap-drop", "ALL",
        "--security-opt", "no-new-privileges",
        "--env", "TMPDIR=/work/tmp",
        "--mount", f"type=bind,src={workdir},dst=/work",
        "--workdir", "/work",
        PYTHON_SANDBOX_IMAGE,
        "python", "-E", "-s", "-B", "verify_inside.py",
    ]


INSIDE_RUNNER = r"""
import json
import subprocess
from pathlib import Path

root = Path("/work")
items = json.loads((root / "items.json").read_text(encoding="utf-8"))
results = []
for item in items:
    path = root / "snippets" / f"{item['item_id']}.py"
    try:
        cp = subprocess.run(
            ["python", "-E", "-s", "-B", str(path)],
            cwd=root / "tmp",
            text=True,
            capture_output=True,
            timeout=5,
            check=False,
        )
        results.append({
            "item_id": item["item_id"],
            "content_sha256": item["content_sha256"],
            "sandbox": "docker",
            "passed": cp.returncode == 0,
            "returncode": cp.returncode,
            "timed_out": False,
            "stderr": cp.stderr[-2000:],
        })
    except subprocess.TimeoutExpired as exc:
        stderr = exc.stderr or ""
        if isinstance(stderr, bytes):
            stderr = stderr.decode("utf-8", errors="replace")
        results.append({
            "item_id": item["item_id"],
            "content_sha256": item["content_sha256"],
            "sandbox": "docker",
            "passed": False,
            "returncode": None,
            "timed_out": True,
            "stderr": stderr[-2000:],
        })
(root / "verification-results.jsonl").write_text(
    "".join(json.dumps(row, sort_keys=True) + "\n" for row in results),
    encoding="utf-8",
)
if not all(row["passed"] for row in results):
    raise SystemExit(1)
"""


def verify(corpus_path: Path, output_path: Path) -> None:
    items = [item for item in load_corpus(corpus_path) if item.verified]
    with tempfile.TemporaryDirectory(prefix="raijin-corpus-") as tmp:
        root = Path(tmp)
        snippets = root / "snippets"
        tmpdir = root / "tmp"
        snippets.mkdir()
        tmpdir.mkdir()
        payload = []
        for item in items:
            (snippets / f"{item.item_id}.py").write_text(item.content, encoding="utf-8")
            payload.append({
                "item_id": item.item_id,
                "content_sha256": sha256_text(item.content),
            })
        (root / "items.json").write_text(json.dumps(payload), encoding="utf-8")
        (root / "verify_inside.py").write_text(INSIDE_RUNNER, encoding="utf-8")
        root.chmod(0o777)
        snippets.chmod(0o755)
        tmpdir.chmod(0o777)
        for path in snippets.iterdir():
            path.chmod(0o644)
        (root / "items.json").chmod(0o644)
        (root / "verify_inside.py").chmod(0o644)

        completed = subprocess.run(build_command(root), text=True, capture_output=True, timeout=120, check=False)
        report = root / "verification-results.jsonl"
        if report.exists():
            output_path.write_text(report.read_text(encoding="utf-8"), encoding="utf-8")
        if completed.returncode != 0:
            raise RuntimeError(
                f"corpus Docker verification failed rc={completed.returncode}: {completed.stderr[-4000:]}"
            )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("corpus", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    verify(args.corpus, args.output)


if __name__ == "__main__":
    main()
