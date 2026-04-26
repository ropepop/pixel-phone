#!/usr/bin/env bash
set -euo pipefail

MANIFEST_FILE=""
PRIVATE_KEY_PEM=""
OUT_FILE=""

usage() {
  cat <<USAGE
Usage: $(basename "$0") --manifest FILE --private-key-pem FILE [--out FILE]

Signs runtime-manifest JSON with SHA256withECDSA and writes Base64 signature.

Options:
  --manifest FILE          Path to runtime-manifest.json
  --private-key-pem FILE   ECDSA private key (PEM)
  --out FILE               Output signature file (default: <manifest>.sig)
  -h, --help               Show this help
USAGE
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

sig_tmp="$(mktemp)"
trap 'rm -f "${sig_tmp}"' EXIT
openssl dgst -sha256 -sign "${PRIVATE_KEY_PEM}" -out "${sig_tmp}" "${MANIFEST_FILE}"
base64 < "${sig_tmp}" | tr -d '\n' > "${OUT_FILE}"
printf '\n' >> "${OUT_FILE}"

echo "Wrote signature: ${OUT_FILE}"
