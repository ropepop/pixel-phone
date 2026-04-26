#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
# shellcheck source=../../../tools/pixel/transport.sh
source "${REPO_ROOT}/tools/pixel/transport.sh"

ADB_SERIAL="${ADB_SERIAL:-}"
RESTART_DNS=1
EXPECTED_CIDR="0.0.0.0/0"
EXPECTED_USERNAME="pihole"
EXPECTED_PASSWORD_FILE="/data/local/pixel-stack/conf/adguardhome/remote-admin-password"

usage() {
  cat <<'EOH'
Usage: enforce_remote_admin_contract.sh [options]

Enforces rooted AdGuard Home remote admin contract on device:
- ADGUARDHOME_REMOTE_ADMIN_ENABLED=1
- ADGUARDHOME_ADMIN_USERNAME (default: pihole)
- ADGUARDHOME_ADMIN_ALLOW_CIDRS=0.0.0.0/0 (default)
- ADGUARDHOME_ADMIN_PASSWORD_FILE present and synced into chroot secrets

Options:
  --adb-serial SERIAL   adb serial/device (default: first "device")
  --cidr CIDR           value for ADGUARDHOME_ADMIN_ALLOW_CIDRS (default: 0.0.0.0/0)
  --username USER       value for ADGUARDHOME_ADMIN_USERNAME (default: pihole)
  --password-file PATH  host-side password file path (default: /data/local/pixel-stack/conf/adguardhome/remote-admin-password)
  --no-restart          do not restart DNS runtime after env update
  -h, --help            show this help
EOH
}

while (( $# > 0 )); do
  case "$1" in
    --adb-serial)
      shift
      ADB_SERIAL="${1:-}"
      ;;
    --cidr)
      shift
      EXPECTED_CIDR="${1:-}"
      ;;
    --username)
      shift
      EXPECTED_USERNAME="${1:-}"
      ;;
    --password-file)
      shift
      EXPECTED_PASSWORD_FILE="${1:-}"
      ;;
    --no-restart)
      RESTART_DNS=0
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

command -v adb >/dev/null 2>&1 || {
  echo "adb is required" >&2
  exit 1
}

if [[ -z "${ADB_SERIAL}" ]]; then
  ADB_SERIAL="$(pixel_transport_require_adb_serial)"
fi
[[ -n "${ADB_SERIAL}" ]] || {
  echo "No adb device in 'device' state found. Use --adb-serial." >&2
  exit 1
}

echo "Using adb serial: ${ADB_SERIAL}"

quote_for_single_quoted_shell_arg() {
  printf "%s" "$1" | sed "s/'/'\"'\"'/g"
}

remote_script="$(cat <<'EOSH'
set -eu
ENV=/data/local/pixel-stack/conf/adguardhome.env
ROOTFS=/data/local/pixel-stack/chroots/adguardhome
CIDR_PLACEHOLDER="$1"
USERNAME_PLACEHOLDER="$2"
HOST_PASSWORD_FILE="$3"
RESTART_PLACEHOLDER="$4"

set_kv() {
  key="$1"; val="$2"
  if grep -q "^${key}=" "$ENV"; then
    sed -i "s|^${key}=.*|${key}=${val}|" "$ENV"
  else
    printf '%s=%s\n' "$key" "$val" >>"$ENV"
  fi
}

mkdir -p /data/local/pixel-stack/conf/adguardhome \
  "${ROOTFS}/etc/pixel-stack/remote-dns/secrets" \
  /data/local/pixel-stack/run

if [ ! -s "${HOST_PASSWORD_FILE}" ]; then
  tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24 > "${HOST_PASSWORD_FILE}"
fi
chmod 600 "${HOST_PASSWORD_FILE}"

cp "${HOST_PASSWORD_FILE}" "${ROOTFS}/etc/pixel-stack/remote-dns/secrets/admin-password"
chmod 600 "${ROOTFS}/etc/pixel-stack/remote-dns/secrets/admin-password"

set_kv ADGUARDHOME_REMOTE_ADMIN_ENABLED 1
set_kv ADGUARDHOME_ADMIN_USERNAME "${USERNAME_PLACEHOLDER}"
set_kv ADGUARDHOME_ADMIN_ALLOW_CIDRS "${CIDR_PLACEHOLDER}"
set_kv ADGUARDHOME_ADMIN_PASSWORD_FILE "${HOST_PASSWORD_FILE}"

echo "Applied env values:"
grep -E '^(ADGUARDHOME_REMOTE_ADMIN_ENABLED|ADGUARDHOME_ADMIN_USERNAME|ADGUARDHOME_ADMIN_ALLOW_CIDRS|ADGUARDHOME_ADMIN_PASSWORD_FILE)=' "$ENV" || true
wc -c "${HOST_PASSWORD_FILE}" "${ROOTFS}/etc/pixel-stack/remote-dns/secrets/admin-password" | sed -n '1,3p'

if [ "${RESTART_PLACEHOLDER}" = "1" ]; then
  echo "Restarting rooted DNS runtime..."
  sh /data/local/pixel-stack/bin/pixel-dns-stop.sh || true
  rm -f /data/local/pixel-stack/run/adguardhome-service-loop.pid /data/local/pixel-stack/run/adguardhome-host.pid || true
  rm -rf /data/local/pixel-stack/run/adguardhome-service-loop.lock || true
  sh /data/local/pixel-stack/bin/pixel-dns-start.sh
fi
EOSH
)"

quoted_cidr="$(quote_for_single_quoted_shell_arg "${EXPECTED_CIDR}")"
quoted_username="$(quote_for_single_quoted_shell_arg "${EXPECTED_USERNAME}")"
quoted_password_file="$(quote_for_single_quoted_shell_arg "${EXPECTED_PASSWORD_FILE}")"
quoted_restart="$(quote_for_single_quoted_shell_arg "${RESTART_DNS}")"

printf '%s\n' "${remote_script}" | adb -s "${ADB_SERIAL}" shell su -c "sh -s -- '${quoted_cidr}' '${quoted_username}' '${quoted_password_file}' '${quoted_restart}'"
