#!/system/bin/sh
set -eu

PORT="${TICKET_SCREEN_PORT:-9388}"

if command -v ss >/dev/null 2>&1; then
  ss -ltn 2>/dev/null | grep -E "127[.]0[.]0[.]1:${PORT}[[:space:]]" >/dev/null && exit 0
  ss -ltn 2>/dev/null | grep -E "[:.]${PORT}[[:space:]]" >/dev/null && exit 0
fi

if command -v curl >/dev/null 2>&1; then
  curl -fsS --max-time 3 "http://127.0.0.1:${PORT}/api/v1/health" >/dev/null
  exit $?
fi

exit 1
