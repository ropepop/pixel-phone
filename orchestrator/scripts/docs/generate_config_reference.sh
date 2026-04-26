#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
OUTPUT_FILE="${REPO_ROOT}/docs/reference/orchestrator/CONFIG.md"
TEMPLATE_FILE="${REPO_ROOT}/orchestrator/templates/orchestrator/orchestrator-config-v1.example.json"
STACK_CONFIG_FILE="${REPO_ROOT}/orchestrator/android-orchestrator/core-config/src/main/kotlin/lv/jolkins/pixelorchestrator/coreconfig/StackConfigV1.kt"

command -v jq >/dev/null 2>&1 || {
  echo "jq is required for generate_config_reference.sh" >&2
  exit 1
}

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT
TEMPLATE_TSV="${TMP_DIR}/template.tsv"
KOTLIN_TSV="${TMP_DIR}/kotlin.tsv"

append_template_defaults() {
  jq -r '
    paths(scalars) as $p |
    [($p | map(tostring) | join(".")), (getpath($p) | tostring)] | @tsv
  ' "${TEMPLATE_FILE}" | while IFS=$'\t' read -r path value; do
    [[ -n "${value}" ]] || value="(empty)"
    printf '%s\t%s\t%s\n' "${path}" "${value}" "${TEMPLATE_FILE#${REPO_ROOT}/}" >> "${TEMPLATE_TSV}"
  done
}

append_kotlin_defaults() {
  perl -ne '
    if (/data class\s+([A-Za-z0-9_]+)\(/) {
      $class = $1;
      next;
    }
    if ($class && /^\s*val\s+([A-Za-z0-9_]+)\s*:[^=]+=\s*(.+?)\s*,?\s*$/) {
      $key = $1;
      $value = $2;
      $value =~ s/^\s+|\s+$//g;
      $value = "(empty)" if $value eq "";
      print "$class.$key\t$value\t$ARGV\n";
    }
  ' "${STACK_CONFIG_FILE}" > "${KOTLIN_TSV}"
}

write_table() {
  local title="$1"
  local input_tsv="$2"
  printf '## %s\n\n' "${title}" >> "${OUTPUT_FILE}"
  printf '| Key | Default | Source |\n' >> "${OUTPUT_FILE}"
  printf '| --- | --- | --- |\n' >> "${OUTPUT_FILE}"
  LC_ALL=C sort -u "${input_tsv}" | while IFS=$'\t' read -r key value source; do
    [[ -n "${key}" ]] || continue
    [[ -n "${value}" ]] || value="(empty)"
    printf '| `%s` | `%s` | `%s` |\n' "${key}" "${value}" "${source}" >> "${OUTPUT_FILE}"
  done
  printf '\n' >> "${OUTPUT_FILE}"
}

append_template_defaults
append_kotlin_defaults

mkdir -p "$(dirname "${OUTPUT_FILE}")"
cat > "${OUTPUT_FILE}" <<'EOF_MD'
# Orchestrator Config Reference

Generated from source files by `orchestrator/scripts/docs/generate_config_reference.sh`.
EOF_MD

printf '\n' >> "${OUTPUT_FILE}"
write_table "Config Template Defaults" "${TEMPLATE_TSV}"
write_table "Kotlin Model Defaults" "${KOTLIN_TSV}"

echo "Wrote ${OUTPUT_FILE}"
