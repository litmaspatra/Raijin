# ADR 0002 — Cloud Coding Boundary

**Status:** Accepted

## Decision
Cloud System 2 is coding-only and OpenAI-compatible.

A local coding attempt may retry once. If it still fails and network/policy permit escalation, the brain emits a consent request before any cloud transmission unless an explicit future setting authorizes coding escalation.

## Local-only classes
- contacts and phone numbers
- messages
- notification content
- call transcripts
- personal memory/facts
- behavioral history

Retrieved coding material must pass an egress scrubber before transmission.

Cloud results must pass local validation before acceptance.
