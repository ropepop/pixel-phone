#!/usr/bin/env python3
"""Actual CLI/filesystem/process comparison with the frozen cleanup owner.

The existing runtime contract owns broad retention/limit coverage. This adds
the migration boundary: exact receipts/files, partial moves, restart and signal
rollback. Every database is a helper-owned synthetic file under TemporaryDirectory.
"""
import hashlib
import os
from pathlib import Path
import signal
import stat
import subprocess
import sys
import tempfile
import time

OLD_SHA = "044272114774d47814c88fa193bfdba84f478fdc7e2ee894ffa8e8085a8cbfe8"
REL = "orchestrator/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-runtime-cleanup.sh"


def write(path, content=b"synthetic cleanup fixture\n", age=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)
    if age is not None:
        os.utime(path, (age, age))


def snapshot(root):
    result = {}
    for p in sorted(root.rglob("*")):
        if p.parts[len(root.parts)] in {"adapters", "control"}:
            continue
        s = p.lstat()
        if p.is_symlink():
            data = ("link", os.readlink(p).replace(str(root), "$ROOT"))
        elif p.is_file():
            data = ("file", s.st_size, hashlib.sha256(p.read_bytes().replace(str(root).encode(), b"$ROOT")).hexdigest())
        else:
            data = ("dir",)
        result[str(p.relative_to(root))] = (stat.S_IMODE(s.st_mode), data)
    return result


def invoke(executable, scenario, root):
    stack, db = root / "stack", root / "superuser/db"
    control, adapters = root / "control", root / "adapters"
    adapters.mkdir(); control.mkdir()
    for name in ["cache", "termux", "local-tmp"]:
        (root / name).mkdir()
    (stack / "logs").mkdir(parents=True)
    protected = root / "protected"
    protected.write_text("\n")
    flags = ["--protected-list", str(protected), "--stack-base", str(stack),
             "--orchestrator-cache", str(root / "cache"), "--termux-home", str(root / "termux"),
             "--local-tmp", str(root / "local-tmp"), "--superuser-log-db", str(db),
             "--superuser-package", "fixture.cleanup", "--superuser-log-max-bytes", "8",
             "--known-log-max-bytes", "8", "--stack-log-max-bytes", "16"]
    old_time = time.time() - 40 * 86400
    write(db, b"original database payload")
    write(Path(str(db) + "-wal"), b"original wal payload")
    write(Path(str(db) + "-shm"), b"original shm payload")
    write(control / "checks", b"0")
    recheck = control / "recheck.sh"
    root_mode = "false" if scenario in {"root-before", "recovery-failed"} else "true"
    recheck.write_text(f'''#!/bin/sh
n=$(cat '{control}/checks'); n=$((n + 1)); printf '%s' "$n" > '{control}/checks'
if [ "$n" -eq 2 ]; then
  {'exit 1' if scenario == 'root-after' else ':'}
  {'printf "%s" "$$" > ' + str(control / 'child-pid') + '; printf ready > ' + str(control / 'ready') + '; exec sleep 30' if scenario.startswith('signal-') or scenario == 'restart' else ':'}
fi
{root_mode}
''')
    flags += ["--root-recheck-command", f"sh '{recheck}'"]
    write(adapters / "am", f"#!/bin/sh\nprintf '%s\\n' \"$*\" >> '{control}/effects'\n".encode())
    if scenario in {"move-failure", "rollback-failure"}:
        write(control / "moves", b"0")
        failing = '[ "$n" -eq 2 ]' if scenario == "move-failure" else '[ "$n" -eq 2 ] || [ "$n" -eq 3 ]'
        write(adapters / "mv", f'''#!/bin/sh
n=$(cat '{control}/moves'); n=$((n + 1)); printf '%s' "$n" > '{control}/moves'
if {failing}; then exit 1; fi
exec /bin/mv "$@"
'''.encode())
    if scenario == "delete-failure":
        write(adapters / "rm", b'#!/bin/sh\ncase "$*" in *pixel-cleanup-backup*) exit 1;; esac\nexec /bin/rm "$@"\n')
    for adapter in adapters.iterdir(): adapter.chmod(0o755)
    if scenario.startswith("recovery"):
        for suffix in ["", "-wal", "-shm"]:
            write(Path(str(db) + ".pixel-cleanup-backup" + suffix), ("backup" + suffix).encode())
            if scenario == "recovery-missing": Path(str(db) + suffix).unlink()
    if scenario == "protected-db": protected.write_text(str(db) + "\n")
    if scenario == "ceiling":
        write(db, b"12345678"); Path(str(db) + "-wal").unlink(); Path(str(db) + "-shm").unlink()
    if scenario == "missing":
        for suffix in ["", "-wal", "-shm"]: Path(str(db) + suffix).unlink()
    if scenario in {"logs", "logs-dry", "protected-logs", "unknown-logs", "zero-limit"}:
        for name in ["vpn/logs/tailscaled.log", "ssh/logs/dropbear.log"]:
            for suffix in ["", ".1", ".2", ".3", ".old", ".bak-fixture"]:
                write(stack / (name + suffix), b"0123456789abcdef", old_time)
        if scenario == "protected-logs":
            protected.write_text(str(stack / "vpn/logs/tailscaled.log") + "\n" + str(stack / "vpn/logs/tailscaled.log.1") + "\n")
        if scenario == "unknown-logs": write(stack / "logs/unknown.log", b"do not touch", old_time)
        if scenario == "zero-limit": flags += ["--known-log-max-bytes", "0", "--stack-log-max-bytes", "0"]
    if scenario in {"artifacts", "artifacts-dry", "unicode-retention"}:
        write(root / "local-tmp/pixel-orchestrator-debug.apk", age=old_time)
        write(root / "local-tmp/unknown.bin", age=old_time)
        write(stack / "run/orchestrator-action-results/receipt.json", age=old_time)
        write(stack / "conf/runtime/artifacts/sha256/protected", age=old_time)
        protected.write_text(str(stack / "conf/runtime/artifacts/sha256/protected") + "\n")
        base = root / "termux/telegram-train-app/workloads/site-notifications/.artifacts"
        stamps = ["20260101T010101Z", "20260201T010101Z", "20260301T010101Z"]
        if scenario == "unicode-retention": stamps.insert(0, "éééééééé")
        for i, stamp in enumerate(stamps):
            write(base / f"site-notifier/site-notifier-bundle-site-notifier-{stamp}.tar", age=old_time + i * 60)
            write(base / f"site-notifier/source-site-notifier-{stamp}.tar", age=old_time)
            path = base / f"component-releases/site_notifier-site-notifier-{stamp}"
            path.mkdir(parents=True); os.utime(path, (old_time, old_time))
    if scenario.endswith("dry"): flags.append("--dry-run")
    if scenario == "byte-paths":
        path = root / "local-tmp" / os.fsdecode(b"pixel-orchestrator-\xff-debug.apk")
        write(path, age=old_time)
        protected.write_bytes(os.fsencode(path) + b"\n")
        write(root / "local-tmp" / os.fsdecode(b"pixel-orchestrator-\xfe-debug.apk"), age=old_time)
        write(root / "local-tmp/pixel-orchestrator-\ufffd-debug.apk", age=old_time)
    if scenario not in {"artifacts", "artifacts-dry", "unicode-retention", "byte-paths", "dns-idle", "dns-active"}: flags.append("--frequent")
    dns = None
    if scenario.startswith("dns-"):
        flags.append("--retired-dns")
        write(stack / "chroots/adguardhome/var/log/adguardhome/adguardhome.log", age=old_time)
        if scenario == "dns-active":
            dns = subprocess.Popen(["bash", "-c", "exec -a AdGuardHome sleep 30"], start_new_session=True)
            time.sleep(.1)
    before_db = [p.read_bytes() for p in [db, Path(str(db) + "-wal"), Path(str(db) + "-shm")] if p.exists()]
    env = dict(os.environ, PATH=str(adapters) + ":" + os.environ["PATH"])
    command = (["sh", str(executable)] if executable.suffix == ".sh" else [str(executable)]) + flags
    child = None
    try:
        child = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, start_new_session=True)
        if scenario.startswith("signal-") or scenario == "restart":
            deadline = time.monotonic() + 10
            while not (control / "ready").exists():
                assert child.poll() is None and time.monotonic() < deadline, "signal barrier was never reached"
                time.sleep(.01)
            sig = int(scenario.split("-")[1]) if scenario.startswith("signal-") else signal.SIGKILL
            os.killpg(child.pid, sig)
        stdout, stderr = child.communicate(timeout=20)
        if scenario == "restart":
            assert child.returncode == -signal.SIGKILL
            assert list(db.parent.glob("*.pixel-cleanup-backup*")), "kill barrier did not leave a pending rotation"
            child = subprocess.Popen(command + ["--dry-run"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, start_new_session=True)
            stdout, stderr = child.communicate(timeout=20)
    finally:
        if child is not None and child.poll() is None:
            os.killpg(child.pid, signal.SIGKILL); child.wait(timeout=3)
        if dns:
            os.killpg(dns.pid, signal.SIGTERM); dns.wait(timeout=3)
    assert not stderr.strip() or scenario.startswith("signal-"), stderr
    receipts = sorted(stdout.decode(errors="surrogateescape").replace(str(root), "$ROOT").splitlines())
    if scenario in {"root-before", "root-after", "move-failure", "restart"} or scenario.startswith("signal-"):
        assert [p.read_bytes() for p in [db, Path(str(db) + "-wal"), Path(str(db) + "-shm")]] == before_db
        assert not list(db.parent.glob("*.pixel-cleanup-backup*"))
    if scenario == "move-failure": assert any("rotation_prepare_failed_rolled_back" in r for r in receipts), receipts
    if scenario == "root-after": assert any("root_recheck_failed_rolled_back" in r for r in receipts), receipts
    if scenario.startswith("signal-"): assert child.returncode == 128 + int(scenario.split("-")[1]), (child.returncode, stderr)
    if scenario == "restart": assert any("recovered_interrupted_rotation" in r for r in receipts), receipts
    if (control / "child-pid").exists():
        pid = (control / "child-pid").read_text()
        status = subprocess.run(["ps", "-p", pid, "-o", "stat="], capture_output=True, text=True).stdout.strip()
        if status and not status.startswith("Z"):
            os.kill(int(pid), signal.SIGKILL)
            raise AssertionError(("orphaned root-recheck child", pid, status))
    effects = (control / "effects").read_text().splitlines() if (control / "effects").exists() else []
    return child.returncode, receipts, snapshot(root), effects


def main():
    native = Path(sys.argv[1]).resolve()
    baseline = Path(sys.argv[2]).read_bytes() if len(sys.argv) > 2 else subprocess.check_output(["git", "show", "9d1aa21:" + REL], cwd=Path(__file__).resolve().parents[4])
    assert hashlib.sha256(baseline).hexdigest() == OLD_SHA, "frozen cleanup source changed"
    scenarios = ["success", "root-before", "root-after", "move-failure", "rollback-failure", "delete-failure",
                 "recovery-success", "recovery-missing", "recovery-failed", "protected-db", "ceiling", "missing",
                 "logs", "logs-dry", "protected-logs", "unknown-logs", "zero-limit", "artifacts", "artifacts-dry",
                 "unicode-retention", "byte-paths", "dns-idle", "dns-active", "signal-1", "signal-2", "signal-15", "restart"]
    if sys.platform == "darwin": scenarios.remove("byte-paths")  # APFS refuses invalid UTF-8 filenames.
    if os.environ.get("CLEANUP_SCENARIO"):
        scenarios = [os.environ["CLEANUP_SCENARIO"]]
    with tempfile.TemporaryDirectory(prefix="pixel-cleanup-parity-") as tmp:
        tmp = Path(tmp); old = tmp / "old.sh"; old.write_bytes(baseline)
        for scenario in scenarios:
            results = []
            for i, executable in enumerate([old, native]):
                root = tmp / f"{scenario}-{i}"; root.mkdir()
                results.append(invoke(executable, scenario, root))
            assert results[0] == results[1], (scenario, results)
            print("PASS:", scenario, flush=True)
    print(f"PASS: {len(scenarios)} actual cleanup CLI/filesystem/process comparisons; all disposable state removed")


if __name__ == "__main__": main()
