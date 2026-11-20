# Phase 0B Official Run Archive

- GitHub Actions run: `37757372027`
- Head SHA: `2dc509bc934cf8b57ce0734500baf35d002c6b0e`
- Artifact: `phase0b-benchmark-logs`
- Artifact ID: `11541908699`
- Artifact digest: `sha256:32041680a1c629018e68f866e6f9df37bd892c015344a56b15444643b06ddcda`
- Runner: GitHub-hosted Ubuntu 24.04
- CPU reported by `lscpu`: AMD EPYC 7763, 2 logical CPUs
- No gate-condition model errors were recorded.

## Pass counts

| Condition | Result |
|---|---:|
| A raw 0.5B | 27 / 67 |
| B FTS | 23 / 67 |
| C hybrid | 22 / 67 |
| D oracle | 12 / 29 covered |
| E wrong snippet | 28 / 67 |
| F 1.5B raw | 39 / 67 |

Primary covered hybrid-vs-raw lift: **-20.7 percentage points** with 95% paired-bootstrap CI **[-37.9, -6.9]**.

The pre-registered gate failed. The exact analyzer output is archived beside this file as `phase0b-official-analysis.json`.

The full per-task logs and incremental summary remain in the GitHub Actions artifact above rather than being copied into the source tree.
