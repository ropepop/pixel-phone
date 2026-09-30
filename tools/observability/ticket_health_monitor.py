#!/usr/bin/env python3
"""Launch the repository-owned native, read-only Ticket health monitor."""

import os
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
BINARY = ROOT / "orchestrator/android-orchestrator/pixel-health/target/release/ticket-health-monitor"


def main() -> int:
  try:
    os.execv(str(BINARY), [str(BINARY), *sys.argv[1:]])
  except OSError:
    print(
      "Ticket health monitor native command is unavailable. Build it with:\n"
      f"cargo build --locked --release --manifest-path {ROOT / 'orchestrator/android-orchestrator/pixel-health/Cargo.toml'} "
      "--features ticket-health-monitor --bin ticket-health-monitor",
      file=sys.stderr,
    )
    return 2


if __name__ == "__main__":
  raise SystemExit(main())
