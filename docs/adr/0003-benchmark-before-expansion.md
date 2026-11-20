# ADR 0003 — Benchmark Before Feature Expansion

**Status:** Accepted

## Decision
The local coding retrieval hypothesis is treated as an early risk, not a late feature.

Phase 0B runs before substantial backend feature expansion. It tests whether retrieval materially improves a ~0.5B coding model on held-out constrained Python tasks.

## Why
If retrieval provides little lift, building the full adapter/repair architecture first would waste time and complexity.

## Consequences
- held-out tasks are authored before corpus tuning,
- corpus starts hand-curated and small,
- 0.5B raw / retrieval variants are compared,
- 1.5B and OpenAI-compatible cloud results are reference ceilings,
- correctness, retrieval failures, prefill, latency and RSS are recorded,
- the coding architecture may be simplified or abandoned based on measured results.
