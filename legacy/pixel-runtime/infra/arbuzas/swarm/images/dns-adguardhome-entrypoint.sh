#!/usr/bin/env bash
set -euo pipefail

SOURCE_CONFIG="${ARBUZAS_DNS_SOURCE_CONFIG_FILE:-/etc/arbuzas/dns/AdGuardHome.source.yaml}"
RENDERED_CONFIG="${ARBUZAS_DNS_RENDERED_CONFIG_FILE:-/opt/adguardhome/conf/AdGuardHome.yaml}"
WORK_DIR="${ARBUZAS_DNS_WORK_DIR:-/opt/adguardhome/work}"

install -d -m 0755 "$(dirname "${RENDERED_CONFIG}")" "${WORK_DIR}" "${WORK_DIR}/data" "${WORK_DIR}/filters"
/usr/local/bin/prepare-arbuzas-adguardhome-config.sh "${SOURCE_CONFIG}" "${RENDERED_CONFIG}"

exec /opt/adguardhome/AdGuardHome \
  --no-check-update \
  -c "${RENDERED_CONFIG}" \
  -w "${WORK_DIR}"

