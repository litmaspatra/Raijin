# ADR 0005 — Proxy Calls Are Dropped

**Status:** Dropped

## Decision

Autonomous proxy calls are permanently out of scope.

Raijin may resolve a contact, ask for confirmation, dial the call and request speaker mode when the owner explicitly asks for it. The owner always does the talking.

No AI voice on the carrier call, acoustic loopback conversation, whisper mode, live call transcription, post-call summarisation or call-content extraction will be built.

## Consequences

- the backend contains no proxy-call engine,
- call actions use `ActionArgs.Call(speaker)`,
- telephony call-active UI/timer state remains app-local,
- SIP/VoIP does not restore the dropped proxy-call product scope.
