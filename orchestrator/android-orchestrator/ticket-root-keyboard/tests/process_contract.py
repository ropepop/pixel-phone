#!/usr/bin/env python3
"""Run only inside a disposable Linux container, with no /system mount.

python3 tests/process_contract.py /path/to/rust-helper [/path/to/old-c-helper]

Exercises real processes, pipes, deadlines and parent death using harmless
stand-ins at Android's command paths. No production test flag or device input.
"""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time


STUB = r'''#!/usr/bin/python3
import json, os, sys, time
from pathlib import Path

command = Path(sys.argv[0]).name
with open(os.environ["KEYBOARD_TEST_LOG"], "a") as log:
    log.write(json.dumps({"pid": os.getpid(), "command": command, "args": sys.argv[1:]}) + "\n")
mode = os.environ.get("KEYBOARD_TEST_MODE", "ok")
stage = "dumpsys" if command == "dumpsys" else ("keys" if sys.argv[1] == "keyevent" else "tap")
if mode == stage + "_timeout":
    time.sleep(10)
if mode == stage + "_failed":
    sys.exit(7)
if command == "dumpsys":
    if mode == "visible":
        print("mInputShown=true mImeWindowVis=2")
    elif mode == "hidden_override":
        print("mInputShown=true mImeWindowVis=0")
    elif mode == "empty":
        pass
    elif mode == "split_visible":
        os.write(1, b"x" * 4090 + b"mInputSh")
        time.sleep(0.03)
        os.write(1, b"own=true mImeWindowVis=2")
    elif mode == "split_hidden":
        os.write(1, b"mInputShown=true" + b"x" * 4080 + b"mImeWind")
        time.sleep(0.03)
        os.write(1, b"owVis=0")
    else:
        print("mInputShown=false mImeWindowVis=0")
'''


def stopped(pid):
    try:
        return Path(f"/proc/{pid}/stat").read_text().split(") ", 1)[1][0] == "Z"
    except FileNotFoundError:
        return True


def entries(log):
    return [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []


def check(binary, directory):
    log = directory / "commands.jsonl"
    args = ["--input-x", "123", "--input-y", "456"]
    tap = ["input", ["tap", "123", "456"]]
    ime = ["dumpsys", ["input_method"]]

    def keys(digits):
        return ["input", ["keyevent", "--delay", "20", "KEYCODE_MOVE_END"]
                + ["KEYCODE_DEL"] * 8 + ["KEYCODE_" + digit for digit in digits]]

    cases = [
        ("normal", args, b"12\n", "ok", 0, [tap, ime, keys("12")]),
        ("all-digits-low", args, b"01234567\n", "ok", 0, [tap, ime, keys("01234567")]),
        ("all-digits-high", args, b"89", "ok", 0, [tap, ime, keys("89")]),
        ("open-then-focus", ["--open-x", "9", "--open-y", "8"] + args,
         b"12\r\n", "ok", 0, [["input", ["tap", "9", "8"]], tap, ime, keys("12")]),
        ("first-line", args, b"12\rjunk", "ok", 0, [tap, ime, keys("12")]),
        ("first-c-string", args, b"12\x00junk", "ok", 0, [tap, ime, keys("12")]),
        ("visible-refusal", args, b"12", "visible", 47, [tap, ime]),
        ("split-visible-refusal", args, b"12", "split_visible", 47, [tap, ime]),
        ("hidden-overrides-shown", args, b"12", "hidden_override", 0, [tap, ime, keys("12")]),
        ("split-hidden-overrides-shown", args, b"12", "split_hidden", 0, [tap, ime, keys("12")]),
        ("empty-dump", args, b"12", "empty", 0, [tap, ime, keys("12")]),
        ("focus-failure", args, b"12", "tap_failed", 44, [tap]),
        ("open-failure", ["--open-x", "9", "--open-y", "8"] + args,
         b"12", "tap_failed", 45, [["input", ["tap", "9", "8"]]]),
        ("dump-failure", args, b"12", "dumpsys_failed", 46, [tap, ime]),
        ("keys-failure", args, b"12", "keys_failed", 51, [tap, ime, keys("12")]),
        ("focus-timeout", args, b"12", "tap_timeout", 54, [tap]),
        ("dump-timeout", args, b"12", "dumpsys_timeout", 54, [tap, ime]),
        ("keys-timeout", args, b"12", "keys_timeout", 54, [tap, ime, keys("12")]),
        ("missing-coordinates", [], b"12", "ok", 40, []),
        ("partial-open", ["--open-x", "1"] + args, b"12", "ok", 40, []),
        ("unknown-arg", args + ["--surprise"], b"12", "ok", 40, []),
        ("missing-value", args + ["--input-x"], b"12", "ok", 40, []),
    ]
    for value in ("-1", "10001", "1x", "", " ", "1 ", "9999999999999999999999999999"):
        cases.append(("invalid-coordinate", ["--input-x", value, "--input-y", "0"],
                      b"12", "ok", 40, []))
    for value in (b"", b"1", b"123456789", b" 12", b"12x", b"\n12", b"\xff12"):
        cases.append(("invalid-digits", args, value, "ok", 41, []))
    for name, argv, stdin, mode, code, expected in cases:
        log.unlink(missing_ok=True)
        env = dict(os.environ, KEYBOARD_TEST_LOG=str(log), KEYBOARD_TEST_MODE=mode)
        start = time.monotonic()
        result = subprocess.run([str(binary), *argv], input=stdin, capture_output=True, env=env, timeout=4)
        elapsed = time.monotonic() - start
        observed = entries(log)
        assert result.returncode == code, (name, result.returncode, code)
        assert result.stdout == result.stderr == b"", (name, "unexpected output")
        assert [[row["command"], row["args"]] for row in observed] == expected, name
        assert all(stopped(row["pid"]) for row in observed), (name, "child still running")
        if code == 54:
            assert 2.5 <= elapsed < 4, (name, elapsed)
        if code == 0:
            assert elapsed >= 0.39, (name, "settling interval skipped", elapsed)

    # Cancellation must terminate both the helper and its current Android command.
    log.unlink(missing_ok=True)
    env = dict(os.environ, KEYBOARD_TEST_LOG=str(log), KEYBOARD_TEST_MODE="tap_timeout")
    owner_code = """
import subprocess, sys, time
p = subprocess.Popen(sys.argv[1:], stdin=subprocess.PIPE)
p.stdin.write(b'12\\n'); p.stdin.close()
print(p.pid, flush=True)
time.sleep(10)
"""
    owner = subprocess.Popen([sys.executable, "-c", owner_code, str(binary), *args],
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
    helper = int(owner.stdout.readline())
    try:
        deadline = time.monotonic() + 2
        while not entries(log) and time.monotonic() < deadline:
            time.sleep(0.01)
        observed = entries(log)
        assert observed, "owned Android command did not start"
        owner.kill()
        owner.wait(timeout=1)
        deadline = time.monotonic() + 1
        while not (stopped(helper) and all(stopped(row["pid"]) for row in observed)) and time.monotonic() < deadline:
            time.sleep(0.01)
        assert stopped(helper), "helper survived owner death"
        assert all(stopped(row["pid"]) for row in observed), "Android command survived owner death"
    finally:
        if owner.poll() is None:
            owner.kill()
            owner.wait()
    print(f"PASS {binary.name}: {len(cases)} command cases plus owner/child cancellation", flush=True)


if __name__ == "__main__":
    if sys.platform != "linux" or not Path("/.dockerenv").exists() or Path("/system").exists():
        raise SystemExit("Requires a disposable Linux Docker container with no /system mount")
    if len(sys.argv) < 2:
        raise SystemExit("Supply Rust executable, optionally followed by the old C executable")
    binaries = [Path(value).resolve(strict=True) for value in sys.argv[1:]]
    try:
        Path("/system/bin").mkdir(parents=True)
        for command in ("input", "dumpsys"):
            path = Path("/system/bin") / command
            path.write_text(STUB)
            path.chmod(0o755)
        with tempfile.TemporaryDirectory(prefix="keyboard-contract-") as temporary:
            for binary in binaries:
                check(binary, Path(temporary))
    finally:
        shutil.rmtree("/system")
