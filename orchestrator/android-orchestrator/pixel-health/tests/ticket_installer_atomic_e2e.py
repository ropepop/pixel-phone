#!/usr/bin/env python3
"""Emit an owned Linux regression journey using the actual Kotlin installer wire."""
from pathlib import Path
import re
import sys

wire = Path(sys.argv[1]).read_text()
assert wire.count("/bin/pixel-runtime-cleanup'") == 2
wire = wire.replace('/data/local/pixel-stack', '/proof/stack')
wire, count = re.subn(r"(?m)^cp '[^']*/asset-stage-[^']+'", "cp '/proof/source'", wire)
assert count == 1, 'expected exactly one actual emitted local asset source'
print('''set -eu
mkdir -p /proof/stack/bin /proof/adapters
target=/proof/stack/bin/pixel-runtime-cleanup
owner=
cleanup() { [ -z "$owner" ] || kill "$owner" 2>/dev/null || true; }
trap cleanup EXIT
cp /bin/sleep "$target"
cp /bin/true /proof/source
"$target" 30 & owner=$!
sleep .1
if cp /proof/source "$target" 2>/proof/old-error; then
  echo 'FAIL: old in-place copy unexpectedly succeeded over running Linux ELF' >&2; exit 1
fi
grep -q 'Text file busy' /proof/old-error
echo 'PASS old in-place installer reproduced ETXTBSY'
cat > /proof/installer.sh <<'INSTALLER'
'''+wire+'''
INSTALLER
sh /proof/installer.sh
kill -0 "$owner"
cmp /proof/source "$target"
[ "$(stat -c %a "$target")" = 755 ]
"$target"
[ -z "$(find /proof/stack/bin -name '*.asset.*' -print)" ]
echo 'PASS exact emitted atomic installer replaced running ELF; old process alive/new path executable/mode/stage cleanup'
cat > /proof/adapters/cp <<'ADAPTER'
#!/bin/sh
printf partial > "$2"
exit 9
ADAPTER
chmod 755 /proof/adapters/cp
if PATH=/proof/adapters:$PATH sh /proof/installer.sh; then
  echo 'FAIL partial stage copy was admitted' >&2; exit 1
fi
cmp /proof/source "$target"
[ -z "$(find /proof/stack/bin -name '*.asset.*' -print)" ]
echo 'PASS partial-copy failure retained current executable and removed owned stage'
kill "$owner"; wait "$owner" 2>/dev/null || true; owner=
''')
