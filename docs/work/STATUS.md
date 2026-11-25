# STATUS — requirement verification map

Audit pass: 2026-11-23 (against `main` @ 28b968b + this pass's commits).
Levels per PRD 10.4: `unit-verified` (behaviour tests pass in CI),
`build-verified` (compiles in CI, no behaviour test), `missing`/`partial`.
**Nothing here is device-verified.** No requirement has been confirmed on
the iQOO Z9s; every Android-facing row still needs `docs/work/DEVICE_TESTS.md`
steps run by the owner.

| ID | Pri | Level | Evidence | Notes |
|---|---|---|---|---|
| UI-1 | P0 | build-verified | `hud/`, boot receivers | Reboot survival device-only |
| UI-2 | P0 | unit-verified | `IslandRenderTest` | Reduce-Motion path unaudited |
| UI-3 | P0 | unit-verified (contract) | `BrainContractTest.failedVisualStateRequiresASpeakableReason` (2026-11-24) | Blank failure reasons rejected at construction; full audit: 3 silent app-side Failed sites found and given spoken lines (mic permission, suggestion schedule failure, suggestion expired) |
| UI-4 | P0 | build-verified | island composable gestures | |
| UI-5 | P0 | unit-verified | `LongAnswerSnapshotTest`, `PreferencesPaparazziTest` | JVM render, goldens refreshed 2026-11-22 |
| UI-6 | P1 | missing | — | No type-to-Raijin field |
| UI-7 | P1 | missing | — | No follow-up listen window |
| UI-8 | P1 | absent (audited 2026-11-24) | — | No TileService, no AppWidget provider, no inbound share target in the manifest; existing ACTION_SEND code is outbound sharing only |
| UI-9 | P2 | missing | — | Default-assistant role |
| VO-1 | P0 | unit-verified | `STTEngineRearmTest`, `IntentAlternativeResolverTest`, `ContactMatchingTest` (audited 2026-11-24) | n-best wired end to end: recognizer requests 5 alternatives, service routes them into utterance handling, app/contact resolution consumes `spokenAlternatives`; on-device STT itself remains held (STT Phase 1) |
| VO-2 | P1 | pending-owner | — | On-device STT (STT Phase 1) parked on owner decision |
| VO-3 | P1 | build-verified | TTS wiring exists | Voice tuning device-only |
| VO-4 | P2 | missing | — | Wake word, needs battery numbers first |
| BR-1 | P0 | unit-verified | `RaijinBrainTest` | Router/greeting logic; spoken latency device-only |
| BR-2 | P0 | build-verified | `cloud/ProviderRegistry.kt` | Persistence across restart device-only |
| BR-3 | P0 | unit-verified | `ProviderFailoverPolicyTest` | Circuit breaker, Retry-After, 401/403 policy |
| BR-4 | P0 | unit-verified | `ProviderFailoverPolicyTest` | ≤3 attempts, first-token budget |
| BR-5 | P0 | build-verified | `ui/ProviderFailoverSection.kt` | |
| BR-6 | P0 | audited (2026-11-24) | presets + `testConnection` | Test button makes a real HTTPS round trip (models fetch + tiny completion) with honest 404/model-mismatch guidance; the button's runtime result is inherently device-verified, not CI |
| BR-7 | P1 | absent (audited 2026-11-24) | client exists | No SSE client: `OpenAiCompatibleClient.complete` is a single-shot POST; `supportsStreaming` is a config flag nothing consumes |
| BR-8 | P1 | unit-verified | `SkillToolMapperTest` | Tool schema mapping; provider round-trip needs network |
| BR-9 | P1 | unit-verified | `SessionContextTest`, `RaijinBrainTest` | Per-request context selection (owner decision 2026-11-25 supersedes the PRD rolling window): follow-up questions carry up to the last 3 session turns to the cloud; anything else sends the current message unchanged. History in-memory only (never persisted); "clear history" wipes it locally. No contacts/memory facts/location in cloud prompts |
| BR-10 | P1 | unit-verified | `PersonaTest` (2026-11-24) | Persona prompt is `brain-core/resources/persona/system-prompt.v1.md` loaded via `Persona` (version marker enforced); wording change = new file version + VERSION bump; SkillAuthoringService's drafting prompt is a separate function and stays separate |
| BR-11 | P1 | unit-verified | `EgressGateTest`, `ToolEgressPolicy` | L0/L1/L2 gate vs provider fake |
| CT-1 | P0 | unit-verified | `RaijinDatabaseMigrationTest` (2026-11-23) | ContactEntity registered, real migrations tested |
| CT-2 | P0 | unit-verified | `RelationshipAliasesTest`, `AliasMergeTest` | |
| CT-3 | P0 | unit-verified | `ContactMatchingTest` | Devanagari↔Latin normalisation |
| CT-4 | P0 | unit-verified | `ContactGoldenEvalTest` | Phonetic keys + n-best scoring |
| CT-5 | P0 | unit-verified (logic) | `ContactContextBoostTest` | Signal COLLECTION parked: owner privacy-scope decision open |
| CT-6 | P0 | unit-verified | `ContactScorer` bands in `ContactMatchingTest` | |
| CT-7 | P0 | unit-verified | `ContactCorrectionTest` | |
| CT-8 | P1 | unit-verified | CT-8 tests in `RaijinBrainTest` (2026-11-25) | Calls replaced the manual confirm gate with a 2 s auto-proceed countdown ("Calling X… tap to cancel", new `InputKind.COUNTDOWN`, cancel-only control in both island mappings); messages still confirm manually. Explicit confirm inside the window hurries the call; supersede/cancel/correction all abort the dial. Device rendering remains device-pending |
| CT-9 | P1 | unit-verified | `ContactGoldenEvalTest` | 200 pairs, ≥90% top-1 / ≥98% top-3 asserted in CI |
| CT-10 | P1 | unit-verified | `ContactNumberLabelTest` | Address-book number label (mobile/work/home/custom) matches as an extra name form ("call Rahul work"); same-name clarification options show the label ("Rahul (work)"). Learned per-number preference rides the existing CT-7 alias learning (picking a number learns the spoken form for that phone). Label is transient (`@Ignore`, no migration) |
| AP-1 | P0 | build-verified | `apps/` inventory | Refresh-on-broadcast unaudited |
| AP-2 | P0 | unit-verified | `AppResolverTest` | Built-in + learned aliases |
| AP-3 | P0 | unit-verified | `DeepLinksTest` | |
| AP-4 | P1 | build-verified | `ui/accessibility/` toggle surface | Refusal wording unaudited |
| AP-5 | P1 | audited (2026-11-24) | `RaijinAccessibilityService` | Inventory: single-purpose WhatsApp send automation (find send button, click, timeout watcher). No general primitives (click-by-text, scroll, text entry, back) |
| AP-6 | P1 | missing | — | See→decide→act loop |
| AP-7 | P1 | unit-verified (policy) | `A11ySafetyPolicyTest` (2026-11-24) | Denylist + irreversible-action confirm + payments-blocked policy in brain-core, wired as the chokepoint in `RaijinAccessibilityService`; WhatsApp send unchanged (brain confirm = the confirmation). Device-level refusal unverified |
| AP-8 | P1 | absent, moot today (audited 2026-11-24) | `SessionTrustPolicy` + test (media analogue) | No screen content leaves the device anywhere: the accessibility service only locates/clicks WhatsApp's send button, message text is pre-composed locally. The L2 per-session consent mechanism is absent but has nothing to gate until held AP-6 lands |
| AP-9 | P1 | build-verified | `OemSetupSection.kt` | |
| TM-1 | P0 | unit-verified | `HinglishTimeParserTest` | 108 cases ≥ PRD's 100 |
| TM-2 | P0 | build-verified | `SET_ALARM` declared; actuator path | Clock-app skip-UI is OEM-dependent |
| TM-3 | P0 | build-verified | actuator path | |
| TM-4 | P0 | audited (2026-11-24) | reminder engine + `ReminderBroadcastReceiver` | Exact-alarm prompt exists: `PermissionBroker` opens `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` at need; receiver checks `canScheduleExactAlarms()` and falls back to inexact honestly. The prompt itself is device-visible only |
| TM-5 | P0 | build-verified | `ReminderRearmReceiver` | Boot/timezone re-arm device-only |
| TM-6 | P0 | build-verified | receiver + notification path | Spoken context line unaudited |
| TM-7 | P0 | unit-verified | `RaijinBrainTest` cancel/list/undo rows, `BrainContractTest`, `UndoChipSnapshotTest` | 5s undo chip after voice-created reminders (2026-11-23): tap-only (voice "undo" future); proactive auto-schedule keeps Done flash; device flow unverified |
| TM-8 | P1 | unit-verified | `ReminderRecurrenceTest` | |
| TM-9 | P1 | unit-verified | `ConditionalReminderTest`, `BrainContractTest`, `ConditionMatcherTest`, `ConditionalReminderPolicyTest` | Conditional reminders (2026-11-24): structured ConditionWatch, event-driven cancel on reply + 30-min fire-time check, quiet island chip; English-only phrase pattern; reply titles kept in-memory only; device flow unverified |
| MU-1 | P0 | unit-verified | `MusicLibraryIndexTest`, `LocalMusicProvider` | |
| MU-2 | P0 | unit-verified (logic) | `MediaSessionLogicTest`, `MediaSessionCommander` | Real app sessions device-only |
| MU-4 | P0 | unit-verified (logic) | `MediaSessionLogicTest`, `SessionTrustPolicyTest` | |
| MU-5 | P1 | unit-verified | `PlayerEventTrackerTest` | |
| MU-6 | P1 | unit-verified | `MusicTasteModelTest`, `MusicTasteStoreTest` | |
| MU-7 | P1 | unit-verified | `MusicTasteSamplerTest` | Acceptance rate is owner-measured, open |
| MU-8 | P1 | unit-verified | `MU-8` parse tests in `RaijinBrainTest` + taste-model tests (2026-11-24) | "pasand aaya" → LIKED, "ye mat chalao" → DISLIKED (parsed before play-query so it is never shadowed), "aur aisa" → MORE_LIKE_THIS, each with common variants; actuation records a `MusicEvent` into `MusicTasteStore` immediately with honest failures when notification access is off or nothing is playing |
| ST-1 | P1 | partial | All-files prompt deferred to first use | |
| ST-2 | P1 | absent (audited 2026-11-24) | — | Legacy `StorageAuditor` is only an app-cache/log auditor; no Room file index, no WorkManager dependency in the app, no incremental mtime scan, no island progress, no charging constraint. Build pending; design fork (MediaStore-first vs wait for ST-1) is with the owner |
| ST-3 | P1 | unit-verified | `DuplicateDetectorTest` (2026-11-23) | brain-core funnel: size → edge hash → full hash; no Android wiring yet (M2 gate) |
| ST-4 | P1 | absent (audited 2026-11-24) | `DuplicateDetector` covers duplicates only | No category taxonomy (large files, old downloads, WhatsApp media, screenshots, APKs, empty folders, junk); classifier is brain-core pure work but its input shape depends on the ST-2 index design |
| ST-5/6/7 | P1 | intentionally disabled | `StorageDeletionDisabledTest` | Deletion disabled at all layers (2026-11-22 privacy repair); re-enable is an owner decision |
| ST-8 | P1 | absent (audited 2026-11-24) | — | None of the PRD phrases parse ("storage kitni bachi hai", "duplicates hata do", "WhatsApp ka kachra saaf karo", "bade files dikhao"); only English "clean storage/cache/junk" exist (legacy auditor). Answers depend on ST-2/ST-4; "duplicates hata do" additionally needs the ST-5/6/7 owner decisions |
| NT-1 | P1 | unit-verified | `NotificationCountTest`, `NotificationSummaryTest` | Retention/ring buffer unaudited |
| NT-2 | P1 | unit-verified | `ProactiveNotificationParserTest` (2026-11-23) | 11 tests; fixed tomorrow+time, invalid-hour rollover, strong/weak candidate ranking |
| NT-3 | P1 | unit-verified (logic) | `PendingSuggestionStoreTest` (2026-11-23) | Chip reuses the existing NeedsInput island flow (no new goldens needed); tap-only, voice accept future; store/TTL/queue JVM-tested |
| NT-4 | P1 | unit-verified (logic) | `ProactiveNotificationParserTest`, `PendingSuggestionStoreTest` (2026-11-23) | Owner-confirmed split WIRED: FLIGHT/BILL/MEETING = high importance; explicit times = high confidence; high+high auto-creates, else silent island chip. Device flow unverified |
| NT-5 | P1 | unit-verified | `NotificationQueryParseTest`, `NotificationQueryAnswerTest`, `BrainContractTest` (2026-11-24) | Voice notification questions answered locally from the listener buffer ("kya notifications aaye?", "WhatsApp pe kisne bheja?" + English variants) via existing READ_NOTIFICATIONS type; same honesty rules as the brief; device flow unverified |
| SK-1 | P1 | unit-verified | `SkillValidatorTest`, `SkillJsonParserTest` | Unknown action = validation error, fail closed |
| SK-2 | P1 | unit-verified | `SkillInstallServiceTest`, `SkillFetcherTest`, `SkillRunLogDaoTest` | |
| SK-3 | P1 | unit-verified | `SkillDryRunTest`, `SkillAuthoringServiceTest`, `SkillInstallApprovalPaparazziTest` | |
| SK-4 | P1 | unit-verified | `LearnedSkillsPaparazziTest` | "What I've learned" screen |
| ME-1 | P0 | unit-verified | `RaijinDatabaseMigrationTest` (2026-11-23) | exportSchema=true, v2→9 + v4→9 paths, data preserved; no destructive fallback |
| ME-2 | P0 | build-verified | `RoomRaijinMemoryAdapter` | |
| ME-3 | P1 | unit-verified | `MemoryTierSchemaTest`, `PatternMinerTest` | |
| ME-4 | P1 | unit-verified | `WhatIKnowPaparazziTest` | Edit/delete/forget-all screen; export unaudited |
| ME-5 | P1 | unit-verified | `UserPreferencesStoreTest` | |
| EX-1 | P0 | build-verified | `diagnostics/Diagnostics.kt` | Diagnostics self-test green-on-device unverified |
| EX-2 | P0 | build-verified | `OemSetupSection.kt` | |
| 7.10b | — | unit-verified (planner) | `NoteDraftingTest`, `NoteOb3Test`, `NoteProposalPlannerTest`, `NoteTriggerParser`, `VaultIndexTest` | Vault path choice parked on owner |

Explicitly out of scope (owner, 2026-11-18): accessibility app control depth,
storage cleaning (see ST-5/6/7), device testing (owner-run, pending).
