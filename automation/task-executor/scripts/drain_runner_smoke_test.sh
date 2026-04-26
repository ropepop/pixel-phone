#!/usr/bin/env bash
set -euo pipefail

# Script-level smoke test used by CI.
DRAIN_RUNNER_SELF_TEST=1 bash "$(dirname "$0")/drain_runner.sh"

echo "drain_runner_smoke_test passed"
