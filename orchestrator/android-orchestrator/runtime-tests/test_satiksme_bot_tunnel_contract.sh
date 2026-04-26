#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
TUNNEL_SCRIPT="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/satiksme/satiksme-web-tunnel-service-loop.sh"

if ! rg -Fq 'cloudflared tunnel credentials file missing' "${TUNNEL_SCRIPT}"; then
  echo "FAIL: satiksme tunnel script does not report missing credentials clearly" >&2
  exit 1
fi

if ! rg -Fq '"TunnelID"' "${TUNNEL_SCRIPT}"; then
  echo "FAIL: satiksme tunnel script no longer extracts TunnelID from credentials" >&2
  exit 1
fi

if ! rg -Fq 'tunnel: ${tunnel_id}' "${TUNNEL_SCRIPT}"; then
  echo "FAIL: satiksme tunnel config no longer uses the credential TunnelID" >&2
  exit 1
fi

echo "PASS: satiksme tunnel contract is present"
