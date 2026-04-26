#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

violations="$(
  git -C "${REPO_ROOT}" ls-files \
    | rg '^(\.codex-tmp/|output/|\.artifacts/|orchestrator/\.artifacts/|workloads/[^/]+/\.artifacts/|workloads/[^/]+/output/|\.playwright-cli/)' \
    || true
)"

if [[ -n "${violations}" ]]; then
  echo "FAIL: tracked generated output remains under managed garbage roots:" >&2
  printf '%s\n' "${violations}" >&2
  exit 1
fi

echo "PASS: no tracked generated output remains under managed garbage roots"
