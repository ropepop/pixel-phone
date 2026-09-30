#!/system/bin/sh
# Sole device operator runs this with an exact packaged native executable and
# frozen9d1aa21 shell oracle. Every effect is confined to a newly owned subtree.
set -eu
native=${1:?native executable required}
oracle=${2:?frozen shell oracle required}
fixture=${3:?new helper-owned /data/local/tmp/pixel-cleanup-* subtree required}
case "$fixture" in /data/local/tmp/pixel-cleanup-*) ;; *) exit 2;; esac
case "$fixture" in *[!a-zA-Z0-9_./-]*) exit 2;; esac
test ! -e "$fixture"
test -x "$native"
test "$(sha256sum "$oracle" | cut -d ' ' -f 1)" = 044272114774d47814c88fa193bfdba84f478fdc7e2ee894ffa8e8085a8cbfe8
mkdir -m 755 "$fixture"
trap 'rm -rf "$fixture"' EXIT
work=$fixture/work
for journey in normal protected dry root-before root-after partial-move recovery-missing recovery-success; do
 for owner in old native; do
  rm -rf "$work"
  mkdir -p "$work/control/bin" "$work/db" "$work/stack/vpn/logs" "$work/stack/run/orchestrator-action-results" "$work/cache" "$work/termux" "$work/tmp"
  printf '\n' > "$work/protected"
  printf 'synthetic-original-database\n' > "$work/db/history"
  printf 'synthetic-original-wal\n' > "$work/db/history-wal"
  printf 'synthetic-original-shm\n' > "$work/db/history-shm"
  printf '0123456789abcdef' > "$work/stack/vpn/logs/tailscaled.log"
  printf 'extra rotation' > "$work/stack/vpn/logs/tailscaled.log.2"
  printf 'unowned log preserved' > "$work/stack/vpn/logs/unowned.log"
  printf 'old synthetic receipt' > "$work/stack/run/orchestrator-action-results/old.json"
  touch -t 202001010101 "$work/stack/run/orchestrator-action-results/old.json"
  printf 0 > "$work/control/checks"
  cat > "$work/control/recheck.sh" <<EOF
#!/system/bin/sh
n=\$(cat '$work/control/checks'); n=\$((n + 1)); printf '%s' "\$n" > '$work/control/checks'
test '$journey' != root-before || exit 1
if [ '$journey' = root-after ] && [ "\$n" -eq 2 ]; then exit 1; fi
exit 0
EOF
  # No force-stop request reaches Android or an installed package.
  printf '#!/system/bin/sh\nexit 0\n' > "$work/control/bin/am"
  chmod 755 "$work/control/bin/am"
  case "$journey" in
   protected) printf '%s\n' "$work/db/history" "$work/stack/vpn/logs/tailscaled.log" > "$work/protected";;
   partial-move)
    printf 0 > "$work/control/moves"
    cat > "$work/control/bin/mv" <<EOF
#!/system/bin/sh
case "\$*" in *pixel-cleanup-backup*)
 n=\$(cat '$work/control/moves'); n=\$((n + 1)); printf '%s' "\$n" > '$work/control/moves'
 [ "\$n" -ne 2 ] || exit 1;; esac
exec /system/bin/mv "\$@"
EOF
    chmod 755 "$work/control/bin/mv";;
   recovery-*)
    for suffix in '' -wal -shm; do
     printf 'synthetic-backup%s' "$suffix" > "$work/db/history.pixel-cleanup-backup$suffix"
     [ "$journey" != recovery-missing ] || rm "$work/db/history$suffix"
    done;;
  esac
  set -- --frequent --protected-list "$work/protected" --stack-base "$work/stack" --orchestrator-cache "$work/cache" --termux-home "$work/termux" --local-tmp "$work/tmp" --superuser-log-db "$work/db/history" --superuser-package invalid.pixelcleanup.fixture --root-recheck-command "sh '$work/control/recheck.sh'" --superuser-log-max-bytes 8 --known-log-max-bytes 8 --stack-log-max-bytes 16
  [ "$journey" != dry ] || set -- "$@" --dry-run
  if [ "$owner" = old ]; then
   PATH="$work/control/bin:$PATH" TMPDIR="$work/control" sh "$oracle" "$@" > "$fixture/$owner.receipts"
  else
   PATH="$work/control/bin:$PATH" TMPDIR="$work/control" "$native" "$@" > "$fixture/$owner.receipts"
  fi
  sort "$fixture/$owner.receipts" > "$fixture/$owner.sorted"
  {
   find "$work" -path "$work/control" -prune -o -type d -print | sort
   find "$work" -path "$work/control" -prune -o -type f -print | sort | while IFS= read -r path; do
    stat -c '%a:%u:%g:%s' "$path"
    sha256sum "$path"
   done
  } > "$fixture/$owner.files"
 done
 cmp "$fixture/old.sorted" "$fixture/native.sorted"
 cmp "$fixture/old.files" "$fixture/native.files"
 case "$journey" in
  root-before) grep -q root_recheck_failed_before_rotation "$fixture/native.receipts";;
  root-after) grep -q root_recheck_failed_rolled_back "$fixture/native.receipts";;
  partial-move) grep -q rotation_prepare_failed_rolled_back "$fixture/native.receipts";;
  recovery-missing) grep -q recovered_interrupted_rotation "$fixture/native.receipts";;
  recovery-success) grep -q reconciled_interrupted_success "$fixture/native.receipts";;
 esac
 printf 'PASS Android cleanup: %s\n' "$journey"
done
printf 'PASS 8 actual Android cleanup old/native receipt and filesystem journeys; synthetic history only; fixture removed on exit\n'
