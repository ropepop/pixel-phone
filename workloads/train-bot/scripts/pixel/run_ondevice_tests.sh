#!/usr/bin/env bash
set -euo pipefail

cat <<'MSG' >&2
run_ondevice_tests.sh has been retired.
Train-bot tests now run on the workstation.
Use `make pixel-native-test`.
MSG
exit 1
