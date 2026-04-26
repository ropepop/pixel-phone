#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
BUNDLE_DIR=""
STATE_ONLY=0
START_SERVICES=1

usage() {
  cat <<'EOF'
Usage: install-arbuzas-dns-runtime.sh [options]

Install the arbuzas DNS runtime assets and optional migrated state bundle onto a Debian host.

Options:
  --repo-root PATH    Repo root (default: inferred from this script)
  --bundle-dir PATH   Optional bundle directory created by cutover_dns_to_arbuzas.sh
  --state-only        Refresh migrated state only; leave installed assets untouched
  --no-start          Install/update files but do not restart services
  -h, --help          Show this help text
EOF
}

while (( $# > 0 )); do
  case "$1" in
    --repo-root) shift; REPO_ROOT="${1:-}" ;;
    --bundle-dir) shift; BUNDLE_DIR="${1:-}" ;;
    --state-only) STATE_ONLY=1 ;;
    --no-start) START_SERVICES=0 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root." >&2
  exit 1
fi

install_root="/etc/arbuzas/dns"
state_root="${install_root}/state"
tls_root="${install_root}/tls"
secrets_root="${install_root}/secrets"
log_root="/var/log/arbuzas/dns"
runtime_root="/run/arbuzas/dns"
ADGUARDHOME_RELEASE_URL="${ADGUARDHOME_RELEASE_URL:-https://static.adguard.com/adguardhome/release/AdGuardHome_linux_amd64.tar.gz}"
APT_UPDATED=0

copy_bundle_file() {
  local src_name="$1"
  local dst_path="$2"
  local optional="${3:-0}"
  local src_path="${BUNDLE_DIR}/${src_name}"

  if [[ -r "${src_path}" ]]; then
    install -D -m 0600 "${src_path}" "${dst_path}"
    return 0
  fi
  if [[ "${optional}" == "1" ]]; then
    return 0
  fi
  echo "Missing required bundle file: ${src_path}" >&2
  return 1
}

apt_install_if_missing() {
  local package_name="$1"
  local probe_cmd="$2"
  if eval "${probe_cmd}" >/dev/null 2>&1; then
    return 0
  fi
  if (( APT_UPDATED == 0 )); then
    apt-get update
    APT_UPDATED=1
  fi
  DEBIAN_FRONTEND=noninteractive apt-get install -y "${package_name}"
}

ensure_host_dependencies() {
  apt_install_if_missing ca-certificates "dpkg -s ca-certificates"
  apt_install_if_missing curl "command -v curl"
  apt_install_if_missing git "command -v git"
  apt_install_if_missing openssh-client "command -v ssh"
  apt_install_if_missing python3 "command -v python3"
  apt_install_if_missing python3-yaml "python3 -c 'import yaml'"
  apt_install_if_missing tar "command -v tar"
  apt_install_if_missing nginx-full "command -v nginx"
  apt_install_if_missing libnginx-mod-stream "test -e /usr/lib/nginx/modules/ngx_stream_module.so || test -e /usr/share/nginx/modules-available/mod-stream.conf"
}

ensure_adguardhome_binary() {
  if [[ -x /opt/adguardhome/AdGuardHome ]]; then
    return 0
  fi
  local tmpdir archive
  tmpdir="$(mktemp -d)"
  archive="${tmpdir}/AdGuardHome_linux_amd64.tar.gz"
  trap 'rm -rf "${tmpdir}"' RETURN
  curl -fsSL "${ADGUARDHOME_RELEASE_URL}" -o "${archive}"
  tar -xzf "${archive}" -C "${tmpdir}"
  install -d -m 0755 /opt/adguardhome
  install -m 0755 "${tmpdir}/AdGuardHome/AdGuardHome" /opt/adguardhome/AdGuardHome
  rm -rf "${tmpdir}"
  trap - RETURN
}

if (( STATE_ONLY == 0 )); then
  ensure_host_dependencies
  ensure_adguardhome_binary
  install -d -m 0755 /etc/arbuzas /var/log/arbuzas /run/arbuzas
  install -d -m 0755 "${install_root}" "${state_root}" "${tls_root}" "${secrets_root}" "${log_root}" "${runtime_root}"
  install -d -m 0755 /opt/adguardhome/conf /opt/adguardhome/work /opt/adguardhome/work/data /opt/adguardhome/work/filters

  install -m 0755 "${REPO_ROOT}/legacy/pixel-runtime/infra/adguardhome/debian/prepare-arbuzas-adguardhome-config.sh" /usr/local/bin/prepare-arbuzas-adguardhome-config.sh
  install -m 0755 "${REPO_ROOT}/legacy/pixel-runtime/infra/adguardhome/debian/adguardhome-policy-publisher.py" /usr/local/bin/adguardhome-policy-publisher.py
  install -m 0755 "${REPO_ROOT}/legacy/pixel-runtime/infra/adguardhome/debian/arbuzas-dns-frontctl.sh" /usr/local/bin/arbuzas-dns-frontctl.sh
  install -m 0755 "${REPO_ROOT}/legacy/pixel-runtime/orchestrator/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-doh-identityctl" /usr/local/bin/adguardhome-doh-identityctl
  install -m 0755 "${REPO_ROOT}/legacy/pixel-runtime/orchestrator/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-doh-identities.py" /usr/local/bin/adguardhome-doh-identities.py
  install -m 0755 "${REPO_ROOT}/legacy/pixel-runtime/orchestrator/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-doh-identity-web.py" /usr/local/bin/adguardhome-doh-identity-web.py
  install -m 0644 "${REPO_ROOT}/legacy/pixel-runtime/infra/adguardhome/debian/arbuzas-dns-nginx.conf.template" "${install_root}/arbuzas-dns-nginx.conf.template"

  install -m 0644 "${REPO_ROOT}/legacy/pixel-runtime/infra/adguardhome/debian/systemd/arbuzas-dns-adguardhome.service" /etc/systemd/system/arbuzas-dns-adguardhome.service
  install -m 0644 "${REPO_ROOT}/legacy/pixel-runtime/infra/adguardhome/debian/systemd/arbuzas-dns-identity-web.service" /etc/systemd/system/arbuzas-dns-identity-web.service
  install -m 0644 "${REPO_ROOT}/legacy/pixel-runtime/infra/adguardhome/debian/systemd/arbuzas-dns-frontend.service" /etc/systemd/system/arbuzas-dns-frontend.service

  ln -sfn /usr/local/bin/arbuzas-dns-frontctl.sh /usr/local/bin/pixel-dns-frontctl.sh
  install -d -m 0755 /etc/pixel-stack
  ln -sfn "${install_root}" /etc/pixel-stack/remote-dns
  if [[ ! -e /var/log/adguardhome ]]; then
    ln -sfn "${log_root}" /var/log/adguardhome
  fi

  systemctl daemon-reload
  systemctl disable --now cloudflared-dns.service >/dev/null 2>&1 || true
fi

if [[ -n "${BUNDLE_DIR}" ]]; then
  copy_bundle_file runtime.env "${install_root}/runtime.env"
  copy_bundle_file AdGuardHome.source.yaml "${install_root}/AdGuardHome.source.yaml"
  copy_bundle_file state/identity-observability.sqlite "${state_root}/identity-observability.sqlite" 1
  copy_bundle_file state/ddns-last-ipv4 "${state_root}/ddns-last-ipv4" 1
  copy_bundle_file tls/fullchain.pem "${tls_root}/fullchain.pem"
  copy_bundle_file tls/privkey.pem "${tls_root}/privkey.pem"
  copy_bundle_file secrets/admin-password "${secrets_root}/admin-password"
  copy_bundle_file secrets/cloudflare-token "${secrets_root}/cloudflare-token"
  copy_bundle_file secrets/ipinfo-lite-token "${secrets_root}/ipinfo-lite-token" 1
fi

rm -f \
  "${install_root}/doh-identities.json" \
  "${state_root}/doh-usage-events.jsonl" \
  "${state_root}/doh-usage-cursor.json" \
  "${state_root}/querylog-view-preference.json" \
  "${state_root}/ipinfo-lite-cache.json"

chmod 600 "${install_root}/runtime.env" "${install_root}/AdGuardHome.source.yaml" 2>/dev/null || true
chmod 600 "${tls_root}/fullchain.pem" "${tls_root}/privkey.pem" "${secrets_root}/admin-password" "${secrets_root}/cloudflare-token" "${secrets_root}/ipinfo-lite-token" 2>/dev/null || true

if (( START_SERVICES == 1 )); then
  systemctl enable arbuzas-dns-adguardhome.service arbuzas-dns-identity-web.service arbuzas-dns-frontend.service >/dev/null
  systemctl restart arbuzas-dns-adguardhome.service arbuzas-dns-identity-web.service arbuzas-dns-frontend.service
  systemctl is-active --quiet arbuzas-dns-adguardhome.service arbuzas-dns-identity-web.service arbuzas-dns-frontend.service
fi

echo "arbuzas DNS runtime install complete"
