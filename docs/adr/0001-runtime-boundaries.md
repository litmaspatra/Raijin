# ADR 0001 — Runtime Boundaries

**Status:** Accepted

## Decision
Raijin is Android-native first. Routine personal tasks execute locally. Graphify is strictly a developer knowledge tool and is not shipped as a runtime dependency. Hindsight Lite is the runtime memory engine and will be integrated after its native/custom version is supplied.

Cloud use is restricted to hard coding escalation through an OpenAI-compatible interface.

## Consequences
- Pure Kotlin contracts are established before Android implementations.
- Memory is represented through interfaces until Hindsight Lite arrives.
- No Python/NetworkX/Graphify dependency is introduced into the Raijin APK.
- Personal user data is not eligible for cloud coding payloads.
