#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
# shellcheck source=../../../tools/pixel/transport.sh
source "${REPO_ROOT}/tools/pixel/transport.sh"

ADB_SERIAL="${ADB_SERIAL:-}"
ADB_CONNECT=""
OUTPUT_ROOT="${OUTPUT_ROOT:-output/arbuzas/dns-cutover}"
ARBUZAS_HOST="${ARBUZAS_HOST:-arbuzas}"
ARBUZAS_USER="${ARBUZAS_USER:-aleksandrsdaniilsjolkins}"
ARBUZAS_SSH_PORT="${ARBUZAS_SSH_PORT:-22}"
DEPLOY_HOST=1

usage() {
  cat <<'EOF'
Usage: cutover_dns_to_arbuzas.sh [options]

Stage the live DNS runtime state from Pixel into an arbuzas-ready bundle and,
when host access is available, install the arbuzas DNS runtime and migrated state.

Options:
  --adb-serial SERIAL      Target adb serial (default: first connected device)
  --adb-connect HOST:PORT  Run adb connect before resolving the target device
  --output-root PATH       Output root (default: output/arbuzas/dns-cutover)
  --arbuzas-host HOST      SSH host for arbuzas (default: arbuzas)
  --arbuzas-user USER      SSH user for arbuzas (default: aleksandrsdaniilsjolkins)
  --arbuzas-port PORT      SSH port for arbuzas (default: 22)
  --stage-only             Build the migration bundle but do not deploy it
  -h, --help               Show this help text
EOF
}

while (( $# > 0 )); do
  case "$1" in
    --adb-serial) shift; ADB_SERIAL="${1:-}" ;;
    --adb-connect) shift; ADB_CONNECT="${1:-}" ;;
    --output-root) shift; OUTPUT_ROOT="${1:-}" ;;
    --arbuzas-host) shift; ARBUZAS_HOST="${1:-}" ;;
    --arbuzas-user) shift; ARBUZAS_USER="${1:-}" ;;
    --arbuzas-port) shift; ARBUZAS_SSH_PORT="${1:-}" ;;
    --stage-only) DEPLOY_HOST=0 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

require_cmd adb
require_cmd jq
require_cmd python3
require_cmd install

if [[ -n "${ADB_CONNECT}" ]]; then
  adb connect "${ADB_CONNECT}" >/dev/null 2>&1 || true
fi

resolve_adb_target() {
  if [[ -n "${ADB_SERIAL}" ]]; then
    printf '%s\n' "${ADB_SERIAL}"
    return 0
  fi

  pixel_transport_require_adb_serial
}

adb_target="$(resolve_adb_target)"
if [[ -z "${adb_target}" ]]; then
  echo "No adb target available. Pass --adb-serial or --adb-connect." >&2
  exit 1
fi

if ! adb -s "${adb_target}" get-state >/dev/null 2>&1; then
  echo "ADB target unreachable: ${adb_target}" >&2
  exit 1
fi

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
root="${OUTPUT_ROOT}/${stamp}"
bundle_dir="${root}/bundle"
repo_payload_dir="${root}/repo-payload"
mkdir -p "${bundle_dir}/state" "${bundle_dir}/tls" "${bundle_dir}/secrets" "${repo_payload_dir}"

adb_su_cat() {
  local remote_path="$1"
  adb -s "${adb_target}" exec-out su -c "cat '${remote_path}'" 2>/dev/null
}

adb_su_cmd() {
  local cmd="$1"
  adb -s "${adb_target}" shell "su -c \"${cmd}\"" 2>/dev/null
}

copy_remote_file() {
  local remote_path="$1"
  local local_path="$2"
  local optional="${3:-0}"
  local tmp_path="${local_path}.tmp"

  if adb_su_cat "${remote_path}" > "${tmp_path}"; then
    mv -f "${tmp_path}" "${local_path}"
    return 0
  fi
  rm -f "${tmp_path}" >/dev/null 2>&1 || true
  if [[ "${optional}" == "1" ]]; then
    return 0
  fi
  echo "Missing required device file: ${remote_path}" >&2
  exit 1
}

copy_repo_file() {
  local src_path="$1"
  local dst_path="$2"
  mkdir -p "$(dirname "${dst_path}")"
  install -m 0644 "${src_path}" "${dst_path}"
}

copy_repo_exec() {
  local src_path="$1"
  local dst_path="$2"
  mkdir -p "$(dirname "${dst_path}")"
  install -m 0755 "${src_path}" "${dst_path}"
}

copy_remote_file "/data/local/pixel-stack/chroots/adguardhome/etc/pixel-stack/remote-dns/runtime.env" "${bundle_dir}/runtime.env"
copy_remote_file "/data/local/pixel-stack/chroots/adguardhome/opt/adguardhome/conf/AdGuardHome.yaml" "${bundle_dir}/AdGuardHome.source.yaml"
copy_remote_file "/data/local/pixel-stack/chroots/adguardhome/etc/pixel-stack/remote-dns/doh-identities.json" "${bundle_dir}/doh-identities.json"
copy_remote_file "/data/local/pixel-stack/chroots/adguardhome/etc/pixel-stack/remote-dns/state/doh-usage-events.jsonl" "${bundle_dir}/state/doh-usage-events.jsonl" 1
copy_remote_file "/data/local/pixel-stack/chroots/adguardhome/etc/pixel-stack/remote-dns/state/doh-usage-cursor.json" "${bundle_dir}/state/doh-usage-cursor.json" 1
copy_remote_file "/data/local/pixel-stack/chroots/adguardhome/etc/pixel-stack/remote-dns/state/identity-observability.sqlite" "${bundle_dir}/state/identity-observability.sqlite" 1
copy_remote_file "/data/local/pixel-stack/chroots/adguardhome/etc/pixel-stack/remote-dns/state/querylog-view-preference.json" "${bundle_dir}/state/querylog-view-preference.json" 1
copy_remote_file "/data/local/pixel-stack/chroots/adguardhome/etc/pixel-stack/remote-dns/state/ipinfo-lite-cache.json" "${bundle_dir}/state/ipinfo-lite-cache.json" 1
copy_remote_file "/data/local/pixel-stack/chroots/adguardhome/etc/pixel-stack/remote-dns/state/ddns-last-ipv4" "${bundle_dir}/state/ddns-last-ipv4" 1
copy_remote_file "/data/local/pixel-stack/chroots/adguardhome/etc/pixel-stack/remote-dns/tls/fullchain.pem" "${bundle_dir}/tls/fullchain.pem"
copy_remote_file "/data/local/pixel-stack/chroots/adguardhome/etc/pixel-stack/remote-dns/tls/privkey.pem" "${bundle_dir}/tls/privkey.pem"
copy_remote_file "/data/local/pixel-stack/conf/adguardhome/remote-admin-password" "${bundle_dir}/secrets/admin-password"
copy_remote_file "/data/local/pixel-stack/conf/ddns/cloudflare-token" "${bundle_dir}/secrets/cloudflare-token"
copy_remote_file "/data/local/pixel-stack/conf/adguardhome/ipinfo-lite-token" "${bundle_dir}/secrets/ipinfo-lite-token" 1

python3 - "${bundle_dir}/runtime.env" <<'PY'
import shlex
import sys
from pathlib import Path

path = Path(sys.argv[1])
entries = {}
order = []
for raw_line in path.read_text(encoding="utf-8").splitlines():
    line = raw_line.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    key, value = line.split("=", 1)
    key = key.strip()
    if not key:
        continue
    try:
        parsed = shlex.split(value, posix=True)
        entries[key] = parsed[0] if parsed else ""
    except ValueError:
        entries[key] = value
    if key not in order:
        order.append(key)

updates = {
    "PIHOLE_REMOTE_HTTPS_PORT": "2789",
    "PIHOLE_REMOTE_DOT_PORT": "2790",
    "PIHOLE_REMOTE_DOT_ENABLED": "1",
    "PIHOLE_REMOTE_DOT_IDENTITY_ENABLED": "1",
    "ADGUARDHOME_REMOTE_DOT_INTERNAL_PORT": "8853",
    "PIHOLE_REMOTE_TLS_CERT_FILE": "/etc/arbuzas/dns/tls/fullchain.pem",
    "PIHOLE_REMOTE_TLS_KEY_FILE": "/etc/arbuzas/dns/tls/privkey.pem",
    "PIHOLE_DDNS_LAST_IPV4_FILE": "/etc/arbuzas/dns/state/ddns-last-ipv4",
    "ADGUARDHOME_DOH_IDENTITIES_FILE": "/etc/arbuzas/dns/doh-identities.json",
    "ADGUARDHOME_DOH_USAGE_EVENTS_FILE": "/etc/arbuzas/dns/state/doh-usage-events.jsonl",
    "ADGUARDHOME_DOH_USAGE_CURSOR_FILE": "/etc/arbuzas/dns/state/doh-usage-cursor.json",
    "ADGUARDHOME_DOH_OBSERVABILITY_DB_FILE": "/etc/arbuzas/dns/state/identity-observability.sqlite",
    "ADGUARDHOME_DOH_ACCESS_LOG_FILE": "/var/log/arbuzas/dns/remote-nginx-doh-access.log",
    "ADGUARDHOME_DOT_ACCESS_LOG_FILE": "/var/log/arbuzas/dns/remote-nginx-dot-access.log",
    "ADGUARDHOME_DOH_IDENTITY_WEB_QUERYLOG_VIEW_PREFERENCE_FILE": "/etc/arbuzas/dns/state/querylog-view-preference.json",
    "ADGUARDHOME_DOH_IDENTITY_WEB_IPINFO_CACHE_FILE": "/etc/arbuzas/dns/state/ipinfo-lite-cache.json",
    "ADGUARDHOME_IPINFO_LITE_TOKEN_FILE": "/etc/arbuzas/dns/secrets/ipinfo-lite-token",
    "ADGUARDHOME_ADMIN_PASSWORD_FILE": "/etc/arbuzas/dns/secrets/admin-password",
    "PIHOLE_REMOTE_CF_TOKEN_SECRET_FILE": "/etc/arbuzas/dns/secrets/cloudflare-token",
}
for key, value in updates.items():
    entries[key] = value
    if key not in order:
        order.append(key)

path.write_text("".join(f"{key}={shlex.quote(entries[key])}\n" for key in order), encoding="utf-8")
PY

copy_repo_exec "${REPO_ROOT}/legacy/pixel-runtime/infra/adguardhome/debian/install-arbuzas-dns-runtime.sh" "${repo_payload_dir}/legacy/pixel-runtime/infra/adguardhome/debian/install-arbuzas-dns-runtime.sh"
copy_repo_exec "${REPO_ROOT}/legacy/pixel-runtime/infra/adguardhome/debian/prepare-arbuzas-adguardhome-config.sh" "${repo_payload_dir}/legacy/pixel-runtime/infra/adguardhome/debian/prepare-arbuzas-adguardhome-config.sh"
copy_repo_exec "${REPO_ROOT}/legacy/pixel-runtime/infra/adguardhome/debian/adguardhome-policy-publisher.py" "${repo_payload_dir}/legacy/pixel-runtime/infra/adguardhome/debian/adguardhome-policy-publisher.py"
copy_repo_exec "${REPO_ROOT}/legacy/pixel-runtime/infra/adguardhome/debian/arbuzas-dns-frontctl.sh" "${repo_payload_dir}/legacy/pixel-runtime/infra/adguardhome/debian/arbuzas-dns-frontctl.sh"
copy_repo_file "${REPO_ROOT}/legacy/pixel-runtime/infra/adguardhome/debian/arbuzas-dns-nginx.conf.template" "${repo_payload_dir}/legacy/pixel-runtime/infra/adguardhome/debian/arbuzas-dns-nginx.conf.template"
copy_repo_file "${REPO_ROOT}/legacy/pixel-runtime/infra/adguardhome/debian/systemd/arbuzas-dns-adguardhome.service" "${repo_payload_dir}/legacy/pixel-runtime/infra/adguardhome/debian/systemd/arbuzas-dns-adguardhome.service"
copy_repo_file "${REPO_ROOT}/legacy/pixel-runtime/infra/adguardhome/debian/systemd/arbuzas-dns-identity-web.service" "${repo_payload_dir}/legacy/pixel-runtime/infra/adguardhome/debian/systemd/arbuzas-dns-identity-web.service"
copy_repo_file "${REPO_ROOT}/legacy/pixel-runtime/infra/adguardhome/debian/systemd/arbuzas-dns-frontend.service" "${repo_payload_dir}/legacy/pixel-runtime/infra/adguardhome/debian/systemd/arbuzas-dns-frontend.service"
copy_repo_exec "${REPO_ROOT}/legacy/pixel-runtime/orchestrator/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-doh-identities.py" "${repo_payload_dir}/legacy/pixel-runtime/orchestrator/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-doh-identities.py"
copy_repo_exec "${REPO_ROOT}/legacy/pixel-runtime/orchestrator/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-doh-identityctl" "${repo_payload_dir}/legacy/pixel-runtime/orchestrator/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-doh-identityctl"
copy_repo_exec "${REPO_ROOT}/legacy/pixel-runtime/orchestrator/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-doh-identity-web.py" "${repo_payload_dir}/legacy/pixel-runtime/orchestrator/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-doh-identity-web.py"

python3 - "${bundle_dir}" "${root}/manifest.json" "${adb_target}" "${ARBUZAS_HOST}" <<'PY'
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

bundle = Path(sys.argv[1])
manifest = Path(sys.argv[2])
payload = {
    "generated_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
    "adb_target": sys.argv[3],
    "arbuzas_host": sys.argv[4],
    "https_port": 2789,
    "dot_port": 2790,
    "bundle_files": sorted(str(path.relative_to(bundle)) for path in bundle.rglob("*") if path.is_file()),
}
manifest.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY

echo "Staged migration bundle: ${bundle_dir}"

if (( DEPLOY_HOST == 0 )); then
  echo "Stage-only mode complete"
  exit 0
fi

require_cmd ssh
require_cmd scp

ssh_target="${ARBUZAS_USER}@${ARBUZAS_HOST}"
remote_stage="/tmp/arbuzas-dns-cutover-${stamp}"

ssh_base=(ssh -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 -p "${ARBUZAS_SSH_PORT}" "${ssh_target}")
scp_base=(scp -o StrictHostKeyChecking=accept-new -P "${ARBUZAS_SSH_PORT}")

"${ssh_base[@]}" "mkdir -p '${remote_stage}' '${remote_stage}/repo-payload' '${remote_stage}/bundle'" >/dev/null
"${scp_base[@]}" -r "${repo_payload_dir}/." "${ssh_target}:${remote_stage}/repo-payload/"
"${scp_base[@]}" -r "${bundle_dir}/." "${ssh_target}:${remote_stage}/bundle/"
"${ssh_base[@]}" "sudo /bin/bash '${remote_stage}/repo-payload/legacy/pixel-runtime/infra/adguardhome/debian/install-arbuzas-dns-runtime.sh' --repo-root '${remote_stage}/repo-payload' --bundle-dir '${remote_stage}/bundle'"

echo "arbuzas deploy complete via ${ssh_target}"
