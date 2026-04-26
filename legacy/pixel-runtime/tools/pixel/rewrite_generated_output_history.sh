#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

usage() {
  cat <<'USAGE'
Usage: rewrite_generated_output_history.sh

One-time maintenance command that removes generated output history from git refs.
Run this only from a clean clone or after all local work is committed or backed up.
USAGE
}

if (( $# > 0 )); then
  case "$1" in
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
fi

if ! command -v git-filter-repo >/dev/null 2>&1; then
  echo "Missing required command: git-filter-repo" >&2
  exit 2
fi

if ! git -C "${REPO_ROOT}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "Repository not found at ${REPO_ROOT}" >&2
  exit 2
fi

if [[ -n "$(git -C "${REPO_ROOT}" status --porcelain)" ]]; then
  echo "History rewrite requires a clean worktree. Commit, stash, or back up local changes first." >&2
  exit 1
fi

git -C "${REPO_ROOT}" filter-repo --force \
  --path output \
  --path .playwright-cli \
  --path-glob 'workloads/*/output/**' \
  --invert-paths

cat <<'NEXT_STEPS'
History rewrite complete.

Next steps:
1. Inspect the rewritten refs locally.
2. Force-push the rewritten branches and tags.
3. Have collaborators reclone or hard-reset to the rewritten refs.
NEXT_STEPS
