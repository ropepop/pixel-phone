#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ENTRY_START="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-dns-start.sh"
START_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-start"
STOP_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-stop"
NGINX_TEMPLATE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-remote-nginx.conf.template"
WEB_HELPER="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-doh-identity-web.py"

if ! rg -Fq 'stage_template "adguardhome-doh-identity-web.py"' "${ENTRY_START}"; then
  echo "FAIL: pixel-dns-start missing adguardhome-doh-identity-web.py staging" >&2
  exit 1
fi

if ! rg -Fq 'PIHOLE_REMOTE_DOH_IDENTITY_WEB=' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing sidecar binary constant" >&2
  exit 1
fi
if ! rg -Fq 'start_identity_web_sidecar()' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing sidecar start helper" >&2
  exit 1
fi
if ! rg -Fq 'identity_frontend_healthy()' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing identity frontend health helper" >&2
  exit 1
fi
if ! rg -Fq 'stop_identity_web_sidecar()' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing sidecar stop helper" >&2
  exit 1
fi
if ! rg -Fq 'status:identity_web' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing sidecar runtime status output" >&2
  exit 1
fi
if ! rg -Fq -- '--remote-reload-frontend' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing frontend-only reload mode for identity updates" >&2
  exit 1
fi
if rg -Fq 'start_identity_web_sidecar || true' "${START_FILE}"; then
  echo "FAIL: adguardhome-start still treats sidecar startup as warning-only (expected fail-closed)" >&2
  exit 1
fi
if ! rg -Fq 'start_identity_web_sidecar || return 1' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing fail-closed sidecar startup path" >&2
  exit 1
fi
if ! rg -Fq 'identity_frontend_healthy || return 1' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing identity frontend health enforcement in remote healthcheck" >&2
  exit 1
fi

if ! rg -Fq 'REMOTE_DOH_IDENTITY_WEB_PID_FILE=' "${STOP_FILE}"; then
  echo "FAIL: adguardhome-stop missing sidecar pidfile constant" >&2
  exit 1
fi
if ! rg -Fq "pkill -f 'adguardhome-doh-identity-web.py'" "${STOP_FILE}"; then
  echo "FAIL: adguardhome-stop missing sidecar pkill fallback" >&2
  exit 1
fi

if ! rg -Fq 'location = /pixel-stack/identity {' "${NGINX_TEMPLATE}"; then
  echo "FAIL: nginx template missing /pixel-stack/identity proxy location" >&2
  exit 1
fi
if ! rg -Fq 'location ^~ /pixel-stack/identity/api/' "${NGINX_TEMPLATE}"; then
  echo "FAIL: nginx template missing /pixel-stack/identity/api/ proxy location" >&2
  exit 1
fi
if ! rg -Fq 'location = /pixel-stack/identity/bootstrap.js {' "${NGINX_TEMPLATE}"; then
  echo "FAIL: nginx template missing early bootstrap injector route" >&2
  exit 1
fi
if ! rg -Fq "sub_filter '<head>' '<head><script src=\"/pixel-stack/identity/bootstrap.js\"></script>';" "${NGINX_TEMPLATE}"; then
  echo "FAIL: nginx template missing bootstrap injector head sub_filter" >&2
  exit 1
fi
if ! rg -Fq "sub_filter '</body>' '<script src=\"/pixel-stack/identity/inject.js\"></script></body>';" "${NGINX_TEMPLATE}"; then
  echo "FAIL: nginx template missing identity injector sub_filter" >&2
  exit 1
fi
if ! rg -Fq 'proxy_set_header Accept-Encoding "";' "${NGINX_TEMPLATE}"; then
  echo "FAIL: nginx template missing Accept-Encoding override for HTML injection" >&2
  exit 1
fi

if ! rg -Fq 'ADGUARDHOME_DOH_IDENTITY_WEB_RESTART_MODE' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper missing restart mode override env" >&2
  exit 1
fi
if ! rg -Fq -- '--remote-reload-frontend' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper missing frontend-only reload default" >&2
  exit 1
fi
if ! rg -Fq '/pixel-stack/identity/api/v1/querylog/summary' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper missing querylog summary API route" >&2
  exit 1
fi
if ! rg -Fq '/pixel-stack/identity/api/v1/adguard/querylog' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper missing native querylog proxy route" >&2
  exit 1
fi
if ! rg -Fq '/pixel-stack/identity/api/v1/adguard/stats' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper missing native stats proxy route" >&2
  exit 1
fi
if ! rg -Fq '/pixel-stack/identity/api/v1/adguard/clients/search' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper missing native clients/search proxy route" >&2
  exit 1
fi
if ! rg -Fq 'bootstrap.js' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper missing bootstrap injector endpoint" >&2
  exit 1
fi
if ! rg -Fq 'Querylog Visibility' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper missing Querylog Visibility panel" >&2
  exit 1
fi
if ! rg -Fq 'Revocation requires confirmation. Revoke is blocked when only one identity remains.' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper missing persistent revoke guidance text" >&2
  exit 1
fi
if ! rg -Fq 'Renaming keeps the existing DoH token and DoT hostname.' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper missing rename guidance text" >&2
  exit 1
fi
if ! rg -Fq 'At least one identity must remain.' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper missing explicit last-identity revoke hint" >&2
  exit 1
fi
if ! rg -Fq 'renameBtn.textContent = "Rename";' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper missing rename action button text" >&2
  exit 1
fi
if ! rg -Fq 'saveRenameBtn.textContent = state.renameSubmittingId === entry.id ? "Renaming..." : "Save rename";' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper missing rename save/in-flight button state" >&2
  exit 1
fi
if ! rg -Fq '/pixel-stack/identity/api/v1/identities/${{encodeURIComponent(identityId)}}/rename' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper missing rename API wiring" >&2
  exit 1
fi
if ! rg -Fq 'Press Revoke again for ${{identityId}} within 2s to confirm.' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper missing in-page revoke double-click guidance status" >&2
  exit 1
fi
if ! rg -Fq '}, 2000);' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper missing 2s revoke confirmation timeout" >&2
  exit 1
fi
if ! rg -Fq 'Revocation confirmation expired for ${{identityId}}.' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper missing revoke confirmation expiry feedback" >&2
  exit 1
fi
if ! rg -Fq 'revokeBtn.className = "btn btn-outline-danger btn-sm";' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper revoke confirm-state button styling drifts from revoke button style" >&2
  exit 1
fi
if ! rg -Fq 'revokeBtn.textContent = "Revoke";' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper revoke confirm-state button text drifts from revoke button text" >&2
  exit 1
fi
if ! rg -Fq 'state.revokeArmedId' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper missing revoke arming state tracking" >&2
  exit 1
fi
if ! rg -Fq 'revokeBtn.textContent = "Revoking...";' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper missing revoke in-flight button state" >&2
  exit 1
fi
if ! rg -Fq 'state.identities = state.identities.filter((entry) => entry.id !== identityId);' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper missing optimistic identity list update on revoke" >&2
  exit 1
fi
if ! rg -Fq 'Include internal querylog' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper missing include-internal querylog toggle label" >&2
  exit 1
fi
if ! rg -Fq '<th>Expiration</th>' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper missing identities expiration column" >&2
  exit 1
fi
if ! rg -Fq 'No expiry' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper missing no-expiry create option" >&2
  exit 1
fi
if ! rg -Fq 'querylog_view_mode' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper missing querylog_view_mode key wiring" >&2
  exit 1
fi
if ! rg -Fq 'internal_total_count' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper missing internal_total_count key wiring" >&2
  exit 1
fi
if ! rg -Fq 'top_clients_internal' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper missing top_clients_internal key wiring" >&2
  exit 1
fi
if ! rg -Fq 'top_clients_user' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper missing top_clients_user key wiring" >&2
  exit 1
fi
if ! rg -Fq 'internal_probe_domain_counts' "${WEB_HELPER}"; then
  echo "FAIL: identity web helper missing internal_probe_domain_counts key wiring" >&2
  exit 1
fi

echo "PASS: DoH identity web control plane runtime contracts are present"
