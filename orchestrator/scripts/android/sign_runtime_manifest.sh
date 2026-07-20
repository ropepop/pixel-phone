#!/usr/bin/env bash
set -euo pipefail

MANIFEST_FILE=""
PRIVATE_KEY_PEM=""
OUT_FILE=""
TIMINGS_FILE="${PIXEL_PHASE_TIMINGS_FILE:-}"
TIMING_TOTAL_START_MS=""
TIMING_PHASE_START_MS=""

usage() {
  cat <<USAGE
Usage: $(basename "$0") --manifest FILE --private-key-pem FILE [--out FILE]

Signs runtime-manifest JSON with SHA256withECDSA and writes Base64 signature.

Options:
  --manifest FILE          Path to runtime-manifest.json
  --private-key-pem FILE   ECDSA private key (PEM)
  --out FILE               Output signature file (default: <manifest>.sig)
  --timings-file FILE      Append JSONL phase timings to FILE
  -h, --help               Show this help
USAGE
}

timing_now_ms() {
  python3 -c 'import time; print(time.monotonic_ns() // 1_000_000)'
}

timing_start() {
  [[ -n "${TIMINGS_FILE}" ]] || return 0
  mkdir -p "$(dirname "${TIMINGS_FILE}")"
  TIMING_TOTAL_START_MS="$(timing_now_ms)"
  TIMING_PHASE_START_MS="${TIMING_TOTAL_START_MS}"
}

timing_mark() {
  local phase="$1"
  local now_ms=""
  [[ -n "${TIMINGS_FILE}" ]] || return 0
  now_ms="$(timing_now_ms)"
  printf '{"script":"sign_runtime_manifest","phase":"%s","durationMs":%d}\n' \
    "${phase}" "$((now_ms - TIMING_PHASE_START_MS))" >> "${TIMINGS_FILE}"
  TIMING_PHASE_START_MS="${now_ms}"
}

timing_finish() {
  local now_ms=""
  [[ -n "${TIMINGS_FILE}" ]] || return 0
  now_ms="$(timing_now_ms)"
  printf '{"script":"sign_runtime_manifest","phase":"total","durationMs":%d}\n' \
    "$((now_ms - TIMING_TOTAL_START_MS))" >> "${TIMINGS_FILE}"
}

while (( $# > 0 )); do
  case "$1" in
    --manifest)
      shift
      MANIFEST_FILE="${1:-}"
      ;;
    --private-key-pem)
      shift
      PRIVATE_KEY_PEM="${1:-}"
      ;;
    --out)
      shift
      OUT_FILE="${1:-}"
      ;;
    --timings-file)
      shift
      TIMINGS_FILE="${1:-}"
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

[[ -n "${MANIFEST_FILE}" ]] || { echo "--manifest is required" >&2; exit 2; }
[[ -n "${PRIVATE_KEY_PEM}" ]] || { echo "--private-key-pem is required" >&2; exit 2; }
[[ -f "${MANIFEST_FILE}" ]] || { echo "Manifest not found: ${MANIFEST_FILE}" >&2; exit 1; }
[[ -f "${PRIVATE_KEY_PEM}" ]] || { echo "Private key not found: ${PRIVATE_KEY_PEM}" >&2; exit 1; }

if [[ -z "${OUT_FILE}" ]]; then
  OUT_FILE="${MANIFEST_FILE}.sig"
fi

if ! command -v openssl >/dev/null 2>&1; then
  echo "openssl not found" >&2
  exit 1
fi

timing_start
sig_tmp="$(mktemp)"
out_tmp="$(mktemp "${OUT_FILE}.tmp.XXXXXX")"
trap 'rm -f "${sig_tmp}" "${out_tmp}"' EXIT
openssl dgst -sha256 -sign "${PRIVATE_KEY_PEM}" -out "${sig_tmp}" "${MANIFEST_FILE}"
timing_mark "sign"
base64 < "${sig_tmp}" | tr -d '\n' > "${out_tmp}"
printf '\n' >> "${out_tmp}"
mv -f "${out_tmp}" "${OUT_FILE}"
timing_mark "publish"
timing_finish

echo "Wrote signature: ${OUT_FILE}"
