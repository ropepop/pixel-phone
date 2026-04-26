#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ENTRY_START="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-dns-start.sh"
START_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-start"
NGINX_TEMPLATE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-remote-nginx.conf.template"

if ! rg -Fq 'stage_template "adguardhome-doh-identities.py"' "${ENTRY_START}"; then
  echo "FAIL: pixel-dns-start missing adguardhome-doh-identities.py staging" >&2
  exit 1
fi
if ! rg -Fq 'stage_template "adguardhome-doh-identityctl"' "${ENTRY_START}"; then
  echo "FAIL: pixel-dns-start missing adguardhome-doh-identityctl staging" >&2
  exit 1
fi
if ! rg -Fq 'stage_template "pixel-dns-identityctl"' "${ENTRY_START}"; then
  echo "FAIL: pixel-dns-start missing host wrapper pixel-dns-identityctl staging" >&2
  exit 1
fi

if ! rg -Fq 'ensure_doh_identity_store()' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing DoH identity store bootstrap helper" >&2
  exit 1
fi
if ! rg -Fq 'identityctl ensure-legacy' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing legacy token import hook" >&2
  exit 1
fi
if ! rg -Fq 'identityctl nginx-token-block' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing multi-token nginx render hook" >&2
  exit 1
fi
if ! rg -Fq 'identityctl nginx-dot-sni-map' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing DoT SNI allow-map render hook" >&2
  exit 1
fi
if ! rg -Fq 'primary_doh_token()' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing primary DoH token helper" >&2
  exit 1
fi
if ! rg -Fq '__STREAM_DOT_GATE_BLOCK__' "${NGINX_TEMPLATE}"; then
  echo "FAIL: nginx template missing DoT stream gate placeholder" >&2
  exit 1
fi
if ! rg -Fq '__NGINX_LOAD_MODULES_BLOCK__' "${NGINX_TEMPLATE}"; then
  echo "FAIL: nginx template missing dynamic module placeholder for stream mode" >&2
  exit 1
fi

if ! rg -Fq "log_format pixel_doh" "${NGINX_TEMPLATE}"; then
  echo "FAIL: nginx template missing dedicated DoH access log format" >&2
  exit 1
fi
if ! rg -Fq 'location ~ ^/[A-Za-z0-9._~-]+/dns-query/?$' "${NGINX_TEMPLATE}"; then
  echo "FAIL: nginx template missing unknown-token 404 guard route" >&2
  exit 1
fi

echo "PASS: DoH identity control runtime wiring and nginx logging contracts are present"
