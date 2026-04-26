#!/usr/bin/env bash
set -euo pipefail

cat >&2 <<'MSG'
release_runtime_artifacts.sh is deprecated.

Runtime artifact delivery no longer uses GitHub Releases.
Use local bundle packaging + on-device staging instead:

  1) bash orchestrator/scripts/android/package_runtime_bundle.sh [bundle inputs...]
  2) bash orchestrator/scripts/android/deploy_orchestrator_apk.sh --runtime-bundle-dir <bundle-dir> --action bootstrap
MSG
exit 2
