#!/usr/bin/env bash
set -euo pipefail

# Example daily sync script for phase-1 mock schedules.
# Usage:
#   ./scripts/sync_schedule.sh /path/to/source/2026-02-25.json

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 /path/to/source/YYYY-MM-DD.json"
  exit 1
fi

SRC="$1"
BASENAME="$(basename "$SRC")"

if [[ ! "$BASENAME" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}\.json$ ]]; then
  echo "Input file name must be YYYY-MM-DD.json"
  exit 1
fi

mkdir -p ./data/schedules
cp "$SRC" "./data/schedules/$BASENAME"

echo "Synced ./data/schedules/$BASENAME"
