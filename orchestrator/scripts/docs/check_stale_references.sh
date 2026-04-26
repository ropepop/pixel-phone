#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

cd "${REPO_ROOT}"

errors=0

declare -a ACTIVE_DOCS
while IFS= read -r file; do
  ACTIVE_DOCS+=("${file}")
done < <(find docs -type f -name '*.md' ! -path 'docs/archive/*' | sort)
ACTIVE_DOCS+=("README.md")

report_error() {
  printf 'ERROR: %s\n' "$*" >&2
  errors=$((errors + 1))
}

check_removed_script_references() {
  local pattern='scripts/deploy_to_termux_home\.sh|scripts/ops/setup_root_dropbear_ssh\.sh'
  local matches
  matches="$(rg -n --color never -e "${pattern}" "${ACTIVE_DOCS[@]}" || true)"
  if [[ -n "${matches}" ]]; then
    report_error 'active docs still reference removed scripts:'
    printf '%s\n' "${matches}" >&2
  fi
}

check_forbidden_termux_references() {
  local pattern='termux-related|/data/data/com\.termux|com\.termux|Termux app context|Termux:Boot|termux-home|~/pixel-stack/bin'
  local matches
  matches="$(rg -n --color never -e "${pattern}" "${ACTIVE_DOCS[@]}" || true)"
  if [[ -n "${matches}" ]]; then
    report_error 'active docs contain forbidden Termux references:'
    printf '%s\n' "${matches}" >&2
  fi
}

check_script_references() {
  local line file_line line_no token resolved found
  while IFS= read -r line; do
    [[ -n "${line}" ]] || continue
    file_line="${line%%:*}"
    line_no="${line#*:}"
    line_no="${line_no%%:*}"
    token="${line##*:}"

    case "${token}" in
      scripts/*.sh)
        found=0
        for resolved in \
          "${REPO_ROOT}/${token}" \
          "${REPO_ROOT}/orchestrator/${token}" \
          "${REPO_ROOT}/automation/task-executor/${token}"; do
          if [[ -f "${resolved}" ]]; then
            found=1
            break
          fi
        done
        [[ "${found}" == "1" ]] || report_error "${file_line}:${line_no}: missing file for reference ${token}"
        ;;
    esac
  done < <(rg -n -o --color never 'scripts/[A-Za-z0-9._/-]+\.sh' "${ACTIVE_DOCS[@]}" || true)
}

check_local_markdown_links() {
  local file line target dir resolved
  while IFS=$'\t' read -r file line target; do
    [[ -n "${file}" ]] || continue
    target="${target#<}"; target="${target%>}"; target="${target%% *}"; target="${target%%#*}"

    if [[ -z "${target}" || "${target}" == http://* || "${target}" == https://* || "${target}" == mailto:* || "${target}" == tel:* ]]; then
      continue
    fi

    if [[ "${target}" == /* ]]; then
      [[ -e "${target}" ]] || report_error "${file}:${line}: broken absolute link target ${target}"
      continue
    fi

    dir="$(cd "$(dirname "${file}")" && pwd)"
    resolved="${dir}/${target}"
    [[ -e "${resolved}" ]] || report_error "${file}:${line}: broken relative link target ${target}"
  done < <(perl -ne 'while (/\[[^\]]+\]\(([^)]+)\)/g) { print "$ARGV\t$.\t$1\n"; }' "${ACTIVE_DOCS[@]}")
}

check_removed_script_references
check_forbidden_termux_references
check_script_references
check_local_markdown_links

if (( errors > 0 )); then
  printf 'FAILED: %s documentation reference issue(s) detected.\n' "${errors}" >&2
  exit 1
fi

printf 'OK: no forbidden Termux references, removed-script references, missing script paths, or broken local markdown links in active docs.\n'
