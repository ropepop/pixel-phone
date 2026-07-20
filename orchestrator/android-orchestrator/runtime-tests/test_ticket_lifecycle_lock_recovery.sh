#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HELPER="${ROOT}/app/src/main/assets/runtime/entrypoints/pixel-ticket-lifecycle-lock.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

# shellcheck source=/dev/null
. "${HELPER}"
LOCK="${TMP}/ticket.lock"
mkdir "${LOCK}"
printf '99999999\n' > "${LOCK}/owner.pid"

ticket_lock_acquire "${LOCK}" 1
[[ "$(<"${LOCK}/owner.pid")" == "$$" ]]
ticket_lock_release
[[ ! -e "${LOCK}" ]]

if rg -Fq 'rm -rf' "${HELPER}"; then
  echo "FAIL: lifecycle lock helper recursively deletes contested state" >&2
  exit 1
fi
rm -rf "${TMP}"
trap - EXIT
echo "PASS: dead lifecycle owner is recovered and only the owned lock is released"
