#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ORCHESTRATOR_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
CRATE_ROOT="${ORCHESTRATOR_ROOT}/android-orchestrator/ticket-root-keyboard"
OUTPUT="${1:-${ORCHESTRATOR_ROOT}/android-orchestrator/app/build/generated/ticketRootKeyboardAssets/ticket-root-keyboard}"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-${HOME}/Library/Android/sdk}}"
NDK_ROOT="${ANDROID_NDK_HOME:-}"

if [[ -z "${NDK_ROOT}" ]]; then
  NDK_ROOT="$(find "${SDK_ROOT}/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1)"
fi
if [[ -z "${NDK_ROOT}" || ! -d "${NDK_ROOT}" ]]; then
  echo "Android NDK not found" >&2
  exit 2
fi

case "$(uname -s)" in
  Darwin) host_tag="darwin-x86_64" ;;
  Linux) host_tag="linux-x86_64" ;;
  *) echo "unsupported build host: $(uname -s)" >&2; exit 2 ;;
esac

clang="${NDK_ROOT}/toolchains/llvm/prebuilt/${host_tag}/bin/aarch64-linux-android29-clang"
if [[ ! -x "${clang}" ]]; then
  echo "Android arm64 clang not found: ${clang}" >&2
  exit 2
fi

if ! command -v cargo >/dev/null 2>&1; then
  echo "Rust Cargo not found; install Rust and the aarch64-linux-android target" >&2
  exit 2
fi

TARGET_DIR="${ORCHESTRATOR_ROOT}/android-orchestrator/app/build/ticketRootKeyboardRust"
CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="${clang}" \
  cargo build --manifest-path "${CRATE_ROOT}/Cargo.toml" \
    --locked --offline --release --target aarch64-linux-android --target-dir "${TARGET_DIR}"
mkdir -p "$(dirname "${OUTPUT}")"
cp "${TARGET_DIR}/aarch64-linux-android/release/ticket-root-keyboard" "${OUTPUT}"
chmod 0755 "${OUTPUT}"
