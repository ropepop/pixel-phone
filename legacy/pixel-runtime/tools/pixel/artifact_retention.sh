#!/usr/bin/env bash

if [[ -n "${PIXEL_ARTIFACT_RETENTION_SH_LOADED:-}" ]]; then
  return 0
fi
PIXEL_ARTIFACT_RETENTION_SH_LOADED=1

PIXEL_ARTIFACT_RETENTION_HOURS="${PIXEL_ARTIFACT_RETENTION_HOURS:-72}"

pixel_artifact_retention_warn() {
  printf '[%s] artifact retention: %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*" >&2
}

pixel_artifact_retention_prune() {
  local retention_hours="${1:-${PIXEL_ARTIFACT_RETENTION_HOURS}}"
  shift || true

  if (( $# == 0 )); then
    return 0
  fi

  if ! command -v python3 >/dev/null 2>&1; then
    pixel_artifact_retention_warn "python3 is unavailable; skipping stale artifact cleanup"
    return 0
  fi

  if ! python3 - "${retention_hours}" "$@" <<'PY'
import datetime as dt
import errno
import os
import stat
import sys
import time


def warn(message: str) -> None:
    timestamp = dt.datetime.now().astimezone().strftime("%Y-%m-%dT%H:%M:%S%z")
    print(f"[{timestamp}] artifact retention: {message}", file=sys.stderr)


def path_label(path: str) -> str:
    return path or "<empty>"


try:
    retention_hours = float(sys.argv[1])
except (IndexError, ValueError):
    warn("invalid retention window; skipping stale artifact cleanup")
    raise SystemExit(0)

roots = [root for root in sys.argv[2:] if root]
if not roots:
    raise SystemExit(0)

cutoff = time.time() - (retention_hours * 3600)

for root in roots:
    if not os.path.lexists(root):
        continue

    def onerror(err: OSError) -> None:
        target = getattr(err, "filename", root)
        warn(f"unable to inspect {path_label(target)}: {err.strerror}")

    stale_dirs = []
    for current_root, _, _ in os.walk(root, topdown=False, followlinks=False, onerror=onerror):
        if os.path.normpath(current_root) == os.path.normpath(root):
            continue
        try:
            current_root_st = os.lstat(current_root)
        except FileNotFoundError:
            continue
        except OSError as exc:
            warn(f"unable to inspect {path_label(current_root)}: {exc.strerror}")
            continue
        if stat.S_ISDIR(current_root_st.st_mode) and current_root_st.st_mtime < cutoff:
            stale_dirs.append(current_root)

    for current_root, dirnames, filenames in os.walk(root, topdown=False, followlinks=False, onerror=onerror):
        for name in filenames:
            path = os.path.join(current_root, name)
            try:
                st = os.lstat(path)
            except FileNotFoundError:
                continue
            except OSError as exc:
                warn(f"unable to inspect {path_label(path)}: {exc.strerror}")
                continue
            if st.st_mtime >= cutoff:
                continue
            try:
                os.unlink(path)
            except FileNotFoundError:
                continue
            except OSError as exc:
                warn(f"failed to remove stale path {path_label(path)}: {exc.strerror}")

        for name in dirnames:
            path = os.path.join(current_root, name)
            try:
                st = os.lstat(path)
            except FileNotFoundError:
                continue
            except OSError as exc:
                warn(f"unable to inspect {path_label(path)}: {exc.strerror}")
                continue

            if stat.S_ISDIR(st.st_mode) and not os.path.islink(path):
                continue

            if st.st_mtime >= cutoff:
                continue
            try:
                os.unlink(path)
            except FileNotFoundError:
                continue
            except OSError as exc:
                warn(f"failed to remove stale path {path_label(path)}: {exc.strerror}")

    for path in stale_dirs:
        try:
            os.rmdir(path)
        except FileNotFoundError:
            continue
        except OSError as exc:
            if exc.errno not in (errno.ENOTEMPTY, errno.EEXIST):
                warn(f"failed to remove stale path {path_label(path)}: {exc.strerror}")
PY
  then
    pixel_artifact_retention_warn "stale artifact cleanup hit an unexpected error; continuing"
  fi

  return 0
}
