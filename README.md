# Raijin

Raijin is a native Android personal companion backend built around a fast deterministic reflex layer, local private memory, a gated small local-reasoning tier, and provider-neutral OpenAI-compatible cloud escalation for complex work.

This repository is the source of truth for the Raijin brain/backend project.

## Development status

Phase 0B is closed: the on-device coding adapter failed its frozen benchmark gate and is dropped by ADR 0007.

Phase 1 minimal vertical slice is implemented and awaiting review.

See [PROJECT_PLAN.md](PROJECT_PLAN.md).

## Runtime principles

- Native Android-first architecture.
- Routine personal actions stay local and offline.
- FastBrain handles obvious commands deterministically.
- A small local model may handle only categories that pass a separate pre-registered held-out gate.
- Coding is cloud-only.
- Cloud transport is OpenAI-compatible and provider-neutral.
- Personal memory, contacts, notification content and call content remain local-only.
- Hindsight Lite will provide Raijin memory once the separately developed native version is delivered.
- Graphify is a developer knowledge/context tool only; it is not part of Raijin runtime.
- Proxy calls are dropped: Raijin resolves, confirms and dials; the owner talks.

## Review gates

Each phase is implemented, tested, documented, committed, and then paused for external review before the next phase starts.
