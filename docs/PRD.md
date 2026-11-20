# Raijin — Product Requirements Document v1.0

**Standalone HUD Agent for Android**

| | |
|---|---|
| Status | **Draft for owner approval** (2026-11-08). Becomes binding for all agents once the owner approves; ADR 0009 (section 13) then formalises the changes. |
| Owner | litmaspatra |
| Target device | iQOO Z9s (Android 14/15, OriginOS/Funtouch), `minSdk 29`, `targetSdk 35` |
| Supersedes | Project PRD v0.1 (Termux era, proxy calls) and frontend PRD v0.5, wherever they conflict with this document |
| Audience | Every coding agent working in this repo (Claude, Antigravity, Codex, others) and the owner |

## 0. How agents must use this document

1. Read in this order: this PRD → `HANDOFF.md` → `AGENTS.md` → `docs/adr/` → `docs/work/CLAIMS.md` (created in M0).
2. Claim a workstream task before starting (section 10). Never work on something another agent has claimed.
3. Everything you build must be verifiable. Section 10.4 defines the only allowed wording for "done".
4. Where this PRD and an older ADR disagree, this PRD wins only for the items listed in section 13. Anything else, the ADRs still stand.
5. If a requirement is unclear, pick the most reasonable reading, write the assumption in your PR, and continue. Do not stall.

---

## 1. Product definition

Raijin is **one Android app** that behaves like a personal HUD agent in the spirit of Karen, Friday and Jarvis: a small floating island that is always present, wakes on tap (and later by voice), understands the owner's speech in English, Hindi and Hinglish, and **does things on the phone** rather than just talking.

It is a **doer first, a chatter second**. It must answer "hi" and general questions, but its value is in actions: call, message, open and control apps, set alarms and reminders, play music, clean storage, watch notifications, and learn new skills.

### 1.1 Goals

- G1. One installed app, no Termux, no companion process, no laptop.
- G2. Cloud LLM brain through the owner's own API keys, with **multiple providers and automatic failover** so it never just stops.
- G3. Routine actions feel instant (target under 800 ms from end of speech to action) because they are handled by deterministic local code, not the cloud.
- G4. Understands the owner's world: contact aliases, mispronounced names, installed apps, music taste, habits.
- G5. Extensible: the owner (or Raijin itself) can create and install skills.
- G6. Safe by construction: risky actions are confirmed, deletions are recoverable, personal data stays on the device (section 6).

### 1.2 Non-goals

- No proxy calls (ADR 0005, permanently dropped). Raijin resolves, confirms and dials; the owner talks.
- No Play Store distribution requirement. The app is sideloaded for one owner, which is what makes All-files access and Accessibility control acceptable.
- No on-device app compilation, no Termux, no Hermes dependency.
- No payments or banking automation, ever.
- No multi-user or account system.

---

## 2. Current state (code audit, 2026-11-08)

This is from **reading the code, not running it**. Treat each line as a hypothesis until M0's diagnostics screen confirms it on the device.

### 2.1 What happens today when the owner says something

```
Island tap → HudOverlay → RaijinService.onIslandTap → STTEngine (SpeechRecognizer, single shot)
 → RaijinService.handleUserUtterance
     ├─ ad-hoc regex chain (custom skills, "remember…", "learn skill…", "who is / what is")
     └─ RaijinBrain.processUtterance (brain-core)
          └─ ReflexParser (rigid regex: open X / call X / message X: text / play X /
             pause·next / clean storage / remind me to X in N min)
          └─ anything else → cloud ONLY if a key is configured, else Failed("I didn't understand that…")
```

**Why "hi" got no answer:** it is not a reflex pattern, the default provider (NVIDIA NIM) has an empty key so the cloud is "not configured", the result is a `Failed` state, and TTS only speaks on `Done`. So the failure was silent. Even with a key, it would be a one-shot call with no persona, history or failover.

### 2.2 Feature reality table

| Area | Status | Evidence |
|---|---|---|
| Island UI, 8 states | REAL | `IslandComposable`, `HudOverlay` |
| Mic on tap | REAL | `STTEngine` uses Android `SpeechRecognizer` (may depend on Google online recognition) |
| Open app | REAL but weak | substring match on launcher labels; no fuzzy match, no Hindi names |
| Call contact | REAL | `TelecomManager.placeCall`; speaker toggle may not stick |
| WhatsApp send | PARTIAL | URL + accessibility click on "Send"; WhatsApp only; fragile |
| Contact aliases | DEAD | `ContactEntity` exists but is not registered in `RaijinDatabase` |
| Fuzzy/phonetic names | MISSING | SQL `LIKE %q%` only; a mispronounced name returns nothing |
| Reminders | PARTIAL | one-shot exact alarm works; recurrence and conditions stored but never used; not re-armed after reboot; exact-alarm permission not requested at runtime |
| Clock alarms/timers | MISSING | no `AlarmClock` intents |
| Music | STUB | `PLAY_FROM_SEARCH` + media keys; "play something" does nothing; no learning |
| Notifications | PARTIAL | listener + regex parser real; shared map not thread-safe |
| Notifications → reminders | MISSING | parser output is not turned into reminders |
| Storage | STUB | `StorageAuditor` only touches Raijin's own cache; "clean storage" confirms then wipes app cache |
| Duplicate finding | MISSING | |
| Accessibility control | PARTIAL | WhatsApp send only; no generic tap/scroll/read |
| Cloud LLM | PARTIAL | clean OpenAI-compatible client; **one** provider, **no failover**, API key in **plaintext SharedPreferences**, no streaming, no history, no tool calling |
| Wake word | DEAD | `WakeWordDetector` never instantiated; design would drain battery |
| TTS | REAL | but speaks only `Done`; reminder voice never wired |
| Skills | STUB | regex → fixed `ActionType`; unrecognised descriptions become `DELETE_FILES` (dangerous, see 2.2.1) |
| Local LLM (llama.cpp JNI) | DEAD | links a stub that returns null |
| Hermes/Termux bridge | DEAD | `HermesConnection` never instantiated |

### 2.2.1 Defects that must be fixed in M0

- `RaijinDatabase.fallbackToDestructiveMigration()` with `exportSchema=false` silently wipes memory, reminders and skills on any schema change.
- Voice-created skills map any unrecognised description to `DELETE_FILES` (`RaijinService.kt:439-443`; `SkillEngine.kt:82` also hardcodes it), so a skill the owner builds can end up deleting files. Fail closed instead.
- Manifest: `BIND_ACCESSIBILITY_SERVICE` / `BIND_NOTIFICATION_LISTENER_SERVICE` declared as `<uses-permission>` (they belong only on the service); `MANAGE_EXTERNAL_STORAGE` declared but unused; `FOREGROUND_SERVICE_MICROPHONE` requested but the service type is `specialUse|dataSync` (`dataSync` has a time limit on Android 15).
- No `SET_ALARM` permission; exact-alarm permission never requested (denied by default on Android 14+).
- Boot receiver only posts a notification; it does not re-arm reminders or start the service.
- Soft-delete exists twice with different trash folders (`ActionExecutor.executeSoftDelete` → `filesDir/raijin_trash`; `PermissionBroker.softDelete`); no job ever purges either after 24 hours.
- `ContactEntity` (aliases, corrections) is not listed in `RaijinDatabase` entities, so it is never created.
- `networkAvailable=true` hardcoded in the capability snapshot.
- Brain sends arbitrary raw text to cloud on `Unknown`, which already violates ADR 0002 as written; cloud error text can leak into the UI.

### 2.3 Disposition

| Keep | Refactor | Delete (M0) |
|---|---|---|
| Island/HUD visuals and animations | `OpenAiCompatibleClient` → provider registry (WS-B) | `app/legacy/**` |
| `brain-contract` / `brain-core` architecture | `PermissionBroker` + `ActionExecutor` (dedupe, split by capability) | `app/.../hermes/**` |
| `AndroidActuator` pattern | `ReflexParser` → pluggable reflex router (WS-A) | `FixtureBrain` from production wiring (move to tests) |
| `RoomRaijinMemoryAdapter` (until Hindsight Lite exists) | `NotificationListenerService` (thread safety, classification) | `llama_stub.cpp` + `llm_jni.cpp` (re-add only if WS-O local LLM is approved) |
| Reminder alarm path | `SkillEngine` → manifest runtime (WS-K) | Old `WakeWordDetector` (rewrite in WS-C) |
| | `RaijinAccessibilityService` → generic control (WS-J) | `IslandViewModel` fixture playback, unless used by UI tests |

---

## 3. Principles

1. **Local first, cloud second.** Order: reflex/skill → local fuzzy parsers → cloud tool-calling → (optional, gated) local LLM. Goal: at least 70 percent of everyday commands never touch the network.
2. **Never silent.** Every failure is shown on the island **and spoken** in one short line. Never leave the user with no feedback.
3. **No fake success.** A stub must say "not implemented yet" to the user. It must never report `Done`.
4. **Propose → Show → Confirm → Act → Report** for anything irreversible (send, call, delete, install, in-app destructive action). Reversible instant actions (alarm, reminder, open app, music) do not ask first but show an **Undo** chip for 5 seconds.
5. **Deletion is always recoverable for 24 hours.**
6. **Personal data stays on the phone** (section 6).
7. **Pure logic lives in `brain-core`** (plain Kotlin, JVM-testable, no Android imports): fuzzy matching, Hinglish date parsing, duplicate-grouping algorithm, provider failover policy, skill validation. Android code is thin adapters. This is what lets any agent test its work without the device.
8. **Hindi and Hinglish are first-class**, not an afterthought.
9. **One question maximum** when ambiguous; otherwise take the best guess.
10. Short answers. Calm, sharp, slightly dry voice. No filler ("I have successfully completed your request").

---

## 4. Architecture

```
            ┌───────────────────────────── app (Android) ─────────────────────────────┐
 tap/voice  │  HUD Island  ◄─ IslandVisualState ─┐                                      │
 ─────────► │  Voice I/O (STT, TTS, wake)        │   Adapters (thin, Android-only):     │
            │  Foreground service                │   Contacts · Apps · Alarms · Media   │
            │  Diagnostics / Settings UI         │   Storage · Notifications · A11y     │
            └──────────────┬─────────────────────┘   Providers (HTTP) · Keystore        │
                           │ RaijinBrainBridge                 ▲ ActuatorPort            │
            ┌──────────────▼───────────────────────────────────┴────────────────────────┤
            │ brain-core (pure Kotlin)                                                  │
            │  Router ─► 1 Reflex/Skills ─► 2 Local parsers (names, time, apps)         │
            │            ─► 3 Cloud agent loop (tool calling) ─► 4 Local LLM (optional) │
            │  Policy gate (confirm/egress) · Capability registry = tool schemas        │
            │  Preference model · Skill runtime · Provider failover policy              │
            └──────────────┬────────────────────────────────────────────────────────────┘
                           │ brain-contract (typed actions, ports, state)
```

### 4.1 Request lifecycle

1. Utterance (text) arrives with n-best STT alternatives when available.
2. **Reflex/skill match** (exact phrases, learned aliases, installed skills). If hit → typed action.
3. **Local parsers** resolve entities: contact (fuzzy + phonetic), app, time expression (Hinglish), song/artist, file category.
4. If still unresolved → **cloud agent loop**: redacted utterance (section 6) + tool schemas generated from the capability registry; the model returns tool calls; each call passes through the **policy gate** before reaching the actuator. Maximum 4 tool rounds per request.
5. Actuator executes, returns `EXECUTED | DENIED | FAILED`, island shows result, TTS speaks one line.
6. Outcome is logged for learning (corrections, confirmations, preferences) and for the user-visible action log.

### 4.2 Learning loop (inherited from the original design)

When the cloud resolves a command that a deterministic rule could have handled, and the owner did not correct or undo it, Raijin may cache a **learned reflex** (phrase pattern → existing handler + parameters). Learned reflexes can only parameterise existing handlers; they can never introduce new capabilities. Communication actions always keep their confirm gate.

---

## 5. Termux: can it be dropped?

**Yes. Fully, and the app is already nearly there.** Nothing in `app/` depends on Termux; the only Termux-bound code is dead (`hermes/**`) or developer tooling (`scripts/phase1d/run-termux.sh`).

| What Termux was used for | Replacement | Milestone |
|---|---|---|
| Python/Termux brain | Already replaced by native `brain-core` | done |
| Hermes websocket bridge (`127.0.0.1:9119`) | Delete the code | M0 |
| Phase 1D device benchmarks | In-app Diagnostics → Benchmark screen | M0 (STT/TTS/latency), M4 (local LLM) |
| Local LLM (Qwen via Termux/Ollama) | Cloud brain by default. Optional in-app llama.cpp JNI module, **off by default, downloaded on demand**, only if WS-O is approved | M4+ |
| Build/test/rollback of generated apps | Cloud LLM edits a GitHub repo through the API; **GitHub Actions** builds; the app downloads the artifact and hands it to `PackageInstaller` | M3 |
| Temporary web apps | Small in-app HTTP server + WebView | M3 |

Caveat on space: an on-device model file would be 1 to 2 GB by itself, so the local model is optional and downloadable, not bundled. The Hermes coach/developer bots are separate products; Raijin has no dependency on them and may call them later over HTTPS as an optional remote-agent skill.

---

## 6. Data egress and privacy policy

This policy is **proposed to replace** the coding-only cloud rule in ADR 0001/0002 and the C7 proposal in ADR 0008. It needs owner approval (section 13).

| Class | Examples | May go to cloud? |
|---|---|---|
| **L0 — never leaves device** | contact names, numbers and aliases; call content; notification content; file names, paths and contents; memory facts; location; API keys; clipboard | **No.** Enforced by the policy gate in `brain-core`, not by convention |
| **L1 — redacted utterance** | the spoken command with known entities replaced by placeholders: "call [CONTACT_1]", "open [APP_1]", digits runs masked | Yes, to the owner's configured providers |
| **L2 — per-use consent** | "summarise my notifications", "draft a letter from this file", screen content for assistive control | Only after an on-island confirmation; every L2 send is written to the **consent ledger** (visible in the app) |

Rules:

- Redaction is **best-effort and heuristic** (Hinglish names can slip through). Therefore Settings has a **Private mode** switch: cloud is disabled except for general chat, and only the owner's raw typed/spoken general questions with no matched entities are sent. In Private mode, anything needing the cloud tells the owner so instead of sending.
- The cloud model returns tool calls with placeholders; **resolution to real contacts happens locally**.
- Notification text is **untrusted data**. It is never placed in a prompt that has tool access (prompt-injection boundary).
- Every provider call is logged locally (provider, time, class, token count, no content) in the action log.
- API keys are stored with **Android Keystore** (AES-GCM, key never exported), never in plaintext, never logged, never in backups (`allowBackup=false` already).

---

## 7. Feature requirements

Priority: **P0** = M0–M1 must have; **P1** = M2–M3; **P2** = later / nice to have. "Acc." is the acceptance check a reviewer can run.

### 7.1 HUD, voice and interaction (WS-C voice, WS-D UI)

| ID | Pri | Requirement | Acc. |
|---|---|---|---|
| UI-1 | P0 | Island floats top-centre over other apps, survives screen-off/on, restarts after reboot | Reboot phone → island present without opening the app |
| UI-2 | P0 | All 8 states plus Brief; respects Reduce Motion | Existing behaviour retained, motion off → crossfade only |
| UI-3 | P0 | **Every Failed state is spoken** (one short line) | Break the network → hear "Couldn't reach the cloud, trying another provider" or a clear failure |
| UI-4 | P0 | Tap to listen; tap again to cancel; long-press opens quick settings | |
| UI-5 | P0 | Island shows long answers in an expandable card (more than one line), scrollable, tap to dismiss | Ask "explain GST in 5 lines" |
| UI-6 | P1 | **Type-to-Raijin** field on the island for when speaking is impractical | |
| UI-7 | P1 | **Follow-up window:** after a question or clarification, listen again for 6 s without a tap | |
| UI-8 | P1 | Quick Settings tile, home widget and Android **Share target** ("share text to Raijin") | |
| UI-9 | P2 | Default-assistant role (long-press power/home) if feasible on OriginOS | |
| VO-1 | P0 | STT hint languages `hi-IN` and `en-IN`; request **n-best alternatives** (≥5) and pass all to the router | Log shows alternatives for a spoken Hindi name |
| VO-2 | P1 | Prefer **on-device** recognition where available (API 31+ `createOnDeviceSpeechRecognizer`), with fallback; evaluate sherpa-onnx | Works in airplane mode for reflex commands |
| VO-3 | P1 | TTS: Hindi/English voice selection, tuned rate/pitch, interruptible (tap or new utterance stops speech) | |
| VO-4 | P2 | **Wake word "Raijin"**, default **off**, behind a setting, only after battery is measured; Android 14/15 background microphone rules must be verified on device | Measured battery cost is documented before enabling |

### 7.2 Brain and cloud providers (WS-A core, WS-B providers)

| ID | Pri | Requirement | Acc. |
|---|---|---|---|
| BR-1 | P0 | **Chat route:** greetings and small talk are answered (local canned replies for hi/hello/namaste/thanks for instant response, cloud for the rest) | "hi" → spoken reply in under 1 s |
| BR-2 | P0 | **Provider registry:** any number of OpenAI-compatible providers, each with label, base URL, key (Keystore), model, priority, enabled flag, tool-calling flag, streaming flag, timeout | Add 3 providers in Settings; all persist across restart |
| BR-3 | P0 | **Automatic failover:** on 429, 5xx, timeout or network error try the next enabled provider; honour `Retry-After`; circuit breaker (3 consecutive failures → cool-down 60 s, doubling to 10 min); 401/403 marks the provider "needs attention" and tells the owner once | Put a wrong key on provider 1 → request still succeeds via provider 2 |
| BR-4 | P0 | Per-request budget: at most 3 provider attempts and 8 s before the first token, then a spoken failure | |
| BR-5 | P0 | **Test button** per provider (sends a tiny request, shows latency and result); import key by paste | |
| BR-6 | P0 | Presets for convenience only (NVIDIA NIM, OpenRouter, Groq, GitHub Models, Gemini's OpenAI-compatible endpoint, LiteLLM/OmniRoute gateways) plus **Custom base URL**. Presets must be verified with the Test button, not assumed correct | |
| BR-7 | P1 | **Streaming** responses (SSE) shown progressively and spoken sentence by sentence | |
| BR-8 | P1 | **Tool calling** from the capability registry (typed JSON schemas). Providers without tool support use a strict-JSON fallback prompt | "Wake me at 6 and play something calm after" produces two tool calls |
| BR-9 | P1 | Conversation history: short rolling window (last N turns, default 10), cleared on demand | "What about tomorrow?" resolves against the previous turn |
| BR-10 | P1 | Persona prompt (calm, sharp, dry, brief, bilingual) is a versioned file in the repo, not scattered strings | |
| BR-11 | P1 | Privacy gate implementing section 6 (L0/L1/L2, Private mode, consent ledger) | Unit tests prove contacts/notification text never reach the provider fake |
| BR-12 | P2 | Cost/usage view per provider; "prefer free providers first" ordering option | |
| BR-13 | P2 | Native adapters for non-OpenAI-compatible APIs only if the owner needs one | |

### 7.3 Contacts and names (WS-E)

The owner will often mispronounce or half-pronounce names; STT will mis-hear them. Resolution must absorb both.

| ID | Pri | Requirement | Acc. |
|---|---|---|---|
| CT-1 | P0 | Index all contacts with display name, structured names, nicknames, organisation, and **aliases**; register `ContactEntity` in the database properly (with real migrations) | |
| CT-2 | P0 | **Alias table:** owner-defined and learned ("Maa" = "Mom" = "Mummy" = "मम्मी"). "Call my mother" works through relationship aliases | "Call Mummy" dials the contact saved as "Maa" |
| CT-3 | P0 | **Normalisation:** lowercase, strip diacritics, collapse repeated vowels, Devanagari ↔ Latin transliteration so a Hindi-script contact matches a Latin-script transcript and the reverse | |
| CT-4 | P0 | **Phonetic keys** tuned for Indian-name confusions (v/w, sh/s/ś, aa/a, th/t, d/dh, ph/f, z/j, ri/ru, nasal endings) plus Jaro-Winkler and edit distance on normalised forms; score **every n-best STT alternative** | See golden set below |
| CT-5 | P0 | **Context boost:** call frequency, recency, time-of-day patterns, whether the name matches a recent message sender | |
| CT-6 | P0 | **Confidence bands:** score ≥ 0.85 and margin ≥ 0.15 over runner-up → proceed; 0.50–0.85 → one question with up to 3 voice-selectable options ("pehla / doosra"); < 0.50 → "I couldn't find X" | |
| CT-7 | P0 | **Correction learning:** a confirmed choice stores "spoken form → contact" so the same mispronunciation resolves instantly next time; wrong-contact corrections are remembered permanently | Correct once, never asked again |
| CT-8 | P1 | Calls: even at high confidence show a **2-second cancelable countdown** ("Calling Rahul Sharma… tap to cancel"); messages always show the draft first | |
| CT-9 | P1 | Golden test set: ≥ 200 (spoken variant → expected contact) pairs in `brain-core` tests, covering Hindi, English and Hinglish; **target ≥ 90 percent top-1** and ≥ 98 percent in top-3 on the set | CI test |
| CT-10 | P1 | Multi-number contacts: pick by label (mobile/work) and by learned preference | |

### 7.4 Apps: launch (WS-E) and control (WS-J)

| ID | Pri | Requirement | Acc. |
|---|---|---|---|
| AP-1 | P0 | **App inventory:** installed launchable apps with label, package, aliases; refreshed on package add/remove broadcasts | |
| AP-2 | P0 | Resolve spoken names with the same fuzzy + phonetic pipeline as contacts, plus built-in aliases ("insta", "yt", "gpay", "whatsapp business") and learned aliases | "Open insta" opens Instagram |
| AP-3 | P0 | Open app, open app **settings page**, open **specific screens** by deep link where known (e.g. WhatsApp chat, Maps search) | |
| AP-4 | P1 | **App control via Accessibility Service — user-togglable.** In Settings: Enable/Disable with a clear status. When disabled, any control action fails with "Turn on accessibility control in settings" and offers a one-tap jump to the system page | Toggle off → control command refused with explanation |
| AP-5 | P1 | Generic control primitives: global actions (back, home, recents, notifications, quick settings, screenshot, lock screen), find node by text/description/id, click, long-click, set text, scroll, swipe/tap gestures, read visible screen text | |
| AP-6 | P1 | **See → decide → act loop** for open-ended control, capped at 15 steps, each step shown on the island, stoppable by tapping the island | |
| AP-7 | P1 | **Hard safety rules:** default denylist for banking/UPI/payment apps, password managers, and system install/uninstall dialogs; irreversible in-app actions (send, post, pay, delete, submit) always require confirm; payments are blocked outright | Ask it to pay on a UPI app → refused |
| AP-8 | P1 | Reading screen content to the cloud is **L2** (per-session consent). Deterministic per-app **playbooks** (e.g. WhatsApp send) run fully local | |
| AP-9 | P1 | Onboarding explains Android 13+ "Allow restricted settings" for sideloaded apps | |

### 7.5 Time: reminders, alarms, timers (WS-F)

| ID | Pri | Requirement | Acc. |
|---|---|---|---|
| TM-1 | P0 | **Deterministic Hinglish/English date-time parser** in `brain-core`: "5 minute baad", "kal subah 6 baje", "parso", "aaj shaam ko", "raat 9 baje", "har somvar 8 baje", "next Friday 3pm", "in 2 hours". Defaults: subah 7:00, dopahar 1:00 pm, shaam 6:00 pm, raat 9:00 pm | ≥ 100 parser test cases in CI |
| TM-2 | P0 | **Alarms** through `AlarmClock.ACTION_SET_ALARM` with `EXTRA_SKIP_UI` (normal permission `com.android.alarm.permission.SET_ALARM`) so they live in the system Clock app; if the OEM clock ignores skip-UI, open it prefilled and say so | "Wake me at 6:30" → alarm exists in Clock |
| TM-3 | P0 | **Timers** through `AlarmClock.ACTION_SET_TIMER` | |
| TM-4 | P0 | **Reminders** in Raijin's own engine with exact alarms. Onboarding requests exact-alarm access (denied by default on Android 14+); fallback to WorkManager with a visible "may be late" warning | Reminder fires on time with phone idle |
| TM-5 | P0 | Re-arm everything on boot, time-zone change, time change and app update | Reboot with a pending reminder → still fires |
| TM-6 | P0 | Reminder firing: full-screen/heads-up notification **plus spoken line with context** (why, who), snooze 5/10/30 min, mark done | |
| TM-7 | P0 | Undo chip for 5 s after creation; "cancel my 6 o'clock alarm", "what reminders do I have?" | |
| TM-8 | P1 | Recurring reminders (daily/weekly/monthly/custom) with correct re-arming | |
| TM-9 | P1 | Conditional reminders ("if Rahul doesn't reply by evening") using the notification listener, 30-minute check window, no polling loop | |
| TM-10 | P2 | Location reminders (geofence), calendar events via `CalendarContract` | |

### 7.6 Music (WS-G)

Honest constraint: instant, reliable autoplay on a **streaming** app depends on that app's intent support. Therefore three tiers, tried in order, and the owner names the apps that matter (open question Q1).

| ID | Pri | Requirement | Acc. |
|---|---|---|---|
| MU-1 | P0 | **Tier 1 — local library:** MediaStore index of the owner's songs, in-app **Media3** player with notification and lock-screen controls. Deterministic and instant | "Play Arijit" starts within ~1 s |
| MU-2 | P0 | **Tier 2 — streaming apps:** `MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH`, app-specific deep links, and control of the running player through `MediaController` sessions (play/pause/next/seek/volume) | Works on at least the primary streaming app the owner names |
| MU-3 | P1 | **Tier 3 — accessibility fallback** (when enabled): tap the first result | |
| MU-4 | P0 | Transport voice commands in English/Hindi: pause, resume, next, previous, repeat, louder, quieter, "ye gaana kya hai" | |
| MU-5 | P1 | **Now-playing observation** across any player via active media sessions (uses the existing notification-listener access) to learn from music played outside Raijin | |
| MU-6 | P1 | **Preference model (local, interpretable):** events = played, completed %, skipped < 30 s, replayed, liked/disliked by voice, time-of-day bucket, weekday/weekend, source. Aggregate per track/artist/language/genre with exponential decay | "Show music taste" screen lists top artists/languages and why |
| MU-7 | P1 | **"Play something":** sample from the preference model with softmax temperature, weighted by time-of-day and recent plays, avoid repeats from the last 20 | Over a week, picks accepted without skip ≥ 70 percent (owner-measured) |
| MU-8 | P1 | Feedback phrases update the model immediately: "pasand aaya", "ye mat chalao", "aur aisa" | |
| MU-9 | P2 | Mood/activity requests ("gym songs", "sone wale gaane") mapped to learned tags; cloud may help classify **track titles only**, and only with consent (L2) | |

### 7.7 Storage scan and management (WS-H)

| ID | Pri | Requirement | Acc. |
|---|---|---|---|
| ST-1 | P1 | Permission: **All-files access** (`MANAGE_EXTERNAL_STORAGE`) requested only when the owner first uses storage features (sideload-only decision); SAF as the documented fallback | |
| ST-2 | P1 | **Index** in Room: path, size, mtime, mime, lazy hash; incremental by mtime; runs in WorkManager with island progress; optional "only while charging" | Re-scan after small change is fast |
| ST-3 | P1 | **Duplicate detection:** group by size → hash first+last 64 KB → full hash only on collisions. Algorithm lives in `brain-core` with tests | Planted duplicates are all found, no false positives in tests |
| ST-4 | P1 | **Categories:** duplicates, large files (> 100 MB), old downloads (> 90 days), WhatsApp media (sent-copy and already-saved duplicates), screenshots, already-installed APKs, empty folders, junk (`.tmp`, thumbnails), app caches | "How much space can I free?" gives per-category bytes |
| ST-5 | P1 | **Report first:** shows list with names, sizes and total reclaimable; nothing is touched before confirm | |
| ST-6 | P1 | **24-hour trash:** moves into `<volume>/.RaijinTrash/<date>/` with a restore manifest (same-volume rename is fast); a WorkManager job purges after 24 h; "restore the files you deleted" works | Delete → restore within 24 h returns files byte-identical |
| ST-7 | P1 | **Safeguards:** protected-path list (owner-editable; default includes any Obsidian vault, read-only), never touch other apps' private data, never delete the last copy of a duplicate set, DCIM originals only as exact-hash duplicates with explicit confirm | |
| ST-8 | P1 | Voice: "storage kitni bachi hai", "duplicates hata do", "WhatsApp ka kachra saaf karo", "bade files dikhao" | |
| ST-9 | P2 | Near-duplicate photos (perceptual hash), per-app storage breakdown (UsageStats), reorganise-by-learned-style suggestions | |

File names and paths are **L0**: all storage reasoning is local.

### 7.8 Notifications and reminders from them (WS-I)

| ID | Pri | Requirement | Acc. |
|---|---|---|---|
| NT-1 | P1 | Reliable `NotificationListenerService` ingestion with thread-safe state, per-app allow/deny list, retention 7 days, ring buffer | |
| NT-2 | P1 | **Local classifier:** OTP/ignore, bills and due dates, deliveries, meetings/invites, "call me at 5", WhatsApp/SMS messages containing time or task expressions. Banking OTPs are never stored | |
| NT-3 | P1 | **Suggested reminder chip** on the island ("Electricity bill due Friday — remind me Thursday 6 pm?"). Accept by tap/voice | A bill-style notification yields a suggestion |
| NT-4 | P1 | **Trust ladder:** after N (default 3) accepted suggestions of the same pattern, offer "auto-create these with undo"; owner-controlled | |
| NT-5 | P1 | Voice: "kya notifications aaye?", "WhatsApp pe kisne bheja?" — answered **locally** from the buffer | |
| NT-6 | P2 | Summaries by cloud only with L2 consent and with no tool access in that call | |

### 7.9 Skills: build and install (WS-K)

A **skill** is a signed-by-hash, declarative manifest executed by an interpreter inside `brain-core`. It can only invoke tools that already exist in the capability registry. No downloaded code is ever executed (no dex loading, no shell), so a skill cannot exceed what the app already permits.

```
skill.json: id, version, name, description, locale triggers (phrases / regex / examples),
            parameters (typed), steps (call_tool, ask_user, speak, if/else, bounded loop, wait),
            requires (capabilities/permissions), egress_class, sha256
```

| ID | Pri | Requirement | Acc. |
|---|---|---|---|
| SK-1 | P1 | Manifest schema + validator + interpreter (pure Kotlin, tested). Unknown tool or action = validation error, **never a fallback to another action** | Unknown action rejected |
| SK-2 | P1 | **Install** from file, HTTPS URL with checksum, or a GitHub repo; shows required capabilities/permissions and risk; owner approves; versioned with rollback; enable/disable; run log | |
| SK-3 | P1 | **Create by voice/text:** "make a skill: when I say good night, set alarm 6:30 and turn on DND". Cloud drafts the manifest (L1 data only) → validator → **dry-run preview of every step** → owner approves → saved | |
| SK-4 | P1 | Learned reflexes (section 4.2) are visible, editable and deletable in a "What I've learned" screen | |
| SK-5 | P2 | Optional sandboxed scripting (QuickJS) for logic that steps cannot express, still limited to registry tools | |
| SK-6 | P2 | Skill sharing as a single file/QR between the owner's devices | |

### 7.10 Memory and preferences (WS-A, WS-L)

| ID | Pri | Requirement | Acc. |
|---|---|---|---|
| ME-1 | P0 | Room database with **real migrations** and `exportSchema=true`; no destructive fallback | Migration test in CI |
| ME-2 | P0 | `RoomRaijinMemoryAdapter` is the canonical `RaijinMemoryPort` implementation until Hindsight Lite is delivered; the port stays the only access path | |
| ME-3 | P1 | Tiers: hot (session), warm (30 days), cold (permanent). A daily pattern job turns 5+ interactions in a time band into a stored pattern | |
| ME-4 | P1 | **"What I know about you" screen:** list, edit, delete any fact, preference, alias, learned reflex; "forget everything" button; export to a file the owner controls | |
| ME-5 | P1 | User preferences: language, TTS voice and speed, confirmation strictness, protected paths, quiet hours, preferred providers | |

### 7.10b Obsidian note-maker (owner-requested 2026-11-19)

Dedicated capability, not part of ST storage cleanup. Owner request: a note-maker
that understands ideas, not a paraphraser: distinct concepts get distinct notes,
notes link to existing notes with [[wikilinks]], placement follows the vault's
real folders and conventions, and the owner's meaning/uncertainty is preserved.

| ID | Pri | Feature | Verification |
|---|---|---|---|
| OB-1 | P1 | **Vault-scoped note write:** `WRITE_VAULT_NOTE` writes/appends markdown strictly inside the owner-configured vault root (never a generic file write); unset root blocks writes; path escape refused; create-only never overwrites; append preserves existing content/frontmatter | unit + device |
| OB-2 | P1 | **Vault awareness:** read-only scan of the real vault (folders, note titles, sampled frontmatter/wikilink/tag/daily-note conventions, aggregated on-device); structure awareness comes from the scan, never invented folders; links resolve only to existing titles, ambiguous or broken targets are flagged | unit |
| OB-3 | P1 | **Note drafting with review:** dictated/thought input is drafted into note proposals (title, placement, links, body preserving meaning + uncertainty, speaker-claims vs owner-words kept distinct); owner reviews original input vs proposals before ANY write (same consent pattern as skill authoring); cloud drafting discloses exactly what leaves (input + folder/title metadata) before sending | unit + render (goldens) + device |
| OB-4 | P2 | **Convention-following placement:** new note vs append/update follows detected vault conventions and semantic need (duplicate-concept detection against the index), with the reason shown in review | unit |

### 7.11 Additional features Claude recommends

| ID | Pri | Feature | Why |
|---|---|---|---|
| EX-1 | P0 | **Diagnostics screen:** one button per capability that self-tests it and reports PASS/FAIL with the reason (mic, STT, TTS, each provider, contacts, app inventory, alarms, exact alarms, accessibility, notification access, storage, media). Plus action log and consent ledger | The owner could not tell which features work; this removes guesswork and gives every agent an objective check |
| EX-2 | P0 | **OEM setup wizard** for OriginOS/Funtouch: overlay permission, autostart, battery "unrestricted"/high-background-power allowance, background pop-ups; deep-link where possible, plain instructions otherwise | The island dies silently on aggressive OEM battery managers |
| EX-3 | P1 | **Morning brief** (existing trigger): pending reminders, unread highlights, today's suggestions | Original product promise |
| EX-4 | P1 | **Undo last action** and **action log** ("what did you do today?") | Trust |
| EX-5 | P1 | Quick device controls: flashlight, volume, brightness, Do Not Disturb (needs policy access), Wi-Fi/Bluetooth via Settings panels, battery status | Cheap, high-frequency, instant |
| EX-6 | P1 | **Web search/answers with sources** through the cloud (L1) | Q&A feels complete |
| EX-7 | P1 | **Routines** (multi-step macros) built as skills ("good night", "leaving home") | Natural use of the skill runtime |
| EX-8 | P1 | **Document drafting** in Hindi/English (letters, notices, applications) with copy/share; content that may be sensitive uses L2 consent or Private mode | Existing product intent |
| EX-9 | P2 | Calculator/unit/currency conversion local; transliteration/translation | |
| EX-10 | P2 | Encrypted export/import of settings, aliases, skills and memory (keys excluded) for moving phones | |
| EX-11 | P2 | App usage and battery insights (needs usage access) | |

---

## 8. Non-functional requirements

Targets are **proposed**; WS-M measures them on the iQOO Z9s and the numbers replace the targets in this table.

| Area | Target |
|---|---|
| Tap → listening | < 300 ms |
| End of speech → action fired (reflex path) | < 800 ms |
| Cloud path | first streamed token < 2 s p50; failover total < 8 s |
| Idle battery (service + island) | < 2 percent per day |
| Idle RAM | < 150 MB |
| APK size | < 60 MB without models |
| Reliability | no ANR; service restarts after kill; reminders survive reboot |
| Security | keys in Keystore; no secrets in logs/commits; no exported components without need; accessibility/notification services expose no IPC |
| Accessibility (of the UI) | content descriptions, Reduce Motion, large font safe |
| Android versions | works on 10–15; Android 14/15 foreground-service rules (microphone type, `dataSync` limits, boot-time restrictions) validated on device before relying on them |

---

## 9. Permissions and onboarding

One guided onboarding, every permission optional, every refusal degrades gracefully with a spoken explanation.

| Capability | Permission / access | Degrades to |
|---|---|---|
| Island | Overlay (`SYSTEM_ALERT_WINDOW`) + OEM background pop-up toggles | In-app only |
| Voice | `RECORD_AUDIO` | Type-to-Raijin |
| Contacts, calls | `READ_CONTACTS`, `CALL_PHONE` | Dialer prefilled |
| Alarms/timers | `com.android.alarm.permission.SET_ALARM` | Open Clock prefilled |
| Reminders | Exact alarm access; notifications | WorkManager with warning |
| Notifications | Notification-listener access | No notification features |
| App control | Accessibility service (+ "Allow restricted settings" on Android 13+ sideloads) | Control actions refused with guidance |
| Storage | All-files access (or SAF) | Media-only via MediaStore |
| Music | Media permissions; media-session access via listener | Search-intent playback |
| Installing built apps | Install-unknown-apps | Download only |
| Boot behaviour | `RECEIVE_BOOT_COMPLETED`, battery exemption | Manual start |

Remove from the manifest: `MANAGE_EXTERNAL_STORAGE` until ST-1 is built; the `BIND_*` permissions as `<uses-permission>`; `SEND_SMS` unless a feature needs it; `FOREGROUND_SERVICE_DATA_SYNC` unless justified.

---

## 10. Multi-agent working agreement

Several agents will edit this repository, and the owner may switch agents when one hits a limit. These rules replace the old "backend owns `brain-*`, frontend owns `app/`" split and the "stop after every phase" gate (section 13).

### 10.1 Workstreams and ownership

| WS | Area | Owns (paths) |
|---|---|---|
| A | Brain core, router, policy gate, contract | `brain-core/`, `brain-contract/`, `docs/adr/` |
| B | Cloud providers, failover, keystore | `app/.../providers/` (migrated from `cloud/`) |
| C | Voice: STT, TTS, wake word | `app/.../audio/` |
| D | HUD/island UI, settings, onboarding, diagnostics UI | `app/.../ui/`, `hud/`, `MainActivity` |
| E | Contacts + app resolution | `brain-core/.../resolve/`, `app/.../contact/`, `app/.../apps/` |
| F | Time: parser, alarms, reminders | `brain-core/.../time/`, `app/.../reminder/` |
| G | Music + preferences | `app/.../music/`, `brain-core/.../preference/` |
| H | Storage | `app/.../storage/`, `brain-core/.../storage/` |
| I | Notifications | `app/.../bridge/` |
| J | Accessibility control | `app/.../ui/accessibility/` |
| K | Skills | `brain-core/.../skill/`, `app/.../skill/` |
| L | Memory/DB/migrations | `app/.../memory/`, `RaijinDatabase.kt` |
| M | QA, CI, diagnostics, device test script, docs/work | `.github/`, `docs/work/`, `app/src/test`, `app/src/androidTest` |
| N | Builder pipeline + web apps (M3) | `app/.../builder/` |
| O | Optional local LLM (needs owner approval) | `app/.../localllm/` |

Shared files (`AndroidManifest.xml`, `gradle/libs.versions.toml`, `settings.gradle.kts`, `RaijinService.kt`): make **small, append-style edits**, rebase right before opening the PR, never reformat or reorder.

### 10.2 Claims, branches, handoff

- **Claim before you start:** add one row to `docs/work/CLAIMS.md` (`WS | task | agent | branch | date | status`) as a tiny direct commit to `main`. Statuses: `claimed`, `in-progress`, `paused`, `in-review`, `done`.
- **Branches:** `agent/<agent-name>/<ws>-<slug>`. One task per PR. Squash-merge into `main`.
- **If you are about to hit a usage limit:** commit work in progress to your branch, set the row to `paused`, and write exactly what is done, what is not, and the next step in the row's notes and in `docs/work/LOG.md`.
- **Every session ends** by appending to `docs/work/LOG.md` (what changed, what is verified and how, what is not, what is next). `HANDOFF.md` is replaced in M0 by a short pointer to this PRD, `CLAIMS.md`, `LOG.md` and `STATUS.md`.
- `docs/work/STATUS.md` holds one row per requirement ID with its verification level (10.4).

### 10.3 Contract changes

`brain-contract/` changes are **additive by default** and need: an ADR entry (a few lines is enough), a PR labelled `contract`, and green CI. WS-A reviews. **Breaking** changes need owner approval. Pure modules must keep compiling with the Kotlin and JVM versions in `gradle/libs.versions.toml` (fix the AGENTS.md "2.0.21" text, which no longer matches).

### 10.4 Definition of done and the only allowed status words

| Level | Meaning | Who can claim it |
|---|---|---|
| `unit-verified` | Behaviour tests pass in CI | any agent |
| `build-verified` | Debug APK builds in CI | any agent |
| `device-verified` | The owner (or a recorded device run) confirmed it on the iQOO Z9s using the steps in `docs/work/DEVICE_TESTS.md` | **only the owner**, or an agent quoting the owner's confirmation |

Agents must never write "works" or "done" for Android behaviour that is only `build-verified`. Every Android-facing PR adds or updates its steps in `DEVICE_TESTS.md` and its Diagnostics self-test (EX-1).

Other rules: pure logic gets behaviour tests first; no stubs that report success; no secrets or keys in commits; keep the Ponytail hygiene from `AGENTS.md` (no duplication, no dead abstractions, small modules); unknown or unsupported input must fail safe, never fall back to a different action.

---

## 11. Milestones

Gate rule: a milestone closes when its items are `build-verified` and the owner has run its device tests. Agents may start the next milestone's **pure-logic** work in parallel; Android-facing work waits for the previous gate.

| M | Name | Contents | Exit |
|---|---|---|---|
| **M0** | Stabilise and make it talk | Delete dead code (section 2.3); fix section 2.2.1 defects; **BR-1, BR-2..BR-5, UI-3**; Keystore; real DB migrations (ME-1); manifest cleanup; **Diagnostics (EX-1)**; OEM wizard (EX-2); `CLAIMS/LOG/STATUS/DEVICE_TESTS`; ADR 0009 | "hi" gets an answer; provider failover demonstrated; diagnostics all green or explained |
| **M1** | Core actions, instantly | CT-1..CT-7, AP-1..AP-3, TM-1..TM-7, MU-1/2/4, VO-1, UI-5, BR-8 (tool calling) with BR-11 policy gate | Golden utterances in section 14 pass |
| **M2** | Device intelligence | ST-*, NT-1..NT-5, AP-4..AP-9, MU-5..MU-8, ME-3/4, EX-3..EX-5, TM-8/9 | Storage report + safe delete + restore; notification suggestion; accessibility toggle |
| **M3** | Extensibility | SK-*, EX-6..EX-8, BR-7/9, builder pipeline and web apps (WS-N), UI-6..UI-8 | Create, preview and install a skill by voice |
| **M4** | Voice depth | VO-2..VO-4 (on-device STT, wake word if battery allows), optional local LLM (WS-O, needs owner approval), CT-9 tuning | Offline reflex commands; wake-word battery numbers documented |
| **M5** | Hardening | Fuzz/property tests, privacy tests, measured latency/RAM/battery, release candidate | Section 8 numbers measured and met or knowingly waived |

---

## 12. Contract additions needed (WS-A, additive)

- Action types: `SET_ALARM`, `SET_TIMER`, `CANCEL_REMINDER`, `CHAT_REPLY`, `CONTROL_APP`, `STORAGE_SCAN`, `FIND_DUPLICATES`, `CLEAN_STORAGE`, `RESTORE_TRASH`, `INSTALL_SKILL`, `CREATE_SKILL`, `RUN_SKILL`, `DEVICE_TOGGLE`, `MUSIC_PLAY_QUERY` with source tier.
- Args/targets: `Notification` target; `Alarm`, `Timer`, `CleanStorage(candidates, bytes, trashHandle)`, `ControlApp(steps)`, `Skill(manifestRef)`.
- Entity candidates carry **match metadata** (match type, matched alias, phonetic score, STT alternative index).
- Ports: `ContactResolverPort`, `AppResolverPort`, `ProviderRegistryPort` (providers, health, failover result), `PreferencePort`, `ConsentLedgerPort`, `SkillStorePort`.
- `CapabilitySnapshot`: overlay, exact alarms, notification access, accessibility enabled, all-files access, per-provider status, real network state; drop dead `localCodingAvailable`.
- Presentation: long-answer state with streaming text; `Brief` already exists.
- Cloud categories: replace coding-only `CloudDataCategory` with the L0/L1/L2 model of section 6.

---

## 13. Decisions in this PRD that need owner approval (ADR 0009)

| # | Change | Supersedes |
|---|---|---|
| 1 | General-purpose cloud brain with the L0/L1/L2 egress policy, Private mode and consent ledger | ADR 0001/0002 "cloud is coding-only"; ADR 0008 C7 (proposed only) |
| 2 | Room `RaijinMemoryPort` implementation is canonical until Hindsight Lite exists | ADR 0004 / HANDOFF "no parallel memory store" |
| 3 | Workstream ownership + claims replace backend/frontend ownership; milestone gates with owner device sign-off replace per-phase "stop for review" | `AGENTS.md` ownership and phase-gate rules; `HANDOFF.md` "Next action: STOP" |
| 4 | Lighter contract-change process (10.3) | `AGENTS.md` "any contract change needs owner approval" |
| 5 | Wake word stays a product goal but moves to M4, default off, pending battery data | `HANDOFF.md` "wake word required in Phase 4" |
| 6 | Termux retired; Phase 1D Termux scripts and plan replaced by in-app Diagnostics/Benchmark | `PHASE_1D_PLAN.md`, `scripts/phase1d/run-termux.sh` |
| 7 | `INTERNET` permission is allowed (already in manifest) | Older "no INTERNET" notes |
| 8 | Proxy calls remain dropped | (no change; the old Project PRD text is obsolete) |

---

## 14. Golden utterances (acceptance script for the owner and agents)

Each row is a manual device test and, where the logic is pure, also a unit test.

| # | Say | Expected |
|---|---|---|
| 1 | "Hi" / "Namaste" | Spoken greeting in under 1 s |
| 2 | "Explain GST in three lines" | Streamed answer; expandable card; spoken |
| 3 | (break provider 1) "Tell me a joke" | Answer via provider 2; one-line note that it switched |
| 4 | "Call Mummy" (saved as "Maa") | Resolves; 2 s cancelable countdown; dials |
| 5 | Mispronounce a contact's name | Correct contact or ≤ 3 spoken options; corrected once, never asked again |
| 6 | "Open insta" / "open Whatsaap" | Correct app opens |
| 7 | "Message Rahul: reaching in 10 minutes" | Draft shown; confirm; sent |
| 8 | "Wake me at 6:30" | Alarm appears in Clock; undo chip |
| 9 | "Kal subah 7 baje dawai yaad dilana" | Reminder created for tomorrow 07:00; fires with spoken context |
| 10 | "Set a timer for 12 minutes" | Timer in Clock |
| 11 | "Play something" | Music starts within ~2 s from preference model |
| 12 | "Skip" / "ye mat chalao" | Skips; preference updated |
| 13 | "Play Arijit Singh" | Local library first, else streaming app |
| 14 | "Kitni storage bachi hai?" | Free/used and category breakdown |
| 15 | "Duplicates dhoondo" | Report with sizes; nothing touched |
| 16 | "Haan, hata do" (after report) | Files moved to 24 h trash; island shows bytes freed |
| 17 | "Wapas laao" | Files restored |
| 18 | (receive a bill notification) | Island suggests a reminder |
| 19 | "Kya notifications aaye?" | Answered locally, no network |
| 20 | Accessibility off: "Scroll down in Chrome" | Refused with one-tap path to enable |
| 21 | Accessibility on: "Go back, open notifications" | Executes global actions |
| 22 | "Pay 500 to Rahul on GPay" | Refused (payments blocked) |
| 23 | "Make a skill: good night = alarm 6:30 + silent" | Draft, step preview, approve, installed |
| 24 | "Good night" | Skill runs |
| 25 | Airplane mode: "Call Maa" | Works (no cloud needed) |
| 26 | Reboot with a pending reminder | Island returns; reminder fires |
| 27 | Private mode on: "Call Rahul" then a general question | Call works locally; question refused or answered per Private mode rules, nothing personal sent |

---

## 15. Open questions for the owner

1. Which music apps do you actually use (Spotify, YouTube Music, JioSaavn, local files)? This decides MU-2 priorities.
2. Which providers and models do you already have keys for, and in what order should they be tried?
3. Do you want Hindi or Hinglish replies by default, and should Raijin speak Hindi in Devanagari-aware TTS?
4. Is sideload-only acceptable permanently (this PRD assumes yes)?
5. Wake word: required soon (battery cost accepted) or later (M4, default off)?
6. What exactly did Antigravity build on branches or locally that is not yet in `main`? Agents must reconcile it before M0 starts.
7. Is Private mode meant to be the default, or off by default?
