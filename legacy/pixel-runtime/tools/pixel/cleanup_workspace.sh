#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck source=./artifact_retention.sh
source "${SCRIPT_DIR}/artifact_retention.sh"

MODE="prune"
RETENTION_HOURS="${PIXEL_ARTIFACT_RETENTION_HOURS:-72}"

usage() {
  cat <<'USAGE'
Usage: cleanup_workspace.sh [--dry-run | --check]

Conservatively manages generated workspace garbage under approved scratch,
output, and artifact roots.

Options:
  --dry-run   Report stale candidates and tracked-path violations without deleting
  --check     Fail if stale garbage exists or tracked files appear in managed roots
  -h, --help  Show help
USAGE
}

log() {
  printf '[%s] cleanup_workspace: %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"
}

while (( $# > 0 )); do
  case "$1" in
    --dry-run)
      MODE="dry-run"
      ;;
    --check)
      MODE="check"
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unsupported argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

if ! command -v git >/dev/null 2>&1; then
  echo "cleanup_workspace.sh requires git" >&2
  exit 2
fi

if ! git -C "${REPO_ROOT}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "cleanup_workspace.sh must run inside a git repository" >&2
  exit 2
fi

managed_roots=(
  "${REPO_ROOT}/.codex-tmp"
  "${REPO_ROOT}/output"
  "${REPO_ROOT}/.artifacts"
  "${REPO_ROOT}/orchestrator/.artifacts"
)

if [[ -d "${REPO_ROOT}/workloads" ]]; then
  for workload_root in "${REPO_ROOT}/workloads"/*; do
    [[ -d "${workload_root}" ]] || continue
    managed_roots+=("${workload_root}/.artifacts")
    managed_roots+=("${workload_root}/output")
  done
fi

protected_roots=(
  "${REPO_ROOT}/ops/evidence"
  "${REPO_ROOT}/state/browser-use"
)

roots_file="$(mktemp)"
protected_file="$(mktemp)"
scan_file="$(mktemp)"
cleanup_tmp() {
  rm -f "${roots_file}" "${protected_file}" "${scan_file}" 2>/dev/null || true
}
trap cleanup_tmp EXIT

printf '%s\n' "${managed_roots[@]}" > "${roots_file}"
printf '%s\n' "${protected_roots[@]}" > "${protected_file}"

python3 - "${REPO_ROOT}" "${RETENTION_HOURS}" "${roots_file}" "${protected_file}" > "${scan_file}" <<'PY'
import os
import subprocess
import sys
import time


repo_root = os.path.abspath(sys.argv[1])
retention_hours = float(sys.argv[2])
roots_file = sys.argv[3]
protected_file = sys.argv[4]
cutoff = time.time() - (retention_hours * 3600)


def load_lines(path: str) -> list[str]:
    with open(path, "r", encoding="utf-8") as handle:
        return [os.path.abspath(line.strip()) for line in handle if line.strip()]


managed_roots = load_lines(roots_file)
protected_roots = load_lines(protected_file)


def under_any(path: str, roots: list[str]) -> bool:
    normalized = os.path.abspath(path)
    for root in roots:
        try:
            if os.path.commonpath([normalized, root]) == root:
                return True
        except ValueError:
            continue
    return False


tracked_paths: set[str] = set()
for root in managed_roots:
    rel = os.path.relpath(root, repo_root)
    result = subprocess.run(
        ["git", "-C", repo_root, "ls-files", "--", rel],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        continue
    for line in result.stdout.splitlines():
        line = line.strip()
        if not line:
            continue
        tracked = os.path.abspath(os.path.join(repo_root, line))
        if under_any(tracked, protected_roots):
            continue
        tracked_paths.add(tracked)

candidate_files: dict[str, int] = {}
candidate_dirs: set[str] = set()

for root in managed_roots:
    if not os.path.lexists(root):
        continue
    if os.path.islink(root) or not os.path.isdir(root):
        continue
    for current_root, dirnames, filenames in os.walk(root, topdown=False, followlinks=False):
        current_root = os.path.abspath(current_root)
        if under_any(current_root, protected_roots):
            continue

        for name in filenames:
            path = os.path.join(current_root, name)
            if under_any(path, protected_roots):
                continue
            try:
                st = os.lstat(path)
            except FileNotFoundError:
                continue
            if st.st_mtime >= cutoff:
                continue
            candidate_files[path] = st.st_size

        for name in dirnames:
            path = os.path.join(current_root, name)
            if under_any(path, protected_roots):
                continue
            try:
                st = os.lstat(path)
            except FileNotFoundError:
                continue
            if os.path.islink(path) and st.st_mtime < cutoff:
                candidate_files[path] = st.st_size

        if current_root == root:
            continue

        try:
            root_st = os.lstat(current_root)
        except FileNotFoundError:
            continue
        if root_st.st_mtime >= cutoff or os.path.islink(current_root):
            continue

        removable = True
        try:
            entries = list(os.scandir(current_root))
        except FileNotFoundError:
            continue

        for entry in entries:
            path = os.path.abspath(entry.path)
            if under_any(path, protected_roots):
                removable = False
                break
            if entry.is_dir(follow_symlinks=False):
                if os.path.islink(path):
                    if path in candidate_files:
                        continue
                    removable = False
                    break
                if path in candidate_dirs:
                    continue
                removable = False
                break
            if path in candidate_files:
                continue
            removable = False
            break

        if removable:
            candidate_dirs.add(current_root)

for path in sorted(tracked_paths):
    print(f"TRACKED\t{path}")

for path, size in sorted(candidate_files.items()):
    print(f"CANDIDATE\tfile\t{size}\t{path}")

for path in sorted(candidate_dirs):
    print(f"CANDIDATE\tdir\t0\t{path}")
PY

tracked_count=0
candidate_count=0
candidate_bytes=0

while IFS=$'\t' read -r record_type field_a field_b field_c; do
  [[ -n "${record_type}" ]] || continue
  case "${record_type}" in
    TRACKED)
      tracked_count=$((tracked_count + 1))
      log "tracked_violation path=${field_a}"
      ;;
    CANDIDATE)
      candidate_count=$((candidate_count + 1))
      candidate_bytes=$((candidate_bytes + field_b))
      log "stale_candidate kind=${field_a} bytes=${field_b} path=${field_c}"
      ;;
  esac
done < "${scan_file}"

summary="mode=${MODE} tracked=${tracked_count} stale=${candidate_count} stale_bytes=${candidate_bytes}"

case "${MODE}" in
  dry-run)
    log "${summary}"
    exit 0
    ;;
  check)
    log "${summary}"
    if (( tracked_count > 0 || candidate_count > 0 )); then
      exit 1
    fi
    exit 0
    ;;
esac

if (( tracked_count > 0 )); then
  log "${summary}"
  log "refusing to prune managed roots while tracked-path violations exist"
  exit 1
fi

if (( candidate_count == 0 )); then
  log "${summary}"
  log "no stale generated output found"
  exit 0
fi

pixel_artifact_retention_prune "${RETENTION_HOURS}" "${managed_roots[@]}"

post_scan_file="$(mktemp)"
trap 'rm -f "${roots_file}" "${protected_file}" "${scan_file}" "${post_scan_file}" 2>/dev/null || true' EXIT

python3 - "${REPO_ROOT}" "${RETENTION_HOURS}" "${roots_file}" "${protected_file}" > "${post_scan_file}" <<'PY'
import os
import subprocess
import sys
import time


repo_root = os.path.abspath(sys.argv[1])
retention_hours = float(sys.argv[2])
roots_file = sys.argv[3]
protected_file = sys.argv[4]
cutoff = time.time() - (retention_hours * 3600)


def load_lines(path: str) -> list[str]:
    with open(path, "r", encoding="utf-8") as handle:
        return [os.path.abspath(line.strip()) for line in handle if line.strip()]


managed_roots = load_lines(roots_file)
protected_roots = load_lines(protected_file)


def under_any(path: str, roots: list[str]) -> bool:
    normalized = os.path.abspath(path)
    for root in roots:
        try:
            if os.path.commonpath([normalized, root]) == root:
                return True
        except ValueError:
            continue
    return False


candidate_files: dict[str, int] = {}
candidate_dirs: set[str] = set()

for root in managed_roots:
    if not os.path.lexists(root):
        continue
    if os.path.islink(root) or not os.path.isdir(root):
        continue
    for current_root, dirnames, filenames in os.walk(root, topdown=False, followlinks=False):
        current_root = os.path.abspath(current_root)
        if under_any(current_root, protected_roots):
            continue

        for name in filenames:
            path = os.path.join(current_root, name)
            if under_any(path, protected_roots):
                continue
            try:
                st = os.lstat(path)
            except FileNotFoundError:
                continue
            if st.st_mtime >= cutoff:
                continue
            candidate_files[path] = st.st_size

        for name in dirnames:
            path = os.path.join(current_root, name)
            if under_any(path, protected_roots):
                continue
            try:
                st = os.lstat(path)
            except FileNotFoundError:
                continue
            if os.path.islink(path) and st.st_mtime < cutoff:
                candidate_files[path] = st.st_size

        if current_root == root:
            continue

        try:
            root_st = os.lstat(current_root)
        except FileNotFoundError:
            continue
        if root_st.st_mtime >= cutoff or os.path.islink(current_root):
            continue

        removable = True
        try:
            entries = list(os.scandir(current_root))
        except FileNotFoundError:
            continue

        for entry in entries:
            path = os.path.abspath(entry.path)
            if under_any(path, protected_roots):
                removable = False
                break
            if entry.is_dir(follow_symlinks=False):
                if os.path.islink(path):
                    if path in candidate_files:
                        continue
                    removable = False
                    break
                if path in candidate_dirs:
                    continue
                removable = False
                break
            if path in candidate_files:
                continue
            removable = False
            break

        if removable:
            candidate_dirs.add(current_root)

for path, size in sorted(candidate_files.items()):
    print(f"CANDIDATE\tfile\t{size}\t{path}")

for path in sorted(candidate_dirs):
    print(f"CANDIDATE\tdir\t0\t{path}")
PY

remaining_count=0
remaining_bytes=0
while IFS=$'\t' read -r record_type _kind bytes _path; do
  [[ "${record_type}" == "CANDIDATE" ]] || continue
  remaining_count=$((remaining_count + 1))
  remaining_bytes=$((remaining_bytes + bytes))
done < "${post_scan_file}"

reclaimed_bytes=$((candidate_bytes - remaining_bytes))
log "mode=${MODE} tracked=${tracked_count} stale_before=${candidate_count} stale_after=${remaining_count} reclaimed_bytes=${reclaimed_bytes}"

if (( remaining_count > 0 )); then
  log "stale generated output remains after pruning"
  exit 1
fi

exit 0
