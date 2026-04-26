#!/usr/bin/env bash
set -euo pipefail

cat <<'MSG' >&2
sync_repo_to_pixel.sh has been retired.
Train-bot no longer syncs a source checkout to the device.
Use `./scripts/pixel/prepare_native_release.sh` to build a native release artifact locally,
or `make pixel-native-deploy` to build, package, and redeploy the rooted runtime.
MSG
exit 1
