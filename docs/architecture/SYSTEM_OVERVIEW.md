# Raijin Backend System Overview

## Runtime layers

### FastBrain
Local, offline and deterministic-first. Handles routine actions such as calls, messages and opening apps. Phase 1 proves the text → route → typed proposal → actuator → Island-state loop.

### Local reasoning
A small on-device model is reserved for basic reasoning such as contact disambiguation, short Hinglish drafts and reminder parsing. It may not handle a task category until a separate pre-registered held-out gate passes.

### Cloud
Complex work uses a provider-neutral OpenAI-compatible port. Coding is cloud-only after the Phase 0B gate failure. Broader general/document cloud categories are proposed in ADR 0008 C7 but remain unimplemented until owner approval.

### Builder pipeline
App building uses the owner's GitHub repository plus a cloud model and GitHub Actions as the build farm. The phone receives the resulting APK and prompts the owner to install it.

## Memory
Raijin memory is Hindsight Lite. Claude is developing a native/customized version separately. Raijin depends on the stable `RaijinMemoryPort` contract, not on Hindsight implementation details.

Entity resolution combines platform sources with the MemoryPort. No parallel Raijin memory store should duplicate Hindsight.

## Developer knowledge
Graphify is used by development agents to map/query this repository between sessions. It is never linked into the application runtime.

## Frontend boundary
The frontend joins this repository as `:app` and depends on `:brain-contract`.

The bridge exposes:
- `StateFlow<IslandVisualState>`
- `StateFlow<CapabilitySnapshot>`
- utterance processing
- confirmation, option-choice and free-text clarification resolution
- cancellation
- context updates
- capability snapshot input

The eight core Island states are `Idle`, `Listening`, `Resolving`, `Thinking`, `Acting`, `NeedsInput`, `Done`, and `Failed`. `Brief(lines)` is an additive morning-brief expansion.

Compose/UI code must not perform routing, memory, model selection or policy.

## Calls
Proxy calls are permanently dropped. The brain resolves and confirms; the app dials and optionally enables speaker mode; the owner talks.

## Dependency direction

```text
app
 |
brain-contract
 |
brain-core
 +--> RaijinMemoryPort --> Hindsight adapter (later)
 +--> ActuatorPort --> Android adapters (app-side later)
 +--> local-reasoning port (later, gated)
 +--> cloud port (later)
```

Pure contracts and `brain-core` must not import Android APIs.
