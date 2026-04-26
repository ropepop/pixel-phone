#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
START_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-start"

render_block="$(sed -n '/^render_adguardhome_config()/,/^}/p' "${START_FILE}")"

if [[ -z "${render_block}" ]]; then
  echo "FAIL: missing render_adguardhome_config() helper in ${START_FILE}" >&2
  exit 1
fi

if printf '%s\n' "${render_block}" | rg -Fq 'cat > "${ADGUARDHOME_CONFIG_FILE}" <<EOF_CFG'; then
  echo "FAIL: render_adguardhome_config still writes directly to the live config path" >&2
  exit 1
fi

if ! printf '%s\n' "${render_block}" | rg -Fq 'mktemp'; then
  echo "FAIL: render_adguardhome_config missing temporary file staging for atomic writes" >&2
  exit 1
fi

if ! printf '%s\n' "${render_block}" | rg -Fq 'mv '; then
  echo "FAIL: render_adguardhome_config missing atomic move into place" >&2
  exit 1
fi

if ! printf '%s\n' "${render_block}" | rg -Fq 'clients:'; then
  echo "FAIL: render_adguardhome_config missing AdGuard persistent clients block" >&2
  exit 1
fi

if ! printf '%s\n' "${render_block}" | rg -Fq 'name: "Loopback internal"'; then
  echo "FAIL: render_adguardhome_config missing loopback suppression client entry" >&2
  exit 1
fi

if ! printf '%s\n' "${render_block}" | rg -Fq 'ignore_querylog: true'; then
  echo "FAIL: render_adguardhome_config missing loopback querylog suppression flag" >&2
  exit 1
fi

if ! printf '%s\n' "${render_block}" | rg -Fq 'ignore_statistics: true'; then
  echo "FAIL: render_adguardhome_config missing loopback statistics suppression flag" >&2
  exit 1
fi

if ! rg -Fq 'render_preserved_adguardhome_dynamic_blocks()' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing preserved dynamic config block helper" >&2
  exit 1
fi

for symbol in \
  'for key in filtering filters whitelist_filters user_rules;' \
  '${preserved_dynamic_blocks}log:' \
  'existing_schema_version()'; do
  if ! rg -Fq "${symbol}" "${START_FILE}"; then
    echo "FAIL: adguardhome-start missing preserved filter-state symbol ${symbol}" >&2
    exit 1
  fi
done

if ! rg -Fq 'restore_last_good_adguardhome_config()' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing last-good config restore helper" >&2
  exit 1
fi

if ! rg -Fq 'backup_last_good_adguardhome_config()' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing last-good config backup helper" >&2
  exit 1
fi

if ! rg -Fq 'adguardhome_entered_setup_mode()' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing setup-mode detection helper" >&2
  exit 1
fi

if ! rg -Fq 'tcp_listener_local "${ADGUARDHOME_SETUP_WEB_PORT}"' "${START_FILE}"; then
  echo "FAIL: setup-mode detection does not probe the AdGuard setup listener port" >&2
  exit 1
fi

if ! rg -Fq 'restore_last_good_adguardhome_config || return 1' "${START_FILE}"; then
  echo "FAIL: startup path does not fail closed when setup-mode recovery cannot restore a last-good config" >&2
  exit 1
fi

if ! rg -Fq 'Rendered AdGuardHome config missing loopback suppression client' "${START_FILE}"; then
  echo "FAIL: validate_rendered_config missing loopback suppression validation guard" >&2
  exit 1
fi

echo "PASS: adguardhome-start protects config writes atomically and detects setup-mode drift with backup restore markers"
