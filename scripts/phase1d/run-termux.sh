#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CONFIG="${1:-$ROOT/scripts/phase1d/manifest.local.json}"
OUT="${2:-$HOME/raijin-phase1d/$(date +%Y%m%d-%H%M%S)}"
python3 "$ROOT/scripts/phase1d/measure.py" "$CONFIG" "$OUT"
