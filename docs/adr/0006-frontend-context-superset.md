# ADR 0006 — Frontend Context Contract Is a Compatibility Superset

**Status:** Accepted; re-verified against frontend PRD v0.5, source check still pending

## Context

The frontend PRD v0.5 is now explicitly bound to `brain-contract/BrainContract.kt`. Its section 3 uses:
- `Resolving(label?, stepTrace)`,
- selected/current object references in `ActiveContext`,
- a thin frontend that consumes the backend contract directly.

The app source itself is still not in this repository.

## Decision

The backend contract keeps:
- optional human-facing `Resolving.label`,
- optional `Resolving.stepTrace`,
- selected/current object references,
- `DeviceContext` with nullable `wifiConnected`, `bluetoothConnected` and `batteryPercent`.

No SSID, Bluetooth device identity or other sensitive network/device identifiers are part of this generic contract.

ADR 0008 separately records the reviewed additive gaps identified by PRD v0.5.

## Consequences

- PRD v0.5 can consume the contract without a parallel frontend state model,
- optional device context remains non-sensitive and backend-safe,
- no backend logic may require optional device fields unless a later reviewed change makes them mandatory.

## Source re-verification gate

The PRD has been re-verified, but the actual `app/` source has not yet joined this repository.

When `:app` is uploaded, re-check its implementation against the contract before frontend/backend integration proceeds. Any mismatch requires a reviewed contract change rather than silent adaptation.
