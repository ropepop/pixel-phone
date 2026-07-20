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
  PIXEL_RUNTIME_DROPBEAR_ARTIFACT_DIR \
  PIXEL_RUNTIME_TAILSCALE_BUNDLE; do
  if ! rg -Fq "${env_name}" "${PACKAGE_SCRIPT}"; then
    echo "FAIL: package_runtime_bundle.sh missing ${env_name} override" >&2
    exit 1
  fi
done

if rg -q 'adguardhome-rootfs|dns-runtime-assets|PIXEL_RUNTIME_ROOTFS_TARBALL' "${PACKAGE_SCRIPT}"; then
  echo "FAIL: general runtime packaging still includes retired DNS artifacts" >&2
  exit 1
fi

for required in 'create_deterministic_tar' 'create_deterministic_tar "${bundle_stage}" "${DROPBEAR_BUNDLE_OUT}"'; do
  if ! rg -Fq "${required}" "${PACKAGE_SCRIPT}"; then
    echo "FAIL: package_runtime_bundle.sh missing deterministic tar contract fragment ${required}" >&2
    exit 1
  fi
done

help_output="$(bash "${PACKAGE_SCRIPT}" --help)"
for required in '--dropbear-artifact-dir' '--tailscale-bundle' '--platform-only' '--print-inputs'; do
  if ! grep -Fq -- "${required}" <<<"${help_output}"; then
    echo "FAIL: package_runtime_bundle.sh --help missing ${required}" >&2
    exit 1
  fi
done

dropbear_dir="${TMP_ROOT}/dropbear"
tailscale_bundle="${TMP_ROOT}/tailscale-bundle.tar"
train_bundle="${TMP_ROOT}/train-bot-bundle.tar"
satiksme_bundle="${TMP_ROOT}/satiksme-bot-bundle.tar"
site_notifier_bundle="${TMP_ROOT}/site-notifier-bundle.tar"
subscription_bundle="${TMP_ROOT}/subscription-bot-bundle.tar"
mkdir -p "${dropbear_dir}"
printf 'dropbear' > "${dropbear_dir}/dropbearmulti"
chmod +x "${dropbear_dir}/dropbearmulti"
printf 'tailscale' > "${tailscale_bundle}"
printf 'train' > "${train_bundle}"
printf 'satiksme' > "${satiksme_bundle}"
printf 'site' > "${site_notifier_bundle}"
printf 'subscription' > "${subscription_bundle}"

print_inputs_output="$(
  PIXEL_RUNTIME_DROPBEAR_ARTIFACT_DIR="${dropbear_dir}" \
  PIXEL_RUNTIME_TAILSCALE_BUNDLE="${tailscale_bundle}" \
  bash "${PACKAGE_SCRIPT}" --platform-only --print-inputs
)"

if ! grep -Fq "DROPBEAR_ARTIFACT_DIR=${dropbear_dir}" <<<"${print_inputs_output}"; then
  echo "FAIL: package_runtime_bundle.sh did not honor explicit dropbear input" >&2
  exit 1
fi

fast_selected_output="$(
  env -u PIXEL_RUNTIME_TRAIN_BOT_BUNDLE \
    -u PIXEL_RUNTIME_SATIKSME_BOT_BUNDLE \
    -u PIXEL_RUNTIME_SITE_NOTIFIER_BUNDLE \
    -u PIXEL_RUNTIME_SUBSCRIPTION_BOT_BUNDLE \
    PIXEL_RUNTIME_DROPBEAR_ARTIFACT_DIR="${dropbear_dir}" \
    PIXEL_RUNTIME_TAILSCALE_BUNDLE="${tailscale_bundle}" \
    bash "${PACKAGE_SCRIPT}" --train-bot-bundle "${train_bundle}" --print-inputs
)"

if ! grep -Fq "TRAIN_BOT_BUNDLE=${train_bundle}" <<<"${fast_selected_output}"; then
  echo "FAIL: fast packaging did not retain the explicitly selected workload" >&2
  exit 1
fi
for empty_input in SATIKSME_BOT_BUNDLE SITE_NOTIFIER_BUNDLE SUBSCRIPTION_BOT_BUNDLE; do
  if ! grep -Fxq "${empty_input}=" <<<"${fast_selected_output}"; then
    echo "FAIL: fast packaging should not resolve unselected ${empty_input}" >&2
    exit 1
  fi
done

fast_bundle_dir="${TMP_ROOT}/fast-bundle"
PIXEL_RUNTIME_DROPBEAR_ARTIFACT_DIR="${dropbear_dir}" \
  PIXEL_RUNTIME_TAILSCALE_BUNDLE="${tailscale_bundle}" \
  bash "${PACKAGE_SCRIPT}" \
    --train-bot-bundle "${train_bundle}" \
    --manifest-version fast-selected \
    --out-dir "${fast_bundle_dir}" >/dev/null

python3 - "${fast_bundle_dir}/runtime-manifest.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    artifacts = {artifact["id"] for artifact in json.load(handle)["artifacts"]}

expected = {
    "dropbear-bundle",
    "tailscale-bundle",
    "train-bot-bundle",
}
if artifacts != expected:
    raise SystemExit(f"fast bundle selected unexpected artifacts: {sorted(artifacts)}")
PY

strict_selected_output="$(
  PIXEL_RUNTIME_DROPBEAR_ARTIFACT_DIR="${dropbear_dir}" \
  PIXEL_RUNTIME_TAILSCALE_BUNDLE="${tailscale_bundle}" \
  PIXEL_RUNTIME_TRAIN_BOT_BUNDLE="${train_bundle}" \
  PIXEL_RUNTIME_SATIKSME_BOT_BUNDLE="${satiksme_bundle}" \
  PIXEL_RUNTIME_SITE_NOTIFIER_BUNDLE="${site_notifier_bundle}" \
  PIXEL_RUNTIME_SUBSCRIPTION_BOT_BUNDLE="${subscription_bundle}" \
  bash "${PACKAGE_SCRIPT}" --full --print-inputs
)"
for expected in \
  "TRAIN_BOT_BUNDLE=${train_bundle}" \
  "SATIKSME_BOT_BUNDLE=${satiksme_bundle}" \
  "SITE_NOTIFIER_BUNDLE=${site_notifier_bundle}" \
  "SUBSCRIPTION_BOT_BUNDLE=${subscription_bundle}"; do
  if ! grep -Fqx "${expected}" <<<"${strict_selected_output}"; then
    echo "FAIL: strict packaging did not retain complete workload selection: ${expected}" >&2
    exit 1
  fi
done

echo "PASS: runtime bundle packaging excludes retired DNS and resolves only selected active inputs"
