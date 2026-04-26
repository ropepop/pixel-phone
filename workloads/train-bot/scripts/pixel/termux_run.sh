#!/usr/bin/env bash
set -euo pipefail

cat <<'MSG' >&2
termux_run.sh has been retired for train-bot.
The train-bot Pixel pipeline now uses host-side build/test and the rooted native runtime only.
Use `make pixel-native-test` or `make pixel-native-deploy`.
MSG
exit 1
