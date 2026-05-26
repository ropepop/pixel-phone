#!/usr/bin/env python3
"""Pixel wrapper for the shared local-first host mirror helper."""

from __future__ import annotations

import pathlib
import runpy
import sys


script = pathlib.Path(__file__).resolve().parents[3] / "ops" / "tools" / "arbuzas" / "host_mirror.py"
if not script.exists():
    raise SystemExit(f"shared host mirror helper not found: {script}")

sys.argv[0] = str(script)
runpy.run_path(str(script), run_name="__main__")
