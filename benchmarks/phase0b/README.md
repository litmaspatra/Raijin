# Phase 0B Benchmark Harness

This directory contains the pre-registered benchmark harness for the local coding-adapter risk gate.

## Current status
**Closed.** The official held-out run completed and failed the frozen adoption gate. The on-device coding adapter is dropped by ADR 0007. This directory is retained as immutable benchmark evidence; do not tune or repurpose the frozen inputs after the result.

## Frozen protocol direction
- Qwen2.5-Coder-0.5B **base**
- native Qwen FIM tokens
- Q4_K_M
- temperature 0
- max output tokens 128
- bounded single-hole slot infill
- native FIM conditions use raw continuation assembly with only stop/suffix truncation
- chat-style reference conditions use the separate dedent-block normalizer
- candidate/test execution in Docker with no network, memory/CPU/PID limits and read-only root
- minimum 60 unique held-out tasks
- executable test + private reference solution for every task
- null-slot vacuity check
- blind corpus construction
- oracle mapping only after held-out and corpus are both frozen
- paired bootstrap adoption rule

See `docs/benchmarks/PHASE_0B_PLAN.md`.

## Files
- `prompt_template.txt` — actual hashed native-FIM prompt template
- `schema.py` — task, reference-solution and corpus schemas
- `harness.py` — normalization, indentation, sandbox execution, validation and hash checks
- `decision.py` — paired-bootstrap decision logic
- `manifest.example.json` — pinned protocol/run-manifest shape
- `heldout/` — future independently authored/reviewed tasks
- `corpus/` — future blind-built coding corpus
- `tests/` — harness/decision tests

## Private reference solutions
Reference solutions live outside this repository. The manifest records only their SHA-256.

## Stop condition
After these harness fixes and the five-task real-model format pilot pass review, stop. Claude authors the held-out tasks only after that review, and the user reviews them before freeze.
