#!/system/bin/sh
exec "${PIXEL_STACK_ROOT:-/data/local/pixel-stack}/bin/pixel-runtime-cleanup" pixel-ticket-start.sh "$@"
