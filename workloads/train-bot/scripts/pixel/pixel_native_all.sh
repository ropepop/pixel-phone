#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

"${SCRIPT_DIR}/run_native_tests.sh"
"${WORKSPACE_ROOT}/tools/pixel/redeploy.sh" --scope train_bot
"${SCRIPT_DIR}/e2e_all.sh"
