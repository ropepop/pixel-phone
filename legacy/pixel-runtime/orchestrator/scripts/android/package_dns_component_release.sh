#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKSPACE_ROOT="$(cd "${REPO_ROOT}/.." && pwd)"
APP_ROOT="${REPO_ROOT}/android-orchestrator"

ROOTFS_TARBALL="${PIXEL_RUNTIME_ROOTFS_TARBALL:-}"
RELEASE_ID=""
OUT_DIR=""
DNS_RUNTIME_ASSET_SOURCE_ROOT="${APP_ROOT}/app/src/main/assets/runtime"

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Builds a self-contained DNS component release bundle for on-device staging via deploy_orchestrator_apk.sh --component-release-dir.

Options:
  --rootfs-tarball FILE    AdGuardHome rootfs tarball file
  --release-id VALUE       Release id string (default: local-<UTC timestamp>)
  --out-dir DIR            Output dir (default: .artifacts/component-releases/dns-<release-id>)
  -h, --help               Show help
USAGE
}

while (( $# > 0 )); do
  case "$1" in
    --rootfs-tarball)
      shift
      ROOTFS_TARBALL="${1:-}"
      ;;
    --release-id)
      shift
      RELEASE_ID="${1:-}"
      ;;
    --out-dir)
      shift
      OUT_DIR="${1:-}"
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

[[ -n "${ROOTFS_TARBALL}" ]] || {
  echo "Missing rootfs tarball. Pass --rootfs-tarball or set PIXEL_RUNTIME_ROOTFS_TARBALL." >&2
  exit 1
}
[[ -f "${ROOTFS_TARBALL}" ]] || {
  echo "Rootfs tarball not found: ${ROOTFS_TARBALL}" >&2
  exit 1
}

command -v tar >/dev/null 2>&1 || { echo "tar not found" >&2; exit 1; }

if [[ -z "${RELEASE_ID}" ]]; then
  RELEASE_ID="local-$(date -u +%Y%m%dT%H%M%SZ)"
fi

if [[ -z "${OUT_DIR}" ]]; then
  OUT_DIR="${REPO_ROOT}/.artifacts/component-releases/dns-${RELEASE_ID}"
fi

bash "${WORKSPACE_ROOT}/tools/pixel/cleanup_workspace.sh"
mkdir -p "${OUT_DIR}/artifacts"
OUT_DIR="$(cd "${OUT_DIR}" && pwd)"

sha256_file() {
  local path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${path}" | awk '{print $1}'
  else
    shasum -a 256 "${path}" | awk '{print $1}'
  fi
}

size_bytes() {
  local path="$1"
  if stat -f "%z" "${path}" >/dev/null 2>&1; then
    stat -f "%z" "${path}"
  else
    stat -c "%s" "${path}"
  fi
}

create_deterministic_tar() {
  local source_dir="$1"
  local output_tar="$2"
  python3 - "${source_dir}" "${output_tar}" <<'PY'
import os
import stat
import sys
import tarfile
from pathlib import Path

source = Path(sys.argv[1]).resolve()
output = Path(sys.argv[2]).resolve()
fixed_mtime = 0
skip_names = {".DS_Store"}

def should_skip(name: str) -> bool:
    return name in skip_names or name.startswith("._")

def normalized_arcname(path: Path) -> str:
    if path == source:
        return "."
    return "./" + path.relative_to(source).as_posix()

def add_entry(tf: tarfile.TarFile, path: Path) -> None:
    st = os.lstat(path)
    info = tarfile.TarInfo(normalized_arcname(path))
    info.uid = 0
    info.gid = 0
    info.uname = "root"
    info.gname = "root"
    info.mtime = fixed_mtime
    info.mode = stat.S_IMODE(st.st_mode)
    if stat.S_ISDIR(st.st_mode):
        info.type = tarfile.DIRTYPE
        info.size = 0
        tf.addfile(info)
        return
    if stat.S_ISLNK(st.st_mode):
        info.type = tarfile.SYMTYPE
        info.linkname = os.readlink(path)
        info.size = 0
        tf.addfile(info)
        return
    if stat.S_ISREG(st.st_mode):
        info.type = tarfile.REGTYPE
        info.size = st.st_size
        with path.open("rb") as handle:
            tf.addfile(info, handle)
        return
    raise SystemExit(f"Unsupported file type in bundle stage: {path}")

with tarfile.open(output, "w") as tf:
    add_entry(tf, source)
    for root, dirs, files in os.walk(source, topdown=True, followlinks=False):
        dirs[:] = sorted(name for name in dirs if not should_skip(name))
        files = sorted(name for name in files if not should_skip(name))
        root_path = Path(root)
        for directory in dirs:
            add_entry(tf, root_path / directory)
        for file_name in files:
            add_entry(tf, root_path / file_name)
PY
}

ROOTFS_NAME="adguardhome-rootfs-arm64.tar"
DNS_RUNTIME_ASSETS_NAME="dns-runtime-assets.tar"
ROOTFS_OUT="${OUT_DIR}/artifacts/${ROOTFS_NAME}"
DNS_RUNTIME_ASSETS_OUT="${OUT_DIR}/artifacts/${DNS_RUNTIME_ASSETS_NAME}"

cp "${ROOTFS_TARBALL}" "${ROOTFS_OUT}"

dns_bundle_stage="$(mktemp -d)"
trap 'rm -rf "${dns_bundle_stage}"' EXIT
mkdir -p "${dns_bundle_stage}/templates/rooted" "${dns_bundle_stage}/bin"
cp -a "${DNS_RUNTIME_ASSET_SOURCE_ROOT}/templates/rooted/." "${dns_bundle_stage}/templates/rooted/"
install -m 0755 "${DNS_RUNTIME_ASSET_SOURCE_ROOT}/entrypoints/pixel-dns-start.sh" "${dns_bundle_stage}/bin/pixel-dns-start.sh"
install -m 0755 "${DNS_RUNTIME_ASSET_SOURCE_ROOT}/entrypoints/pixel-dns-stop.sh" "${dns_bundle_stage}/bin/pixel-dns-stop.sh"
create_deterministic_tar "${dns_bundle_stage}" "${DNS_RUNTIME_ASSETS_OUT}"

ROOTFS_SHA="$(sha256_file "${ROOTFS_OUT}")"
ROOTFS_SIZE="$(size_bytes "${ROOTFS_OUT}")"
DNS_RUNTIME_ASSETS_SHA="$(sha256_file "${DNS_RUNTIME_ASSETS_OUT}")"
DNS_RUNTIME_ASSETS_SIZE="$(size_bytes "${DNS_RUNTIME_ASSETS_OUT}")"
MANIFEST_PATH="${OUT_DIR}/release-manifest.json"

export MANIFEST_PATH RELEASE_ID ROOTFS_NAME ROOTFS_SHA ROOTFS_SIZE DNS_RUNTIME_ASSETS_NAME DNS_RUNTIME_ASSETS_SHA DNS_RUNTIME_ASSETS_SIZE
python3 - <<'PY'
import json
import os
from pathlib import Path

manifest = {
    "schema": 1,
    "componentId": "dns",
    "releaseId": os.environ["RELEASE_ID"],
    "signatureSchema": "none",
    "artifacts": [
        {
            "id": "adguardhome-rootfs",
            "url": f"/data/local/pixel-stack/conf/runtime/components/dns/artifacts/{os.environ['ROOTFS_NAME']}",
            "sha256": os.environ["ROOTFS_SHA"],
            "fileName": os.environ["ROOTFS_NAME"],
            "sizeBytes": int(os.environ["ROOTFS_SIZE"]),
            "required": True,
        },
        {
            "id": "dns-runtime-assets",
            "url": f"/data/local/pixel-stack/conf/runtime/components/dns/artifacts/{os.environ['DNS_RUNTIME_ASSETS_NAME']}",
            "sha256": os.environ["DNS_RUNTIME_ASSETS_SHA"],
            "fileName": os.environ["DNS_RUNTIME_ASSETS_NAME"],
            "sizeBytes": int(os.environ["DNS_RUNTIME_ASSETS_SIZE"]),
            "required": True,
        },
    ],
}

Path(os.environ["MANIFEST_PATH"]).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
PY

cat <<EOF_SUMMARY
DNS component release ready:
  ${OUT_DIR}

Resolved inputs:
  rootfs: ${ROOTFS_TARBALL}
  dns-runtime-assets: ${DNS_RUNTIME_ASSET_SOURCE_ROOT}

Stage on device with:
  bash orchestrator/scripts/android/deploy_orchestrator_apk.sh --component-release-dir "${OUT_DIR}" --action redeploy_component --component dns
EOF_SUMMARY
