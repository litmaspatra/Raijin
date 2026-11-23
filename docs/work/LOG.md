# LOG — session history

## 2026-11-23 (00:30–01:20 IST) — Instinct overnight session

**Changed**
- 621fb9c: ME-1 Room migration path test (v2→9 and v4→9, seeded data survives,
  Room validates final schema); ST-3 DuplicateDetector in brain-core
  (size → 64KiB-edge hash → full hash funnel, 7 tests); docs/work/STATUS.md
  created (full PRD verification map).
- 7e5f8bd: NT-2 ProactiveNotificationParser — 11 new tests pinned and fixed
  three defects: "tomorrow at 5 pm" ignored "tomorrow" when a clock time
  matched; bare numbers like "28" in due dates became hour 28 and rolled
  Calendar leniently into garbage times; bare numbers now lose to
  meridiem/colon-marked candidates (strong/weak ranking). CI: brain-core XML
  test-count summary added to the test job (zero-test guard), matching the
  app job's evidence standard.
- c2d01b0: NT-4 SuggestionTrustLadder in brain-core (7 tests, unwired per
  M2 gate — pure logic only).
- This file + DEVICE_TESTS.md; STATUS.md updated for NT-2/TM-7/NT-4.

**Verified (CI runs, both jobs green)**
- 45059108399 (621fb9c): app XML 170/0 failures; migration + duplicate tests green.
- 45060961984 (7e5f8bd): app XML 182/0; brain-core XML 439/0 (first counted).
- c2d01b0 run pending at log time.

**NOT verified**: everything Android-facing remains device-unverified
(nothing has run on the iQOO Z9s). Lint crashes intermittently with the
known upstream Kotlin-metadata bug (issuetracker 386304679), non-blocking.

**Surfaced for owner/main decision**
- NT-4 wiring: NotificationListenerService auto-creates reminders from
  parsed notifications today, with no suggestion step. The ladder is ready;
  wiring it changes live behavior → owner's call (asked via main).
- TM-7 undo chip: missing. Needs an additive brain-contract field, HUD
  Compose chip, and NEW Paparazzi goldens (new baselines need approval
  before recording).
- BR-9 conversation history: absent end-to-end (single-prompt port).
  Wiring it would send prior turns to the cloud → egress implications, so
  design first.

**Next**
- Owner decision on NT-4 wiring, then TM-7 undo chip (needs golden
  approval), then BR-9 design.

## 2026-11-23 (09:47 IST) — owner NT-4 decision

Owner decided (via main): no 3-accept trust ladder; auto-create stays for
high-importance/high-confidence notification events, low-importance or
low-confidence events become suggest-first chips. SuggestionTrustLadder and
its tests REMOVED (dead code after the decision). Importance mapping and
the NT-3 chip UI are the remaining work, planned for tonight's session;
the importance rule proposal goes to the owner for confirmation first.

## 2026-11-23 (22:00 IST) — NT-3 chip + NT-4 split landed

- 12bcd9f: DetectedEvent gains importance (owner-confirmed map:
  FLIGHT/BILL/MEETING high) and confidence (explicit time expressions high;
  bare-number fallbacks and defaults low). NotificationListenerService
  auto-creates only high+high; lower events queue in PendingSuggestionStore
  (12h TTL) and surface as silent island chips reusing the existing
  NeedsInput confirm/cancel flow — no new goldens needed. Accept schedules
  via the extracted ProactiveReminderScheduler (shared with auto-create);
  dismiss drops; the next queued chip surfaces after each resolve. Chips
  are tap-only and silent (no TTS) by design: low-importance events should
  not interrupt. Voice acceptance is future work.
- Verified: parser importance/confidence pinned by 2 new test groups;
  store by 4 tests. First CI run (45221499003) RED on my own test bug —
  assertNull(message, actual) args reversed at two call sites in
  PendingSuggestionStoreTest (compileDebugUnitTestKotlin error); fixed in
  the follow-up commit with these docs.
- NOT verified: the whole chip flow on device (rendering, tap routing,
  alarm scheduling after accept) — JVM/build level only.

## 2026-11-23 (23:00 IST) — TM-7 undo chip (branch tm7-undo-chip)

- Voice-created reminders now offer a one-shot 5s undo: actuator returns
  EXECUTED with UndoAction.DeleteReminder(id); brain emits
  IslandVisualState.Undoable instead of Done; the island renders a green
  DONE chip with an Undo control; accept runs ActuatorPort.undo (cancels
  the alarm PendingIntent and marks the row fired - no Room schema change).
- Additive contract: ActionResult.EXECUTED gains undoAction/undoWindowMs
  (default 5000, PRD); sealed UndoAction; IslandVisualState.Undoable;
  ActuatorPort.undo (default honest failure); bridge resolveUndo.
- Behavioral scope (main-approved): voice-created reminders only; proactive
  auto-schedule keeps its Done flash; tap-only, no "undo that" voice phrase
  yet; one-shot token, expiry is a no-op to Idle.
- Verified: UndoChipTest (6 tests) + BrainContractTest (2) at JVM level.
  Golden for the new chip staged via PR failure artifact for owner-pixel
  inspection BEFORE any baseline is recorded.
- NOT verified: device rendering/tap/alarm cancellation on hardware.

## 2026-11-24 (00:30 IST) — AP-7 accessibility safety policy

- New brain-core A11ySafetyPolicy: denylist (UPI/payment/banking, password
  managers/authenticators, system installers), irreversible-label confirm
  gate (send/post/pay/delete/submit + Hindi labels), payments blocked
  outright (no confirmation unlocks). Package prefix matching, fail closed.
- Wired as the chokepoint in RaijinAccessibilityService: denied packages'
  events are ignored; the WhatsApp Send tap is CONFIRM_REQUIRED satisfied
  by the owner-confirmed PendingSend (brain SEND_MESSAGE confirmation
  remains the confirmation point - behavior unchanged).
- No payment ActionType exists, so voice "pay X" has no execution path
  (fail-closed by absence). AP-6 loop will route through this policy.
- Verified: A11ySafetyPolicyTest (10 tests) JVM-level. NOT verified:
  on-device refusal behavior.

## 2026-11-24 (00:55 IST) — UI-3 every-Failed-spoken hardening

- Contract: IslandVisualState.Failed now requires a non-blank errorReason
  (a blank reason would be silently unspeakable). Audited all 17 brain-side
  constructions - all non-blank by construction (literals or
  contract-guaranteed DENIED/FAILED reasons).
- App-side audit found THREE silent Failed sites, two of them mine from the
  NT-3 work earlier tonight: mic-permission refusal, suggestion schedule
  failure, suggestion expired. Each got a short spoken line per UI-3.
- Defensive: IslandViewModel JSON "failed" mapping defaults a blank label.
  IslandState.Failed itself NOT require-hardened: some app-side reasons are
  dynamic and cannot all be statically proven non-blank - noted honestly.
- Verified: new contract test + audit. NOT device-verified.
