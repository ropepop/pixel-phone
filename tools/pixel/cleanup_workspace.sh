#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck source=./artifact_retention.sh
source "${SCRIPT_DIR}/artifact_retention.sh"
pixel_workspace_cleanup_native workspace "${REPO_ROOT}" "$@"
