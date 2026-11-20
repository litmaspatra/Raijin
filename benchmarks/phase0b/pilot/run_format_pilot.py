from __future__ import annotations

import ast
import hashlib
import json
import os
import platform
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
import sys
sys.path.insert(0, str(ROOT))

from harness import OutputFormat, assemble_candidate, render_fim_prompt
from schema import HeldOutTask


HERE = Path(__file__).resolve().parent
TASKS = json.loads((HERE / "tasks.json").read_text(encoding="utf-8"))
OUT = HERE / "pilot-output.jsonl"
META = HERE / "pilot-metadata.json"


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def main() -> None:
    llama_bin = os.environ.get("LLAMA_BIN", "llama")
    model_path = os.environ.get("RAIJIN_PILOT_MODEL_PATH")
    if not model_path:
        raise SystemExit("RAIJIN_PILOT_MODEL_PATH is required")

    version = subprocess.run(
        [llama_bin, "--version"],
        text=True,
        capture_output=True,
        check=False,
    )

    rows = []
    for raw_task in TASKS:
        task = HeldOutTask.from_dict(raw_task)
        prompt = render_fim_prompt(task, retrieved_context="")
        command = [
            llama_bin,
            "-m",
            model_path,
            "-no-cnv",
            "--no-display-prompt",
            "--temp",
            "0",
            "--seed",
            "0",
            "-n",
            "128",
            "-p",
            prompt,
        ]
        completed = subprocess.run(
            command,
            text=True,
            capture_output=True,
            timeout=180,
            check=False,
        )
        raw_output = completed.stdout
        candidate = assemble_candidate(task, raw_output, output_format=OutputFormat.FIM)
        try:
            ast.parse(candidate)
            syntax_valid = True
        except SyntaxError:
            syntax_valid = False

        row = {
            "task_id": task.task_id,
            "prompt": prompt,
            "prompt_sha256": sha256_bytes(prompt.encode("utf-8")),
            "raw_output": raw_output,
            "stderr": completed.stderr,
            "returncode": completed.returncode,
            "assembled_candidate": candidate,
            "syntax_valid": syntax_valid,
        }
        rows.append(row)
        print(json.dumps(row, ensure_ascii=False))

    failed = [
        row["task_id"]
        for row in rows
        if row["returncode"] != 0 or not row["raw_output"] or not row["syntax_valid"]
    ]
    if failed:
        raise SystemExit(f"real-model pilot failed for tasks: {failed}")

    OUT.write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
        encoding="utf-8",
    )
    META.write_text(
        json.dumps(
            {
                "model_path": model_path,
                "model_sha256": sha256_bytes(Path(model_path).read_bytes()),
                "llama_version_stdout": version.stdout,
                "llama_version_stderr": version.stderr,
                "llama_cpp_release_tag": os.environ.get("RAIJIN_LLAMA_CPP_TAG"),
                "llama_cpp_asset_sha256": os.environ.get("RAIJIN_LLAMA_CPP_ASSET_SHA256"),
                "platform": platform.platform(),
                "task_count": len(rows),
            },
            indent=2,
        ),
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
