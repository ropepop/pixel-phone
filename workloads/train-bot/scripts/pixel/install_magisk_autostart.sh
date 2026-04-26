#!/usr/bin/env bash
set -euo pipefail

cat <<'MSG'
Legacy per-project Magisk autostart has been retired.
Train bot lifecycle is owned by the rooted Pixel orchestrator runtime.
Use scripts/pixel/redeploy_release.sh to bootstrap/start component train_bot.
MSG
