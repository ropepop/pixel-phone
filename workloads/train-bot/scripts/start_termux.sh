#!/usr/bin/env bash
set -euo pipefail

cat <<'MSG' >&2
scripts/start_termux.sh has been retired.
Train-bot no longer uses a Termux-owned runtime or build path.
Use `go run ./cmd/bot` for local development, or `make pixel-native-deploy` for the rooted Pixel runtime.
MSG
exit 1
