from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
import time
from dataclasses import asdict
from pathlib import Path
from typing import Sequence

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

from harness import (
    OutputFormat,
    normalize_fim_output,
    render_fim_prompt,
    run_python_task,
    sha256_file,
)
from retrieval import (
    FTS5Retriever,
    JsonTokenizerCounter,
    MiniLMOnnxEmbedder,
    RankedHit,
    VectorRetriever,
    build_context,
    deterministic_wrong_snippet,
    load_verified_corpus,
    reciprocal_rank_fusion,
    retrieval_query,
)
from schema import HeldOutTask


CONDITION_ORDER = ("A_raw", "B_fts", "C_hybrid", "D_oracle", "E_wrong", "F_1_5b_raw")

FROZEN_CODE_PATHS = {
    "decision_sha256": ROOT / "decision.py",
    "runner_sha256": ROOT / "run_benchmark.py",
    "retrieval_sha256": ROOT / "retrieval.py",
    "harness_sha256": ROOT / "harness.py",
    "pins_sha256": ROOT / "pins.py",
}


def verify_frozen_code_hashes(manifest: dict) -> None:
    for key, path in FROZEN_CODE_PATHS.items():
        expected = str(manifest.get(key, "")).strip().lower()
        actual = sha256_file(path)
        if not expected:
            raise ValueError(f"manifest {key} is not frozen")
        if expected != actual:
            raise ValueError(
                f"{key} mismatch: manifest={expected!r}, actual={actual!r}"
            )


def load_tasks(path: Path) -> list[HeldOutTask]:
    text = path.read_text(encoding="utf-8").strip()
    if not text:
        return []
    if text.startswith("["):
        rows = json.loads(text)
    else:
        rows = [json.loads(line) for line in text.splitlines() if line.strip()]
    return [HeldOutTask.from_dict(row) for row in rows]


def load_oracle(path: Path) -> dict[str, dict]:
    return {
        row["task_id"]: row
        for row in (
            json.loads(line)
            for line in path.read_text(encoding="utf-8").splitlines()
            if line.strip()
        )
    }


def verify_manifest_hashes(
    manifest: dict,
    *,
    tasks: Path,
    corpus: Path,
    oracle: Path,
    prompt: Path,
    dry_run: bool,
) -> None:
    checks = {
        "corpus_sha256": corpus,
        "oracle_mapping_sha256": oracle,
        "prompt_sha256": prompt,
    }
    if not dry_run:
        checks["heldout_sha256"] = tasks

    for key, path in checks.items():
        actual = sha256_file(path)
        expected = str(manifest[key]).lower()
        if actual != expected:
            raise ValueError(f"{key} mismatch: expected={expected}, actual={actual}")


def run_llama(
    llama_bin: Path,
    model_path: Path,
    prompt: str,
    *,
    seed: int,
    temperature: float,
    max_tokens: int,
    threads: int,
) -> dict:
    started = time.perf_counter()
    command = [
        str(llama_bin),
        "-m",
        str(model_path),
        "-no-cnv",
        "--no-display-prompt",
        "--temp",
        str(temperature),
        "--seed",
        str(seed),
        "-t",
        str(threads),
        "-n",
        str(max_tokens),
        "-p",
        prompt,
    ]

    try:
        cp = subprocess.run(
            command,
            text=True,
            capture_output=True,
            timeout=240,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        elapsed_ms = (time.perf_counter() - started) * 1000
        stderr = exc.stderr or ""
        if isinstance(stderr, bytes):
            stderr = stderr.decode("utf-8", errors="replace")
        return {
            "raw_output": "",
            "stderr": stderr,
            "elapsed_ms": elapsed_ms,
            "model_error": "llama.cpp call timed out after 240 seconds",
            "returncode": None,
        }
    except OSError as exc:
        elapsed_ms = (time.perf_counter() - started) * 1000
        return {
            "raw_output": "",
            "stderr": "",
            "elapsed_ms": elapsed_ms,
            "model_error": f"llama.cpp launch failed: {exc}",
            "returncode": None,
        }

    elapsed_ms = (time.perf_counter() - started) * 1000
    model_error = None
    if cp.returncode != 0:
        model_error = f"llama.cpp exited with code {cp.returncode}"

    return {
        "raw_output": cp.stdout or "",
        "stderr": cp.stderr or "",
        "elapsed_ms": elapsed_ms,
        "model_error": model_error,
        "returncode": cp.returncode,
    }


def hit_rows(hits: Sequence[RankedHit], limit: int = 20) -> list[dict]:
    return [asdict(hit) for hit in hits[:limit]]


def write_summary(path: Path, summary: list[dict]) -> None:
    temp = path.with_suffix(".json.tmp")
    temp.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    temp.replace(path)


def posthoc_failure_taxonomy(
    *,
    passed: bool,
    verification,
    condition: str,
    oracle_row: dict | None,
    selected_ids: Sequence[str],
    full_ranked_ids: Sequence[str],
) -> dict:
    relevant = (
        set(oracle_row["item_ids"])
        if oracle_row and oracle_row["coverage"] == "covered"
        else set()
    )
    ranked = set(full_ranked_ids)
    selected = set(selected_ids)

    retrieval_failure = bool(
        relevant and condition in {"B_fts", "C_hybrid"} and not (relevant & ranked)
    )
    ranking_failure = bool(
        relevant
        and condition in {"B_fts", "C_hybrid"}
        and (relevant & ranked)
        and not (relevant & selected)
    )
    adaptation_failure = bool(
        not passed
        and verification is not None
        and relevant
        and condition in {"B_fts", "C_hybrid", "D_oracle"}
        and (condition == "D_oracle" or bool(relevant & selected))
        and verification.syntax_valid
        and not verification.timed_out
        and not verification.resource_killed
    )
    return {
        "retrieval_failure": retrieval_failure,
        "ranking_failure": ranking_failure,
        "adaptation_failure": adaptation_failure,
        "verification_failure": bool(verification is not None and not passed),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tasks", required=True, type=Path)
    parser.add_argument("--corpus", required=True, type=Path)
    parser.add_argument("--oracle", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--prompt-template", required=True, type=Path)
    parser.add_argument("--llama-bin", required=True, type=Path)
    parser.add_argument("--model-0_5b", required=True, type=Path)
    parser.add_argument("--model-1_5b", type=Path)
    parser.add_argument("--minilm-model", required=True, type=Path)
    parser.add_argument("--minilm-tokenizer", required=True, type=Path)
    parser.add_argument("--context-tokenizer", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    verify_frozen_code_hashes(manifest)
    verify_manifest_hashes(
        manifest,
        tasks=args.tasks,
        corpus=args.corpus,
        oracle=args.oracle,
        prompt=args.prompt_template,
        dry_run=args.dry_run,
    )

    tasks = load_tasks(args.tasks)
    if args.dry_run:
        if len(tasks) != 5 or any(not task.task_id.startswith("pilot-") for task in tasks):
            raise ValueError("dry-run is restricted to the five pilot tasks")

    if sha256_file(args.model_0_5b) != manifest["primary_model"]["gguf_artifact"]["sha256"]:
        raise ValueError("primary GGUF hash mismatch")
    if (
        args.model_1_5b
        and sha256_file(args.model_1_5b)
        != manifest["reference_model_1_5b"]["gguf_artifact"]["sha256"]
    ):
        raise ValueError("1.5B GGUF hash mismatch")
    if sha256_file(args.minilm_model) != manifest["retrieval"]["vector"]["model_sha256"]:
        raise ValueError("MiniLM ONNX hash mismatch")
    if (
        sha256_file(args.minilm_tokenizer)
        != manifest["retrieval"]["vector"]["tokenizer_sha256"]
    ):
        raise ValueError("MiniLM tokenizer hash mismatch")
    if (
        sha256_file(args.context_tokenizer)
        != manifest["retrieval"]["context_budget_tokenizer"]["sha256"]
    ):
        raise ValueError("Qwen tokenizer hash mismatch")

    corpus = load_verified_corpus(args.corpus)
    corpus_by_id = {item.item_id: item for item in corpus}
    oracle = load_oracle(args.oracle)

    fts = FTS5Retriever(corpus)
    vector = VectorRetriever(
        corpus,
        MiniLMOnnxEmbedder(args.minilm_model, args.minilm_tokenizer),
    )
    counter = JsonTokenizerCounter(args.context_tokenizer)

    seed = int(manifest["primary_model"]["seed"])
    temperature = float(manifest["primary_model"]["temperature"])
    max_tokens = int(manifest["primary_model"]["max_output_tokens"])
    threads = int(manifest["runner"]["llama_threads"])

    args.output_dir.mkdir(parents=True, exist_ok=True)
    summary_path = args.output_dir / "summary.json"
    summary: list[dict] = []
    write_summary(summary_path, summary)

    for condition in CONDITION_ORDER:
        if condition == "F_1_5b_raw" and args.model_1_5b is None:
            continue
        condition_dir = args.output_dir / condition
        condition_dir.mkdir(parents=True, exist_ok=True)

        for task in tasks:
            total_start = time.perf_counter()
            oracle_row = oracle.get(task.task_id)

            if condition == "D_oracle" and (
                not oracle_row or oracle_row["coverage"] != "covered"
            ):
                row = {
                    "task_id": task.task_id,
                    "condition": condition,
                    "status": "skipped_uncovered",
                    "passed": False,
                    "model_error": None,
                    "wrong_snippet_in_oracle": False,
                }
                (condition_dir / f"{task.task_id}.json").write_text(
                    json.dumps(row, indent=2),
                    encoding="utf-8",
                )
                summary.append(row)
                write_summary(summary_path, summary)
                continue

            retrieval_start = time.perf_counter()
            query = ""
            fts_hits: list[RankedHit] = []
            vector_hits: list[RankedHit] = []
            hybrid_hits: list[RankedHit] = []

            if condition in {"B_fts", "C_hybrid", "E_wrong"}:
                query = retrieval_query(task)
                fts_hits = fts.rank(query)

            if condition in {"C_hybrid", "E_wrong"}:
                vector_hits = vector.rank(query)
                hybrid_hits = reciprocal_rank_fusion(
                    fts_hits,
                    vector_hits,
                    k=int(manifest["retrieval"]["hybrid"]["rrf_k"]),
                )

            wrong_snippet_in_oracle = False
            if condition == "B_fts":
                full_ranked = fts_hits
                requested_ids = [hit.item_id for hit in fts_hits[:3]]
            elif condition == "C_hybrid":
                full_ranked = hybrid_hits
                requested_ids = [hit.item_id for hit in hybrid_hits[:3]]
            elif condition == "D_oracle":
                requested_ids = list(oracle_row["item_ids"])[:3]
                full_ranked = [
                    RankedHit(
                        item_id,
                        corpus_by_id[item_id].kind.value,
                        index + 1,
                        1.0,
                    )
                    for index, item_id in enumerate(requested_ids)
                ]
            elif condition == "E_wrong":
                excluded = {
                    *(hit.item_id for hit in fts_hits[:3]),
                    *(hit.item_id for hit in hybrid_hits[:3]),
                }
                wrong = deterministic_wrong_snippet(
                    task.task_id,
                    seed,
                    corpus,
                    fts_hits,
                    vector_hits,
                    excluded,
                )
                requested_ids = [wrong.item_id]
                full_ranked = [RankedHit(wrong.item_id, wrong.kind.value, 1, 1.0)]
                wrong_snippet_in_oracle = bool(
                    oracle_row
                    and oracle_row.get("coverage") == "covered"
                    and wrong.item_id in set(oracle_row.get("item_ids", []))
                )
            else:
                requested_ids = []
                full_ranked = []

            context, selected_ids, context_tokens = build_context(
                requested_ids,
                corpus_by_id,
                counter,
                top_k=int(manifest["retrieval"]["top_k"]),
                budget_tokens=int(manifest["retrieval"]["context_budget_tokens"]),
            )
            retrieval_ms = (time.perf_counter() - retrieval_start) * 1000

            prompt_start = time.perf_counter()
            prompt = render_fim_prompt(task, context)
            prompt_ms = (time.perf_counter() - prompt_start) * 1000

            model_path = (
                args.model_1_5b
                if condition == "F_1_5b_raw"
                else args.model_0_5b
            )
            model_call = run_llama(
                args.llama_bin,
                model_path,
                prompt,
                seed=seed,
                temperature=temperature,
                max_tokens=max_tokens,
                threads=threads,
            )
            raw_output = model_call["raw_output"]
            model_ms = model_call["elapsed_ms"]

            base_retrieval_log = {
                "query": query,
                "fts": hit_rows(fts_hits),
                "vector": hit_rows(vector_hits),
                "hybrid": hit_rows(hybrid_hits),
                "selected_ids": selected_ids,
                "context_tokens": context_tokens,
                "posthoc_oracle_ids": (
                    oracle_row["item_ids"]
                    if oracle_row and oracle_row.get("coverage") == "covered"
                    else []
                ),
            }

            if model_call["model_error"]:
                total_ms = (time.perf_counter() - total_start) * 1000
                row = {
                    "task_id": task.task_id,
                    "condition": condition,
                    "status": "model_error",
                    "prompt": prompt,
                    "prompt_sha256": hashlib.sha256(prompt.encode("utf-8")).hexdigest(),
                    "raw_output": raw_output,
                    "normalized_output": "",
                    "passed": False,
                    "verification": None,
                    "model_error": model_call["model_error"],
                    "model_returncode": model_call["returncode"],
                    "model_stderr": model_call["stderr"],
                    "wrong_snippet_in_oracle": wrong_snippet_in_oracle,
                    "retrieval": base_retrieval_log,
                    "failure_taxonomy": {
                        "retrieval_failure": False,
                        "ranking_failure": False,
                        "adaptation_failure": False,
                        "verification_failure": False,
                    },
                    "timings_ms": {
                        "retrieval": retrieval_ms,
                        "prompt_build": prompt_ms,
                        "model_wall_including_model_load": model_ms,
                        "verification": 0.0,
                        "total": total_ms,
                    },
                }
                (condition_dir / f"{task.task_id}.json").write_text(
                    json.dumps(row, indent=2, ensure_ascii=False),
                    encoding="utf-8",
                )
                summary.append({
                    "task_id": task.task_id,
                    "condition": condition,
                    "passed": False,
                    "model_error": row["model_error"],
                    "wrong_snippet_in_oracle": wrong_snippet_in_oracle,
                    "selected_ids": selected_ids,
                    "failure_taxonomy": row["failure_taxonomy"],
                    "timings_ms": row["timings_ms"],
                })
                write_summary(summary_path, summary)
                continue

            normalized = normalize_fim_output(raw_output, task.suffix)

            verify_start = time.perf_counter()
            verification = run_python_task(
                task,
                raw_output,
                output_format=OutputFormat.FIM,
                inner_timeout_seconds=int(manifest["candidate_inner_timeout_seconds"]),
                outer_timeout_seconds=int(manifest["candidate_outer_timeout_seconds"]),
            )
            verification_ms = (time.perf_counter() - verify_start) * 1000

            taxonomy = posthoc_failure_taxonomy(
                passed=verification.passed,
                verification=verification,
                condition=condition,
                oracle_row=oracle_row,
                selected_ids=selected_ids,
                full_ranked_ids=[hit.item_id for hit in full_ranked],
            )
            total_ms = (time.perf_counter() - total_start) * 1000

            row = {
                "task_id": task.task_id,
                "condition": condition,
                "status": "completed",
                "prompt": prompt,
                "prompt_sha256": hashlib.sha256(prompt.encode("utf-8")).hexdigest(),
                "raw_output": raw_output,
                "normalized_output": normalized,
                "passed": verification.passed,
                "verification": asdict(verification),
                "model_error": None,
                "model_returncode": model_call["returncode"],
                "model_stderr": model_call["stderr"],
                "wrong_snippet_in_oracle": wrong_snippet_in_oracle,
                "retrieval": base_retrieval_log,
                "failure_taxonomy": taxonomy,
                "timings_ms": {
                    "retrieval": retrieval_ms,
                    "prompt_build": prompt_ms,
                    "model_wall_including_model_load": model_ms,
                    "verification": verification_ms,
                    "total": total_ms,
                },
            }
            (condition_dir / f"{task.task_id}.json").write_text(
                json.dumps(row, indent=2, ensure_ascii=False),
                encoding="utf-8",
            )
            summary.append({
                "task_id": task.task_id,
                "condition": condition,
                "passed": verification.passed,
                "model_error": None,
                "wrong_snippet_in_oracle": wrong_snippet_in_oracle,
                "selected_ids": selected_ids,
                "failure_taxonomy": taxonomy,
                "timings_ms": row["timings_ms"],
            })
            write_summary(summary_path, summary)


if __name__ == "__main__":
    main()
