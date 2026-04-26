#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PACKAGE_SCRIPT="${REPO_ROOT}/scripts/android/package_dns_component_release.sh"
REDEPLOY_SCRIPT="${REPO_ROOT}/scripts/android/pixel_redeploy.sh"

if [[ ! -x "${PACKAGE_SCRIPT}" ]]; then
  echo "FAIL: missing dns component release packager ${PACKAGE_SCRIPT}" >&2
  exit 1
fi

for required in '--rootfs-tarball' 'dns-runtime-assets' '"componentId": "dns"'; do
  if ! rg -Fq -- "${required}" "${PACKAGE_SCRIPT}"; then
    echo "FAIL: dns component release packager missing ${required}" >&2
    exit 1
  fi
done

for required in 'create_deterministic_tar' 'create_deterministic_tar "${dns_bundle_stage}" "${DNS_RUNTIME_ASSETS_OUT}"'; do
  if ! rg -Fq "${required}" "${PACKAGE_SCRIPT}"; then
    echo "FAIL: dns component release packager missing deterministic tar contract fragment ${required}" >&2
    exit 1
  fi
done

if ! rg -Fq 'PACKAGE_DNS_COMPONENT_RELEASE_SCRIPT' "${REDEPLOY_SCRIPT}"; then
  echo "FAIL: pixel_redeploy.sh missing dedicated dns component release packager hook" >&2
  exit 1
fi

if ! rg -Fq 'package_dns_release' "${REDEPLOY_SCRIPT}"; then
  echo "FAIL: pixel_redeploy.sh missing dns release packaging path" >&2
  exit 1
fi

echo "PASS: dns component release packaging contract is present"
