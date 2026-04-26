#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "scripts/pixel/deploy_root_daemon.sh has been folded into scripts/pixel/redeploy_release.sh" >&2
exec "${SCRIPT_DIR}/redeploy_release.sh" "$@"
