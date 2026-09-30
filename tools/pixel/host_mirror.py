#!/usr/bin/env python3
"""Pixel wrapper for the shared local-first host mirror helper."""

from __future__ import annotations

import pathlib
import os
import sys


script = pathlib.Path(__file__).resolve().parents[3] / "ops" / "tools" / "arbuzas" / "host-mirror.sh"
if not script.exists():
    raise SystemExit(f"shared host mirror helper not found: {script}")

os.execv(str(script), [str(script), *sys.argv[1:]])
