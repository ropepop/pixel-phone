#!/usr/bin/env bash
set -euo pipefail

CONF_FILE="${ARBUZAS_DNS_NGINX_CONF_FILE:-/var/lib/arbuzas/dns/runtime/arbuzas-dns-nginx.conf}"
NGINX_PREFIX="${ARBUZAS_DNS_NGINX_PREFIX:-/usr/share/nginx}"
RENDER_INTERVAL_SECONDS="${ARBUZAS_DNS_RENDER_INTERVAL_SECONDS:-20}"

render_config() {
  /usr/local/bin/arbuzas-dns-frontctl.sh render
}

reload_frontend() {
  nginx -c "${CONF_FILE}" -p "${NGINX_PREFIX}" -s reload >/dev/null 2>&1 || true
}

render_config
nginx -c "${CONF_FILE}" -p "${NGINX_PREFIX}" &
nginx_pid=$!

cleanup() {
  if kill -0 "${nginx_pid}" >/dev/null 2>&1; then
    nginx -c "${CONF_FILE}" -p "${NGINX_PREFIX}" -s quit >/dev/null 2>&1 || kill "${nginx_pid}" >/dev/null 2>&1 || true
  fi
  wait "${nginx_pid}" 2>/dev/null || true
}

trap cleanup INT TERM

while kill -0 "${nginx_pid}" >/dev/null 2>&1; do
  sleep "${RENDER_INTERVAL_SECONDS}"
  if ! kill -0 "${nginx_pid}" >/dev/null 2>&1; then
    break
  fi
  if render_config; then
    reload_frontend
  fi
done

wait "${nginx_pid}"

