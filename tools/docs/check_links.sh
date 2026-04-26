#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT}"

fail=0
while IFS= read -r -d '' f; do
  while IFS= read -r link; do
    target="${link#*](}"
    target="${target%)}"
    case "${target}" in
      http://*|https://*|mailto:*|\#*)
        continue
        ;;
    esac
    resolved="$(dirname "${f}")/${target}"
    if [ ! -e "${resolved}" ]; then
      echo "broken link: ${f} -> ${target}"
      fail=1
    fi
  done < <(rg -o '\[[^]]+\]\(([^)]+)\)' "${f}" || true)
done < <(find docs -type f -name '*.md' -print0)

if [ "${fail}" -ne 0 ]; then
  exit 1
fi

echo "docs link check passed"
