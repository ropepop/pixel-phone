#!/system/bin/sh
set -eu
exec "$(dirname "$0")/pixel-runtime-cleanup" pixel-management-health.sh "$@"
