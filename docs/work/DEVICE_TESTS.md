# DEVICE TESTS — owner-run acceptance on the iQOO Z9s

Only the owner (or a recorded device run) can mark a requirement
`device-verified` (PRD 10.4). Status of every row below: **UNRUN**.

Install the latest CI-built debug APK before starting. Work top to bottom;
note the row, what happened, and approximately what time.

## A. First run and M0 exit

| # | Step | Expected |
|---|---|---|
| A1 | Tap the island, say "Hi" | Spoken greeting in under ~1 s |
| A2 | Settings → Providers: add two providers, put a WRONG key on the first | Both persist after app restart |
| A3 | Say "Tell me a joke" | Answer arrives via provider 2; island notes the switch (BR-3/4) |
| A4 | Settings → Diagnostics (EX-1) | All checks green or explained |
| A5 | Walk the OEM setup wizard (EX-2) | Battery/overlay/notification steps shown for OriginOS |
| A6 | Break the network (airplane), ask a general question | A clear spoken failure line, never silence (UI-3) |

## B. Contacts and apps (M1)

| # | Step | Expected |
|---|---|---|
| B1 | "Call Mummy" (with a contact saved as "Maa") | Resolves to Maa; 2 s cancelable countdown; dials (CT-2/8) |
| B2 | Mispronounce a contact name | Correct contact or ≤ 3 spoken options (CT-4/6) |
| B3 | Pick the right contact when asked, then repeat the same mispronunciation | Resolves instantly, never asked again (CT-7) |
| B4 | "Open insta", "open Whatsaap" | Correct app opens (AP-1/2) |
| B5 | "Message Rahul: reaching in 10 minutes" | Draft shown first; nothing sends without confirm (CT-8) |

## C. Time (M1)

| # | Step | Expected |
|---|---|---|
| C1 | "Wake me at 6:30" | Alarm exists in the system Clock app (TM-2); note whether the Clock UI opened (OEM-dependent) |
| C2 | "Set a timer for 12 minutes" | Timer running in Clock (TM-3) |
| C3 | "Kal subah 7 baje dawai yaad dilana" | Reminder for tomorrow 07:00 (TM-1/4) |
| C4 | Let the C3 reminder fire with the phone idle | Full-screen/heads-up + spoken line with context (TM-6) |
| C5 | Reboot with a pending reminder | Island returns without opening the app; reminder still fires (UI-1, TM-5) |
| C6 | "What reminders do I have?" then "cancel my 7 o'clock reminder" | Listed; cancelled (TM-7 partial: no undo chip yet) |

## D. Music (M1)

| # | Step | Expected |
|---|---|---|
| D1 | "Play Arijit Singh" | Local library hit starts within ~1-2 s (MU-1) |
| D2 | "Pause" / "next" / "ye gaana kya hai" | Transport control + spoken answer (MU-2/4) |
| D3 | "ye mat chalao" | Track skips; later "play something" avoids it (MU-8/7) |

## E. Notifications (M2)

| # | Step | Expected |
|---|---|---|
| E1 | Receive a bill-style notification | Island suggests a reminder; NOTHING is auto-created (NT-3 — pending the owner's NT-4 decision; current build auto-creates, see STATUS.md) |
| E2 | "Kya notifications aaye?" | Answered locally, no network (NT-5) |

## F. Safety rails (M1/M2)

| # | Step | Expected |
|---|---|---|
| F1 | Accessibility OFF: "Scroll down in Chrome" | Refused with one-tap path to enable (AP-4) |
| F2 | "Pay 500 to Rahul on GPay" | Refused outright (AP-7 — denylist currently MISSING, see STATUS.md) |
| F3 | Private mode on: "Call Rahul", then a general question | Call local; cloud question follows Private-mode rules; nothing personal leaves (BR-11) |
| F4 | "Make a skill: good night = alarm 6:30 + silent" | Draft manifest, step-by-step preview, approve, installed (SK-3) |
| F5 | "Good night" | The F4 skill runs (SK-1/2) |

## G. Storage (M2 — currently disabled)

Deletion is disabled at every layer in this build (owner decision,
2026-11-22). Skip storage-delete rows until re-enabled; "kitni storage bachi
hai" reporting rows remain valid.

Record results in this file (date, build SHA, pass/fail + notes) or report
them in chat; an agent will transcribe.
