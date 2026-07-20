#!/system/bin/sh
set -eu

for arg in "$@"; do
  case "${arg}" in
    --deep|--full) ;;
    *)
      echo "unsupported ticket health argument: ${arg}" >&2
      exit 2
      ;;
  esac
done

STACK_ROOT="${PIXEL_STACK_ROOT:-/data/local/pixel-stack}"
CONF_ENV="${STACK_ROOT}/conf/apps/ticket-screen.env"
RUNTIME_ENV="${STACK_ROOT}/apps/ticket-screen/env/ticket-screen.env"

if [ -r "${CONF_ENV}" ]; then
  # shellcheck disable=SC1090
  . "${CONF_ENV}"
fi
if [ -r "${RUNTIME_ENV}" ]; then
  # shellcheck disable=SC1090
  . "${RUNTIME_ENV}"
fi

: "${TICKET_SCREEN_PORT:=9388}"

valid_port() {
  case "${1:-}" in
    ''|*[!0-9]*) return 1 ;;
    *) [ "${1}" -ge 1 ] && [ "${1}" -le 65535 ] ;;
  esac
}

listener_ready() {
  port="$1"
  command -v ss >/dev/null 2>&1 || return 1
  ss -ltn 2>/dev/null | grep -E "[:.]${port}[[:space:]]" >/dev/null 2>&1
}

http_status_ready() {
  host="$1"
  port="$2"
  path="$3"

  valid_port "${port}" || return 1
  if command -v curl >/dev/null 2>&1; then
    [ "$(curl -sS -o /dev/null -w '%{http_code}' --connect-timeout 1 --max-time 1 "http://${host}:${port}${path}" 2>/dev/null || true)" = "200" ]
    return $?
  fi
  if command -v nc >/dev/null 2>&1; then
    if command -v timeout >/dev/null 2>&1; then
      first_line="$(printf 'GET %s HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n' "${path}" "${host}" | timeout 2 nc -w 1 "${host}" "${port}" 2>/dev/null | sed -n '1p' | tr -d '\r' || true)"
    else
      first_line="$(printf 'GET %s HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n' "${path}" "${host}" | nc -w 1 "${host}" "${port}" 2>/dev/null | sed -n '1p' | tr -d '\r' || true)"
    fi
    case "${first_line}" in
      HTTP/*' 200 '*) return 0 ;;
      *) return 1 ;;
    esac
  fi
  listener_ready "${port}"
}

http_status_ready 127.0.0.1 "${TICKET_SCREEN_PORT}" /api/v1/health
