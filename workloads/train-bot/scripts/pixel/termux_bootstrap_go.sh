#!/usr/bin/env bash
set -euo pipefail

cat <<'MSG' >&2
termux_bootstrap_go.sh has been retired.
Train-bot no longer bootstraps or builds inside Termux.
Use `make pixel-native-test` or `make pixel-native-deploy`.
MSG
exit 1
