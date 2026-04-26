#!/usr/bin/env bash
set -euo pipefail

ADB_SERIAL=""

usage() {
  cat <<'EOF_HELP'
Usage: doh-identity-control.sh [--adb-serial SERIAL] -- <identityctl args>

Examples:
  doh-identity-control.sh -- list
  doh-identity-control.sh -- create --id iphone
  doh-identity-control.sh -- usage --window 7d --json
EOF_HELP
}

while (( $# > 0 )); do
  case "$1" in
    --adb-serial)
      shift
      ADB_SERIAL="${1:-}"
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      break
      ;;
    *)
      break
      ;;
  esac
  shift
done

if (( $# == 0 )); then
  echo "Missing identityctl arguments. Pass args after --." >&2
  usage >&2
  exit 2
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found" >&2
  exit 1
fi

adb_cmd=(adb)
if [[ -n "${ADB_SERIAL}" ]]; then
  adb_cmd+=( -s "${ADB_SERIAL}" )
fi

escape_for_single_quote() {
  printf "%s" "$1" | sed "s/'/'\"'\"'/g"
}

remote_cmd="/data/local/pixel-stack/bin/pixel-dns-identityctl"
for arg in "$@"; do
  remote_cmd="${remote_cmd} '$(escape_for_single_quote "${arg}")'"
done

"${adb_cmd[@]}" shell "su -c \"${remote_cmd}\""
