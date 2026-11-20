from __future__ import annotations

import ast
import hashlib
import json
import re
import subprocess
import tempfile
import textwrap
import uuid
from collections import Counter
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import Callable, Iterable, Sequence

from schema import HeldOutTask, KnowledgeDependency, ReferenceSolution
from pins import PYTHON_SANDBOX_IMAGE


FIM_PREFIX = "<|fim_prefix|>"
FIM_SUFFIX = "<|fim_suffix|>"
FIM_MIDDLE = "<|fim_middle|>"
SPECIAL_TOKENS = (
    "[end of text]",
    "<|endoftext|>",
    "<|file_sep|>",
    FIM_PREFIX,
    FIM_SUFFIX,
    FIM_MIDDLE,
)
PROMPT_TEMPLATE_PATH = Path(__file__).with_name("prompt_template.txt")
PROMPT_TEMPLATE = PROMPT_TEMPLATE_PATH.read_text(encoding="utf-8")

DISTRIBUTION_MINIMUMS = {
    KnowledgeDependency.REASONING_ONLY: 15,
    KnowledgeDependency.API_KNOWLEDGE: 15,
    KnowledgeDependency.API_KNOWLEDGE_PLUS_ADAPTATION: 20,
    KnowledgeDependency.ALGORITHMIC_REASONING: 10,
}

LEADING_PROSE_LINE = re.compile(
    r"^\s*(?:here(?:'s| is)(?: the)?(?: replacement)? code|"
    r"sure(?:,\s*here(?:'s| is)(?: the)? code)?|"
    r"the code|replacement code|answer|solution)\s*[:.!]?\s*$",
    re.IGNORECASE,
)
TRAILING_PROSE_LINE = re.compile(
    r"^\s*(?:hope this helps|this should work)\s*[.!]?\s*$",
    re.IGNORECASE,
)


class OutputFormat(str, Enum):
    FIM = "fim"
    CHAT_BLOCK = "chat_block"


@dataclass(frozen=True)
class ExecutionResult:
    passed: bool
    timed_out: bool
    stderr: str
    syntax_valid: bool
    resource_killed: bool = False


@dataclass(frozen=True)
class SuiteValidation:
    task_count: int
    null_failures: int
    reference_passes: int


def truncate_at_special_token(raw: str) -> str:
    positions = [raw.find(token) for token in SPECIAL_TOKENS if raw.find(token) >= 0]
    return raw[: min(positions)] if positions else raw


def normalize_chat_output(raw: str) -> str:
    text = truncate_at_special_token(raw).strip("\r\n")

    fenced = re.search(
        r"\x60\x60\x60(?:python|py)?\s*\n(.*?)\x60\x60\x60",
        text,
        flags=re.IGNORECASE | re.DOTALL,
    )
    if fenced:
        text = fenced.group(1).strip("\r\n")
    else:
        lines = text.splitlines()
        while lines and LEADING_PROSE_LINE.fullmatch(lines[0]):
            lines.pop(0)
        while lines and TRAILING_PROSE_LINE.fullmatch(lines[-1]):
            lines.pop()
        text = "\n".join(lines).strip("\r\n")

    return textwrap.dedent(text).strip("\r\n")


def normalize_fim_output(raw: str, suffix: str) -> str:
    # Native FIM output is a continuation of the exact prefix. Do not dedent,
    # prose-strip, or re-indent it. Only stop-truncate model control tokens and
    # an accidentally repeated suffix.
    text = truncate_at_special_token(raw)

    # Only try suffix-repeat trimming when the suffix contains real code/text.
    # A structural suffix such as "\n" is common and must not truncate a
    # multi-line FIM completion at its first newline.
    significant_suffix = suffix.strip("\r\n")
    if significant_suffix.strip():
        exact_index = text.find(suffix)
        if exact_index >= 0:
            text = text[:exact_index]
        else:
            match = re.search(
                r"(?:^|\n)" + re.escape(significant_suffix),
                text,
            )
            if match:
                text = text[: match.start()]

    return text.rstrip("\r\n")


def _hole_indent(prefix: str) -> tuple[str, str]:
    last_newline = prefix.rfind("\n")
    line_start = last_newline + 1
    tail = prefix[line_start:]
    if tail and not tail.isspace():
        return prefix, ""
    return prefix[:line_start], tail


def reference_block_to_fim_output(task: HeldOutTask, slot_code: str) -> str:
    """Convert a dedented reference block into the raw shape native FIM emits.

    The first generated line continues directly after the prefix's existing
    indentation. Every later line is absolute relative to the surrounding file,
    so add the hole indentation to those lines only.
    """
    normalized = textwrap.dedent(slot_code).strip("\r\n")
    if not normalized:
        return normalized

    _, indent = _hole_indent(task.prefix)
    lines = normalized.splitlines()
    if len(lines) == 1:
        return lines[0]
    tail = "\n".join(
        (indent + line) if line else indent
        for line in lines[1:]
    )
    return lines[0] + "\n" + tail


def assemble_candidate(
    task: HeldOutTask,
    raw_slot_output: str,
    output_format: OutputFormat = OutputFormat.FIM,
) -> str:
    if output_format is OutputFormat.FIM:
        slot = normalize_fim_output(raw_slot_output, task.suffix)
        return f"{task.prefix}{slot}{task.suffix}"

    if output_format is OutputFormat.CHAT_BLOCK:
        slot = normalize_chat_output(raw_slot_output)
        base_prefix, indent = _hole_indent(task.prefix)
        indented = textwrap.indent(slot, indent, predicate=lambda _: True)
        return f"{base_prefix}{indented}{task.suffix}"

    raise ValueError(f"unsupported output format: {output_format}")


def comment_retrieved_context(retrieved_context: str) -> str:
    if not retrieved_context.strip():
        return "# (none)"
    return "\n".join(f"# {line}" if line else "#" for line in retrieved_context.splitlines())


def render_fim_prompt(task: HeldOutTask, retrieved_context: str = "") -> str:
    return PROMPT_TEMPLATE.format(
        task_id=task.task_id,
        instruction=task.instruction,
        retrieved_context_comments=comment_retrieved_context(retrieved_context),
        prefix=task.prefix,
        suffix=task.suffix,
    )


def _syntax_valid(candidate: str) -> bool:
    try:
        ast.parse(candidate)
    except SyntaxError:
        return False
    return True


def build_docker_command(
    root: Path,
    container_name: str,
    *,
    inner_timeout_seconds: int = 5,
) -> list[str]:
    return [
        "docker",
        "run",
        "--rm",
        "--name",
        container_name,
        "--network",
        "none",
        "--memory",
        "128m",
        "--cpus",
        "0.5",
        "--pids-limit",
        "64",
        "--read-only",
        "--cap-drop",
        "ALL",
        "--security-opt",
        "no-new-privileges",
        "--env",
        "TMPDIR=/work/tmp",
        "--mount",
        f"type=bind,src={root},dst=/work",
        "--workdir",
        "/work",
        PYTHON_SANDBOX_IMAGE,
        "timeout",
        "--kill-after=1s",
        f"{inner_timeout_seconds}s",
        "python",
        "-E",
        "-s",
        "-B",
        "test_candidate.py",
    ]


def _cleanup_container(
    container_name: str,
    cleanup_executor: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
) -> None:
    for command in (
        ["docker", "kill", container_name],
        ["docker", "rm", "-f", container_name],
    ):
        try:
            cleanup_executor(
                command,
                text=True,
                capture_output=True,
                timeout=5,
                check=False,
            )
        except (subprocess.SubprocessError, OSError):
            pass


def run_python_task(
    task: HeldOutTask,
    raw_slot_output: str,
    *,
    output_format: OutputFormat = OutputFormat.FIM,
    inner_timeout_seconds: int = 5,
    outer_timeout_seconds: int = 20,
    executor: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
    cleanup_executor: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
) -> ExecutionResult:
    if task.language.lower() != "python":
        raise ValueError(f"unsupported executable language for Phase 0B harness: {task.language}")
    if outer_timeout_seconds <= inner_timeout_seconds:
        raise ValueError("outer timeout must be greater than inner timeout")

    candidate = assemble_candidate(task, raw_slot_output, output_format=output_format)
    syntax_valid = _syntax_valid(candidate)
    if not syntax_valid:
        return ExecutionResult(
            passed=False,
            timed_out=False,
            stderr="candidate is not syntactically valid Python",
            syntax_valid=False,
        )

    container_name = f"raijin-phase0b-{uuid.uuid4().hex[:12]}"

    with tempfile.TemporaryDirectory(prefix="raijin-phase0b-") as tmp:
        root = Path(tmp)
        candidate_path = root / "candidate.py"
        test_path = root / "test_candidate.py"
        tmp_path = root / "tmp"
        candidate_path.write_text(candidate, encoding="utf-8")
        test_path.write_text(task.test_code, encoding="utf-8")
        tmp_path.mkdir()

        # TemporaryDirectory defaults to mode 0700. Docker may run through
        # user-namespace/rootless remapping on CI, so make only this disposable
        # working tree traversable/writable. The container root remains read-only.
        root.chmod(0o777)
        candidate_path.chmod(0o644)
        test_path.chmod(0o644)
        tmp_path.chmod(0o777)

        try:
            completed = executor(
                build_docker_command(
                    root,
                    container_name,
                    inner_timeout_seconds=inner_timeout_seconds,
                ),
                text=True,
                capture_output=True,
                timeout=outer_timeout_seconds,
                check=False,
            )
        except subprocess.TimeoutExpired as exc:
            _cleanup_container(container_name, cleanup_executor)
            stderr = exc.stderr or ""
            if isinstance(stderr, bytes):
                stderr = stderr.decode("utf-8", errors="replace")
            return ExecutionResult(
                passed=False,
                timed_out=True,
                stderr=stderr or f"container client timed out after {outer_timeout_seconds}s",
                syntax_valid=True,
            )

        # GNU timeout normally returns 124. Exit 137 is SIGKILL and is
        # deliberately reported separately because it can also indicate an OOM
        # or another resource kill; do not misclassify it as a timeout.
        timed_out = completed.returncode == 124
        resource_killed = completed.returncode == 137
        if completed.stderr:
            stderr = completed.stderr
        elif timed_out:
            stderr = f"candidate timed out after {inner_timeout_seconds}s"
        elif resource_killed:
            stderr = "candidate was killed by SIGKILL (exit 137); possible resource limit/OOM"
        else:
            stderr = ""

        return ExecutionResult(
            passed=completed.returncode == 0,
            timed_out=timed_out,
            stderr=stderr,
            syntax_valid=True,
            resource_killed=resource_killed,
        )


def _load_jsonl_records(path: Path) -> list[dict]:
    records: list[dict] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            records.append(json.loads(line))
        except json.JSONDecodeError as exc:
            raise ValueError(f"invalid JSON at {path}:{line_number}: {exc}") from exc
    return records


def load_tasks(path: Path) -> list[HeldOutTask]:
    tasks = [HeldOutTask.from_dict(raw) for raw in _load_jsonl_records(path)]
    _assert_unique_ids((task.task_id for task in tasks), "task_id")
    return tasks


def load_reference_solutions(path: Path) -> dict[str, ReferenceSolution]:
    solutions = [ReferenceSolution.from_dict(raw) for raw in _load_jsonl_records(path)]
    _assert_unique_ids((solution.task_id for solution in solutions), "reference solution task_id")
    return {solution.task_id: solution for solution in solutions}


def _assert_unique_ids(ids: Iterable[str], label: str) -> None:
    seen: set[str] = set()
    for value in ids:
        if value in seen:
            raise ValueError(f"duplicate {label}: {value}")
        seen.add(value)


def validate_task_count(tasks: Sequence[HeldOutTask], minimum: int = 60) -> None:
    unique = {task.task_id for task in tasks}
    if len(unique) != len(tasks):
        raise ValueError("held-out set contains duplicate task IDs")
    if len(unique) < minimum:
        raise ValueError(f"held-out set must contain at least {minimum} unique tasks; found {len(unique)}")


def validate_distribution(tasks: Sequence[HeldOutTask]) -> None:
    counts = Counter(task.knowledge_dependency for task in tasks)
    missing = {
        kind.value: minimum - counts[kind]
        for kind, minimum in DISTRIBUTION_MINIMUMS.items()
        if counts[kind] < minimum
    }
    if missing:
        details = ", ".join(f"{kind}: need {amount} more" for kind, amount in sorted(missing.items()))
        raise ValueError(f"held-out distribution minimums not met: {details}")


def validate_vacuity(
    tasks: Sequence[HeldOutTask],
    references: dict[str, ReferenceSolution],
    runner: Callable[[HeldOutTask, str, OutputFormat], ExecutionResult] | None = None,
) -> SuiteValidation:
    task_ids = {task.task_id for task in tasks}
    missing_refs = sorted(task_ids - references.keys())
    extra_refs = sorted(references.keys() - task_ids)
    if missing_refs or extra_refs:
        raise ValueError(
            f"reference-solution IDs must match held-out task IDs; missing={missing_refs}, extra={extra_refs}"
        )

    if runner is None:
        runner = lambda task, slot, output_format: run_python_task(
            task,
            slot,
            output_format=output_format,
        )

    null_failures = 0
    reference_passes = 0
    failures: list[str] = []

    for task in tasks:
        null_result = runner(task, "pass", OutputFormat.CHAT_BLOCK)
        if not null_result.passed:
            null_failures += 1
        else:
            failures.append(f"{task.task_id}: null slot unexpectedly passed")

        reference = references[task.task_id].slot_code
        chat_reference_result = runner(task, reference, OutputFormat.CHAT_BLOCK)
        if not chat_reference_result.passed:
            failures.append(
                f"{task.task_id}: reference solution failed chat-block validation "
                f"(timed_out={chat_reference_result.timed_out}, "
                f"resource_killed={chat_reference_result.resource_killed}, "
                f"syntax_valid={chat_reference_result.syntax_valid}, "
                f"stderr={chat_reference_result.stderr!r})"
            )
            continue

        fim_reference = reference_block_to_fim_output(task, reference)
        normalized_fim_reference = normalize_fim_output(fim_reference, task.suffix)
        if normalized_fim_reference != fim_reference:
            failures.append(
                f"{task.task_id}: normalize_fim_output changed the reference FIM fixture"
            )
            continue

        fim_reference_result = runner(task, fim_reference, OutputFormat.FIM)
        if fim_reference_result.passed:
            reference_passes += 1
        else:
            failures.append(
                f"{task.task_id}: reference solution failed native-FIM validation "
                f"(timed_out={fim_reference_result.timed_out}, "
                f"resource_killed={fim_reference_result.resource_killed}, "
                f"syntax_valid={fim_reference_result.syntax_valid}, "
                f"stderr={fim_reference_result.stderr!r})"
            )

    if failures:
        raise ValueError("vacuity/reference validation failed: " + " | ".join(failures))

    return SuiteValidation(
        task_count=len(tasks),
        null_failures=null_failures,
        reference_passes=reference_passes,
    )

def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_manifest_hashes(
    manifest_path: Path,
    heldout_path: Path,
    corpus_path: Path,
    prompt_template_path: Path,
    oracle_mapping_path: Path,
    reference_solutions_path: Path,
) -> None:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    expected = {
        "heldout_sha256": sha256_file(heldout_path),
        "corpus_sha256": sha256_file(corpus_path),
        "prompt_sha256": sha256_file(prompt_template_path),
        "oracle_mapping_sha256": sha256_file(oracle_mapping_path),
        "reference_solutions_sha256": sha256_file(reference_solutions_path),
    }
    for key, actual in expected.items():
        recorded = str(manifest.get(key, "")).strip().lower()
        if recorded != actual:
            raise ValueError(f"{key} mismatch: manifest={recorded!r}, actual={actual!r}")
