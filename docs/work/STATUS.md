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
| UI-3 | P0 | partial | failure states exist in island VM | "Every Failed spoken" not asserted by a test |
| UI-4 | P0 | build-verified | island composable gestures | |
| UI-5 | P0 | unit-verified | `LongAnswerSnapshotTest`, `PreferencesPaparazziTest` | JVM render, goldens refreshed 2026-11-22 |
| UI-6 | P1 | missing | — | No type-to-Raijin field |
| UI-7 | P1 | missing | — | No follow-up listen window |
| UI-8 | P1 | partial | — | Tile/widget/share target unaudited |
| UI-9 | P2 | missing | — | Default-assistant role |
| VO-1 | P0 | partial | `STTEngine`, `STTEngineRearmTest` | n-best alternatives routing unaudited |
| VO-2 | P1 | pending-owner | — | On-device STT (STT Phase 1) parked on owner decision |
| VO-3 | P1 | build-verified | TTS wiring exists | Voice tuning device-only |
| VO-4 | P2 | missing | — | Wake word, needs battery numbers first |
| BR-1 | P0 | unit-verified | `RaijinBrainTest` | Router/greeting logic; spoken latency device-only |
| BR-2 | P0 | build-verified | `cloud/ProviderRegistry.kt` | Persistence across restart device-only |
| BR-3 | P0 | unit-verified | `ProviderFailoverPolicyTest` | Circuit breaker, Retry-After, 401/403 policy |
| BR-4 | P0 | unit-verified | `ProviderFailoverPolicyTest` | ≤3 attempts, first-token budget |
| BR-5 | P0 | build-verified | `ui/ProviderFailoverSection.kt` | |
| BR-6 | P0 | partial | presets exist | "Verified with Test button" is a runtime claim, not CI |
| BR-7 | P1 | partial | client exists | SSE streaming unaudited |
| BR-8 | P1 | unit-verified | `SkillToolMapperTest` | Tool schema mapping; provider round-trip needs network |
| BR-9 | P1 | partial | — | Rolling history unaudited |
| BR-10 | P1 | partial | — | Persona-as-versioned-file unaudited |
| BR-11 | P1 | unit-verified | `EgressGateTest`, `ToolEgressPolicy` | L0/L1/L2 gate vs provider fake |
| CT-1 | P0 | unit-verified | `RaijinDatabaseMigrationTest` (2026-11-23) | ContactEntity registered, real migrations tested |
| CT-2 | P0 | unit-verified | `RelationshipAliasesTest`, `AliasMergeTest` | |
| CT-3 | P0 | unit-verified | `ContactMatchingTest` | Devanagari↔Latin normalisation |
| CT-4 | P0 | unit-verified | `ContactGoldenEvalTest` | Phonetic keys + n-best scoring |
| CT-5 | P0 | unit-verified (logic) | `ContactContextBoostTest` | Signal COLLECTION parked: owner privacy-scope decision open |
| CT-6 | P0 | unit-verified | `ContactScorer` bands in `ContactMatchingTest` | |
| CT-7 | P0 | unit-verified | `ContactCorrectionTest` | |
| CT-8 | P1 | partial | — | 2 s cancelable countdown unaudited |
| CT-9 | P1 | unit-verified | `ContactGoldenEvalTest` | 200 pairs, ≥90% top-1 / ≥98% top-3 asserted in CI |
| CT-10 | P1 | partial | — | Multi-number label pick unaudited |
| AP-1 | P0 | build-verified | `apps/` inventory | Refresh-on-broadcast unaudited |
| AP-2 | P0 | unit-verified | `AppResolverTest` | Built-in + learned aliases |
| AP-3 | P0 | unit-verified | `DeepLinksTest` | |
| AP-4 | P1 | build-verified | `ui/accessibility/` toggle surface | Refusal wording unaudited |
| AP-5 | P1 | partial | `RaijinAccessibilityService` | Primitives inventory unaudited |
| AP-6 | P1 | missing | — | See→decide→act loop |
| AP-7 | P1 | missing | — | No denylist found in code |
| AP-8 | P1 | partial | `SessionTrustPolicy` + test (media analogue) | Screen-content egress consent unaudited |
| AP-9 | P1 | build-verified | `OemSetupSection.kt` | |
| TM-1 | P0 | unit-verified | `HinglishTimeParserTest` | 108 cases ≥ PRD's 100 |
| TM-2 | P0 | build-verified | `SET_ALARM` declared; actuator path | Clock-app skip-UI is OEM-dependent |
| TM-3 | P0 | build-verified | actuator path | |
| TM-4 | P0 | partial | reminder engine + `ReminderBroadcastReceiver` | Exact-alarm runtime prompt unaudited |
| TM-5 | P0 | build-verified | `ReminderRearmReceiver` | Boot/timezone re-arm device-only |
| TM-6 | P0 | build-verified | receiver + notification path | Spoken context line unaudited |
| TM-7 | P0 | partial | `RaijinBrainTest` cancel/list rows | Cancel/list unit-verified; 5 s undo chip MISSING (needs additive contract field + HUD chip + new goldens) |
| TM-8 | P1 | unit-verified | `ReminderRecurrenceTest` | |
| TM-9 | P1 | partial | `ProactiveNotificationParser` | Conditional reminder wiring unaudited |
| MU-1 | P0 | unit-verified | `MusicLibraryIndexTest`, `LocalMusicProvider` | |
| MU-2 | P0 | unit-verified (logic) | `MediaSessionLogicTest`, `MediaSessionCommander` | Real app sessions device-only |
| MU-4 | P0 | unit-verified (logic) | `MediaSessionLogicTest`, `SessionTrustPolicyTest` | |
| MU-5 | P1 | unit-verified | `PlayerEventTrackerTest` | |
| MU-6 | P1 | unit-verified | `MusicTasteModelTest`, `MusicTasteStoreTest` | |
| MU-7 | P1 | unit-verified | `MusicTasteSamplerTest` | Acceptance rate is owner-measured, open |
| MU-8 | P1 | partial | taste model update path | Voice feedback phrases unaudited |
| ST-1 | P1 | partial | All-files prompt deferred to first use | |
| ST-2 | P1 | partial | `StorageAuditor` | Room index + WorkManager scan unaudited |
| ST-3 | P1 | unit-verified | `DuplicateDetectorTest` (2026-11-23) | brain-core funnel: size → edge hash → full hash; no Android wiring yet (M2 gate) |
| ST-4 | P1 | partial | — | Category taxonomy unaudited |
| ST-5/6/7 | P1 | intentionally disabled | `StorageDeletionDisabledTest` | Deletion disabled at all layers (2026-11-22 privacy repair); re-enable is an owner decision |
| ST-8 | P1 | partial | — | Storage voice phrases unaudited |
| NT-1 | P1 | unit-verified | `NotificationCountTest`, `NotificationSummaryTest` | Retention/ring buffer unaudited |
| NT-2 | P1 | unit-verified | `ProactiveNotificationParserTest` (2026-11-23) | 11 tests; fixed tomorrow+time, invalid-hour rollover, strong/weak candidate ranking |
| NT-3 | P1 | partial | — | Suggestion chip unaudited |
| NT-4 | P1 | owner-decided | — | Owner (2026-11-23): NO 3-accept ladder (code removed same day). Split, mapping CONFIRMED by owner: FLIGHT/BILL/MEETING = high importance (auto-create when high-confidence); generic REMINDER = low (suggest-first chip). Chip UI (NT-3) not built; split enables only after it lands |
| NT-5 | P1 | partial | local buffer exists | Voice answers unaudited |
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
