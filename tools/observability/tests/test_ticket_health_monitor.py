import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "ticket_health_monitor.py"
SPEC = importlib.util.spec_from_file_location("ticket_health_monitor", MODULE_PATH)
monitor = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
sys.modules[SPEC.name] = monitor
SPEC.loader.exec_module(monitor)
EXPECTED_IDENTITY = "c200ba2b19cf478fbb75ce99bd969ebe47cb313909a7ebf4d5f19c6bf3e325f9"


def spacetime_config() -> dict:
  return {
    "binary": "spacetime",
    "server": "https://maincloud.spacetimedb.com",
    "database": "ticket-remote-prod-v3",
    "ticket_id": "vivi-default",
    "expected_operator_identity": EXPECTED_IDENTITY,
    "timeout_seconds": 12,
  }


def default_thresholds() -> dict:
  return {
    "pixel_frame_age_millis": 1500,
    "relay_frame_age_millis": 1000,
    "resources": {
      "host": {
        "memory_used_percent": {"warning": 80, "failure": 92},
        "root_disk_used_percent": {"warning": 80, "failure": 90},
        "container_cpu_percent": {"warning": 100, "failure": 250},
        "container_memory_percent": {"warning": 20, "failure": 40},
      },
      "pixel": {
        "battery_level_percent": {"warning_below": 25, "failure_below": 10},
        "battery_temperature_c": {"warning": 40, "failure": 45},
        "thermal_status": {"warning": 2, "failure": 4},
        "memory_used_percent": {"warning": 85, "failure": 95},
        "data_disk_used_percent": {"warning": 80, "failure": 90},
      },
    },
  }


def raw_pixel_health(active: bool) -> dict:
  health = {
    "ok": True,
    "sessionState": "live" if active else "stopped",
    "streamActive": active,
    "streamVerdict": "live" if active else "idle",
    "hardwareH264": {
      "active": active,
      "available": True,
      "lastVisibilityCheckResult": "visible" if active else "idle",
    },
    "recovery": {
      "desiredRecoveryStage": "idle",
      "lastDesiredRecoveryResult": "none",
      "lastDesiredRecoveryFailureReason": None,
      "streamStage": "healthy",
      "lastStreamRecoveryResult": "none",
    },
    "ticketState": {"state": "live" if active else "stopped"},
    "viviState": {"state": "TICKET_DETAIL"},
  }
  if active:
    health.update({
      "visibleFrame": {"lastFrameAgoMillis": 100},
      "streamPipeline": {"videoClients": 1, "encoderRunning": True, "lastFrameSentAgoMillis": 100},
    })
  return health


def collect_pixel_with_health(health: dict) -> dict:
  def runner(args, _timeout):
    tail = list(args[3:])
    if tail == ["get-state"]:
      return monitor.CommandResult(0, "device\n", "")
    if tail == ["shell", "su", "-c", monitor.TICKET_LIFECYCLE_PS_COMMAND]:
      return monitor.CommandResult(0, "PID PPID ELAPSED STAT NAME ARGS\n", "")
    if tail[:3] == ["shell", "su", "-c"]:
      return monitor.CommandResult(0, json.dumps(health), "")
    if tail in (
      ["shell", "settings", "get", "system", "accelerometer_rotation"],
      ["shell", "settings", "get", "system", "user_rotation"],
    ):
      return monitor.CommandResult(0, "0\n", "")
    if tail == ["shell", "dumpsys", "battery"]:
      return monitor.CommandResult(0, "status: 3\nlevel: 80\ntemperature: 320\n", "")
    if tail == ["shell", "dumpsys", "thermalservice"]:
      return monitor.CommandResult(0, "Thermal Status: 0\n", "")
    if tail == ["shell", "cat", "/proc/meminfo"]:
      return monitor.CommandResult(0, "MemTotal: 8388608 kB\nMemAvailable: 4194304 kB\n", "")
    if tail == ["shell", "df", "-Pk", "/data"]:
      return monitor.CommandResult(0, "Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/block/data 104857600 52428800 52428800 50% /data\n", "")
    raise AssertionError(f"unexpected adb command: {tail}")

  return monitor.collect_pixel({
    "adb_binary": "adb",
    "serial": "100.76.50.43:5555",
    "timeout_seconds": 10,
    "curl_path": "/data/local/pixel-stack/bin/curl",
    "health_url": "http://127.0.0.1:9388/api/v1/health",
  }, runner)


def healthy_snapshot(active: bool) -> dict:
  pixel_health = {
    "ok": True,
    "session_state": "live" if active else "stopped",
    "stream_active": active,
    "stream_verdict": "live" if active else "idle",
    "visible_frame_age_millis": 200 if active else None,
    "hardware_h264": {"active": active, "available": True, "visibility": "visible" if active else "idle"},
    "stream_pipeline": {
      "video_clients": 1 if active else 0,
      "encoder_running": active,
      "last_frame_sent_ago_millis": 200 if active else None,
    },
    "ticket_state": "live" if active else "stopped",
    "vivi_state": "TICKET_DETAIL",
    "recovery": {
      "desired_stage": "idle",
      "desired_result": "succeeded",
      "desired_failure": "",
      "stream_stage": "idle",
      "stream_result": "succeeded",
    },
  }
  return {
    "public": {"ok": True},
    "host": {
      "ok": True,
      "resource_summary": {
        "memory": {"used_percent": 30},
        "root_disk": {"used_percent": 20},
        "docker": {"ticket": {"cpu_percent": 1, "memory_percent": 2}},
      },
    },
    "spacetime": {
      "ok": True,
      "desired_active": active,
      "viewer_count": 1 if active else 0,
      "phone_stream_state": "streaming" if active else "idle",
      "phone_desired_active": active,
      "relay_video_clients": 1 if active else 0,
      "relay_stream_verdict": "live" if active else "idle",
      "relay_last_frame_ago_millis": 100 if active else -1,
      "relay_status": {
        "phone_connected": True,
        "phone_desired": active,
        "phone_stream_state": "streaming" if active else "idle",
        "live": active,
        "last_frame_ago_millis": 100 if active else None,
      },
      "pending_stream_commands": 0,
    },
    "pixel": {
      "ok": True,
      "adb_state": "device",
      "health": pixel_health,
      "portrait_lock": {"ok": True, "accelerometer_rotation": 0, "user_rotation": 0},
      "resources": {
        "battery": {"level_percent": 80, "temperature_c": 32},
        "thermal_status": 0,
        "memory": {"used_percent": 70},
        "data_disk": {"used_percent": 20},
      },
      "ticket_lifecycle": {
        "ok": True,
        "stuck_helper_count": 0,
        "stuck_start_stop_count": 0,
        "oldest_age_seconds": None,
      },
    },
  }


class TicketHealthMonitorTest(unittest.TestCase):
  def test_config_requires_public_operator_identity_and_forbids_tokens(self):
    config_path = MODULE_PATH.parent / "ticket_health_monitor.config.json"
    config = json.loads(config_path.read_text(encoding="utf-8"))
    monitor.validate_config(config)

    missing_identity = json.loads(json.dumps(config))
    del missing_identity["spacetime"]["expected_operator_identity"]
    with self.assertRaises(ValueError):
      monitor.validate_config(missing_identity)

    token_config = json.loads(json.dumps(config))
    token_config["spacetime"]["token"] = "must-not-be-accepted"
    with self.assertRaises(ValueError):
      monitor.validate_config(token_config)

  def test_config_strictly_validates_resource_thresholds(self):
    config_path = MODULE_PATH.parent / "ticket_health_monitor.config.json"
    config = json.loads(config_path.read_text(encoding="utf-8"))
    broken_configs = []

    missing_resources = json.loads(json.dumps(config))
    del missing_resources["thresholds"]["resources"]
    broken_configs.append(missing_resources)

    boolean_threshold = json.loads(json.dumps(config))
    boolean_threshold["thresholds"]["resources"]["host"]["memory_used_percent"]["warning"] = True
    broken_configs.append(boolean_threshold)

    reversed_high = json.loads(json.dumps(config))
    reversed_high["thresholds"]["resources"]["pixel"]["memory_used_percent"] = {"warning": 96, "failure": 95}
    broken_configs.append(reversed_high)

    reversed_low = json.loads(json.dumps(config))
    reversed_low["thresholds"]["resources"]["pixel"]["battery_level_percent"] = {
      "warning_below": 10,
      "failure_below": 25,
    }
    broken_configs.append(reversed_low)

    extra_field = json.loads(json.dumps(config))
    extra_field["thresholds"]["resources"]["host"]["unexpected"] = {"warning": 1, "failure": 2}
    broken_configs.append(extra_field)

    for broken in broken_configs:
      with self.subTest(config=broken):
        with self.assertRaises(ValueError):
          monitor.validate_config(broken)

  def test_config_rejects_unknown_missing_mistyped_and_nonfinite_values(self):
    config_path = MODULE_PATH.parent / "ticket_health_monitor.config.json"
    config = json.loads(config_path.read_text(encoding="utf-8"))
    broken_configs = []

    extra_top_level = json.loads(json.dumps(config))
    extra_top_level["unexpected"] = "not-allowed"
    broken_configs.append(extra_top_level)

    string_enabled = json.loads(json.dumps(config))
    string_enabled["enabled"] = "false"
    broken_configs.append(string_enabled)

    missing_public_url = json.loads(json.dumps(config))
    del missing_public_url["public"]["page_url"]
    broken_configs.append(missing_public_url)

    mistyped_containers = json.loads(json.dumps(config))
    mistyped_containers["containers"] = "not-an-array"
    broken_configs.append(mistyped_containers)

    nonfinite_threshold = json.loads(json.dumps(config))
    nonfinite_threshold["thresholds"]["resources"]["host"]["memory_used_percent"]["warning"] = float("nan")
    broken_configs.append(nonfinite_threshold)

    boolean_retention = json.loads(json.dumps(config))
    boolean_retention["reporting"]["max_degraded_evidence_reports"] = True
    broken_configs.append(boolean_retention)

    unknown_endpoint_container = json.loads(json.dumps(config))
    unknown_endpoint_container["container_endpoints"][0]["container"] = "not-configured"
    broken_configs.append(unknown_endpoint_container)

    invalid_loopback_port = json.loads(json.dumps(config))
    invalid_loopback_port["pixel"]["health_url"] = "http://127.0.0.1:99999/api/v1/health"
    broken_configs.append(invalid_loopback_port)

    duplicate_container = json.loads(json.dumps(config))
    duplicate_container["containers"].append(json.loads(json.dumps(duplicate_container["containers"][0])))
    broken_configs.append(duplicate_container)

    for broken in broken_configs:
      with self.subTest(config=broken):
        with self.assertRaises(ValueError):
          monitor.validate_config(broken)

    with tempfile.TemporaryDirectory() as directory:
      duplicate_json = Path(directory) / "duplicate.json"
      duplicate_json.write_text('{"version": 1, "version": 1}\n', encoding="utf-8")
      with self.assertRaises(ValueError):
        monitor.load_json(duplicate_json)

  def test_idle_contract_does_not_require_a_running_stream(self):
    verdict = monitor.evaluate_snapshot(
      healthy_snapshot(active=False),
      default_thresholds(),
      [],
    )

    self.assertEqual("healthy_idle", verdict["status"])
    self.assertEqual("no_active_viewer", verdict["viewer_mode"])
    self.assertEqual([], verdict["failures"])
    self.assertTrue(any("standby" in warning for warning in verdict["warnings"]))

  def test_client_disconnected_is_a_settled_healthy_idle_state(self):
    raw_health = raw_pixel_health(active=False)
    raw_health["sessionState"] = "client_disconnected"
    raw_health["ticketState"]["state"] = "client_disconnected"
    collected = collect_pixel_with_health(raw_health)

    self.assertTrue(collected["ok"])
    self.assertTrue(collected["health_contract_ok"])
    self.assertEqual("client_disconnected", collected["health"]["session_state"])
    self.assertEqual("client_disconnected", collected["health"]["ticket_state"])

    snapshot = healthy_snapshot(active=False)
    snapshot["pixel"]["health"]["session_state"] = "client_disconnected"
    snapshot["pixel"]["health"]["ticket_state"] = "client_disconnected"
    snapshot["spacetime"]["phone_stream_state"] = "client_disconnected"
    snapshot["spacetime"]["relay_status"]["phone_stream_state"] = "client_disconnected"
    verdict = monitor.evaluate_snapshot(snapshot, default_thresholds(), ["standby-present"])

    self.assertEqual("healthy_idle", verdict["status"])
    self.assertEqual("no_active_viewer", verdict["viewer_mode"])
    self.assertEqual([], verdict["failures"])

  def test_resource_warning_thresholds_do_not_degrade_an_otherwise_healthy_snapshot(self):
    snapshot = healthy_snapshot(active=False)
    snapshot["host"]["resource_summary"]["memory"]["used_percent"] = 85
    snapshot["pixel"]["resources"]["battery"]["level_percent"] = 20
    snapshot["pixel"]["resources"]["memory"]["used_percent"] = 90

    verdict = monitor.evaluate_snapshot(snapshot, default_thresholds(), ["standby-present"])

    self.assertEqual("healthy_idle", verdict["status"])
    self.assertEqual([], verdict["failures"])
    self.assertTrue(any("Host memory" in warning for warning in verdict["warnings"]))
    self.assertTrue(any("battery level" in warning for warning in verdict["warnings"]))
    self.assertTrue(any("Pixel memory" in warning for warning in verdict["warnings"]))

  def test_resource_failure_thresholds_degrade_with_specific_findings(self):
    snapshot = healthy_snapshot(active=False)
    snapshot["host"]["resource_summary"]["root_disk"]["used_percent"] = 95
    snapshot["host"]["resource_summary"]["docker"]["ticket"] = {"cpu_percent": 300, "memory_percent": 45}
    snapshot["pixel"]["resources"] = {
      "battery": {"level_percent": 5, "temperature_c": 46},
      "thermal_status": 4,
      "memory": {"used_percent": 96},
      "data_disk": {"used_percent": 91},
    }

    verdict = monitor.evaluate_snapshot(snapshot, default_thresholds(), ["standby-present"])

    self.assertEqual("degraded", verdict["status"])
    self.assertEqual({
      "host_root_disk_pressure",
      "host_container_cpu_pressure",
      "host_container_memory_pressure",
      "pixel_battery_critical",
      "pixel_battery_temperature_critical",
      "pixel_thermal_critical",
      "pixel_memory_pressure",
      "pixel_data_disk_pressure",
    }, set(verdict["failures"]))

  def test_ticket_lifecycle_parser_keeps_only_stuck_counts_and_age(self):
    output = (
      "PID PPID ELAPSED STAT NAME ARGS\n"
      "5983 814 12:28:28 Ss sh sh /data/local/pixel-stack/bin/pixel-ticket-start.sh\n"
      "9443 814 12:28:05 Ss sh sh /data/local/pixel-stack/bin/pixel-ticket-stop.sh\n"
      "9567 9443 12:28:03 R tr tr \\000\n"
      "9568 5983 12:28:03 R tr tr \\000\n"
      "222 1 00:00:30 S sh sh /data/local/pixel-stack/bin/pixel-ticket-start.sh\n"
      "333 1 4-01:02:03 S unrelated private email@example.com\n"
    )

    status = monitor._ticket_lifecycle_status(output)

    self.assertEqual({
      "ok": True,
      "stuck_helper_count": 2,
      "stuck_start_stop_count": 2,
      "oldest_age_seconds": 44908,
    }, status)
    self.assertNotIn("email@example.com", json.dumps(status, sort_keys=True))

  def test_ticket_lifecycle_stuck_degrades_an_otherwise_healthy_snapshot(self):
    snapshot = healthy_snapshot(active=False)
    snapshot["pixel"]["ticket_lifecycle"] = {
      "ok": True,
      "stuck_helper_count": 2,
      "stuck_start_stop_count": 2,
      "oldest_age_seconds": 44908,
    }

    verdict = monitor.evaluate_snapshot(snapshot, default_thresholds(), ["standby-present"])

    self.assertEqual("degraded", verdict["status"])
    self.assertIn("pixel_ticket_lifecycle_stuck", verdict["failures"])

  def test_orphaned_ticket_command_reader_is_still_counted(self):
    status = monitor._ticket_lifecycle_status(
      "PID PPID ELAPSED STAT NAME ARGS\n"
      "9567 1 12:28:03 R tr tr \\000\n"
    )

    self.assertEqual({
      "ok": True,
      "stuck_helper_count": 1,
      "stuck_start_stop_count": 0,
      "oldest_age_seconds": 44883,
    }, status)

  def test_ticket_lifecycle_summary_rejects_mistyped_negative_and_inconsistent_values(self):
    malformed = [
      {"ok": True, "stuck_helper_count": "1", "stuck_start_stop_count": 0, "oldest_age_seconds": 61},
      {"ok": True, "stuck_helper_count": 0, "stuck_start_stop_count": -1, "oldest_age_seconds": None},
      {"ok": True, "stuck_helper_count": 0, "stuck_start_stop_count": 0, "oldest_age_seconds": "none"},
      {"ok": True, "stuck_helper_count": 1, "stuck_start_stop_count": 0, "oldest_age_seconds": None},
    ]

    for lifecycle in malformed:
      with self.subTest(lifecycle=lifecycle):
        snapshot = healthy_snapshot(active=False)
        snapshot["pixel"]["ticket_lifecycle"] = lifecycle
        verdict = monitor.evaluate_snapshot(snapshot, default_thresholds(), ["standby-present"])
        self.assertEqual("degraded", verdict["status"])
        self.assertIn("pixel_ticket_lifecycle_metrics_invalid", verdict["failures"])

  def test_ticket_lifecycle_collection_failure_fails_pixel_closed(self):
    def runner(args, _timeout):
      tail = list(args[3:])
      if tail == ["get-state"]:
        return monitor.CommandResult(0, "device\n", "")
      if tail[:3] == ["shell", "su", "-c"] and tail[-1] != monitor.TICKET_LIFECYCLE_PS_COMMAND:
        return monitor.CommandResult(0, json.dumps(raw_pixel_health(active=False)), "")
      if tail in (
        ["shell", "settings", "get", "system", "accelerometer_rotation"],
        ["shell", "settings", "get", "system", "user_rotation"],
      ):
        return monitor.CommandResult(0, "0\n", "")
      if tail == ["shell", "dumpsys", "battery"]:
        return monitor.CommandResult(0, "status: 3\nlevel: 80\ntemperature: 320\n", "")
      if tail == ["shell", "dumpsys", "thermalservice"]:
        return monitor.CommandResult(0, "Thermal Status: 0\n", "")
      if tail == ["shell", "cat", "/proc/meminfo"]:
        return monitor.CommandResult(0, "MemTotal: 8388608 kB\nMemAvailable: 4194304 kB\n", "")
      if tail == ["shell", "df", "-Pk", "/data"]:
        return monitor.CommandResult(0, "Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/block/data 104857600 52428800 52428800 50% /data\n", "")
      if tail == ["shell", "su", "-c", monitor.TICKET_LIFECYCLE_PS_COMMAND]:
        return monitor.CommandResult(1, "", "permission denied")
      raise AssertionError(f"unexpected adb command: {tail}")

    collected = monitor.collect_pixel({
      "adb_binary": "adb", "serial": "100.76.50.43:5555", "timeout_seconds": 10,
      "curl_path": "/data/local/pixel-stack/bin/curl", "health_url": "http://127.0.0.1:9388/api/v1/health",
    }, runner)

    self.assertFalse(collected["ok"])
    self.assertFalse(collected["ticket_lifecycle"]["ok"])

  def test_unknown_host_or_pixel_does_not_add_resource_secondary_failures(self):
    snapshot = healthy_snapshot(active=False)
    snapshot["host"] = {"ok": False, "error": "ssh_unreachable"}
    snapshot["pixel"] = {"ok": False, "error": "adb_unreachable"}

    verdict = monitor.evaluate_snapshot(snapshot, default_thresholds(), ["standby-present"])

    self.assertEqual(["host_unhealthy", "pixel_unhealthy"], verdict["failures"])

  def test_truthy_non_boolean_surface_health_fails_closed(self):
    snapshot = healthy_snapshot(active=False)
    snapshot["public"]["ok"] = "true"
    snapshot["host"]["ok"] = 1
    snapshot["spacetime"]["ok"] = "true"
    snapshot["pixel"]["ok"] = 1

    verdict = monitor.evaluate_snapshot(snapshot, default_thresholds(), ["standby-present"])

    self.assertEqual("degraded", verdict["status"])
    self.assertEqual("unknown", verdict["viewer_mode"])
    self.assertEqual(
      ["public_unhealthy", "host_unhealthy", "spacetime_unhealthy", "pixel_unhealthy"],
      verdict["failures"],
    )

  def test_missing_resource_metrics_fail_closed_when_primary_surface_claims_healthy(self):
    snapshot = healthy_snapshot(active=False)
    del snapshot["host"]["resource_summary"]["memory"]["used_percent"]
    snapshot["pixel"]["resources"] = {}

    verdict = monitor.evaluate_snapshot(snapshot, default_thresholds(), ["standby-present"])

    self.assertIn("host_resource_metrics_invalid", verdict["failures"])
    self.assertIn("pixel_resource_metrics_invalid", verdict["failures"])

  def test_live_contract_requires_fresh_pixel_and_relay_frames(self):
    snapshot = healthy_snapshot(active=True)

    healthy = monitor.evaluate_snapshot(snapshot, default_thresholds(), [])
    snapshot["pixel"]["health"]["visible_frame_age_millis"] = 1501
    degraded = monitor.evaluate_snapshot(snapshot, default_thresholds(), [])

    self.assertEqual("healthy_live", healthy["status"])
    self.assertEqual("degraded", degraded["status"])
    self.assertIn("pixel_frame_stale", degraded["failures"])

  def test_live_contract_rejects_client_disconnected_states(self):
    snapshot = healthy_snapshot(active=True)
    snapshot["spacetime"]["phone_stream_state"] = "client_disconnected"
    snapshot["spacetime"]["relay_status"]["phone_stream_state"] = "client_disconnected"
    snapshot["pixel"]["health"]["session_state"] = "client_disconnected"
    snapshot["pixel"]["health"]["ticket_state"] = "client_disconnected"

    verdict = monitor.evaluate_snapshot(snapshot, default_thresholds(), ["standby-present"])

    self.assertEqual("degraded", verdict["status"])
    self.assertIn("phone_report_not_streaming", verdict["failures"])
    self.assertIn("relay_phone_not_streaming", verdict["failures"])
    self.assertIn("pixel_stream_not_live", verdict["failures"])
    self.assertIn("pixel_ticket_state_not_live", verdict["failures"])

  def test_no_viewer_with_running_pixel_stream_is_degraded(self):
    snapshot = healthy_snapshot(active=False)
    snapshot["pixel"]["health"]["stream_active"] = True
    snapshot["pixel"]["health"]["stream_verdict"] = "waiting_keyframe"

    verdict = monitor.evaluate_snapshot(snapshot, default_thresholds(), [])

    self.assertEqual("degraded", verdict["status"])
    self.assertIn("idle_pixel_stream_still_active", verdict["failures"])

  def test_idle_contract_rejects_unsettled_state_active_capture_and_failed_recovery(self):
    cases = []
    live_session = healthy_snapshot(active=False)
    live_session["pixel"]["health"]["session_state"] = "live"
    live_session["pixel"]["health"]["ticket_state"] = "live"
    cases.append((live_session, "idle_pixel_state_not_settled"))

    attention_session = healthy_snapshot(active=False)
    attention_session["pixel"]["health"]["session_state"] = "needs_attention"
    attention_session["pixel"]["health"]["ticket_state"] = "needs_attention"
    cases.append((attention_session, "idle_pixel_state_not_settled"))

    active_capture = healthy_snapshot(active=False)
    active_capture["pixel"]["health"]["hardware_h264"]["active"] = True
    cases.append((active_capture, "idle_pixel_hardware_capture_active"))

    blocked_recovery = healthy_snapshot(active=False)
    blocked_recovery["pixel"]["health"]["recovery"]["stream_stage"] = "blocked"
    cases.append((blocked_recovery, "pixel_recovery_failed"))

    reported_failure = healthy_snapshot(active=False)
    reported_failure["pixel"]["health"]["recovery"]["desired_failure"] = "reported"
    cases.append((reported_failure, "pixel_recovery_failed"))

    for snapshot, expected_failure in cases:
      with self.subTest(expected_failure=expected_failure):
        verdict = monitor.evaluate_snapshot(snapshot, default_thresholds(), ["standby-present"])
        self.assertEqual("degraded", verdict["status"])
        self.assertIn(expected_failure, verdict["failures"])

  def test_sql_table_parser_handles_ascii_and_box_drawing_tables(self):
    ascii_rows = monitor.parse_sql_table("desiredActive | viewerCount\n--------------+------------\nfalse         | 0\n")
    box_rows = monitor.parse_sql_table("│ streamVerdict │ videoClients │\n├───────────────┼──────────────┤\n│ live          │ 1            │\n")
    single_column_rows = monitor.parse_sql_table('status\n------\n"succeeded"\n"pending"\n')

    self.assertEqual("false", ascii_rows[0]["desiredActive"])
    self.assertEqual("1", box_rows[0]["videoClients"])
    self.assertEqual([{"status": "succeeded"}, {"status": "pending"}], single_column_rows)

  def test_sql_table_parser_removes_exactly_one_outer_quote_pair_from_every_cell(self):
    rows = monitor.parse_sql_table(
      '"desiredActive" | "reason" | "statusJson"\n'
      '----------------+----------+-----------\n'
      'true            | "relay_viewer_added" | "{"streamActive":false,"sessionState":"stopped"}"\n'
    )

    self.assertEqual("true", rows[0]["desiredActive"])
    self.assertEqual("relay_viewer_added", rows[0]["reason"])
    self.assertEqual(
      {"streamActive": False, "sessionState": "stopped"},
      json.loads(rows[0]["statusJson"]),
    )
    self.assertEqual('"nested"', monitor._normalize_cli_cell('""nested""'))

  def test_spacetime_collection_uses_operator_identity_and_keeps_only_safe_status_fields(self):
    outputs = iter([
      'desiredActive | viewerCount | reason\n--------------+-------------+-------\nfalse | 0 | "private email@example.com"\n',
      'streamState | desiredActive | statusJson\n------------+---------------+-----------\n'
      '"client_disconnected" | false | "{"streamActive":false,"streamVerdict":"idle","sessionState":"client_disconnected","rawTicket":"do-not-copy"}"\n',
      'videoClients | streamVerdict | lastFrameAgoMillis | statusJson\n-------------+---------------+--------------------+-----------\n'
      '0 | "idle" | -1 | "{"phoneConnected":true,"phoneDesired":false,"phoneStreamState":"client_disconnected","live":false,"lastFrameAgoMillis":null,"token":"do-not-copy"}"\n',
      'status\n------\n"succeeded"\n"pending"\n',
    ])
    calls = []

    def runner(args, _timeout):
      calls.append(list(args))
      if list(args[1:]) == ["login", "show"]:
        return monitor.CommandResult(0, f"You are logged in as {EXPECTED_IDENTITY}\n", "")
      return monitor.CommandResult(0, next(outputs), "")

    result = monitor.collect_spacetime(spacetime_config(), runner)

    self.assertTrue(result["ok"])
    self.assertTrue(result["operator_identity_verified"])
    self.assertEqual("other", result["desired_reason"])
    self.assertEqual("client_disconnected", result["phone_stream_state"])
    self.assertEqual("client_disconnected", result["phone_status"]["session_state"])
    self.assertEqual("client_disconnected", result["relay_status"]["phone_stream_state"])
    self.assertEqual(1, result["pending_stream_commands"])
    self.assertTrue(all("--anonymous" not in args for args in calls))
    self.assertTrue(all("--token" not in args for args in calls))
    self.assertNotIn("email@example.com", json.dumps(result, sort_keys=True))
    self.assertNotIn("do-not-copy", json.dumps(result, sort_keys=True))

  def test_spacetime_identity_mismatch_stops_before_any_sql(self):
    calls = []

    def runner(args, _timeout):
      calls.append(list(args))
      return monitor.CommandResult(0, f"You are logged in as {'0' * 64}\n", "")

    result = monitor.collect_spacetime(spacetime_config(), runner)

    self.assertFalse(result["ok"])
    self.assertFalse(result["operator_identity_verified"])
    self.assertEqual("spacetime_operator_identity_mismatch", result["error"])
    self.assertEqual([["spacetime", "login", "show"]], calls)

  def test_spacetime_malformed_schema_and_types_fail_closed(self):
    valid = [
      'desiredActive | viewerCount | reason\n--------------+-------------+-------\nfalse | 0 | "no_viewers"\n',
      'streamState | desiredActive | statusJson\n------------+---------------+-----------\n"idle" | false | "{}"\n',
      'videoClients | streamVerdict | lastFrameAgoMillis | statusJson\n-------------+---------------+--------------------+-----------\n0 | "idle" | -1 | "{}"\n',
      'status\n------\n',
    ]
    cases = {
      "wrong_columns": 'desiredActive | viewers | reason\n--------------+---------+-------\nfalse | 0 | "no_viewers"\n',
      "non_boolean": 'desiredActive | viewerCount | reason\n--------------+-------------+-------\nyes | 0 | "no_viewers"\n',
      "non_integer": 'desiredActive | viewerCount | reason\n--------------+-------------+-------\nfalse | zero | "no_viewers"\n',
      "missing_current_row": 'desiredActive | viewerCount | reason\n--------------+-------------+-------\n',
    }
    for name, malformed_desired in cases.items():
      with self.subTest(name=name):
        outputs = iter([malformed_desired, *valid[1:]])

        def runner(args, _timeout):
          if list(args[1:]) == ["login", "show"]:
            return monitor.CommandResult(0, f"You are logged in as {EXPECTED_IDENTITY}\n", "")
          return monitor.CommandResult(0, next(outputs), "")

        result = monitor.collect_spacetime(spacetime_config(), runner)
        self.assertFalse(result["ok"])
        self.assertTrue(result["operator_identity_verified"])

    per_query_cases = {
      "phone_columns": [valid[0], 'streamState | desiredActive | healthJson\n------------+---------------+----------\n"idle" | false | "{}"\n', valid[2], valid[3]],
      "phone_boolean": [valid[0], 'streamState | desiredActive | statusJson\n------------+---------------+-----------\n"idle" | 0 | "{}"\n', valid[2], valid[3]],
      "relay_integer": [valid[0], valid[1], 'videoClients | streamVerdict | lastFrameAgoMillis | statusJson\n-------------+---------------+--------------------+-----------\nzero | "idle" | -1 | "{}"\n', valid[3]],
      "relay_unknown_enum": [valid[0], valid[1], 'videoClients | streamVerdict | lastFrameAgoMillis | statusJson\n-------------+---------------+--------------------+-----------\n0 | "safe_but_unapproved" | -1 | "{}"\n', valid[3]],
      "relay_json_type": [valid[0], valid[1], 'videoClients | streamVerdict | lastFrameAgoMillis | statusJson\n-------------+---------------+--------------------+-----------\n0 | "idle" | -1 | "{"live":"yes"}"\n', valid[3]],
      "command_columns": [*valid[:3], 'state\n-----\n'],
    }
    for name, query_outputs in per_query_cases.items():
      with self.subTest(name=name):
        outputs = iter(query_outputs)

        def runner(args, _timeout):
          if list(args[1:]) == ["login", "show"]:
            return monitor.CommandResult(0, f"You are logged in as {EXPECTED_IDENTITY}\n", "")
          return monitor.CommandResult(0, next(outputs), "")

        result = monitor.collect_spacetime(spacetime_config(), runner)
        self.assertFalse(result["ok"])
        self.assertTrue(result["operator_identity_verified"])

    outputs = iter([*valid[:3], 'status\n------\n"invented state"\n'])

    def invalid_status_runner(args, _timeout):
      if list(args[1:]) == ["login", "show"]:
        return monitor.CommandResult(0, f"You are logged in as {EXPECTED_IDENTITY}\n", "")
      return monitor.CommandResult(0, next(outputs), "")

    invalid_status = monitor.collect_spacetime(spacetime_config(), invalid_status_runner)
    self.assertFalse(invalid_status["ok"])
    self.assertEqual("spacetime_data_invalid", invalid_status["error"])

  def test_unknown_spacetime_state_does_not_invent_live_or_idle_failures(self):
    snapshot = healthy_snapshot(active=False)
    snapshot["spacetime"] = {"ok": False, "error": "spacetime_query_failed"}
    snapshot["pixel"]["health"]["stream_active"] = True
    snapshot["pixel"]["health"]["stream_verdict"] = "live"

    verdict = monitor.evaluate_snapshot(
      snapshot,
      default_thresholds(),
      ["standby-present"],
    )

    self.assertEqual("degraded", verdict["status"])
    self.assertEqual("unknown", verdict["viewer_mode"])
    self.assertEqual(["spacetime_unhealthy"], verdict["failures"])

  def test_unknown_pixel_state_does_not_invent_pixel_secondary_failures(self):
    snapshot = healthy_snapshot(active=True)
    snapshot["pixel"] = {"ok": False, "error": "pixel_health_unavailable"}

    verdict = monitor.evaluate_snapshot(
      snapshot,
      default_thresholds(),
      ["standby-present"],
    )

    self.assertEqual("degraded", verdict["status"])
    self.assertEqual(["pixel_unhealthy"], verdict["failures"])
    self.assertIsNone(verdict["frame_age_millis"])

  def test_pixel_health_contract_requires_typed_idle_and_live_fields(self):
    self.assertTrue(monitor._pixel_health_contract_valid(raw_pixel_health(active=False)))
    self.assertTrue(monitor._pixel_health_contract_valid(raw_pixel_health(active=True)))
    fresh_idle = raw_pixel_health(active=False)
    fresh_idle["hardwareH264"]["lastVisibilityCheckResult"] = "not_run"
    self.assertTrue(monitor._pixel_health_contract_valid(fresh_idle))
    self.assertEqual("not_run", monitor._select_pixel_health(fresh_idle)["hardware_h264"]["visibility"])

    missing_idle = raw_pixel_health(active=False)
    del missing_idle["hardwareH264"]["available"]
    missing_idle_ticket = raw_pixel_health(active=False)
    del missing_idle_ticket["ticketState"]
    missing_idle_vivi = raw_pixel_health(active=False)
    del missing_idle_vivi["viviState"]
    mistyped_idle = raw_pixel_health(active=False)
    mistyped_idle["streamActive"] = "false"
    missing_live_frame = raw_pixel_health(active=True)
    del missing_live_frame["visibleFrame"]["lastFrameAgoMillis"]
    mistyped_live_pipeline = raw_pixel_health(active=True)
    mistyped_live_pipeline["streamPipeline"]["videoClients"] = "1"
    missing_live_ticket = raw_pixel_health(active=True)
    del missing_live_ticket["ticketState"]

    for malformed in (
      missing_idle, missing_idle_ticket, missing_idle_vivi, mistyped_idle,
      missing_live_frame, mistyped_live_pipeline, missing_live_ticket,
    ):
      with self.subTest(health=malformed):
        self.assertFalse(monitor._pixel_health_contract_valid(malformed))

  def test_pixel_collection_and_evaluator_fail_closed_on_invalid_live_contract(self):
    malformed = raw_pixel_health(active=True)
    malformed["streamPipeline"]["encoderRunning"] = "true"
    collected = collect_pixel_with_health(malformed)
    snapshot = healthy_snapshot(active=True)
    snapshot["pixel"] = collected

    verdict = monitor.evaluate_snapshot(snapshot, default_thresholds(), ["standby-present"])

    self.assertFalse(collected["ok"])
    self.assertFalse(collected["health_contract_ok"])
    self.assertEqual(["pixel_unhealthy"], verdict["failures"])

  def test_persisted_health_text_uses_explicit_per_field_fallbacks(self):
    raw = raw_pixel_health(active=True)
    raw["sessionState"] = "safe_but_unapproved"
    raw["streamVerdict"] = "private email@example.com"
    raw["hardwareH264"]["lastVisibilityCheckResult"] = "secret_state"
    raw["ticketState"]["state"] = "made_up"
    raw["viviState"]["state"] = "PRIVATE_SCREEN"
    raw["recovery"]["lastDesiredRecoveryFailureReason"] = "private email@example.com"

    selected = monitor._select_pixel_health(raw)
    serialized = json.dumps(selected, sort_keys=True)

    self.assertEqual("other", selected["session_state"])
    self.assertEqual("other", selected["stream_verdict"])
    self.assertEqual("other", selected["hardware_h264"]["visibility"])
    self.assertEqual("other", selected["ticket_state"])
    self.assertEqual("OTHER_VIVI", selected["vivi_state"])
    self.assertEqual("reported", selected["recovery"]["desired_failure"])
    self.assertNotIn("email@example.com", serialized)
    self.assertNotIn("safe_but_unapproved", serialized)

  def test_host_resource_summary_is_bounded_and_allowlisted(self):
    container_name = "arbuzas-ticket_remote-1"

    def runner(args, _timeout):
      command = args[-1]
      if command == "true":
        return monitor.CommandResult(0, "", "")
      if command.startswith("docker inspect"):
        return monitor.CommandResult(0, '{"Running":true,"Status":"running","Health":{"Status":"healthy","FailingStreak":0}}', "")
      if command.startswith("docker exec"):
        return monitor.CommandResult(0, '{"ok":true,"status":"private email@example.com","secret":"do-not-copy"}', "")
      if command == "cat /proc/meminfo":
        return monitor.CommandResult(0, "MemTotal: 2097152 kB\nMemAvailable: 1048576 kB\nSecret: do-not-copy\n", "")
      if command == "df -Pk /":
        return monitor.CommandResult(0, "Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/root 4194304 1048576 3145728 25% /\n", "")
      if command == "cat /proc/uptime":
        return monitor.CommandResult(0, "12345.67 8910.11\n", "")
      if command.startswith("docker stats --no-stream"):
        return monitor.CommandResult(
          0,
          json.dumps({
            "Name": container_name,
            "CPUPerc": "0.25%",
            "MemPerc": "1.50%",
            "MemUsage": "30MiB / 2GiB",
            "PIDs": "7",
            "Secret": "do-not-copy",
          }) + "\n",
          "",
        )
      raise AssertionError(f"unexpected command: {command}")

    result = monitor.collect_host(
      {"binary": "ssh", "host": "kitty-gration", "user": "ropepop"},
      [{"name": container_name, "health_required": True}],
      [{"name": "ticket_remote_livez", "container": container_name, "url": "http://127.0.0.1:9338/api/v1/livez"}],
      runner,
    )

    self.assertTrue(result["ok"])
    self.assertEqual(50.0, result["resource_summary"]["memory"]["used_percent"])
    self.assertEqual(25, result["resource_summary"]["root_disk"]["used_percent"])
    self.assertEqual(7, result["resource_summary"]["docker"][container_name]["pids"])
    self.assertEqual("other", result["endpoints"]["ticket_remote_livez"]["status"])
    self.assertNotIn("email@example.com", json.dumps(result, sort_keys=True))
    self.assertNotIn("do-not-copy", json.dumps(result, sort_keys=True))

  def test_docker_and_adb_states_use_fixed_privacy_fallbacks(self):
    container_name = "arbuzas-ticket_remote-1"

    def host_runner(args, _timeout):
      command = args[-1]
      if command == "true":
        return monitor.CommandResult(0, "", "")
      if command.startswith("docker inspect"):
        return monitor.CommandResult(
          0,
          '{"Running":true,"Status":"running","Health":{"Status":"private email@example.com","FailingStreak":"private-id"}}',
          "",
        )
      if command.startswith("docker exec"):
        return monitor.CommandResult(0, '{"ok":true,"status":"healthy"}', "")
      if command == "cat /proc/meminfo":
        return monitor.CommandResult(0, "MemTotal: 2097152 kB\nMemAvailable: 1048576 kB\n", "")
      if command == "df -Pk /":
        return monitor.CommandResult(0, "Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/root 4194304 1048576 3145728 25% /\n", "")
      if command == "cat /proc/uptime":
        return monitor.CommandResult(0, "12345.67 8910.11\n", "")
      if command.startswith("docker stats --no-stream"):
        return monitor.CommandResult(0, json.dumps({
          "Name": container_name, "CPUPerc": "0.25%", "MemPerc": "1.50%",
          "MemUsage": "30MiB / 2GiB", "PIDs": "7",
        }) + "\n", "")
      raise AssertionError(f"unexpected command: {command}")

    host = monitor.collect_host(
      {"binary": "ssh", "host": "kitty-gration", "user": "ropepop"},
      [{"name": container_name, "health_required": False}],
      [{"name": "ticket_remote_livez", "container": container_name, "url": "http://127.0.0.1:9338/api/v1/livez"}],
      host_runner,
    )
    self.assertEqual("other", host["containers"][container_name]["health"])
    self.assertIsNone(host["containers"][container_name]["failing_streak"])
    self.assertNotIn("email@example.com", json.dumps(host, sort_keys=True))
    self.assertNotIn("private-id", json.dumps(host, sort_keys=True))

    def pixel_runner(args, _timeout):
      if list(args[3:]) == ["get-state"]:
        return monitor.CommandResult(0, "private email@example.com\n", "")
      raise AssertionError("Pixel collection must stop after an unapproved ADB state")

    pixel = monitor.collect_pixel({
      "adb_binary": "adb", "serial": "100.76.50.43:5555", "timeout_seconds": 10,
      "curl_path": "/data/local/pixel-stack/bin/curl", "health_url": "http://127.0.0.1:9388/api/v1/health",
    }, pixel_runner)
    self.assertEqual("other", pixel["adb_state"])
    self.assertNotIn("email@example.com", json.dumps(pixel, sort_keys=True))

  def test_pixel_resource_summary_keeps_only_battery_thermal_ram_and_disk_numbers(self):
    health_payload = raw_pixel_health(active=False)
    health_payload["recovery"]["lastDesiredRecoveryFailureReason"] = "private email@example.com"
    health_payload["secret"] = "do-not-copy"

    def runner(args, _timeout):
      tail = list(args[3:])
      if tail == ["get-state"]:
        return monitor.CommandResult(0, "device\n", "")
      if tail == ["shell", "su", "-c", monitor.TICKET_LIFECYCLE_PS_COMMAND]:
        return monitor.CommandResult(0, "PID PPID ELAPSED STAT NAME ARGS\n", "")
      if tail[:3] == ["shell", "su", "-c"]:
        return monitor.CommandResult(0, json.dumps(health_payload), "")
      if tail == ["shell", "settings", "get", "system", "accelerometer_rotation"]:
        return monitor.CommandResult(0, "0\n", "")
      if tail == ["shell", "settings", "get", "system", "user_rotation"]:
        return monitor.CommandResult(0, "0\n", "")
      if tail == ["shell", "dumpsys", "battery"]:
        return monitor.CommandResult(0, "status: 3\nlevel: 80\ntemperature: 339\nserial: do-not-copy\n", "")
      if tail == ["shell", "dumpsys", "thermalservice"]:
        return monitor.CommandResult(0, "Thermal Status: 0\nSensor: do-not-copy\n", "")
      if tail == ["shell", "cat", "/proc/meminfo"]:
        return monitor.CommandResult(0, "MemTotal: 8388608 kB\nMemAvailable: 4194304 kB\nSecret: do-not-copy\n", "")
      if tail == ["shell", "df", "-Pk", "/data"]:
        return monitor.CommandResult(0, "Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/block/data 104857600 52428800 52428800 50% /data\n", "")
      raise AssertionError(f"unexpected adb command: {tail}")

    result = monitor.collect_pixel({
      "adb_binary": "adb",
      "serial": "100.76.50.43:5555",
      "timeout_seconds": 10,
      "curl_path": "/data/local/pixel-stack/bin/curl",
      "health_url": "http://127.0.0.1:9388/api/v1/health",
    }, runner)

    self.assertTrue(result["ok"])
    self.assertTrue(result["health_contract_ok"])
    self.assertEqual(80, result["resources"]["battery"]["level_percent"])
    self.assertEqual(33.9, result["resources"]["battery"]["temperature_c"])
    self.assertEqual(0, result["resources"]["thermal_status"])
    self.assertEqual(50.0, result["resources"]["memory"]["used_percent"])
    self.assertEqual(50, result["resources"]["data_disk"]["used_percent"])
    self.assertEqual("reported", result["health"]["recovery"]["desired_failure"])
    self.assertNotIn("email@example.com", json.dumps(result, sort_keys=True))
    self.assertNotIn("do-not-copy", json.dumps(result, sort_keys=True))

  def test_default_runner_drains_but_bounds_stdout_and_stderr(self):
    size = monitor.MAX_COMMAND_CAPTURE_BYTES + 131072
    script = f"import sys; sys.stdout.write('x' * {size}); sys.stderr.write('y' * {size})"

    result = monitor.default_runner([sys.executable, "-c", script], 10)

    self.assertEqual(0, result.returncode)
    self.assertEqual(monitor.MAX_COMMAND_CAPTURE_BYTES, len(result.stdout.encode("utf-8")))
    self.assertEqual(monitor.MAX_COMMAND_CAPTURE_BYTES, len(result.stderr.encode("utf-8")))

  def test_default_runner_bounds_decoded_invalid_utf8(self):
    size = monitor.MAX_COMMAND_CAPTURE_BYTES + 131072
    script = f"import os; os.write(1, b'\\xff' * {size}); os.write(2, b'\\xfe' * {size})"

    result = monitor.default_runner([sys.executable, "-c", script], 10)

    self.assertEqual(0, result.returncode)
    self.assertLessEqual(len(result.stdout.encode("utf-8")), monitor.MAX_COMMAND_CAPTURE_BYTES)
    self.assertLessEqual(len(result.stderr.encode("utf-8")), monitor.MAX_COMMAND_CAPTURE_BYTES)

  def test_atomic_writer_replaces_output_and_keeps_user_readable_mode(self):
    with tempfile.TemporaryDirectory() as directory:
      output = Path(directory) / "latest.json"
      monitor.atomic_write_json(output, {"status": "healthy_idle"})
      first = json.loads(output.read_text(encoding="utf-8"))
      monitor.atomic_write_json(output, {"status": "degraded"})
      second = json.loads(output.read_text(encoding="utf-8"))

      self.assertEqual("healthy_idle", first["status"])
      self.assertEqual("degraded", second["status"])
      self.assertEqual(0o644, output.stat().st_mode & 0o777)
      self.assertEqual(0o755, output.parent.stat().st_mode & 0o777)

  def test_report_writer_prunes_only_old_managed_evidence_and_keeps_readable_modes(self):
    with tempfile.TemporaryDirectory() as directory:
      root = Path(directory)
      evidence_root = root / "evidence"
      output = root / "state" / "latest.json"
      for stamp in ("20260101T000001Z", "20260101T000002Z", "20260101T000003Z"):
        monitor.atomic_write_json(evidence_root / stamp / "summary.json", {"timestamp": stamp})
      preserved = evidence_root / "20260101T000000Z"
      monitor.atomic_write_json(preserved / "summary.json", {"timestamp": "manual"})
      (preserved / "notes.txt").write_text("keep", encoding="utf-8")

      report = {
        "timestamp": "2026-01-01T00:00:04Z",
        "status": "degraded",
        "viewer_mode": "unknown",
      }
      monitor.write_report(report, output, evidence_root, 2)

      managed = monitor._managed_evidence_directories(evidence_root)
      self.assertEqual(["20260101T000004Z", "20260101T000003Z"], [path.name for path in managed])
      self.assertTrue(preserved.is_dir())
      self.assertTrue((preserved / "notes.txt").is_file())
      self.assertEqual(0o644, output.stat().st_mode & 0o777)
      self.assertEqual(0o755, output.parent.stat().st_mode & 0o777)
      self.assertEqual(0o644, (evidence_root / "20260101T000004Z" / "summary.json").stat().st_mode & 0o777)
      self.assertEqual(0o755, (evidence_root / "20260101T000004Z").stat().st_mode & 0o777)


if __name__ == "__main__":
  unittest.main()
