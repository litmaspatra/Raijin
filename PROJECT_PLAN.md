# Raijin Backend Project Plan

## Delivery rule

Every phase ends with:
1. behavior-focused tests and static validation,
2. architecture/scope review,
3. a phase report,
4. a GitHub commit,
5. a stop for external review before the next gated phase.

The plan is intentionally vertical-slice-first. Product capabilities such as music, reminders/calendar, and broad sharing remain product goals, but they are deferred until the core text → reflex → actuator → UI loop is proven.

## Phase 0 — Foundation & Contract Repair
- Repository bootstrap
- Architecture boundaries and ADRs
- Pure Kotlin frontend/brain contract
- Typed actions/targets/cloud payload categories
- Inbound brain commands
- Exact eight Island UI states
- Hindsight-facing memory port contract (implementation external)
- CI/test harness and Gradle wrapper
- Cross-session HANDOFF.md
- Developer-agent rules
- Graphify developer-knowledge workflow

## Phase 0B — Coding Retrieval Benchmark — CLOSED / FAILED GATE

Official result: the hybrid retrieval adapter failed the frozen adoption gate. The on-device coding adapter is dropped by ADR 0007. Frozen benchmark inputs and hashed benchmark code remain untouched for auditability.

Coding is cloud-only. `LOCAL_CODING` remains a reserved compatibility enum value, not an implemented capability.

## Phase 1 — Minimal Vertical Slice
Prove the complete backend control loop before broad feature work.

- text request in
- deterministic reflex route
- typed action proposal
- fake actuator
- Island state/event out
- confirmation/cancel/choice flow
- trace IDs and cancellation
- capability registry
- no Android device API required yet

## Phase 1D — Target-Device Performance Spike
Immediately after the vertical slice.

On the target iQOO device, measure rather than assume:
- candidate small local-reasoning model load/prefill/generation latency and peak RSS,
- sherpa-onnx STT startup/streaming behavior and RSS,
- cold vs warm behavior,
- sustained battery/thermal observations for a short controlled run.

Before Phase 1D ends, pre-register the held-out gate for basic local reasoning categories. Do not let the model handle any category until that gate passes. Results become design inputs, not marketing claims.

## Phase 2 — Reflex Engine & Training Data
- deterministic rules for obvious commands
- typed intent/entity/slot contracts
- confidence and ambiguity handling
- actual tiny classifier/router implementation
- English + Hinglish command dataset
- train/evaluate classifier and slot/entity extraction approach
- measured confusion matrix / held-out accuracy
- no cloud dependency for routine actions

## Phase 3A — Android Context, Contacts & File Intelligence
- active context
- Android contacts/files adapters behind interfaces
- entity resolver built on the MemoryPort + Android sources (no duplicate memory store)
- file index and learned organisation
- WhatsApp storage manager
- read-only Obsidian vault awareness unless explicitly permitted otherwise
- find/move/share operations and 24-hour soft-delete recovery
- ambiguity handling and policy confirmations

## Phase 3B — Calls & App Actions
- app launch
- direct call placement/control where Android permits
- capability and permission handling
- fake-to-real actuator replacement for these action types

## Phase 3C — Notifications & Messaging
- NotificationListenerService ingestion
- WhatsApp/compatible RemoteInput reply path
- typed message channel handling
- clear fallback when direct send is unavailable

## Phase 3D — Sharing
- file/contact/text sharing through supported Android mechanisms
- Android Sharesheet/direct-share adapters
- channel-specific fallbacks without embedding app-specific logic in the brain

### Calls
Proxy calls are permanently dropped. Raijin resolves, confirms and dials; the owner talks. Speaker mode is explicit when requested.

## Phase 4 — Voice & Required Wake Word
- custom "Raijin" wake-word dataset/training/evaluation
- always-on wake word is required, behind a controlled runtime flag
- wake/listen contracts
- streaming STT integration
- TTS integration
- interruption and cancellation
- audio lifecycle
- measure on-device behavior against Phase 1D baselines

## Phase 5 — Hindsight Lite Integration
- integrate the separately-developed native/custom Hindsight Lite supplied by Claude
- implement the Phase 0 MemoryPort adapter
- hot/warm/long-term memory wiring
- entity/fact/preference access
- migration and compatibility tests
- no parallel/duplicate Raijin memory database

## Phase 6 — Local Reasoning Port
- small local model is for basic reasoning only, not coding
- categories remain disabled until the Phase 1D pre-registered gate passes
- provider/runtime sits behind a pure backend port; Android/JNI runtime stays app-side

## Phase 7 — Cloud Port + Consent Ledger
- provider-neutral OpenAI-compatible endpoint abstraction (base URL, key, model)
- supports OpenAI, LiteLLM, OmniRoute and GitHub Models through the same protocol surface
- network preflight, consent ledger, egress policy and retry/token/cost budgets
- coding/app-building is cloud-only
- broader general/document cloud categories remain subject to the C7 policy approval in ADR 0008

## Phase 8 — Reminders & Calendar
Deferred until the vertical slice and memory integration are stable.

- Android AlarmManager/WorkManager scheduling adapters where appropriate
- Android Calendar Provider integration where appropriate
- natural-language recurring reminders
- conditional reminders
- location/geofence reminders
- snooze/completion semantics only where Android primitives do not cover product behavior
- avoid building a duplicate calendar stack without demonstrated need

## Phase 9 — Builder Pipeline
- GitHub repository operations through the owner's authenticated account
- cloud model writes/edits source
- GitHub Actions builds the APK
- app downloads the artifact and prompts installation
- temporary web-app generation and local serving path

## Phase 10 — Music
Deferred until the core assistant loop is stable.

- Media3-facing controller
- MediaStore library access
- queue/playlists/favorites/history as needed
- interpretable preference model
- no model-specific logic in UI contracts

## Phase 11 — Runtime Hardening
- Android service/process lifecycle
- permissions/capability observation
- recovery
- privacy/security tests
- fuzz/property tests
- migration tests
- measured latency/RAM/battery numbers on target device

## Phase 12 — Frontend Integration / Release Candidate
- integrate Claude frontend through the Phase 0 bridge
- end-to-end flows
- release documentation
- final validation
