"""Executable migration checks; all collection is loopback or fixture commands."""
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import resource
import signal
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location("monitor_python_fixtures", Path(__file__).with_name("test_ticket_health_monitor.py"))
fixtures = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fixtures)
monitor = fixtures.monitor
CONFIG = ROOT / "tools/observability/ticket_health_monitor.config.json"
BINARY = ROOT / "orchestrator/android-orchestrator/pixel-health/target/release/ticket-health-monitor"
OLD = [sys.executable, "-B", str(fixtures.MODULE_PATH)]
LAUNCHER_PATH = ROOT / "tools/observability/ticket_health_monitor.py"
CANONICAL = [sys.executable, "-B", str(LAUNCHER_PATH)]
COMMANDS = (("old", OLD), ("rust", [str(BINARY)]), ("canonical", CANONICAL))


def execute(command, config, *extra, env=None):
  return subprocess.run([*command, "--config", str(config), *map(str, extra)], cwd=ROOT,
    env=env, capture_output=True, text=True, timeout=15)


class RustMonitorMigrationTest(unittest.TestCase):
  def setUp(self):
    self.assertTrue(BINARY.is_file(), "build the opt-in release monitor before running executable comparisons")

  def test_missing_native_binary_cannot_collect_or_replace_a_report(self):
    with tempfile.TemporaryDirectory() as directory:
      root = Path(directory)
      launcher = root / "tools/observability/ticket_health_monitor.py"
      launcher.parent.mkdir(parents=True)
      launcher.write_text(LAUNCHER_PATH.read_text())
      output = root / "latest.json"
      sentinel = '{"sentinel":"old report"}\n'
      output.write_text(sentinel)
      config = root / "config.json"
      config.write_text(CONFIG.read_text())
      result = subprocess.run([sys.executable, "-B", str(launcher), "--config", "config.json", "--output", "latest.json"], cwd=root, capture_output=True, text=True, timeout=3)
      self.assertEqual(2, result.returncode, result.stderr)
      self.assertIn("--features ticket-health-monitor --bin ticket-health-monitor", result.stderr)
      self.assertEqual("", result.stdout)
      self.assertEqual(sentinel, output.read_text())
      self.assertFalse((root / "orchestrator").exists())
      self.assertFalse((root / "evidence").exists())

  def test_canonical_launcher_keeps_relative_config_and_process_stdio(self):
    with tempfile.TemporaryDirectory() as directory:
      root = Path(directory)
      (root / "config.json").write_text(CONFIG.read_text())
      outcomes = [subprocess.run([*command, "--config", "config.json", "--check-config"], cwd=root, capture_output=True, text=True, timeout=3) for command in ([str(BINARY)], CANONICAL)]
      self.assertEqual(0, outcomes[0].returncode, outcomes[0].stderr)
      self.assertEqual((outcomes[0].returncode, outcomes[0].stdout, outcomes[0].stderr), (outcomes[1].returncode, outcomes[1].stdout, outcomes[1].stderr))

  def test_existing_decision_and_config_contracts_through_rust_executable(self):
    # The existing suite remains the primary contract owner. Replay its actual
    # inputs through both executables instead of copying its fixtures/assertions.
    original_evaluate, original_validate = monitor.evaluate_snapshot, monitor.validate_config
    counts = {"verdicts": 0, "configs": 0}
    with tempfile.TemporaryDirectory() as directory:
      root = Path(directory)
      path = root / "snapshot.json"
      config_path = root / "config.json"

      def compare_evaluate(snapshot, thresholds, standby):
        config = json.loads(CONFIG.read_text())
        config.update(thresholds=thresholds, standby_devices=standby)
        config_path.write_text(json.dumps(config))
        path.write_text(json.dumps(snapshot))
        expected = original_evaluate(snapshot, thresholds, standby)
        # Some historical tests use a descriptive standby string. This is valid
        # under the serial schema, so the real CLI needs no test-only bypass.
        for command in ([str(BINARY)], CANONICAL):
          native = execute(command, config_path, "--evaluate-snapshot", path)
          self.assertEqual(0 if expected["status"].startswith("healthy") else 1, native.returncode, native.stderr)
          self.assertEqual(expected, json.loads(native.stdout))
        counts["verdicts"] += 1
        return expected

      def compare_validate(config):
        config_path.write_text(json.dumps(config))
        outcomes = [execute(command, config_path, "--check-config") for command in ([str(BINARY)], CANONICAL)]
        native = outcomes[0]
        self.assertEqual(native.returncode, outcomes[1].returncode, outcomes[1].stderr)
        counts["configs"] += 1
        try:
          result = original_validate(config)
        except ValueError:
          self.assertEqual(2, native.returncode, native.stdout)
          raise
        self.assertEqual(0, native.returncode, native.stderr)
        return result

      suite = unittest.defaultTestLoader.loadTestsFromTestCase(fixtures.TicketHealthMonitorTest)
      with patch.object(monitor, "evaluate_snapshot", compare_evaluate), patch.object(monitor, "validate_config", compare_validate):
        result = unittest.TextTestRunner(stream=io.StringIO(), verbosity=0).run(suite)
      self.assertTrue(result.wasSuccessful(), str(result.errors + result.failures))
    print(f"Rust comparison: {counts['verdicts']} verdicts, {counts['configs']} configurations, {result.testsRun} existing tests")

  def test_cli_rejection_pause_and_offline_evaluation_leave_latest_untouched(self):
    with tempfile.TemporaryDirectory() as directory:
      root = Path(directory)
      output = root / "latest.json"
      output.write_text('{"sentinel":"old report"}\n')
      config_path = root / "config.json"
      snapshot = root / "snapshot.json"
      snapshot.write_text(json.dumps(fixtures.healthy_snapshot(False)))
      config = json.loads(CONFIG.read_text())
      for body, extra, expected in [
        ('{"version":1,"version":1}', ["--check-config"], 2),
        (json.dumps({**config, "repair_mode": "enabled"}), [], 2),
        (json.dumps({**config, "enabled": False}), [], 3),
        (json.dumps(config), ["--check-config"], 0),
        (json.dumps(config), ["--evaluate-snapshot", snapshot], 0),
      ]:
        config_path.write_text(body)
        for _, command in COMMANDS:
          result = execute(command, config_path, "--output", output, *extra)
          self.assertEqual(expected, result.returncode, result.stderr)
          self.assertEqual('{"sentinel":"old report"}\n', output.read_text())

  def test_existing_url_and_argument_syntax_remain_compatible(self):
    with tempfile.TemporaryDirectory() as directory:
      config_path = Path(directory) / "config.json"
      for url in ("HTTPS://ticket.example/", "https://ticket.example:/", "https://ticket.example/a@b", "https://ticket.example/?", "https://ticket.example/#", "https://ticket.example:0/", "https://ticket.example:+443/", "https://user@ticket.example/", "https://ticket.example/?token=do-not-copy", "https://ticket.example/#fragment"):
        config = json.loads(CONFIG.read_text())
        config["public"]["page_url"] = url
        config_path.write_text(json.dumps(config))
        outcomes = [execute(command, config_path, "--check-config").returncode for _, command in COMMANDS]
        self.assertEqual([outcomes[0]] * len(outcomes), outcomes, url)
      config_path.write_text(CONFIG.read_text())
      for _, command in COMMANDS:
        result = subprocess.run([*command, f"--config={config_path}", "--check-config"], cwd=ROOT, capture_output=True, text=True, timeout=3)
        self.assertEqual(0, result.returncode, result.stderr)

  def test_complete_fixture_collection_reports_retention_and_privacy_match(self):
    with LocalCollectorFixture(self) as local:
      for case in ("idle", "live", "warm", "stuck", "invalid_pixel", "malformed_sql", "identity_mismatch", "unreachable", "resource_failure", "invalid_utf8", "stale_source", "future_source", "invalid_time", "query_delay", "offset_time", "warm_expired", "warm_unbounded", "round_boundary"):
        with self.subTest(case=case):
          local.set_case(case)
          reports = []
          command_sequences = []
          for label, command in COMMANDS:
            output = local.root / label / "latest.json"
            evidence = local.root / label / "evidence"
            command_log = local.root / "commands.jsonl"
            command_log.unlink(missing_ok=True)
            result = execute(command, local.config_path, "--output", output, "--evidence-root", evidence, env=local.env)
            self.assertIn(result.returncode, (0, 1), result.stderr)
            report = json.loads(output.read_text())
            self.assertEqual(0 if report["status"].startswith("healthy") else 1, result.returncode)
            self.assertEqual([], report["actions"])
            self.assertFalse(report["repair_attempted"])
            self.assertEqual("disabled", report["repair_result"])
            serialized = json.dumps(report)
            self.assertNotIn("do-not-copy", serialized)
            self.assertNotIn("email@example.com", serialized)
            self.assertEqual(0o644, output.stat().st_mode & 0o777)
            self.assertEqual(0o755, output.parent.stat().st_mode & 0o777)
            reports.append(normalized(report))
            sequence = [json.loads(line) for line in command_log.read_text().splitlines()]
            # Both collectors census the same private lifecycle boundary; the native
            # collector additionally recognizes executable owners after this migration.
            command_sequences.append([row[:-1] + ["<Ticket lifecycle process census>"]
              if row[0] == "adb" and row[-1].startswith("ps -A -o PID,PPID,ELAPSED,STAT,NAME,ARGS | awk ") else row
              for row in sequence])
            if case == "stuck":
              self.assertIn("pixel_ticket_lifecycle_stuck", report["failures"])
            if case == "warm":
              self.assertEqual("healthy_warm", report["status"])
            spacetime = report["checked_surfaces"]["spacetime"]
            if case == "stale_source":
              self.assertGreaterEqual(spacetime["relay_last_frame_ago_millis"], 20000)
              self.assertGreaterEqual(spacetime["relay_report_age_millis"], 20000)
              self.assertIn("relay_report_stale_or_unavailable", report["failures"])
            if case == "query_delay":
              self.assertGreaterEqual(spacetime["relay_last_frame_ago_millis"], 140)
              self.assertGreaterEqual(spacetime["relay_report_age_millis"], 140)
            if case == "future_source":
              self.assertEqual("spacetime_clock_unbounded", spacetime["error"])
            if case == "invalid_time":
              self.assertEqual("spacetime_timestamp_invalid", spacetime["error"])
            if case == "warm_unbounded":
              self.assertEqual("spacetime_warm_state_invalid", spacetime["error"])
          for report, commands in zip(reports[1:], command_sequences[1:]):
            self.assertEqual(reports[0], report)
            self.assertEqual(command_sequences[0], commands)
          if case in ("idle", "live"):
            self.assertEqual("healthy_" + case, reports[1]["status"])
      # Evidence containing operator material survives retention. Only compact
      # timestamp directories are managed, as in the existing writer contract.
      evidence = local.root / "rust" / "evidence"
      for stamp in ("20260101T000001Z", "20260101T000002Z", "20260101T000003Z"):
        monitor.atomic_write_json(evidence / stamp / "summary.json", {"timestamp": stamp})
      preserved = evidence / "20260101T000000Z"
      monitor.atomic_write_json(preserved / "summary.json", {})
      (preserved / "notes.txt").write_text("keep")
      config = json.loads(local.config_path.read_text())
      config["reporting"]["max_degraded_evidence_reports"] = 2
      local.config_path.write_text(json.dumps(config))
      local.set_case("stuck")
      result = execute([str(BINARY)], local.config_path, "--output", local.root / "rust/latest.json", "--evidence-root", evidence, env=local.env)
      self.assertEqual(1, result.returncode, result.stderr)
      managed = monitor._managed_evidence_directories(evidence)
      self.assertEqual(2, len(managed))
      self.assertTrue((preserved / "notes.txt").is_file())
      for directory in managed:
        self.assertEqual(0o755, directory.stat().st_mode & 0o777)
        self.assertEqual(0o644, (directory / "summary.json").stat().st_mode & 0o777)
      self.assertTrue(all(path != "/login" for path in local.requests))

  def test_native_lifecycle_owners_remain_visible_without_copying_process_arguments(self):
    with LocalCollectorFixture(self) as local:
      local.set_case("native_stuck")
      for command in ([str(BINARY)], CANONICAL):
        output = local.root / "native-stuck.json"
        result = execute(command, local.config_path, "--output", output, env=local.env)
        self.assertEqual(1, result.returncode, result.stderr)
        report = json.loads(output.read_text())
        self.assertIn("pixel_ticket_lifecycle_stuck", report["failures"])
        self.assertEqual({"ok": True, "stuck_helper_count": 0, "stuck_start_stop_count": 2,
          "oldest_age_seconds": 3724}, report["checked_surfaces"]["pixel"]["ticket_lifecycle"])
        self.assertNotIn("private", json.dumps(report))
        self.assertEqual([], report["actions"])

  def test_timeout_and_interruption_finish_without_recovery_or_partial_latest(self):
    with LocalCollectorFixture(self) as local:
      local.set_case("timeout")
      config = json.loads(local.config_path.read_text())
      config["ssh"]["command_timeout_seconds"] = 1
      local.config_path.write_text(json.dumps(config))
      for label, command in COMMANDS:
        output = local.root / (label + "-timeout.json")
        started = time.monotonic()
        result = execute(command, local.config_path, "--output", output, "--evidence-root", local.root / "timeout-evidence", env=local.env)
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertLess(time.monotonic() - started, 4)
        self.assertEqual("ssh_unreachable", json.loads(output.read_text())["checked_surfaces"]["host"]["error"])
      local.set_case("timeout")
      config["ssh"]["command_timeout_seconds"] = 12
      local.config_path.write_text(json.dumps(config))
      for command in ([str(BINARY)], CANONICAL):
        local.set_case("timeout")
        output = local.root / "interrupted.json"
        output.write_text('{"sentinel":"old report"}\n')
        process = subprocess.Popen([*command, "--config", str(local.config_path), "--output", str(output), "--evidence-root", str(local.root / "interrupt-evidence")], cwd=ROOT, env=local.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
          deadline = time.monotonic() + 5
          while not (local.root / "sleeping.pid").exists() and time.monotonic() < deadline:
            time.sleep(.01)
          self.assertTrue((local.root / "sleeping.pid").exists())
          identity = subprocess.run(["ps", "-p", str(process.pid), "-o", "command="], check=True, capture_output=True, text=True).stdout
          self.assertIn(str(BINARY), identity)
          self.assertNotIn(str(LAUNCHER_PATH), identity)
          process.send_signal(signal.SIGINT)
          process.communicate(timeout=3)
          self.assertEqual(130, process.returncode)
          self.assertEqual('{"sentinel":"old report"}\n', output.read_text())
          self.assertFalse((local.root / "interrupt-evidence").exists())
          pid = int((local.root / "sleeping.pid").read_text())
          with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)
        finally:
          if process.poll() is None:
            process.kill()
            process.communicate()

  def test_failed_report_replacement_preserves_prior_report(self):
    with LocalCollectorFixture(self) as local:
      local.set_case("idle")
      # A directory occupying the final filename makes atomic replacement fail.
      # The writer must discard only its staging file and preserve the target.
      output = local.root / "latest.json"
      output.mkdir()
      (output / "prior").write_text("keep")
      for _, command in COMMANDS:
        result = execute(command, local.config_path, "--output", output, "--evidence-root", local.root / "evidence", env=local.env)
        self.assertEqual(1, result.returncode)
        self.assertEqual("keep", (output / "prior").read_text())
        self.assertEqual([], list(local.root.glob(".latest.json.*.tmp")))
      (output / "prior").unlink()
      output.rmdir()
      sentinel = '{"sentinel":"complete old report"}\n'
      output.write_text(sentinel)

      def partial_write_limit():
        resource.setrlimit(resource.RLIMIT_FSIZE, (512, 512))
        signal.signal(signal.SIGXFSZ, signal.SIG_IGN)

      for _, command in COMMANDS:
        (local.root / "commands.jsonl").unlink(missing_ok=True)
        result = subprocess.run([*command, "--config", str(local.config_path), "--output", str(output), "--evidence-root", str(local.root / "partial-evidence")], cwd=ROOT, env=local.env, capture_output=True, text=True, timeout=5, preexec_fn=partial_write_limit)
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertEqual(sentinel, output.read_text())
        self.assertEqual([], list(local.root.rglob("*.tmp")))
        self.assertEqual([], list((local.root / "partial-evidence").rglob("summary.json")))


def normalized(report):
  report = copy.deepcopy(report)
  for field in ("timestamp", "duration_millis", "evidence_directory"):
    report.pop(field, None)
  spacetime = report["checked_surfaces"]["spacetime"]
  for field in ("phone_observed_at", "relay_observed_at", "phone_report_age_millis", "relay_report_age_millis", "relay_last_frame_ago_millis"):
    spacetime.pop(field, None)
  if spacetime.get("page_open_warm", {}).get("retained_sessions"):
    spacetime["page_open_warm"].pop("remaining_millis")
  report["checked_surfaces"]["pixel"].pop("observed_at", None)
  return report


class LocalCollectorFixture:
  def __init__(self, test):
    self.test = test
    self.temp = tempfile.TemporaryDirectory()
    self.root = Path(self.temp.name)
    self.requests = []
    self.private_headers = []
    self.config_path = self.root / "config.json"

  def __enter__(self):
    cert, key = self.root / "cert.pem", self.root / "key.pem"
    subprocess.run(["openssl", "req", "-x509", "-nodes", "-newkey", "rsa:2048", "-keyout", str(key), "-out", str(cert), "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost", "-days", "1"], check=True, capture_output=True)
    requests = self.requests
    private_headers = self.private_headers
    class Handler(BaseHTTPRequestHandler):
      def do_GET(self):
        requests.append(self.path)
        private_headers.append(self.headers.get("X-Private"))
        status = {"/": 302, "/livez": 200, "/health": 401}.get(self.path, 400)
        self.send_response(status)
        if self.path == "/":
          self.send_header("Location", "/api/v1/auth/start?returnTo=%2F")
        self.end_headers()
        self.wfile.write(b'{"ok":true,"serverVersion":"fixture","assetVersion":"fixture"}')
      def log_message(self, *args):
        pass
    self.server = HTTPServer(("127.0.0.1", 0), Handler)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain(cert, key)
    self.server.socket = tls.wrap_socket(self.server.socket, server_side=True)
    self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
    self.worker.start()
    bin_dir = self.root / "bin"
    bin_dir.mkdir()
    for name in ("ssh", "spacetime", "adb"):
      path = bin_dir / name
      path.write_text("#!" + sys.executable + "\n" + STUB)
      path.chmod(0o755)
    # User curl defaults must never introduce login redirects or credentials.
    curl_home = self.root / "curl-home"
    curl_home.mkdir()
    (curl_home / ".curlrc").write_text('location\nheader = "X-Private: do-not-copy"\n')
    self.env = {**os.environ, "PATH": str(bin_dir) + os.pathsep + os.environ["PATH"], "FIXTURE_ROOT": str(self.root), "SSL_CERT_FILE": str(cert), "CURL_CA_BUNDLE": str(cert), "CURL_HOME": str(curl_home)}
    config = json.loads(CONFIG.read_text())
    base = f"https://localhost:{self.server.server_port}"
    config["public"].update(page_url=base + "/", livez_url=base + "/livez", protected_health_url=base + "/health")
    self.config_path.write_text(json.dumps(config))
    return self

  def set_case(self, case):
    (self.root / "sleeping.pid").unlink(missing_ok=True)
    (self.root / "case.json").write_text(json.dumps({"case": case, "health": fixtures.raw_pixel_health(case in ("live", "warm", "stale_source", "future_source", "invalid_time", "query_delay", "offset_time", "warm_expired", "warm_unbounded"))}))

  def __exit__(self, *args):
    self.server.shutdown()
    self.worker.join()
    self.server.server_close()
    self.test.assertTrue(all(header is None for header in self.private_headers))
    self.temp.cleanup()


STUB = r'''
import json, os, sys, time
from datetime import datetime, timedelta, timezone
from pathlib import Path
root = Path(os.environ["FIXTURE_ROOT"])
fixture = json.loads((root / "case.json").read_text())
case = fixture["case"]
name = Path(sys.argv[0]).name
tail = sys.argv[1:]
with (root / "commands.jsonl").open("a") as log:
  log.write(json.dumps([name, *tail]) + "\n")
def emit(value):
  print(value if isinstance(value, str) else json.dumps(value))
  sys.exit(0)
if name == "ssh":
  command = tail[-1]
  if command == "true":
    if case == "unreachable": sys.exit(1)
    if case == "timeout":
      (root / "sleeping.pid").write_text(str(os.getpid()))
      time.sleep(30)
    emit("")
  if command.startswith("docker inspect"):
    emit({"Running": True, "Status": "running", "Health": {"Status": "healthy", "FailingStreak": 0}, "Secret": "do-not-copy"})
  if command.startswith("docker exec"):
    emit({"ok": True, "status": "private email@example.com", "secret": "do-not-copy"})
  if command == "cat /proc/meminfo": emit("MemTotal: 2097152 kB\nMemAvailable: 1048576 kB\nSecret: do-not-copy")
  if command == "df -Pk /": emit("/dev/root 4194304 1048576 3145728 25% /")
  if command == "cat /proc/uptime": emit("12345.67 8910.11")
  if command.startswith("docker stats --no-stream"):
    for container in json.loads((root / "config.json").read_text())["containers"]:
      print(json.dumps({"Name": container["name"], "CPUPerc": "300%" if case == "resource_failure" else ("2.675%" if case == "round_boundary" else "0.25%"), "MemPerc": "1.50%", "MemUsage": "30MiB / 2GiB", "PIDs": "7", "Secret": "do-not-copy"}))
    sys.exit(0)
elif name == "adb":
  tail = tail[2:]
  if tail == ["get-state"]: emit("private email@example.com" if case == "unreachable" else "device")
  if tail[:3] == ["shell", "su", "-c"]:
    if tail[-1].startswith("ps -A"):
      if case == "native_stuck":
        emit("PID PPID ELAPSED STAT NAME ARGS\n10 1 01:02:03 S pixel-runtime-c /data/local/pixel-stack/bin/pixel-runtime-cleanup pixel-ticket-start.sh private\n11 1 01:02:04 S pixel-runtime-c /data/local/pixel-stack/bin/pixel-runtime-cleanup pixel-ticket-stop.sh\n12 1 05:00:00 S pixel-runtime-c /data/local/pixel-stack/bin/pixel-runtime-cleanup --dry-run\n")
      emit("PID PPID ELAPSED STAT NAME ARGS\n" + ("10 1 01:02:03 S sh sh /data/local/pixel-stack/bin/pixel-ticket-start.sh\n11 10 01:02:02 S tr tr \\000\n12 1 61:01 S tr tr \\x00\n" if case == "stuck" else ""))
    health = fixture["health"]
    health["secret"] = "do-not-copy"
    health["recovery"]["lastDesiredRecoveryFailureReason"] = "private email@example.com" if case == "resource_failure" else None
    if case == "invalid_pixel": del health["hardwareH264"]["available"]
    if case == "invalid_utf8":
      os.write(1, b'\xff' * 393216)
      os.write(2, b'\xfe' * 393216)
      sys.exit(0)
    if case in ("warm", "warm_expired", "warm_unbounded"):
      health["streamVerdict"] = "waiting_keyframe"
      health["recovery"]["streamStage"] = "demand_idle"
      health["streamPipeline"]["lastFrameSentAgoMillis"] = 60000
    emit(health)
  if tail[:4] == ["shell", "settings", "get", "system"]: emit("0")
  if tail == ["shell", "dumpsys", "battery"]: emit("status: 3\nlevel: 80\ntemperature: 320\nserial: do-not-copy")
  if tail == ["shell", "dumpsys", "thermalservice"]: emit("Thermal Status: 0\nSensor: do-not-copy")
  if tail == ["shell", "cat", "/proc/meminfo"]: emit("MemTotal: 8388608 kB\nMemAvailable: 4194304 kB")
  if tail == ["shell", "df", "-Pk", "/data"]: emit("/dev/block/data 104857600 52428800 52428800 50% /data")
elif name == "spacetime":
  if tail == ["login", "show"]: emit("You are logged in as " + ("0" * 64 if case == "identity_mismatch" else "c200ba2b19cf478fbb75ce99bd969ebe47cb313909a7ebf4d5f19c6bf3e325f9"))
  active = case in ("live", "warm", "stale_source", "future_source", "invalid_time", "query_delay", "offset_time", "warm_expired", "warm_unbounded")
  now = datetime.now(timezone.utc)
  def table(columns, values): emit(" | ".join(columns) + "\n" + " | ".join(values))
  query = tail[-1]
  if "ticketremote_stream_desired_state" in query:
    table(["desiredActive", "viewerCount", "reason"], [str(active).lower(), "zero" if case == "malformed_sql" else ("1" if active else "0"), "private email@example.com"])
  if "ticketremote_phone_current_report" in query:
    table(["streamState", "desiredActive", "statusJson", "updatedAt"], ["streaming" if active else "client_disconnected", str(active).lower(), json.dumps({"streamActive": active, "streamVerdict": "live" if active else "idle", "sessionState": "live" if active else "client_disconnected", "rawTicket": "do-not-copy"}), now.isoformat()])
  if "ticketremote_relay_current_report" in query:
    warm = case in ("warm", "warm_expired", "warm_unbounded")
    report_at = now - timedelta(seconds=20) if case == "stale_source" else (now + timedelta(seconds=5) if case == "future_source" else now)
    expiry = now + timedelta(minutes=31) if case == "warm_unbounded" else (now - timedelta(seconds=1) if case == "warm_expired" else now + timedelta(minutes=1))
    if case == "warm_expired": report_at = now - timedelta(seconds=2)
    if case == "query_delay": time.sleep(.15)
    stamp = report_at.isoformat()
    if case == "offset_time": stamp = report_at.astimezone(timezone(timedelta(hours=3))).isoformat().replace("T", " ").replace("+03:00", "+0300")
    if case == "invalid_time": stamp = "not-a-time"
    table(["videoClients", "streamVerdict", "lastFrameAt", "statusJson", "updatedAt"], ["1" if active and not warm else "0", "live" if active and not warm else "idle", '(some = "' + stamp + '")' if active and not warm else "(none = ())", json.dumps({"phoneConnected": True, "phoneDesired": active, "phoneStreamState": "streaming" if active else "client_disconnected", "live": active and not warm, "token": "do-not-copy", "pageOpenWarm": {"retainedSessions": 1 if warm else 0, "expiresAt": expiry.isoformat() if warm else ""}}), stamp])
  if "ticketremote_stream_command" in query: emit("status\nsucceeded")
raise SystemExit("unexpected fixture command: " + name + " " + repr(tail))
'''


if __name__ == "__main__":
  unittest.main()
