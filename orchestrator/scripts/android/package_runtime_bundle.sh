#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APP_ROOT="${REPO_ROOT}/android-orchestrator"
WORKSPACE_ROOT="${REPO_ROOT}"
if [[ -d "${REPO_ROOT}/../workloads" && -d "${REPO_ROOT}/../tools" ]]; then
  WORKSPACE_ROOT="$(cd "${REPO_ROOT}/.." && pwd)"
fi

ROOTFS_TARBALL="${PIXEL_RUNTIME_ROOTFS_TARBALL:-}"
DROPBEAR_ARTIFACT_DIR="${PIXEL_RUNTIME_DROPBEAR_ARTIFACT_DIR:-}"
TAILSCALE_BUNDLE="${PIXEL_RUNTIME_TAILSCALE_BUNDLE:-}"
TRAIN_BOT_BUNDLE="${PIXEL_RUNTIME_TRAIN_BOT_BUNDLE:-}"
SATIKSME_BOT_BUNDLE="${PIXEL_RUNTIME_SATIKSME_BOT_BUNDLE:-}"
SITE_NOTIFIER_BUNDLE="${PIXEL_RUNTIME_SITE_NOTIFIER_BUNDLE:-}"
SUBSCRIPTION_BOT_BUNDLE="${PIXEL_RUNTIME_SUBSCRIPTION_BOT_BUNDLE:-}"
INCLUDE_TRAIN_BOT_BUNDLE=1
INCLUDE_SATIKSME_BOT_BUNDLE=1
INCLUDE_SITE_NOTIFIER_BUNDLE=1
INCLUDE_SUBSCRIPTION_BOT_BUNDLE=1
MANIFEST_VERSION=""
OUT_DIR=""
PRINT_INPUTS=0
DNS_RUNTIME_ASSET_SOURCE_ROOT="${APP_ROOT}/app/src/main/assets/runtime"

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Builds a local runtime bundle for on-device staging via deploy_orchestrator_apk.sh --runtime-bundle-dir.

Options:
  --rootfs-tarball FILE          AdGuardHome rootfs tarball file
  --dropbear-artifact-dir DIR    Dropbear prebuilt dir containing dropbearmulti
  --tailscale-bundle FILE        Tailscale runtime bundle tar
  --train-bot-bundle FILE        Train bot runtime bundle tar
  --satiksme-bot-bundle FILE     Satiksme bot runtime bundle tar
  --site-notifier-bundle FILE    Site notifier runtime bundle tar
  --subscription-bot-bundle FILE Subscription bot runtime bundle tar
  --platform-only               Build a platform-only runtime bundle without workload bundles
  --manifest-version VALUE       Manifest version string (default: local-<UTC timestamp>)
  --out-dir DIR                  Output bundle dir (default: .artifacts/runtime-local/<manifest-version>)
  --print-inputs                 Resolve and print the selected input paths, then exit
  -h, --help                     Show this help
USAGE
}

while (( $# > 0 )); do
  case "$1" in
    --rootfs-tarball)
      shift
      ROOTFS_TARBALL="${1:-}"
      ;;
    --dropbear-artifact-dir)
      shift
      DROPBEAR_ARTIFACT_DIR="${1:-}"
      ;;
    --tailscale-bundle)
      shift
      TAILSCALE_BUNDLE="${1:-}"
      ;;
    --train-bot-bundle)
      shift
      TRAIN_BOT_BUNDLE="${1:-}"
      ;;
    --satiksme-bot-bundle)
      shift
      SATIKSME_BOT_BUNDLE="${1:-}"
      ;;
    --site-notifier-bundle)
      shift
      SITE_NOTIFIER_BUNDLE="${1:-}"
      ;;
    --subscription-bot-bundle)
      shift
      SUBSCRIPTION_BOT_BUNDLE="${1:-}"
      ;;
    --platform-only)
      INCLUDE_TRAIN_BOT_BUNDLE=0
      INCLUDE_SATIKSME_BOT_BUNDLE=0
      INCLUDE_SITE_NOTIFIER_BUNDLE=0
      INCLUDE_SUBSCRIPTION_BOT_BUNDLE=0
      ;;
    --manifest-version)
      shift
      MANIFEST_VERSION="${1:-}"
      ;;
    --out-dir)
      shift
      OUT_DIR="${1:-}"
      ;;
    --print-inputs)
      PRINT_INPUTS=1
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

artifact_roots() {
  printf '%s\n' "${WORKSPACE_ROOT}/.artifacts"
  if [[ "${REPO_ROOT}" != "${WORKSPACE_ROOT}" ]]; then
    printf '%s\n' "${REPO_ROOT}/.artifacts"
  fi
}

append_existing_file_candidates() {
  local path=""
  for path in "$@"; do
    [[ -f "${path}" ]] || continue
    printf '%s\n' "${path}"
  done
}

append_existing_dir_candidates() {
  local path=""
  for path in "$@"; do
    [[ -x "${path}/dropbearmulti" ]] || continue
    printf '%s\n' "${path}"
  done
}

emit_dropbear_candidates() {
  local root=""
  shopt -s nullglob
  while IFS= read -r root; do
    append_existing_dir_candidates \
      "${root}"/dropbear/android-arm64/* \
      "${root}"/cutover/*/release-inputs/dropbear-prebuilt
  done < <(artifact_roots)
  shopt -u nullglob
}

emit_tailscale_candidates() {
  local root=""
  shopt -s nullglob
  while IFS= read -r root; do
    append_existing_file_candidates \
      "${root}"/tailscale/android-arm64/*/tailscale-bundle.tar \
      "${root}"/runtime-release-inputs/*/tailscale-bundle.tar \
      "${root}"/runtime-release/*/tailscale-bundle.tar \
      "${root}"/runtime-local/*/artifacts/tailscale-bundle.tar
  done < <(artifact_roots)
  shopt -u nullglob
}

emit_train_bot_candidates() {
  local root=""
  shopt -s nullglob
  while IFS= read -r root; do
    append_existing_file_candidates \
      "${root}"/train-bot/train-bot-bundle-*.tar \
      "${root}"/component-releases/train_bot-*/artifacts/train-bot-bundle-*.tar \
      "${root}"/runtime-local/*/artifacts/train-bot-bundle.tar
  done < <(artifact_roots)
  append_existing_file_candidates \
    "${WORKSPACE_ROOT}"/workloads/train-bot/.artifacts/train-bot/train-bot-bundle-*.tar \
    "${WORKSPACE_ROOT}"/workloads/train-bot/.artifacts/component-releases/train_bot-*/artifacts/train-bot-bundle-*.tar
  shopt -u nullglob
}

emit_satiksme_bot_candidates() {
  local root=""
  shopt -s nullglob
  while IFS= read -r root; do
    append_existing_file_candidates \
      "${root}"/satiksme-bot/satiksme-bot-bundle-*.tar \
      "${root}"/component-releases/satiksme_bot-*/artifacts/satiksme-bot-bundle-*.tar \
      "${root}"/runtime-local/*/artifacts/satiksme-bot-bundle.tar
  done < <(artifact_roots)
  append_existing_file_candidates \
    "${WORKSPACE_ROOT}"/workloads/satiksme-bot/.artifacts/satiksme-bot/satiksme-bot-bundle-*.tar \
    "${WORKSPACE_ROOT}"/workloads/satiksme-bot/.artifacts/component-releases/satiksme_bot-*/artifacts/satiksme-bot-bundle-*.tar
  shopt -u nullglob
}

emit_site_notifier_candidates() {
  local root=""
  shopt -s nullglob
  while IFS= read -r root; do
    append_existing_file_candidates \
      "${root}"/site-notifier/site-notifier-bundle-*.tar \
      "${root}"/component-releases/site_notifier-*/artifacts/site-notifier-bundle-*.tar \
      "${root}"/runtime-local/*/artifacts/site-notifier-bundle.tar
  done < <(artifact_roots)
  append_existing_file_candidates \
    "${WORKSPACE_ROOT}"/workloads/site-notifications/.artifacts/site-notifier/site-notifier-bundle-*.tar \
    "${WORKSPACE_ROOT}"/workloads/site-notifications/.artifacts/component-releases/site_notifier-*/artifacts/site-notifier-bundle-*.tar
  shopt -u nullglob
}

emit_subscription_bot_candidates() {
  local root=""
  shopt -s nullglob
  while IFS= read -r root; do
    append_existing_file_candidates \
      "${root}"/subscription-bot/subscription-bot-bundle-*.tar \
      "${root}"/component-releases/subscription_bot-*/artifacts/subscription-bot-bundle-*.tar \
      "${root}"/runtime-local/*/artifacts/subscription-bot-bundle.tar
  done < <(artifact_roots)
  append_existing_file_candidates \
    "${WORKSPACE_ROOT}"/workloads/subscription-bot/.artifacts/subscription-bot/subscription-bot-bundle-*.tar \
    "${WORKSPACE_ROOT}"/workloads/subscription-bot/.artifacts/component-releases/subscription_bot-*/artifacts/subscription-bot-bundle-*.tar
  shopt -u nullglob
}

choose_latest_candidate() {
  local label="$1"
  shift
  local candidates=()
  local line=""
  while IFS= read -r line; do
    [[ -n "${line}" ]] || continue
    candidates+=("${line}")
  done < <("$@")

  if (( ${#candidates[@]} == 0 )); then
    echo "Unable to auto-resolve ${label}. Pass the explicit flag or set the corresponding PIXEL_RUNTIME_* environment variable." >&2
    exit 1
  fi

  printf '%s\n' "${candidates[@]}" | sort -u | tail -n 1
}

resolve_inputs() {
  [[ -n "${ROOTFS_TARBALL}" ]] || {
    echo "Missing rootfs tarball. Pass --rootfs-tarball or set PIXEL_RUNTIME_ROOTFS_TARBALL." >&2
    exit 1
  }
  [[ -n "${DROPBEAR_ARTIFACT_DIR}" ]] || DROPBEAR_ARTIFACT_DIR="$(choose_latest_candidate "dropbear artifact dir" emit_dropbear_candidates)"
  [[ -n "${TAILSCALE_BUNDLE}" ]] || TAILSCALE_BUNDLE="$(choose_latest_candidate "tailscale bundle" emit_tailscale_candidates)"
  if (( INCLUDE_TRAIN_BOT_BUNDLE == 1 )); then
    [[ -n "${TRAIN_BOT_BUNDLE}" ]] || TRAIN_BOT_BUNDLE="$(choose_latest_candidate "train-bot bundle" emit_train_bot_candidates)"
  else
    TRAIN_BOT_BUNDLE=""
  fi
  if (( INCLUDE_SATIKSME_BOT_BUNDLE == 1 )); then
    [[ -n "${SATIKSME_BOT_BUNDLE}" ]] || SATIKSME_BOT_BUNDLE="$(choose_latest_candidate "satiksme-bot bundle" emit_satiksme_bot_candidates)"
  else
    SATIKSME_BOT_BUNDLE=""
  fi
  if (( INCLUDE_SITE_NOTIFIER_BUNDLE == 1 )); then
    [[ -n "${SITE_NOTIFIER_BUNDLE}" ]] || SITE_NOTIFIER_BUNDLE="$(choose_latest_candidate "site-notifier bundle" emit_site_notifier_candidates)"
  else
    SITE_NOTIFIER_BUNDLE=""
  fi
  if (( INCLUDE_SUBSCRIPTION_BOT_BUNDLE == 1 )); then
    [[ -n "${SUBSCRIPTION_BOT_BUNDLE}" ]] || SUBSCRIPTION_BOT_BUNDLE="$(choose_latest_candidate "subscription-bot bundle" emit_subscription_bot_candidates)"
  else
    SUBSCRIPTION_BOT_BUNDLE=""
  fi
}

resolve_inputs

if (( PRINT_INPUTS == 1 )); then
  printf 'ROOTFS_TARBALL=%s\n' "${ROOTFS_TARBALL}"
  printf 'DROPBEAR_ARTIFACT_DIR=%s\n' "${DROPBEAR_ARTIFACT_DIR}"
  printf 'TAILSCALE_BUNDLE=%s\n' "${TAILSCALE_BUNDLE}"
  printf 'TRAIN_BOT_BUNDLE=%s\n' "${TRAIN_BOT_BUNDLE}"
  printf 'SATIKSME_BOT_BUNDLE=%s\n' "${SATIKSME_BOT_BUNDLE}"
  printf 'SITE_NOTIFIER_BUNDLE=%s\n' "${SITE_NOTIFIER_BUNDLE}"
  printf 'SUBSCRIPTION_BOT_BUNDLE=%s\n' "${SUBSCRIPTION_BOT_BUNDLE}"
  exit 0
fi

[[ -f "${ROOTFS_TARBALL}" ]] || { echo "Rootfs tarball not found: ${ROOTFS_TARBALL}" >&2; exit 1; }
[[ -d "${DROPBEAR_ARTIFACT_DIR}" ]] || { echo "Dropbear artifact dir not found: ${DROPBEAR_ARTIFACT_DIR}" >&2; exit 1; }
[[ -x "${DROPBEAR_ARTIFACT_DIR}/dropbearmulti" ]] || { echo "Missing dropbearmulti in ${DROPBEAR_ARTIFACT_DIR}" >&2; exit 1; }
[[ -f "${TAILSCALE_BUNDLE}" ]] || { echo "Tailscale bundle not found: ${TAILSCALE_BUNDLE}" >&2; exit 1; }
[[ -d "${DNS_RUNTIME_ASSET_SOURCE_ROOT}/templates/rooted" ]] || {
  echo "Missing rooted DNS templates in ${DNS_RUNTIME_ASSET_SOURCE_ROOT}/templates/rooted" >&2
  exit 1
}
[[ -f "${DNS_RUNTIME_ASSET_SOURCE_ROOT}/entrypoints/pixel-dns-start.sh" ]] || {
  echo "Missing DNS start entrypoint in ${DNS_RUNTIME_ASSET_SOURCE_ROOT}/entrypoints/pixel-dns-start.sh" >&2
  exit 1
}
[[ -f "${DNS_RUNTIME_ASSET_SOURCE_ROOT}/entrypoints/pixel-dns-stop.sh" ]] || {
  echo "Missing DNS stop entrypoint in ${DNS_RUNTIME_ASSET_SOURCE_ROOT}/entrypoints/pixel-dns-stop.sh" >&2
  exit 1
}
if (( INCLUDE_TRAIN_BOT_BUNDLE == 1 )); then
  [[ -f "${TRAIN_BOT_BUNDLE}" ]] || { echo "Train bot bundle not found: ${TRAIN_BOT_BUNDLE}" >&2; exit 1; }
fi
if (( INCLUDE_SATIKSME_BOT_BUNDLE == 1 )); then
  [[ -f "${SATIKSME_BOT_BUNDLE}" ]] || { echo "Satiksme bot bundle not found: ${SATIKSME_BOT_BUNDLE}" >&2; exit 1; }
fi
if (( INCLUDE_SITE_NOTIFIER_BUNDLE == 1 )); then
  [[ -f "${SITE_NOTIFIER_BUNDLE}" ]] || { echo "Site notifier bundle not found: ${SITE_NOTIFIER_BUNDLE}" >&2; exit 1; }
fi
if (( INCLUDE_SUBSCRIPTION_BOT_BUNDLE == 1 )); then
  [[ -f "${SUBSCRIPTION_BOT_BUNDLE}" ]] || { echo "Subscription bot bundle not found: ${SUBSCRIPTION_BOT_BUNDLE}" >&2; exit 1; }
fi

command -v tar >/dev/null 2>&1 || { echo "tar not found" >&2; exit 1; }

if [[ -z "${MANIFEST_VERSION}" ]]; then
  MANIFEST_VERSION="local-$(date -u +%Y%m%dT%H%M%SZ)"
fi

ARTIFACT_ROOT="${WORKSPACE_ROOT}/.artifacts"
if [[ ! -d "${ARTIFACT_ROOT}" && -d "${REPO_ROOT}/.artifacts" ]]; then
  ARTIFACT_ROOT="${REPO_ROOT}/.artifacts"
fi

if [[ -z "${OUT_DIR}" ]]; then
  OUT_DIR="${ARTIFACT_ROOT}/runtime-local/${MANIFEST_VERSION}"
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

bundle_stage="$(mktemp -d)"
trap 'rm -rf "${bundle_stage}"' EXIT
mkdir -p "${bundle_stage}/bin" "${bundle_stage}/conf" "${bundle_stage}/etc/dropbear" "${bundle_stage}/home/root/.ssh" "${bundle_stage}/logs" "${bundle_stage}/run"

install -m 0755 "${DROPBEAR_ARTIFACT_DIR}/dropbearmulti" "${bundle_stage}/bin/dropbearmulti"
ln -sf "dropbearmulti" "${bundle_stage}/bin/dropbear"
ln -sf "dropbearmulti" "${bundle_stage}/bin/dropbearkey"
ln -sf "dropbearmulti" "${bundle_stage}/bin/dbclient"

ROOTFS_NAME="adguardhome-rootfs-arm64.tar"
DNS_RUNTIME_ASSETS_NAME="dns-runtime-assets.tar"
DROPBEAR_BUNDLE_NAME="dropbear-bundle.tar"
TAILSCALE_BUNDLE_NAME="tailscale-bundle.tar"
TRAIN_BOT_BUNDLE_NAME="train-bot-bundle.tar"
SATIKSME_BOT_BUNDLE_NAME="satiksme-bot-bundle.tar"
SITE_NOTIFIER_BUNDLE_NAME="site-notifier-bundle.tar"
SUBSCRIPTION_BOT_BUNDLE_NAME="subscription-bot-bundle.tar"

ROOTFS_OUT="${OUT_DIR}/artifacts/${ROOTFS_NAME}"
DNS_RUNTIME_ASSETS_OUT="${OUT_DIR}/artifacts/${DNS_RUNTIME_ASSETS_NAME}"
DROPBEAR_BUNDLE_OUT="${OUT_DIR}/artifacts/${DROPBEAR_BUNDLE_NAME}"
TAILSCALE_BUNDLE_OUT="${OUT_DIR}/artifacts/${TAILSCALE_BUNDLE_NAME}"
TRAIN_BOT_BUNDLE_OUT="${OUT_DIR}/artifacts/${TRAIN_BOT_BUNDLE_NAME}"
SATIKSME_BOT_BUNDLE_OUT="${OUT_DIR}/artifacts/${SATIKSME_BOT_BUNDLE_NAME}"
SITE_NOTIFIER_BUNDLE_OUT="${OUT_DIR}/artifacts/${SITE_NOTIFIER_BUNDLE_NAME}"
SUBSCRIPTION_BOT_BUNDLE_OUT="${OUT_DIR}/artifacts/${SUBSCRIPTION_BOT_BUNDLE_NAME}"

cp "${ROOTFS_TARBALL}" "${ROOTFS_OUT}"
dns_bundle_stage="$(mktemp -d)"
trap 'rm -rf "${bundle_stage}" "${dns_bundle_stage}"' EXIT
mkdir -p "${dns_bundle_stage}/templates/rooted" "${dns_bundle_stage}/bin"
cp -a "${DNS_RUNTIME_ASSET_SOURCE_ROOT}/templates/rooted/." "${dns_bundle_stage}/templates/rooted/"
install -m 0755 "${DNS_RUNTIME_ASSET_SOURCE_ROOT}/entrypoints/pixel-dns-start.sh" "${dns_bundle_stage}/bin/pixel-dns-start.sh"
install -m 0755 "${DNS_RUNTIME_ASSET_SOURCE_ROOT}/entrypoints/pixel-dns-stop.sh" "${dns_bundle_stage}/bin/pixel-dns-stop.sh"
create_deterministic_tar "${dns_bundle_stage}" "${DNS_RUNTIME_ASSETS_OUT}"
create_deterministic_tar "${bundle_stage}" "${DROPBEAR_BUNDLE_OUT}"
cp "${TAILSCALE_BUNDLE}" "${TAILSCALE_BUNDLE_OUT}"
if (( INCLUDE_TRAIN_BOT_BUNDLE == 1 )); then
  cp "${TRAIN_BOT_BUNDLE}" "${TRAIN_BOT_BUNDLE_OUT}"
fi
if (( INCLUDE_SATIKSME_BOT_BUNDLE == 1 )); then
  cp "${SATIKSME_BOT_BUNDLE}" "${SATIKSME_BOT_BUNDLE_OUT}"
fi
if (( INCLUDE_SITE_NOTIFIER_BUNDLE == 1 )); then
  cp "${SITE_NOTIFIER_BUNDLE}" "${SITE_NOTIFIER_BUNDLE_OUT}"
fi
if (( INCLUDE_SUBSCRIPTION_BOT_BUNDLE == 1 )); then
  cp "${SUBSCRIPTION_BOT_BUNDLE}" "${SUBSCRIPTION_BOT_BUNDLE_OUT}"
fi

ROOTFS_SHA="$(sha256_file "${ROOTFS_OUT}")"
ROOTFS_SIZE="$(size_bytes "${ROOTFS_OUT}")"
DNS_RUNTIME_ASSETS_SHA="$(sha256_file "${DNS_RUNTIME_ASSETS_OUT}")"
DNS_RUNTIME_ASSETS_SIZE="$(size_bytes "${DNS_RUNTIME_ASSETS_OUT}")"
DROPBEAR_SHA="$(sha256_file "${DROPBEAR_BUNDLE_OUT}")"
DROPBEAR_SIZE="$(size_bytes "${DROPBEAR_BUNDLE_OUT}")"
TAILSCALE_SHA="$(sha256_file "${TAILSCALE_BUNDLE_OUT}")"
TAILSCALE_SIZE="$(size_bytes "${TAILSCALE_BUNDLE_OUT}")"
TRAIN_BOT_SHA=""
TRAIN_BOT_SIZE=""
SATIKSME_BOT_SHA=""
SATIKSME_BOT_SIZE=""
SITE_NOTIFIER_SHA=""
SITE_NOTIFIER_SIZE=""
SUBSCRIPTION_BOT_SHA=""
SUBSCRIPTION_BOT_SIZE=""
if (( INCLUDE_TRAIN_BOT_BUNDLE == 1 )); then
  TRAIN_BOT_SHA="$(sha256_file "${TRAIN_BOT_BUNDLE_OUT}")"
  TRAIN_BOT_SIZE="$(size_bytes "${TRAIN_BOT_BUNDLE_OUT}")"
fi
if (( INCLUDE_SATIKSME_BOT_BUNDLE == 1 )); then
  SATIKSME_BOT_SHA="$(sha256_file "${SATIKSME_BOT_BUNDLE_OUT}")"
  SATIKSME_BOT_SIZE="$(size_bytes "${SATIKSME_BOT_BUNDLE_OUT}")"
fi
if (( INCLUDE_SITE_NOTIFIER_BUNDLE == 1 )); then
  SITE_NOTIFIER_SHA="$(sha256_file "${SITE_NOTIFIER_BUNDLE_OUT}")"
  SITE_NOTIFIER_SIZE="$(size_bytes "${SITE_NOTIFIER_BUNDLE_OUT}")"
fi
if (( INCLUDE_SUBSCRIPTION_BOT_BUNDLE == 1 )); then
  SUBSCRIPTION_BOT_SHA="$(sha256_file "${SUBSCRIPTION_BOT_BUNDLE_OUT}")"
  SUBSCRIPTION_BOT_SIZE="$(size_bytes "${SUBSCRIPTION_BOT_BUNDLE_OUT}")"
fi

MANIFEST_PATH="${OUT_DIR}/runtime-manifest.json"
export RUNTIME_INPUTS_JSON="${OUT_DIR}/resolved-inputs.json"
export RUNTIME_MANIFEST_JSON="${MANIFEST_PATH}"
export INCLUDE_TRAIN_BOT_BUNDLE INCLUDE_SATIKSME_BOT_BUNDLE INCLUDE_SITE_NOTIFIER_BUNDLE INCLUDE_SUBSCRIPTION_BOT_BUNDLE
export ROOTFS_NAME DROPBEAR_BUNDLE_NAME TAILSCALE_BUNDLE_NAME
export DNS_RUNTIME_ASSETS_NAME DNS_RUNTIME_ASSETS_SHA DNS_RUNTIME_ASSETS_SIZE
export TRAIN_BOT_BUNDLE_NAME SATIKSME_BOT_BUNDLE_NAME SITE_NOTIFIER_BUNDLE_NAME SUBSCRIPTION_BOT_BUNDLE_NAME
export ROOTFS_SHA ROOTFS_SIZE DROPBEAR_SHA DROPBEAR_SIZE TAILSCALE_SHA TAILSCALE_SIZE
export TRAIN_BOT_SHA TRAIN_BOT_SIZE SATIKSME_BOT_SHA SATIKSME_BOT_SIZE SITE_NOTIFIER_SHA SITE_NOTIFIER_SIZE SUBSCRIPTION_BOT_SHA SUBSCRIPTION_BOT_SIZE
export MANIFEST_VERSION
export RESOLVED_ROOTFS_TARBALL="${ROOTFS_TARBALL}"
export RESOLVED_DNS_RUNTIME_ASSET_SOURCE_ROOT="${DNS_RUNTIME_ASSET_SOURCE_ROOT}"
export RESOLVED_DROPBEAR_ARTIFACT_DIR="${DROPBEAR_ARTIFACT_DIR}"
export RESOLVED_TAILSCALE_BUNDLE="${TAILSCALE_BUNDLE}"
export RESOLVED_TRAIN_BOT_BUNDLE="${TRAIN_BOT_BUNDLE}"
export RESOLVED_SATIKSME_BOT_BUNDLE="${SATIKSME_BOT_BUNDLE}"
export RESOLVED_SITE_NOTIFIER_BUNDLE="${SITE_NOTIFIER_BUNDLE}"
export RESOLVED_SUBSCRIPTION_BOT_BUNDLE="${SUBSCRIPTION_BOT_BUNDLE}"
python3 - <<'PY'
import json
import os
from pathlib import Path

artifacts = [
    {
        "id": "adguardhome-rootfs",
        "url": f"/data/local/pixel-stack/conf/runtime/artifacts/{os.environ['ROOTFS_NAME']}",
        "sha256": os.environ["ROOTFS_SHA"],
        "fileName": os.environ["ROOTFS_NAME"],
        "sizeBytes": int(os.environ["ROOTFS_SIZE"]),
        "required": True,
    },
    {
        "id": "dns-runtime-assets",
        "url": f"/data/local/pixel-stack/conf/runtime/artifacts/{os.environ['DNS_RUNTIME_ASSETS_NAME']}",
        "sha256": os.environ["DNS_RUNTIME_ASSETS_SHA"],
        "fileName": os.environ["DNS_RUNTIME_ASSETS_NAME"],
        "sizeBytes": int(os.environ["DNS_RUNTIME_ASSETS_SIZE"]),
        "required": True,
    },
    {
        "id": "dropbear-bundle",
        "url": f"/data/local/pixel-stack/conf/runtime/artifacts/{os.environ['DROPBEAR_BUNDLE_NAME']}",
        "sha256": os.environ["DROPBEAR_SHA"],
        "fileName": os.environ["DROPBEAR_BUNDLE_NAME"],
        "sizeBytes": int(os.environ["DROPBEAR_SIZE"]),
        "required": True,
    },
    {
        "id": "tailscale-bundle",
        "url": f"/data/local/pixel-stack/conf/runtime/artifacts/{os.environ['TAILSCALE_BUNDLE_NAME']}",
        "sha256": os.environ["TAILSCALE_SHA"],
        "fileName": os.environ["TAILSCALE_BUNDLE_NAME"],
        "sizeBytes": int(os.environ["TAILSCALE_SIZE"]),
        "required": True,
    },
]

if os.environ["INCLUDE_TRAIN_BOT_BUNDLE"] == "1":
    artifacts.append({
        "id": "train-bot-bundle",
        "url": f"/data/local/pixel-stack/conf/runtime/artifacts/{os.environ['TRAIN_BOT_BUNDLE_NAME']}",
        "sha256": os.environ["TRAIN_BOT_SHA"],
        "fileName": os.environ["TRAIN_BOT_BUNDLE_NAME"],
        "sizeBytes": int(os.environ["TRAIN_BOT_SIZE"]),
        "required": True,
    })

if os.environ["INCLUDE_SATIKSME_BOT_BUNDLE"] == "1":
    artifacts.append({
        "id": "satiksme-bot-bundle",
        "url": f"/data/local/pixel-stack/conf/runtime/artifacts/{os.environ['SATIKSME_BOT_BUNDLE_NAME']}",
        "sha256": os.environ["SATIKSME_BOT_SHA"],
        "fileName": os.environ["SATIKSME_BOT_BUNDLE_NAME"],
        "sizeBytes": int(os.environ["SATIKSME_BOT_SIZE"]),
        "required": True,
    })

if os.environ["INCLUDE_SITE_NOTIFIER_BUNDLE"] == "1":
    artifacts.append({
        "id": "site-notifier-bundle",
        "url": f"/data/local/pixel-stack/conf/runtime/artifacts/{os.environ['SITE_NOTIFIER_BUNDLE_NAME']}",
        "sha256": os.environ["SITE_NOTIFIER_SHA"],
        "fileName": os.environ["SITE_NOTIFIER_BUNDLE_NAME"],
        "sizeBytes": int(os.environ["SITE_NOTIFIER_SIZE"]),
        "required": True,
    })

if os.environ["INCLUDE_SUBSCRIPTION_BOT_BUNDLE"] == "1":
    artifacts.append({
        "id": "subscription-bot-bundle",
        "url": f"/data/local/pixel-stack/conf/runtime/artifacts/{os.environ['SUBSCRIPTION_BOT_BUNDLE_NAME']}",
        "sha256": os.environ["SUBSCRIPTION_BOT_SHA"],
        "fileName": os.environ["SUBSCRIPTION_BOT_BUNDLE_NAME"],
        "sizeBytes": int(os.environ["SUBSCRIPTION_BOT_SIZE"]),
        "required": True,
    })

manifest = {
    "schema": 1,
    "manifestVersion": os.environ["MANIFEST_VERSION"],
    "signatureSchema": "none",
    "artifacts": artifacts,
}

Path(os.environ["RUNTIME_MANIFEST_JSON"]).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

payload = {
    "platformOnly": (
        os.environ["INCLUDE_TRAIN_BOT_BUNDLE"] == "0"
        and os.environ["INCLUDE_SATIKSME_BOT_BUNDLE"] == "0"
        and os.environ["INCLUDE_SITE_NOTIFIER_BUNDLE"] == "0"
        and os.environ["INCLUDE_SUBSCRIPTION_BOT_BUNDLE"] == "0"
    ),
    "rootfsTarball": os.environ["RESOLVED_ROOTFS_TARBALL"],
    "dnsRuntimeAssetSourceRoot": os.environ["RESOLVED_DNS_RUNTIME_ASSET_SOURCE_ROOT"],
    "dropbearArtifactDir": os.environ["RESOLVED_DROPBEAR_ARTIFACT_DIR"],
    "tailscaleBundle": os.environ["RESOLVED_TAILSCALE_BUNDLE"],
    "trainBotBundle": os.environ["RESOLVED_TRAIN_BOT_BUNDLE"] or None,
    "satiksmeBotBundle": os.environ["RESOLVED_SATIKSME_BOT_BUNDLE"] or None,
    "siteNotifierBundle": os.environ["RESOLVED_SITE_NOTIFIER_BUNDLE"] or None,
    "subscriptionBotBundle": os.environ["RESOLVED_SUBSCRIPTION_BOT_BUNDLE"] or None,
}
Path(os.environ["RUNTIME_INPUTS_JSON"]).write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY

cat <<EOF_SUMMARY
Runtime bundle ready:
  ${OUT_DIR}

Resolved inputs:
  rootfs: ${ROOTFS_TARBALL}
  dns-runtime-assets: ${DNS_RUNTIME_ASSET_SOURCE_ROOT}
  dropbear: ${DROPBEAR_ARTIFACT_DIR}
  tailscale: ${TAILSCALE_BUNDLE}
  train-bot: ${TRAIN_BOT_BUNDLE}
  satiksme-bot: ${SATIKSME_BOT_BUNDLE}
  site-notifier: ${SITE_NOTIFIER_BUNDLE}
  subscription-bot: ${SUBSCRIPTION_BOT_BUNDLE}

Stage on device with:
  bash orchestrator/scripts/android/deploy_orchestrator_apk.sh --runtime-bundle-dir "${OUT_DIR}" --action bootstrap
EOF_SUMMARY
