#!/usr/bin/env bash
set -euo pipefail

cat <<'MSG' >&2
build_on_pixel.sh has been retired.
Train-bot now cross-compiles on the workstation and deploys only release artifacts to the rooted runtime.
Use `make pixel-native-build` or `make pixel-native-deploy`.
MSG
exit 1
