# Graphify Developer Context

Graphify is a development-agent context tool for Raijin. It is **not** a Raijin runtime dependency.

## Verified upstream
The inspected upstream repository is `Graphify-Labs/graphify` on branch `v8`. Its README and `pyproject.toml` identify the official PyPI package as **`graphifyy` (double-y)** and the CLI command as `graphify`. The inspected project version was `0.9.77`.

## Purpose
- map the codebase across phases,
- inspect relationships before large edits,
- reduce architecture drift between fresh sessions,
- support handoff/review alongside `HANDOFF.md` and ADRs.

## Source of truth
The repository remains canonical. Graphify output is derived developer context.

## CI generation
Graphify is intentionally **not** run on every tiny commit. The workflow runs at phase/handoff checkpoints (changes to `HANDOFF.md` or `docs/phases/**`) or manually.

Code-only extraction is deterministic/local and does not require an API key.

## Fresh-session procedure
1. Read `HANDOFF.md`.
2. Read current phase report / ADRs.
3. Inspect the latest Graphify artifact when code relationships matter.
4. Never add Graphify to Raijin runtime dependencies.
