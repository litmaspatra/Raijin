# ADR 0004 — Memory Contract Before Entity Resolution

**Status:** Accepted; coding-knowledge portion superseded by ADR 0007

## Decision

`RaijinMemoryPort` is the stable boundary for personal memory:
- entities
- facts
- episodes
- preferences

Hindsight Lite remains the only long-term personal memory engine. Entity resolution later combines the Hindsight-backed memory port with Android platform sources such as Contacts and Storage.

The original ADR also introduced a separate `CodeKnowledgePort` so coding retrieval could not contaminate personal memory. Phase 0B later failed its frozen adoption gate. ADR 0007 therefore removes the local coding-adapter path and `CodeKnowledgePort` from the runtime contract.

## Why

The surviving rule is the important privacy boundary: personal memory remains categorically local and Hindsight-specific implementation details do not leak into frontend or brain-core contracts.

Phase 0B benchmark artifacts remain independent historical evidence and do not require a runtime code-knowledge port.

## Consequences
- no duplicate long-term personal memory store,
- Hindsight can be integrated by adapter,
- entity logic can be tested against a fake `RaijinMemoryPort`,
- `CodeKnowledgePort` is no longer part of `brain-contract`,
- the future local reasoning tier must use its own reviewed boundary and gate.
