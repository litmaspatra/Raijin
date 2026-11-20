import ast
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from harness import (
    ExecutionResult,
    OutputFormat,
    assemble_candidate,
    build_docker_command,
    comment_retrieved_context,
    load_reference_solutions,
    normalize_chat_output,
    normalize_fim_output,
    reference_block_to_fim_output,
    render_fim_prompt,
    run_python_task,
    sha256_file,
    truncate_at_special_token,
    validate_distribution,
    validate_manifest_hashes,
    validate_task_count,
    validate_vacuity,
)
from schema import HeldOutTask, KnowledgeDependency, ReferenceSolution


def sample_task(task_id="sample-001", kind=KnowledgeDependency.REASONING_ONLY) -> HeldOutTask:
    return HeldOutTask(
        task_id=task_id,
        language="python",
        instruction="Return the sum of a and b.",
        prefix="def add(a, b):\n    ",
        suffix="\n",
        test_code=(
            "from candidate import add\n"
            "assert add(2, 3) == 5\n"
            "assert add(-1, 1) == 0\n"
        ),
        knowledge_dependency=kind,
    )


class HarnessTest(unittest.TestCase):
    def test_native_fim_prompt_comments_every_context_line(self):
        prompt = render_fim_prompt(
            sample_task(),
            "return dangerous()\nsecond line\n\nthird line",
        )
        self.assertTrue(prompt.startswith("<|fim_prefix|>"))
        self.assertIn("# return dangerous()", prompt)
        self.assertIn("# second line", prompt)
        self.assertIn("#\n# third line", prompt)
        self.assertNotIn("\nreturn dangerous()\n", prompt)
        self.assertLess(prompt.index("# return dangerous()"), prompt.index("<|fim_suffix|>"))
        self.assertTrue(prompt.endswith("<|fim_middle|>"))

    def test_context_commenter_uses_comment_for_empty_context(self):
        self.assertEqual("# (none)", comment_retrieved_context(""))

    def test_fim_assembly_preserves_native_absolute_indentation(self):
        task = HeldOutTask(
            task_id="fim-indent",
            language="python",
            instruction="Return even or odd.",
            prefix="def parity(n):\n    ",
            suffix="\n",
            test_code="",
            knowledge_dependency=KnowledgeDependency.REASONING_ONLY,
        )
        raw = (
            "if n % 2 == 0:\n"
            "        return 'even'\n"
            "    else:\n"
            "        return 'odd'"
        )
        candidate = assemble_candidate(task, raw, output_format=OutputFormat.FIM)
        expected = (
            "def parity(n):\n"
            "    if n % 2 == 0:\n"
            "        return 'even'\n"
            "    else:\n"
            "        return 'odd'\n"
        )
        self.assertEqual(expected, candidate)
        ast.parse(candidate)

    def test_chat_block_is_dedented_then_indented_to_hole(self):
        raw = "    if a:\n        return b\n    return c"
        candidate = assemble_candidate(
            HeldOutTask(
                task_id="chat-indent",
                language="python",
                instruction="Fill block.",
                prefix="def f(a, b, c):\n    ",
                suffix="\n",
                test_code="",
                knowledge_dependency=KnowledgeDependency.REASONING_ONLY,
            ),
            raw,
            output_format=OutputFormat.CHAT_BLOCK,
        )
        self.assertEqual(
            "def f(a, b, c):\n    if a:\n        return b\n    return c\n",
            candidate,
        )

    def test_chat_normalizer_only_strips_whole_line_prose(self):
        fenced = "Sure, here is the code:\n\x60\x60\x60python\n    return a + b\n\x60\x60\x60\nHope this helps!"
        self.assertEqual("return a + b", normalize_chat_output(fenced))

        prose = "Here is the replacement code:\n    return a + b\nThis should work."
        self.assertEqual("return a + b", normalize_chat_output(prose))

        real_code = "answer: int = 0\nsolution: list = []\nsure_value = 1"
        self.assertEqual(real_code, normalize_chat_output(real_code))

    def test_real_pilot_fim_fixtures_assemble_to_valid_python(self):
        fixtures = [
            (
                HeldOutTask(
                    task_id="pilot-single-line",
                    language="python",
                    instruction="Return a plus b.",
                    prefix="def add(a, b):\n    ",
                    suffix="\n",
                    test_code="assert True",
                    knowledge_dependency=KnowledgeDependency.REASONING_ONLY,
                ),
                "return a + b [end of text]\n\n\n",
            ),
            (
                HeldOutTask(
                    task_id="pilot-if-else",
                    language="python",
                    instruction="Return even or odd.",
                    prefix="def parity(n):\n    ",
                    suffix="\n",
                    test_code="assert True",
                    knowledge_dependency=KnowledgeDependency.REASONING_ONLY,
                ),
                "if n % 2 == 0:\n        return 'even'\n    else:\n        return 'odd' [end of text]\n\n\n",
            ),
            (
                HeldOutTask(
                    task_id="pilot-suffix-shape",
                    language="python",
                    instruction="Clean a name.",
                    prefix="def clean_name(name):\n    ",
                    suffix="\n\nRESULT = clean_name(' alice ')\n",
                    test_code="assert True",
                    knowledge_dependency=KnowledgeDependency.REASONING_ONLY,
                ),
                "return name.upper() [end of text]\n\n\n",
            ),
        ]
        for task, raw in fixtures:
            candidate = assemble_candidate(task, raw, output_format=OutputFormat.FIM)
            ast.parse(candidate)
            self.assertNotIn("[end of text]", candidate)

    def test_special_tokens_are_truncated(self):
        for token in ("[end of text]", "<|endoftext|>", "<|file_sep|>", "<|fim_prefix|>", "<|fim_suffix|>", "<|fim_middle|>"):
            self.assertEqual("return 1", truncate_at_special_token("return 1" + token + "junk"))

    def test_fim_normalizer_truncates_repeated_suffix_without_prose_stripping(self):
        suffix = "\nprint(result)\n"
        raw = "answer: int = 0\nprint(result)\nignored"
        self.assertEqual("answer: int = 0", normalize_fim_output(raw, suffix))

    def test_structured_result_reports_success(self):
        def executor(*args, **kwargs):
            return subprocess.CompletedProcess(args=args[0], returncode=0, stdout="", stderr="")

        result = run_python_task(sample_task(), "return a + b", executor=executor)
        self.assertEqual(
            ExecutionResult(passed=True, timed_out=False, stderr="", syntax_valid=True),
            result,
        )

    def test_structured_result_reports_syntax_failure_without_executor(self):
        called = False

        def executor(*args, **kwargs):
            nonlocal called
            called = True
            return subprocess.CompletedProcess(args=args[0], returncode=0, stdout="", stderr="")

        result = run_python_task(sample_task(), "return (", executor=executor)
        self.assertFalse(result.passed)
        self.assertFalse(result.timed_out)
        self.assertFalse(result.syntax_valid)
        self.assertFalse(called)

    def test_outer_timeout_kills_named_container_and_returns_structured_failure(self):
        cleanup_commands = []

        def executor(*args, **kwargs):
            raise subprocess.TimeoutExpired(cmd=args[0], timeout=kwargs["timeout"])

        def cleanup_executor(command, **kwargs):
            cleanup_commands.append(command)
            return subprocess.CompletedProcess(command, 0, "", "")

        result = run_python_task(
            sample_task(),
            "return a + b",
            inner_timeout_seconds=1,
            outer_timeout_seconds=3,
            executor=executor,
            cleanup_executor=cleanup_executor,
        )
        self.assertFalse(result.passed)
        self.assertTrue(result.timed_out)
        self.assertTrue(result.syntax_valid)
        self.assertEqual("docker", cleanup_commands[0][0])
        self.assertEqual("kill", cleanup_commands[0][1])
        self.assertEqual("rm", cleanup_commands[1][1])
        self.assertEqual(cleanup_commands[0][2], cleanup_commands[1][-1])

    def test_inner_timeout_exit_code_is_structured_timeout(self):
        def executor(*args, **kwargs):
            return subprocess.CompletedProcess(args=args[0], returncode=124, stdout="", stderr="")

        result = run_python_task(sample_task(), "return a + b", executor=executor)
        self.assertFalse(result.passed)
        self.assertTrue(result.timed_out)
        self.assertIn("timed out", result.stderr)

    def test_exit_137_is_resource_killed_not_timeout(self):
        def executor(*args, **kwargs):
            return subprocess.CompletedProcess(args=args[0], returncode=137, stdout="", stderr="")

        result = run_python_task(sample_task(), "return a + b", executor=executor)
        self.assertFalse(result.passed)
        self.assertFalse(result.timed_out)
        self.assertTrue(result.resource_killed)
        self.assertIn("SIGKILL", result.stderr)

    def test_reference_solution_fim_shape_is_unchanged_and_valid(self):
        task = HeldOutTask(
            task_id="reference-fim",
            language="python",
            instruction="Return even or odd.",
            prefix="def parity(n):\n    ",
            suffix="\n",
            test_code="from candidate import parity\nassert parity(2) == 'even'\nassert parity(3) == 'odd'\n",
            knowledge_dependency=KnowledgeDependency.REASONING_ONLY,
        )
        reference = "if n % 2 == 0:\n    return 'even'\nelse:\n    return 'odd'"
        fim_raw = reference_block_to_fim_output(task, reference)
        self.assertEqual(
            "if n % 2 == 0:\n        return 'even'\n    else:\n        return 'odd'",
            fim_raw,
        )
        self.assertEqual(fim_raw, normalize_fim_output(fim_raw, task.suffix))
        ast.parse(assemble_candidate(task, fim_raw, output_format=OutputFormat.FIM))

        calls = []
        def runner(_task, slot, output_format):
            calls.append((slot, output_format))
            candidate = assemble_candidate(_task, slot, output_format=output_format)
            try:
                namespace = {}
                exec(candidate, namespace)
                passed = namespace["parity"](2) == "even" and namespace["parity"](3) == "odd"
                return ExecutionResult(passed, False, "", True)
            except Exception as exc:
                return ExecutionResult(False, False, str(exc), False)

        refs = {task.task_id: ReferenceSolution(task.task_id, reference)}
        result = validate_vacuity([task], refs, runner=runner)
        self.assertEqual(1, result.reference_passes)
        self.assertTrue(any(fmt is OutputFormat.FIM for _, fmt in calls))

    def test_sandbox_command_has_import_working_and_resource_limits(self):
        command = build_docker_command(Path("/tmp/phase0b"), "raijin-test", inner_timeout_seconds=5)
        joined = " ".join(command)
        self.assertIn("--network none", joined)
        self.assertIn("--memory 128m", joined)
        self.assertIn("--cpus 0.5", joined)
        self.assertIn("--pids-limit 64", joined)
        self.assertIn("--read-only", command)
        self.assertIn("--cap-drop ALL", joined)
        self.assertIn("no-new-privileges", joined)
        self.assertIn("TMPDIR=/work/tmp", joined)
        self.assertIn("timeout --kill-after=1s 5s", joined)
        self.assertIn("python -E -s -B test_candidate.py", joined)
        self.assertNotIn("-I", command)

    def test_duplicate_tasks_do_not_count_toward_minimum(self):
        with self.assertRaisesRegex(ValueError, "duplicate"):
            validate_task_count([sample_task()] * 60, minimum=60)

    def test_unique_task_minimum_is_enforced(self):
        tasks = [sample_task(f"t{i:03}") for i in range(60)]
        validate_task_count(tasks, minimum=60)
        with self.assertRaises(ValueError):
            validate_task_count(tasks[:59], minimum=60)

    def test_distribution_minimums_are_enforced(self):
        tasks = []
        specs = [
            (KnowledgeDependency.REASONING_ONLY, 15),
            (KnowledgeDependency.API_KNOWLEDGE, 15),
            (KnowledgeDependency.API_KNOWLEDGE_PLUS_ADAPTATION, 20),
            (KnowledgeDependency.ALGORITHMIC_REASONING, 10),
        ]
        index = 0
        for kind, count in specs:
            for _ in range(count):
                tasks.append(sample_task(f"dist-{index}", kind))
                index += 1
        validate_distribution(tasks)
        with self.assertRaisesRegex(ValueError, "distribution"):
            validate_distribution(tasks[:-1])

    def test_vacuity_validator_requires_null_fail_and_reference_pass(self):
        task = sample_task()
        refs = {task.task_id: ReferenceSolution(task.task_id, "return a + b")}

        calls = []

        def runner(_task, slot, output_format):
            calls.append((slot, output_format))
            return ExecutionResult(
                passed=slot != "pass",
                timed_out=False,
                stderr="",
                syntax_valid=True,
            )

        result = validate_vacuity([task], refs, runner=runner)
        self.assertEqual(1, result.null_failures)
        self.assertEqual(1, result.reference_passes)
        self.assertIn(("pass", OutputFormat.CHAT_BLOCK), calls)
        self.assertIn(("return a + b", OutputFormat.CHAT_BLOCK), calls)
        self.assertIn(("return a + b", OutputFormat.FIM), calls)

    def test_vacuity_validator_rejects_vacuous_task(self):
        task = sample_task()
        refs = {task.task_id: ReferenceSolution(task.task_id, "return a + b")}

        def runner(_task, _slot, _output_format):
            return ExecutionResult(True, False, "", True)

        with self.assertRaisesRegex(ValueError, "null slot unexpectedly passed"):
            validate_vacuity([task], refs, runner=runner)

    def test_reference_solution_jsonl_loader_rejects_duplicates(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "reference_solutions.jsonl"
            row = json.dumps({"task_id": "a", "slot_code": "return 1"})
            path.write_text(row + "\n" + row + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "duplicate"):
                load_reference_solutions(path)

    def test_manifest_hash_validator_refuses_any_mismatch(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            paths = {
                "heldout": root / "heldout.jsonl",
                "corpus": root / "corpus.jsonl",
                "prompt": root / "prompt.txt",
                "oracle": root / "oracle.json",
                "references": root / "reference_solutions.jsonl",
            }
            for name, path in paths.items():
                path.write_text(name + "\n", encoding="utf-8")
            manifest = root / "manifest.json"
            manifest.write_text(
                json.dumps(
                    {
                        "heldout_sha256": "wrong",
                        "corpus_sha256": sha256_file(paths["corpus"]),
                        "prompt_sha256": sha256_file(paths["prompt"]),
                        "oracle_mapping_sha256": sha256_file(paths["oracle"]),
                        "reference_solutions_sha256": sha256_file(paths["references"]),
                    }
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "heldout_sha256 mismatch"):
                validate_manifest_hashes(
                    manifest,
                    paths["heldout"],
                    paths["corpus"],
                    paths["prompt"],
                    paths["oracle"],
                    paths["references"],
                )

    def test_manifest_hash_validator_accepts_exact_hashes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            paths = {
                "heldout": root / "heldout.jsonl",
                "corpus": root / "corpus.jsonl",
                "prompt": root / "prompt.txt",
                "oracle": root / "oracle.json",
                "references": root / "reference_solutions.jsonl",
            }
            for name, path in paths.items():
                path.write_text(name + "\n", encoding="utf-8")
            manifest = root / "manifest.json"
            manifest.write_text(
                json.dumps(
                    {
                        "heldout_sha256": sha256_file(paths["heldout"]),
                        "corpus_sha256": sha256_file(paths["corpus"]),
                        "prompt_sha256": sha256_file(paths["prompt"]),
                        "oracle_mapping_sha256": sha256_file(paths["oracle"]),
                        "reference_solutions_sha256": sha256_file(paths["references"]),
                    }
                ),
                encoding="utf-8",
            )
            validate_manifest_hashes(
                manifest,
                paths["heldout"],
                paths["corpus"],
                paths["prompt"],
                paths["oracle"],
                paths["references"],
            )

    def test_manifest_example_pins_base_fim_and_output_budget(self):
        manifest_path = ROOT / "manifest.example.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        model = manifest["primary_model"]
        self.assertEqual("base", model["variant"])
        self.assertEqual("native_fim", model["prompt_format"])
        self.assertEqual("Q4_K_M", model["quantization"])
        self.assertEqual(0, model["temperature"])
        self.assertEqual(128, model["max_output_tokens"])
        self.assertEqual(
            "8d548c47eb7eeb3b0dbfe0f7110226c075dd0ab797d714a72fe9bf54c4a336e5",
            model["gguf_artifact"]["sha256"],
        )
        self.assertEqual("b11436", manifest["llama_cpp"]["release_tag"])
        self.assertEqual(
            "2fb4070732a94cd627d83af65bca72be6af7498ede330ec650a7c55ba7f7c1c2",
            manifest["llama_cpp"]["asset_sha256"],
        )
        self.assertEqual(10_000, manifest["bootstrap_repetitions"])
        self.assertIn("oracle_mapping_sha256", manifest)
        self.assertIn("reference_solutions_sha256", manifest)

    def test_heldout_schema_rejects_pre_corpus_oracle_ids(self):
        raw = {
            "task_id": "x",
            "language": "python",
            "instruction": "x",
            "prefix": "def f():\n    ",
            "suffix": "\n",
            "test_code": "assert True",
            "knowledge_dependency": "api-knowledge",
            "oracle_reference_ids": ["forbidden-before-corpus-freeze"],
        }
        with self.assertRaisesRegex(ValueError, "must not carry oracle mappings"):
            HeldOutTask.from_dict(raw)


if __name__ == "__main__":
    unittest.main()
