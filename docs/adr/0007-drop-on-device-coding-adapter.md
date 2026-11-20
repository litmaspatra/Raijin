# ADR 0007 — Drop the On-Device Coding Adapter

**Status:** Accepted

## Context

Phase 0B tested whether retrieval materially improves a constrained Qwen2.5-Coder-0.5B local coding adapter.

The official held-out run failed the pre-registered adoption gate:
- raw 0.5B: 27/67
- FTS: 23/67
- hybrid: 22/67
- oracle: 12/29 covered
- wrong-snippet control: 28/67
- 1.5B raw reference: 39/67
- hybrid vs raw on covered tasks: -20.7 percentage points
- 95% paired-bootstrap CI: -37.9 to -6.9 percentage points

## Decision

The on-device coding adapter is dropped.

Raijin will not ship a local coding-retrieval adapter based on Phase 0B. Coding is cloud-only through the later provider-neutral OpenAI-compatible cloud port and builder pipeline.

The existing `LOCAL_CODING` and `CLOUD_CODING` enum values remain reserved for compatibility. `LOCAL_CODING` must not be interpreted as an implemented capability.

The separate small local reasoning tier remains in scope, but it is not approved by this ADR. It must pass its own pre-registered held-out gate before handling any task category.

## Consequences

- remove `CodeKnowledgePort` and its runtime design path,
- remove the local coding implementation phase,
- keep Phase 0B artifacts frozen for auditability,
- app building uses cloud model + GitHub repository + GitHub Actions,
- no Phase 0B result is repurposed as evidence for the future local reasoning model.
