#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 3 ]; then
  echo "Usage: $0 <module_id> <domain_dir> <component_id_csv>" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
module_id="$1"
domain_dir="$2"
component_csv="$3"
module_dir="${ROOT}/${domain_dir}/${module_id}"

mkdir -p "${module_dir}" "${ROOT}/ops/evidence/${module_id}" "${ROOT}/docs/runbooks"

manifest="${module_dir}/module.yaml"
cat > "${manifest}" <<MAN
schema: 1
module_id: ${module_id}
owner: ""
runtime:
  type: "service"
  healthcheck: ""
components:
MAN
IFS=',' read -r -a comps <<< "${component_csv}"
for c in "${comps[@]}"; do
  c_trim="$(echo "$c" | xargs)"
  [ -z "${c_trim}" ] && continue
  cat >> "${manifest}" <<CMP
  - id: ${c_trim}
    start: ""
    stop: ""
    health: ""
CMP
done

cat > "${module_dir}/README.md" <<README
# ${module_id}

Module scaffold for ${module_id}.
README

cat > "${ROOT}/docs/runbooks/MODULE_${module_id^^}.md" <<RUNBOOK
# ${module_id} Module Runbook

- Canonical operations: [ROOT_OPERATIONS](./ROOT_OPERATIONS.md)
- Module manifest: [${module_id} manifest](../../${domain_dir}/${module_id}/module.yaml)
RUNBOOK

echo "created scaffold for ${module_id}: ${module_dir}"
