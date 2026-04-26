#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PACKAGE_SCRIPT="${REPO_ROOT}/scripts/android/package_runtime_bundle.sh"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

if ! rg -Fq -- '--print-inputs' "${PACKAGE_SCRIPT}"; then
  echo "FAIL: package_runtime_bundle.sh missing --print-inputs mode" >&2
  exit 1
fi

for env_name in \
  PIXEL_RUNTIME_ROOTFS_TARBALL \
  PIXEL_RUNTIME_DROPBEAR_ARTIFACT_DIR \
  PIXEL_RUNTIME_TAILSCALE_BUNDLE; do
  if ! rg -Fq "${env_name}" "${PACKAGE_SCRIPT}"; then
    echo "FAIL: package_runtime_bundle.sh missing ${env_name} override" >&2
    exit 1
  fi
done

if rg -Fq 'runtime-local/*/artifacts/adguardhome-rootfs-arm64.tar' "${PACKAGE_SCRIPT}"; then
  echo "FAIL: package_runtime_bundle.sh still auto-discovers rootfs tarballs from runtime-local artifacts" >&2
  exit 1
fi

if ! rg -Fq 'dns-runtime-assets' "${PACKAGE_SCRIPT}"; then
  echo "FAIL: package_runtime_bundle.sh missing dns-runtime-assets bundle output" >&2
  exit 1
fi

for required in 'create_deterministic_tar' 'create_deterministic_tar "${dns_bundle_stage}" "${DNS_RUNTIME_ASSETS_OUT}"' 'create_deterministic_tar "${bundle_stage}" "${DROPBEAR_BUNDLE_OUT}"'; do
  if ! rg -Fq "${required}" "${PACKAGE_SCRIPT}"; then
    echo "FAIL: package_runtime_bundle.sh missing deterministic tar contract fragment ${required}" >&2
    exit 1
  fi
done

help_output="$(bash "${PACKAGE_SCRIPT}" --help)"
for required in '--rootfs-tarball' '--dropbear-artifact-dir' '--tailscale-bundle' '--platform-only' '--print-inputs'; do
  if ! grep -Fq -- "${required}" <<<"${help_output}"; then
    echo "FAIL: package_runtime_bundle.sh --help missing ${required}" >&2
    exit 1
  fi
done

rootfs_file="${TMP_ROOT}/adguardhome-rootfs-arm64.tar"
dropbear_dir="${TMP_ROOT}/dropbear"
tailscale_bundle="${TMP_ROOT}/tailscale-bundle.tar"
printf 'rootfs' > "${rootfs_file}"
mkdir -p "${dropbear_dir}"
printf 'dropbear' > "${dropbear_dir}/dropbearmulti"
chmod +x "${dropbear_dir}/dropbearmulti"
printf 'tailscale' > "${tailscale_bundle}"

print_inputs_output="$(
  PIXEL_RUNTIME_ROOTFS_TARBALL="${rootfs_file}" \
  PIXEL_RUNTIME_DROPBEAR_ARTIFACT_DIR="${dropbear_dir}" \
  PIXEL_RUNTIME_TAILSCALE_BUNDLE="${tailscale_bundle}" \
  bash "${PACKAGE_SCRIPT}" --platform-only --print-inputs
)"

if ! grep -Fq "ROOTFS_TARBALL=${rootfs_file}" <<<"${print_inputs_output}"; then
  echo "FAIL: package_runtime_bundle.sh did not honor explicit rootfs input" >&2
  exit 1
fi

missing_rootfs_log="${TMP_ROOT}/missing-rootfs.log"
set +e
bash "${PACKAGE_SCRIPT}" --platform-only --print-inputs >"${missing_rootfs_log}" 2>&1
missing_rootfs_rc=$?
set -e

if [[ "${missing_rootfs_rc}" == "0" ]]; then
  echo "FAIL: package_runtime_bundle.sh succeeded without an explicit rootfs tarball" >&2
  cat "${missing_rootfs_log}" >&2
  exit 1
fi

if ! grep -Fq 'Missing rootfs tarball' "${missing_rootfs_log}"; then
  echo "FAIL: package_runtime_bundle.sh missing explicit-rootfs failure message" >&2
  cat "${missing_rootfs_log}" >&2
  exit 1
fi

echo "PASS: runtime bundle packaging requires an explicit rootfs input and emits dns runtime assets"
