#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OUTPUT_FILE="${REPO_ROOT}/docs/reference/COMMANDS.md"

cd "${REPO_ROOT}"

collect_files() {
  local dir
  for dir in scripts/android scripts/ops scripts/docs; do
    [[ -d "${dir}" ]] || continue
    find "${dir}" -maxdepth 2 -mindepth 1 -type f | sort
  done
}

extract_summary() {
  local file="$1"
  awk '
    NR==1 && /^#!/ { next }
    NR <= 40 && /^[[:space:]]*#/ {
      line = $0
      sub(/^[[:space:]]*#[[:space:]]?/, "", line)
      if (line != "" && line !~ /^shellcheck/) {
        print line
        exit
      }
    }
  ' "${file}" || true
}

extract_usage_lines() {
  local file="$1"
  rg --no-line-number --no-filename 'Usage:[[:space:]].*' "${file}" 2>/dev/null | sed 's/^[[:space:]]*//' | head -n 8 || true
}

extract_long_options() {
  local file="$1"
  rg --no-line-number --no-filename --only-matching -- '--[a-zA-Z0-9][a-zA-Z0-9-]*' "${file}" 2>/dev/null | sort -u || true
}

write_group() {
  local group_name="$1"
  shift
  local -a group_files=("$@")

  printf '## %s\n\n' "${group_name}" >> "${OUTPUT_FILE}"

  local file rel usage_lines summary
  for file in "${group_files[@]}"; do
    rel="${file#./}"
    summary="$(extract_summary "${file}")"
    usage_lines="$(extract_usage_lines "${file}")"

    printf '### `%s`\n\n' "${rel}" >> "${OUTPUT_FILE}"
    if [[ -n "${summary}" ]]; then
      printf '%s\n\n' "${summary}" >> "${OUTPUT_FILE}"
    fi

    printf '**Usage snippets**\n\n' >> "${OUTPUT_FILE}"
    if [[ -n "${usage_lines}" ]]; then
      printf '```text\n%s\n```\n\n' "${usage_lines}" >> "${OUTPUT_FILE}"
    else
      printf '_No `Usage:` lines detected in source._\n\n' >> "${OUTPUT_FILE}"
    fi

    printf '**Long options found in source**\n\n' >> "${OUTPUT_FILE}"
    local opts opt
    opts="$(extract_long_options "${file}")"
    if [[ -n "${opts}" ]]; then
      while IFS= read -r opt; do
        [[ -n "${opt}" ]] || continue
        printf -- '- `%s`\n' "${opt}" >> "${OUTPUT_FILE}"
      done <<EOF_OPTS
${opts}
EOF_OPTS
    else
      printf -- '- _(none detected)_\n' >> "${OUTPUT_FILE}"
    fi
    printf '\n' >> "${OUTPUT_FILE}"
  done
}

mkdir -p "$(dirname "${OUTPUT_FILE}")"
all_files="$(collect_files)"

declare -a android_files
declare -a ops_files
declare -a docs_files

while IFS= read -r file; do
  [[ -n "${file}" ]] || continue
  case "${file}" in
    scripts/android/*) android_files+=("${file}") ;;
    scripts/ops/*) ops_files+=("${file}") ;;
    scripts/docs/*) docs_files+=("${file}") ;;
  esac
done <<EOF_FILES
${all_files}
EOF_FILES

cat > "${OUTPUT_FILE}" <<'EOF_MD'
# Command Reference

Generated from source files by `scripts/docs/generate_command_reference.sh`.
EOF_MD

printf '\n' >> "${OUTPUT_FILE}"
write_group "Android Deployment Scripts (scripts/android)" "${android_files[@]}"
write_group "Operations Scripts (scripts/ops)" "${ops_files[@]}"
write_group "Documentation Scripts (scripts/docs)" "${docs_files[@]}"

echo "Wrote ${OUTPUT_FILE}"
