# ADR 0008 — Full-Vision Contract Scope

**Status:** Accepted for C1-C6 and C8; C7 policy widening is proposed only

## Context

The frontend PRD v0.5 was re-verified against the real backend contract after the frontend architecture was aligned to `brain-contract/BrainContract.kt`.

The backend remains the source of truth. Contract changes are additive unless an explicitly approved cleanup removes a dead abstraction, as with `CodeKnowledgePort` in ADR 0007.

## Accepted contract additions

### C1 — Actuator result boundary
Add `ActuatorPort` and typed `ActionResult` outcomes:
- `EXECUTED`
- `DENIED(reason)`
- `FAILED(reason)`

The brain owns proposals and state transitions. The app implements real Android actuation later.

### C2 — Capabilities in
Add `submitCapabilitySnapshot(snapshot)` to `RaijinBrainBridge` so the app can feed current capabilities/permissions into the brain. `capabilityState` remains the observable output.

### C3 — Free-text clarification
Add `resolveClarification(traceId, answer)` alongside option-based `resolveChoice`.

### C4 — Future action vocabulary only
Reserve these `ActionType` values with no implementation in Phase 1:
- `FIND_FILE`
- `ORGANIZE_FILES`
- `DELETE_FILES`
- `READ_NOTIFICATIONS`
- `DRAFT_DOCUMENT`
- `BUILD_APP`
- `SERVE_WEB_APP`

Add `ActionArgs.Call(speaker)`. Extend reminder args with optional condition and location fields. Soft delete means 24-hour restore when the file phase is implemented.

### C5 — Routing modes
Reserve:
- `LOCAL_REASONING`
- `CLOUD_GENERAL`

The small local model may not handle a category until a separate pre-registered held-out gate passes.

### C6 — Morning brief
Add `IslandVisualState.Brief(lines)`. Phase 1 does not implement morning-brief logic.

### C8 — Proxy calls
ADR 0005 is changed from parked to dropped. Raijin resolves, confirms and dials; the owner talks. Speaker is an explicit call argument.

## Proposed only — C7 cloud-policy widening

No contract or policy implementation is approved by this ADR section yet.

Current ADR 0002 and `CloudDataCategory` remain coding-only until owner review approves a policy widening.

Proposed future categories include:
- `GENERAL_REQUEST`
- `DOCUMENT_CONTENT`

The hard local-only boundary remains unchanged:
- personal memory never leaves the phone,
- contacts and phone numbers never leave the phone,
- notification content never leaves the phone,
- call content never leaves the phone.

Any future general-cloud flow must use explicit disclosure and a consent ledger before transmission.

## Compatibility

The frontend will join this repository as `:app` and depend directly on `:brain-contract`. Unknown additive states/fields must be handled safely by the app.

## Phase 1 review compatibility notes

- `CALL_CONTACT` now requires `ActionArgs.Call`; callers constructing a call dispatch without these typed arguments must migrate.
- `RaijinBrainBridge.submitCapabilitySnapshot` and `RaijinBrainBridge.resolveClarification` are intentionally source-breaking additions for interface implementers, who must implement both.
- `ProcessingMode.LOCAL_REASONING` and `CLOUD_GENERAL` are appended after the pre-existing modes to preserve earlier enum ordinals.
- Phase 1 capability checks are fail-closed: calls require contacts plus phone-call capabilities; messages require contacts. Permission input precedes contact resolution/confirmation.

## Phase 1 follow-up: concurrency and terminal semantics

While a confirmed action is Acting, new utterances are **refused**, not queued. The bridge returns ActionType.NONE with a busy explanation, and emits Acting with a busy label; the original action continues and must publish its terminal Done/Failed. Only unconfirmed pending requests are superseded. Tracked coroutines use isolated supervised child jobs; no caller Job is registered or directly cancelled. Memory exceptions are recoverable, with no sensitive error details in the UI. No brain-contract changes.
