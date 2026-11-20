# Phase 0B Held-out Task Authoring Brief

## Why this document exists
The benchmark must avoid same-author and corpus-contamination leakage.

The held-out task set is authored/reviewed independently. The Raijin implementation agent must not use held-out task content to decide what goes into the retrieval corpus.

## Request to external reviewer / Claude
Produce **at least 60** held-out Python coding tasks.

Each task must be a **bounded single-hole slot-infill** task, not whole-function generation.

## Required JSONL fields
Each line contains only:

```json
{
  "task_id": "py001",
  "language": "python",
  "instruction": "What the missing slot must do.",
  "prefix": "code before the slot",
  "suffix": "code after the slot",
  "test_code": "standalone executable Python test that imports candidate.py",
  "knowledge_dependency": "reasoning-only | api-knowledge | api-knowledge-plus-adaptation | algorithmic-reasoning"
}
```

Do **not** include oracle IDs, corpus IDs, expected retrieval snippets, or hints about which corpus entry should solve the task.

## Task-quality requirements
- Exactly one logical hole.
- The answer should usually be 1–15 lines.
- The expected model output is a **dedented replacement block**. The harness applies the hole indentation to every returned line.
- Every task must have an executable test; manual judging does not count.
- Avoid trivial toy-problem repetition for the majority of the set.
- Include exact Python API recall, adaptation, repair-like slots, and algorithmic reasoning.
- Prefer standard-library APIs or explicitly list a dependency if a task requires one.
- No network access in tests.
- No nondeterministic tests.
- Tests must finish quickly and clean up temporary files.
- Do not include the answer in the prompt/scaffold/test.

## Distribution minimums
At least:
- 15 reasoning-only
- 15 API-knowledge
- 20 API-knowledge-plus-adaptation
- 10 algorithmic-reasoning

Total: >= 60 unique task IDs.

## Reference solutions
Provide a separate private `reference_solutions.jsonl` file containing the correct dedented slot replacement for every task. **Keep this file outside the Raijin repository.**

The benchmark validator will reject any task that:
- passes with a null slot (`pass`), or
- fails with its private reference solution.

Reference solutions are test-validation material, not corpus-building input. Only their SHA-256 is recorded in the Raijin manifest.

## Corpus blindness rule
The retrieval corpus is built **without looking at the held-out tasks or private reference solutions**.

The corpus must come from a generic, predeclared list of common Python standard-library APIs and coding patterns.

Only **after both the held-out set and corpus are frozen** may oracle mappings be created by matching held-out tasks to already-existing corpus items.

If no suitable frozen corpus item exists, that task is labelled `uncovered`. It remains in the overall benchmark denominator.

## Freeze rule
Once the user approves the task set:
1. commit/freeze the held-out JSONL unchanged,
2. validate unique IDs and distribution,
3. run null-slot/reference-solution vacuity validation,
4. record SHA-256,
5. separately build and freeze the blind corpus,
6. record corpus SHA-256,
7. only then create oracle mapping.

## What must not happen
- Do not let retrieval results influence task editing.
- Do not rewrite failed tasks after seeing model outputs unless the entire benchmark is invalidated and re-frozen.
- Do not expose reference solutions to the model.
- Do not inspect held-out tasks to choose corpus topics.
- Do not add hand-picked corpus snippets after seeing model failures.
