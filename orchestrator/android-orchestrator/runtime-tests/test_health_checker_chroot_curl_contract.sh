#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HEALTH_FILE="${ROOT}/health/src/main/kotlin/lv/jolkins/pixelorchestrator/health/RuntimeHealthChecker.kt"

if ! rg -Fq 'rootfs_path/usr/bin/env' "${HEALTH_FILE}" || ! rg -Fq '/usr/bin/curl -V' "${HEALTH_FILE}"; then
  echo "FAIL: RuntimeHealthChecker does not validate chroot curl through /usr/bin/env with a clean PATH" >&2
  exit 1
fi

if ! rg -Fq 'probe_rootfs" /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /usr/bin/curl -ksS -o /dev/null -w' "${HEALTH_FILE}"; then
  echo "FAIL: RuntimeHealthChecker does not execute chroot curl probes through /usr/bin/env with a clean PATH" >&2
  exit 1
fi

echo "PASS: RuntimeHealthChecker supports chroot curl fallback for remote probes"
