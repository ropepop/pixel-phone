#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ORCHESTRATOR_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
CRATE_ROOT="${ORCHESTRATOR_ROOT}/android-orchestrator/pixel-health"
OUTPUT="${1:-${ORCHESTRATOR_ROOT}/android-orchestrator/app/build/generated/pixelHealthJni/arm64-v8a/libpixel_health.so}"
CLEANUP_OUTPUT="${2:-${ORCHESTRATOR_ROOT}/android-orchestrator/app/build/generated/pixelRuntimeCleanupAsset/pixel-runtime-cleanup}"
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

TARGET_DIR="${ORCHESTRATOR_ROOT}/android-orchestrator/app/build/pixelHealthRust"
CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="${clang}" \
CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384" \
CC_aarch64_linux_android="${clang}" \
AR_aarch64_linux_android="${NDK_ROOT}/toolchains/llvm/prebuilt/${host_tag}/bin/llvm-ar" \
  cargo build --manifest-path "${CRATE_ROOT}/Cargo.toml" \
    --locked --release --lib --bin pixel-runtime-cleanup --target aarch64-linux-android --target-dir "${TARGET_DIR}"
mkdir -p "$(dirname "${OUTPUT}")"
cp "${TARGET_DIR}/aarch64-linux-android/release/libpixel_health.so" "${OUTPUT}"
chmod 0755 "${OUTPUT}"
mkdir -p "$(dirname "${CLEANUP_OUTPUT}")"
cp "${TARGET_DIR}/aarch64-linux-android/release/pixel-runtime-cleanup" "${CLEANUP_OUTPUT}"
chmod 0755 "${CLEANUP_OUTPUT}"
