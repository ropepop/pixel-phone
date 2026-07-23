#!/usr/bin/env python3
"""Read-only, privacy-bounded Ticket health collector and evaluator."""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import signal
import shlex
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping, Sequence


def _words(value: str) -> set[str]:
  return set(value.split())


VERSION = "4"
TERMINAL_COMMAND_STATES = _words("succeeded failed expired canceled cancelled completed")
STREAM_COMMAND_STATES = TERMINAL_COMMAND_STATES | _words("pending running warming closed")
ENDPOINT_STATUS_STATES = _words("ok healthy ready live starting degraded error unhealthy unavailable")
DESIRED_REASON_STATES = _words(
  "admin_force admin_stop no_viewers relay_viewer_added relay_viewer_removed relay_viewer_updated viewer_heartbeat viewer_timeout"
)
PHONE_STREAM_STATES = _words(
  "idle starting streaming live stopped control_active control_transition control_exit recovering unavailable "
  "capture_blocked waiting_keyframe stale_recovering preparing_phone browser_decode_recovering timing_uncertain "
  "unknown client_disconnected"
)
RELAY_STREAM_VERDICTS = _words(
  "idle live preparing_phone waiting_keyframe stale_recovering browser_decode_recovering timing_uncertain unavailable unknown"
)
PIXEL_SESSION_STATES = _words(
  "idle starting live stopped control_active control_transition control_exit soft_recovery recovering needs_attention "
  "unavailable client_disconnected"
)
PIXEL_STREAM_VERDICTS = _words(
  "idle live starting waiting_keyframe stale_recovering recovering capture_blocked unavailable unknown"
)
HARDWARE_VISIBILITY_STATES = _words(
  "visible idle unknown not_checked not_run unavailable blocked hidden black error"
)
PIXEL_TICKET_STATES = _words(
  "idle starting live stopped control_active control_transition control_exit soft_recovery needs_attention unavailable "
  "client_disconnected"
)
VIVI_STATES = _words(
  "TICKET_DETAIL TICKET_LIST GENERATED_CONTROL_CODE CONTROL_CODE_POPUP LOGIN NO_TICKETS ROUTE_HOME CART UNKNOWN_VIVI OTHER_VIVI"
)
RECOVERY_STAGE_STATES = _words("idle running healthy recovering blocked failed stopped none")
RECOVERY_RESULT_STATES = _words("none pending succeeded failed recovered skipped not_needed")
RECOVERY_FAILURE_REASONS = _words(
  "timeout phone_not_ready capture_unavailable capture_blocked stream_start_failed ticket_not_ready "
  "vivi_attention_required foreground_mismatch unknown"
)
DOCKER_CONTAINER_STATES = _words("created running paused restarting removing exited dead")
DOCKER_HEALTH_STATES = _words("starting healthy unhealthy")
ADB_STATES = _words("device offline unauthorized unknown")
HEALTHY_IDLE_SESSION_STATES = _words("idle stopped client_disconnected")
SAFE_NAME = re.compile(r"^[A-Za-z0-9_.-]+$")
SAFE_TICKET_ID = re.compile(r"^[A-Za-z0-9_.:-]+$")
SAFE_IDENTITY = re.compile(r"^[0-9a-f]{64}$")
SAFE_COMMAND = re.compile(r"^[A-Za-z0-9_./+-]+$")
SAFE_PIXEL_SERIAL = re.compile(r"^[A-Za-z0-9_.:-]+$")
MANAGED_EVIDENCE_DIRECTORY = re.compile(r"^\d{8}T\d{6}Z$")
MAX_MONITORED_CONTAINERS = 32
MAX_RESOURCE_LINES = 128
MAX_COMMAND_CAPTURE_BYTES = 262144
MAX_EVIDENCE_REPORTS = 1000
TICKET_LIFECYCLE_STUCK_SECONDS = 60
TICKET_LIFECYCLE_PS_COMMAND = (
  "ps -A -o PID,PPID,ELAPSED,STAT,NAME,ARGS | "
  "awk 'NR == 1 || ($5 == \"sh\" && $6 == \"sh\" && "
  "($7 == \"/data/local/pixel-stack/bin/pixel-ticket-start.sh\" || "
  "$7 == \"/data/local/pixel-stack/bin/pixel-ticket-stop.sh\")) || "
  "($5 == \"tr\" && $6 == \"tr\" && ($7 == \"\\\\000\" || $7 == \"\\\\0\" || $7 == \"\\\\x00\"))'"
)


@dataclass(frozen=True)
class CommandResult:
  returncode: int
  stdout: str
  stderr: str


CommandRunner = Callable[[Sequence[str], float], CommandResult]


def utc_now() -> str:
  return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _drain(stream: Any, output: list[bytes]) -> None:
  kept = bytearray()
  try:
    while chunk := stream.read(8192):
      kept.extend(chunk[:max(0, MAX_COMMAND_CAPTURE_BYTES - len(kept))])
  except OSError:
    pass
  finally:
    try:
      stream.close()
    except OSError:
      pass
    output.append(bytes(kept))


def _decode(data: bytes) -> str:
  value = data.decode("utf-8", errors="replace")
  encoded = value.encode()
  return value if len(encoded) <= MAX_COMMAND_CAPTURE_BYTES else encoded[:MAX_COMMAND_CAPTURE_BYTES].decode("utf-8", "ignore")


def default_runner(args: Sequence[str], timeout_seconds: float) -> CommandResult:
  try:
    process = subprocess.Popen(
      list(args), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
      env={**os.environ, "LC_ALL": "C"}, start_new_session=True,
    )
  except OSError as error:
    return CommandResult(124, "", str(error))
  assert process.stdout is not None and process.stderr is not None
  stdout: list[bytes] = []
  stderr: list[bytes] = []
  readers = [
    threading.Thread(target=_drain, args=(process.stdout, stdout), daemon=True),
    threading.Thread(target=_drain, args=(process.stderr, stderr), daemon=True),
  ]
  for reader in readers:
    reader.start()
  timed_out = False
  try:
    process.wait(timeout=timeout_seconds)
  except subprocess.TimeoutExpired:
    timed_out = True
    try:
      os.killpg(process.pid, signal.SIGKILL)
    except (OSError, ProcessLookupError):
      process.kill()
    process.wait()
  for reader in readers:
    reader.join()
  out = _decode(stdout[0] if stdout else b"")
  err = _decode(stderr[0] if stderr else b"")
  if timed_out and len(err.encode()) < MAX_COMMAND_CAPTURE_BYTES - 32:
    err = f"{err}\ncommand timed out".lstrip()
  return CommandResult(124 if timed_out else int(process.returncode or 0), out, err)


def load_json(path: Path) -> dict[str, Any]:
  def unique(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
      if key in result:
        raise ValueError(f"{path} contains duplicate JSON key: {key}")
      result[key] = value
    return result
  with path.open(encoding="utf-8") as handle:
    value = json.load(handle, object_pairs_hook=unique)
  if not isinstance(value, dict):
    raise ValueError(f"{path} must contain a JSON object")
  return value


def _mapping(value: Any, name: str, keys: set[str] | None = None) -> Mapping[str, Any]:
  if not isinstance(value, Mapping):
    raise ValueError(f"{name} must be an object")
  if keys is not None and set(value) != keys:
    raise ValueError(f"{name} must contain exactly: {', '.join(sorted(keys))}")
  return value


def _text(
  value: Any, name: str, *, maximum: int = 512, empty: bool = False, pattern: re.Pattern[str] | None = None,
) -> str:
  if not isinstance(value, str) or (not value and not empty) or len(value) > maximum or any(ord(c) < 32 for c in value):
    raise ValueError(f"{name} is not a valid string")
  if pattern and not pattern.fullmatch(value):
    raise ValueError(f"{name} contains unsupported characters")
  return value


def _number(value: Any, name: str, low: float, high: float) -> float:
  if isinstance(value, bool) or not isinstance(value, (int, float)):
    raise ValueError(f"{name} must be numeric")
  parsed = float(value)
  if not math.isfinite(parsed) or not low <= parsed <= high:
    raise ValueError(f"{name} must be between {low:g} and {high:g}")
  return parsed


def _url(value: Any, name: str, *, https: bool) -> str:
  raw = _text(value, name, maximum=2048)
  try:
    parsed = urllib.parse.urlsplit(raw)
    port = parsed.port
  except ValueError as error:
    raise ValueError(f"{name} is not a valid URL") from error
  common = parsed.username is None and parsed.password is None and not parsed.query and not parsed.fragment
  if https:
    valid = parsed.scheme == "https" and bool(parsed.hostname) and bool(re.fullmatch(r"[A-Za-z0-9.-]+", parsed.hostname or ""))
  else:
    valid = parsed.scheme == "http" and parsed.hostname == "127.0.0.1" and port is not None and bool(re.fullmatch(r"/[A-Za-z0-9_./-]*", parsed.path))
  if not common or not valid or (port is not None and not 1 <= port <= 65535):
    raise ValueError(f"{name} is not an approved credential-free URL")
  return raw


HIGH_THRESHOLDS = {
  "host": {"memory_used_percent": 100, "root_disk_used_percent": 100, "container_cpu_percent": 10000, "container_memory_percent": 100},
  "pixel": {"battery_temperature_c": 100, "thermal_status": 6, "memory_used_percent": 100, "data_disk_used_percent": 100},
}


def _validate_thresholds(raw: Any) -> None:
  value = _mapping(raw, "thresholds", {"pixel_frame_age_millis", "relay_frame_age_millis", "resources"})
  _number(value["pixel_frame_age_millis"], "thresholds.pixel_frame_age_millis", 1, 60000)
  _number(value["relay_frame_age_millis"], "thresholds.relay_frame_age_millis", 1, 60000)
  resources = _mapping(value["resources"], "thresholds.resources", {"host", "pixel"})
  for section, fields in HIGH_THRESHOLDS.items():
    values = _mapping(resources[section], f"thresholds.resources.{section}", set(fields) | ({"battery_level_percent"} if section == "pixel" else set()))
    for field, maximum in fields.items():
      pair = _mapping(values[field], field, {"warning", "failure"})
      warning = _number(pair["warning"], f"{field}.warning", 0, maximum)
      failure = _number(pair["failure"], f"{field}.failure", 0, maximum)
      if warning >= failure:
        raise ValueError(f"{field}.warning must be lower than failure")
  battery = _mapping(resources["pixel"]["battery_level_percent"], "battery_level_percent", {"warning_below", "failure_below"})
  warning = _number(battery["warning_below"], "battery_level_percent.warning_below", 0, 100)
  failure = _number(battery["failure_below"], "battery_level_percent.failure_below", 0, 100)
  if failure >= warning:
    raise ValueError("battery_level_percent.failure_below must be lower than warning_below")


def validate_config(config: Mapping[str, Any]) -> None:
  top = _mapping(config, "config", _words(
    "version enabled paused_reason repair_mode public ssh containers container_endpoints spacetime pixel thresholds reporting standby_devices"
  ))
  if type(top["version"]) is not int or top["version"] != 1 or type(top["enabled"]) is not bool:
    raise ValueError("config version or enabled flag is invalid")
  _text(top["paused_reason"], "paused_reason", maximum=256, empty=True)
  if top["repair_mode"] != "disabled":
    raise ValueError("repair_mode must remain disabled")
  public = _mapping(top["public"], "public", _words("page_url livez_url protected_health_url timeout_seconds"))
  for field in ("page_url", "livez_url", "protected_health_url"):
    _url(public[field], f"public.{field}", https=True)
  _number(public["timeout_seconds"], "public.timeout_seconds", .1, 120)
  ssh = _mapping(top["ssh"], "ssh", _words("binary host user connect_timeout_seconds command_timeout_seconds"))
  _text(ssh["binary"], "ssh.binary", maximum=256, pattern=SAFE_COMMAND)
  _text(ssh["host"], "ssh.host", maximum=253, pattern=SAFE_NAME)
  _text(ssh["user"], "ssh.user", maximum=64, pattern=SAFE_NAME)
  _number(ssh["connect_timeout_seconds"], "ssh.connect_timeout_seconds", 1, 120)
  _number(ssh["command_timeout_seconds"], "ssh.command_timeout_seconds", 1, 300)
  containers = top["containers"]
  if not isinstance(containers, list) or not containers or len(containers) > MAX_MONITORED_CONTAINERS:
    raise ValueError("containers must be a non-empty bounded array")
  names = []
  for index, raw in enumerate(containers):
    item = _mapping(raw, f"containers[{index}]", {"name", "health_required"})
    names.append(_text(item["name"], f"containers[{index}].name", maximum=128, pattern=SAFE_NAME))
    if type(item["health_required"]) is not bool:
      raise ValueError("health_required must be boolean")
  if len(names) != len(set(names)):
    raise ValueError("container names must be unique")
  endpoints = top["container_endpoints"]
  if not isinstance(endpoints, list) or not endpoints or len(endpoints) > MAX_MONITORED_CONTAINERS * 2:
    raise ValueError("container_endpoints must be a non-empty bounded array")
  endpoint_names = []
  for index, raw in enumerate(endpoints):
    item = _mapping(raw, f"container_endpoints[{index}]", {"name", "container", "url"})
    endpoint_names.append(_text(item["name"], "endpoint.name", maximum=128, pattern=SAFE_NAME))
    container = _text(item["container"], "endpoint.container", maximum=128, pattern=SAFE_NAME)
    if container not in names:
      raise ValueError("endpoint container is not configured")
    _url(item["url"], "endpoint.url", https=False)
  if len(endpoint_names) != len(set(endpoint_names)):
    raise ValueError("endpoint names must be unique")
  spacetime = _mapping(top["spacetime"], "spacetime", _words(
    "binary server database ticket_id expected_operator_identity timeout_seconds"
  ))
  _text(spacetime["binary"], "spacetime.binary", maximum=256, pattern=SAFE_COMMAND)
  _url(spacetime["server"], "spacetime.server", https=True)
  _text(spacetime["database"], "spacetime.database", maximum=128, pattern=SAFE_NAME)
  _text(spacetime["ticket_id"], "spacetime.ticket_id", maximum=128, pattern=SAFE_TICKET_ID)
  _text(spacetime["expected_operator_identity"], "spacetime.expected_operator_identity", maximum=64, pattern=SAFE_IDENTITY)
  _number(spacetime["timeout_seconds"], "spacetime.timeout_seconds", 1, 120)
  pixel = _mapping(top["pixel"], "pixel", _words("adb_binary serial timeout_seconds curl_path health_url"))
  _text(pixel["adb_binary"], "pixel.adb_binary", maximum=256, pattern=SAFE_COMMAND)
  _text(pixel["serial"], "pixel.serial", maximum=128, pattern=SAFE_PIXEL_SERIAL)
  _number(pixel["timeout_seconds"], "pixel.timeout_seconds", 1, 120)
  if not _text(pixel["curl_path"], "pixel.curl_path", pattern=SAFE_COMMAND).startswith("/"):
    raise ValueError("pixel.curl_path must be absolute")
  _url(pixel["health_url"], "pixel.health_url", https=False)
  _validate_thresholds(top["thresholds"])
  reporting = _mapping(top["reporting"], "reporting", {"max_degraded_evidence_reports"})
  retention = reporting["max_degraded_evidence_reports"]
  if type(retention) is not int or not 1 <= retention <= MAX_EVIDENCE_REPORTS:
    raise ValueError("invalid evidence retention")
  standby = top["standby_devices"]
  if not isinstance(standby, list) or len(standby) > 16:
    raise ValueError("standby_devices must be a bounded array")
  serials = [_text(value, f"standby_devices[{index}]", maximum=128, pattern=SAFE_PIXEL_SERIAL) for index, value in enumerate(standby)]
  if len(serials) != len(set(serials)):
    raise ValueError("standby_devices must be unique")


def _safe_json(body: str) -> dict[str, Any] | None:
  try:
    value = json.loads(body)
    return value if isinstance(value, dict) else None
  except (json.JSONDecodeError, TypeError):
    return None


def http_probe(url: str, expected_status: int, timeout_seconds: float) -> dict[str, Any]:
  request = urllib.request.Request(url, headers={"User-Agent": f"pixel-ticket-health-monitor/{VERSION}"})
  try:
    with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
      status, body = response.status, response.read(65536)
  except urllib.error.HTTPError as error:
    status, body = error.code, error.read(65536)
  except (urllib.error.URLError, TimeoutError, OSError) as error:
    return {"ok": False, "status": None, "error": type(error).__name__}
  parsed = _safe_json(body.decode("utf-8", "replace")) or {}
  result: dict[str, Any] = {"ok": status == expected_status, "status": status}
  for key in ("ok", "status", "serverVersion", "assetVersion"):
    if key in parsed:
      result[f"body_{key}"] = parsed[key]
  return result


def ssh_base(config: Mapping[str, Any]) -> list[str]:
  return [
    str(config.get("binary", "ssh")), "-o", "BatchMode=yes", "-o",
    f"ConnectTimeout={int(config.get('connect_timeout_seconds', 8))}", "-o", "ConnectionAttempts=1",
    f"{config['user']}@{config['host']}",
  ]


def ssh_run(config: Mapping[str, Any], command: str, runner: CommandRunner) -> CommandResult:
  return runner([*ssh_base(config), command], float(config.get("command_timeout_seconds", 12)))


def _parse_meminfo(output: str) -> dict[str, Any] | None:
  values = {}
  for line in output.splitlines()[:MAX_RESOURCE_LINES]:
    match = re.fullmatch(r"\s*(MemTotal|MemAvailable):\s+(\d+)\s+kB\s*", line)
    if match:
      values[match[1]] = int(match[2])
  total, available = values.get("MemTotal", 0), values.get("MemAvailable", -1)
  if total <= 0 or not 0 <= available <= total:
    return None
  return {"total_mib": round(total / 1024, 1), "available_mib": round(available / 1024, 1), "used_percent": round((total - available) * 100 / total, 1)}


def _parse_disk(output: str) -> dict[str, Any] | None:
  for line in reversed([item.strip() for item in output.splitlines()[:16] if item.strip()]):
    parts = line.split()
    if len(parts) < 6 or not parts[-2].endswith("%"):
      continue
    try:
      total, used, available, percent = int(parts[-5]), int(parts[-4]), int(parts[-3]), int(parts[-2][:-1])
    except ValueError:
      continue
    if total > 0 and min(used, available, percent) >= 0:
      return {"total_mib": round(total / 1024, 1), "used_mib": round(used / 1024, 1), "available_mib": round(available / 1024, 1), "used_percent": percent}
  return None


def _percent(value: Any, maximum: float) -> float | None:
  match = re.fullmatch(r"\s*(\d+(?:\.\d+)?)%\s*", str(value))
  parsed = float(match[1]) if match else maximum + 1
  return round(parsed, 2) if parsed <= maximum else None


def _as_int(value: Any, default: int = 0) -> int:
  try:
    return int(str(value).strip())
  except (TypeError, ValueError):
    return default


def _parse_docker_stats(output: str, allowed_names: Sequence[str]) -> dict[str, dict[str, Any]]:
  allowed = set(allowed_names[:MAX_MONITORED_CONTAINERS])
  result = {}
  for line in output.splitlines()[:MAX_MONITORED_CONTAINERS]:
    item = _safe_json(line.strip())
    name = str(item.get("Name", "")) if item else ""
    if name not in allowed or name in result:
      continue
    memory_usage = str(item.get("MemUsage", "")).strip()
    result[name] = {
      "cpu_percent": _percent(item.get("CPUPerc"), 100000),
      "memory_percent": _percent(item.get("MemPerc"), 100),
      "pids": _as_int(item.get("PIDs"), -1),
    }
    if memory_usage and len(memory_usage) <= 48 and re.fullmatch(r"[0-9A-Za-z.%+ /-]+", memory_usage):
      result[name]["memory_usage"] = memory_usage
  return result


def _collect_host_resources(config: Mapping[str, Any], names: Sequence[str], runner: CommandRunner) -> dict[str, Any]:
  commands = ["cat /proc/meminfo", "df -Pk /", "cat /proc/uptime"]
  stats = "docker stats --no-stream --format " + shlex.quote("{{json .}}")
  if names:
    stats += " " + " ".join(shlex.quote(name) for name in names)
  memory_result, disk_result, uptime_result, stats_result = [ssh_run(config, command, runner) for command in (*commands, stats)]
  memory = _parse_meminfo(memory_result.stdout) if memory_result.returncode == 0 else None
  disk = _parse_disk(disk_result.stdout) if disk_result.returncode == 0 else None
  try:
    uptime = int(float(uptime_result.stdout.strip().split()[0])) if uptime_result.returncode == 0 else -1
  except (IndexError, ValueError):
    uptime = -1
  docker = _parse_docker_stats(stats_result.stdout, names) if stats_result.returncode == 0 else {}
  ok = memory is not None and disk is not None and uptime >= 0 and len(docker) == len(names) and all(
    row["cpu_percent"] is not None and row["memory_percent"] is not None and row["pids"] >= 0 for row in docker.values()
  )
  return {"ok": ok, "uptime_seconds": uptime if uptime >= 0 else None, "memory": memory, "root_disk": disk, "docker": docker}


def collect_public(config: Mapping[str, Any]) -> dict[str, Any]:
  timeout = float(config.get("timeout_seconds", 10))
  root, livez, protected = [
    http_probe(str(config[key]), expected, timeout)
    for key, expected in (("page_url", 200), ("livez_url", 200), ("protected_health_url", 401))
  ]
  return {
    "ok": root["ok"] and livez["ok"] and protected["ok"],
    "root_http": root["status"], "livez_http": livez["status"],
    "unauthenticated_health_http": protected["status"],
    "server_version": livez.get("body_serverVersion"), "asset_version": livez.get("body_assetVersion"),
  }


def _enum(value: Any, allowed: set[str], fallback: str, *, lowercase: bool = True) -> str | None:
  if value is None:
    return None
  if not isinstance(value, str):
    return fallback
  text = value.strip()
  if not text:
    return None
  normalized = text.lower() if lowercase else text
  return normalized if normalized in allowed else fallback


def collect_host(
  ssh_config: Mapping[str, Any], containers: Iterable[Mapping[str, Any]],
  endpoints: Iterable[Mapping[str, Any]], runner: CommandRunner,
) -> dict[str, Any]:
  container_list = list(containers)[:MAX_MONITORED_CONTAINERS]
  reachable = ssh_run(ssh_config, "true", runner).returncode == 0
  result: dict[str, Any] = {"ok": reachable, "ssh": reachable, "containers": {}, "endpoints": {}}
  if not reachable:
    return {**result, "error": "ssh_unreachable"}
  for item in container_list:
    name = str(item["name"])
    response = ssh_run(ssh_config, f"docker inspect --format '{{{{json .State}}}}' {shlex.quote(name)}", runner)
    state = _safe_json(response.stdout.strip()) if response.returncode == 0 else None
    if not state:
      summary = {"ok": False, "status": "missing", "health": None, "failing_streak": None}
    else:
      raw_health = state.get("Health") if isinstance(state.get("Health"), Mapping) else {}
      status = _enum(state.get("Status"), DOCKER_CONTAINER_STATES, "other")
      health = _enum(raw_health.get("Status"), DOCKER_HEALTH_STATES, "other")
      streak = raw_health.get("FailingStreak")
      streak = streak if type(streak) is int and 0 <= streak <= 1_000_000 else None
      ok = state.get("Running") is True and status == "running" and (not item.get("health_required") or health == "healthy")
      summary = {"ok": ok, "status": status, "health": health, "failing_streak": streak}
    result["containers"][name] = summary
  for item in endpoints:
    inner = (
      f"if command -v curl >/dev/null 2>&1; then curl -fsS --max-time 5 {shlex.quote(str(item['url']))}; "
      f"elif command -v wget >/dev/null 2>&1; then wget -qO- -T 5 {shlex.quote(str(item['url']))}; else exit 127; fi"
    )
    response = ssh_run(ssh_config, f"docker exec {shlex.quote(str(item['container']))} sh -lc {shlex.quote(inner)}", runner)
    payload = _safe_json(response.stdout.strip()) if response.returncode == 0 else None
    status = _enum(payload.get("status") if payload else None, ENDPOINT_STATUS_STATES, "other")
    ok = response.returncode == 0 and payload is not None and (payload.get("ok") is True or status in {"ok", "healthy"})
    result["endpoints"][str(item["name"])] = {"ok": ok, "status": status}
  resources = _collect_host_resources(ssh_config, [str(item["name"]) for item in container_list], runner)
  result["resource_summary"] = resources
  result["ok"] = all(row["ok"] for row in result["containers"].values()) and all(row["ok"] for row in result["endpoints"].values()) and resources["ok"]
  return result


def _normalize_cli_cell(value: str) -> str:
  text = value.strip()
  return text[1:-1] if len(text) >= 2 and text[0] == text[-1] and text[0] in {'"', "'"} else text


def _sql_parts(output: str) -> tuple[list[str], list[dict[str, str]], bool]:
  cells = []
  separators = "-+=: ─┼┬┴╭╮╰╯├┤┌┐└┘"
  for raw in output.replace("│", "|").splitlines():
    line = raw.strip().strip("|").strip()
    if not line or all(char in separators for char in line):
      continue
    row = [_normalize_cli_cell(value) for value in (line.split("|") if "|" in line else [line])]
    if any(row):
      cells.append(row)
  if not cells:
    return [], [], True
  header = cells[0]
  malformed = not header or any(not name for name in header) or len(set(header)) != len(header)
  rows = []
  for row in cells[1:]:
    if len(row) != len(header):
      malformed = True
    else:
      rows.append(dict(zip(header, row)))
  return header, rows, malformed


def parse_sql_table(output: str) -> list[dict[str, str]]:
  _, rows, malformed = _sql_parts(output)
  return [] if malformed else rows


def _strict_bool(value: str) -> bool:
  if value.strip().lower() not in {"true", "false"}:
    raise RuntimeError("spacetime_data_invalid")
  return value.strip().lower() == "true"


def _strict_int(value: str, low: int, high: int) -> int:
  if not re.fullmatch(r"-?\d+", value.strip()):
    raise RuntimeError("spacetime_data_invalid")
  parsed = int(value)
  if not low <= parsed <= high:
    raise RuntimeError("spacetime_data_invalid")
  return parsed


def _strict_enum(value: str, allowed: set[str]) -> str:
  normalized = value.strip().lower()
  if normalized not in allowed:
    raise RuntimeError("spacetime_data_invalid")
  return normalized


def _strict_optional(status: Mapping[str, Any], key: str, kind: str, allowed: set[str] | None = None) -> Any:
  value = status.get(key)
  if value is None:
    return None
  if kind == "bool" and type(value) is bool:
    return value
  if kind == "int" and type(value) is int and -1 <= value <= 10**12:
    return value
  if kind == "enum" and isinstance(value, str) and allowed is not None:
    return _strict_enum(value, allowed)
  raise RuntimeError("spacetime_data_invalid")


PHONE_STATUS_FIELDS = {
  "stream_active": ("streamActive", "bool", None),
  "stream_verdict": ("streamVerdict", "enum", PIXEL_STREAM_VERDICTS),
  "session_state": ("sessionState", "enum", PIXEL_SESSION_STATES),
}
RELAY_STATUS_FIELDS = {
  "phone_connected": ("phoneConnected", "bool", None),
  "phone_desired": ("phoneDesired", "bool", None),
  "phone_stream_state": ("phoneStreamState", "enum", PHONE_STREAM_STATES),
  "live": ("live", "bool", None),
  "last_frame_ago_millis": ("lastFrameAgoMillis", "int", None),
}


def _select_strict_status(status: Mapping[str, Any], fields: Mapping[str, tuple[str, str, set[str] | None]]) -> dict[str, Any]:
  return {name: _strict_optional(status, key, kind, allowed) for name, (key, kind, allowed) in fields.items()}


def spacetime_sql(config: Mapping[str, Any], sql: str, columns: Sequence[str], runner: CommandRunner) -> list[dict[str, str]]:
  result = runner([
    str(config.get("binary", "spacetime")), "sql", "--yes", "-s", str(config["server"]), str(config["database"]), sql,
  ], float(config.get("timeout_seconds", 12)))
  if result.returncode != 0:
    raise RuntimeError("spacetime_query_failed")
  header, rows, malformed = _sql_parts(result.stdout)
  if malformed or header != list(columns):
    raise RuntimeError("spacetime_query_schema_invalid")
  return rows


def collect_spacetime(config: Mapping[str, Any], runner: CommandRunner) -> dict[str, Any]:
  login = runner([str(config.get("binary", "spacetime")), "login", "show"], float(config.get("timeout_seconds", 12)))
  identity = re.fullmatch(r"\s*You are logged in as ([0-9a-f]{64})\s*", login.stdout) if login.returncode == 0 else None
  if not identity:
    return {"ok": False, "error": "spacetime_operator_identity_unavailable", "operator_identity_verified": False}
  if identity[1] != str(config["expected_operator_identity"]):
    return {"ok": False, "error": "spacetime_operator_identity_mismatch", "operator_identity_verified": False}
  ticket = str(config["ticket_id"]).replace("'", "''")
  queries = [
    ("desired", "ticketremote_stream_desired_state", "desiredActive, viewerCount, reason", ["desiredActive", "viewerCount", "reason"]),
    ("phone", "ticketremote_phone_current_report", "streamState, desiredActive, statusJson", ["streamState", "desiredActive", "statusJson"]),
    ("relay", "ticketremote_relay_current_report", "videoClients, streamVerdict, lastFrameAgoMillis, statusJson", ["videoClients", "streamVerdict", "lastFrameAgoMillis", "statusJson"]),
    ("commands", "ticketremote_stream_command", "status", ["status"]),
  ]
  try:
    rows = {
      name: spacetime_sql(config, f"SELECT {fields} FROM {table} WHERE ticketId = '{ticket}';", columns, runner)
      for name, table, fields, columns in queries
    }
    if any(len(rows[name]) != 1 for name in ("desired", "phone", "relay")):
      raise RuntimeError("spacetime_current_state_missing")
    desired, phone, relay = rows["desired"][0], rows["phone"][0], rows["relay"][0]
    phone_json, relay_json = _safe_json(phone["statusJson"]), _safe_json(relay["statusJson"])
    if phone_json is None or relay_json is None:
      raise RuntimeError("spacetime_data_invalid")
    statuses = [_strict_enum(row["status"], STREAM_COMMAND_STATES) for row in rows["commands"]]
    result = {
      "ok": True, "operator_identity_verified": True,
      "desired_active": _strict_bool(desired["desiredActive"]),
      "viewer_count": _strict_int(desired["viewerCount"], 0, 1_000_000),
      "desired_reason": _enum(desired["reason"], DESIRED_REASON_STATES, "other"),
      "phone_stream_state": _strict_enum(phone["streamState"], PHONE_STREAM_STATES),
      "phone_desired_active": _strict_bool(phone["desiredActive"]),
      "phone_status": _select_strict_status(phone_json, PHONE_STATUS_FIELDS),
      "relay_video_clients": _strict_int(relay["videoClients"], 0, 1_000_000),
      "relay_stream_verdict": _strict_enum(relay["streamVerdict"], RELAY_STREAM_VERDICTS),
      "relay_last_frame_ago_millis": _strict_int(relay["lastFrameAgoMillis"], -1, 10**12),
      "relay_status": _select_strict_status(relay_json, RELAY_STATUS_FIELDS),
      "pending_stream_commands": sum(status not in TERMINAL_COMMAND_STATES for status in statuses),
    }
    return result
  except RuntimeError as error:
    return {"ok": False, "error": str(error), "operator_identity_verified": True}


def _dig(value: Mapping[str, Any], *keys: str) -> Any:
  current: Any = value
  for key in keys:
    if not isinstance(current, Mapping) or key not in current:
      return None
    current = current[key]
  return current


def _safe_int(value: Any, low: int = -1, high: int = 10**12) -> int | None:
  return value if type(value) is int and low <= value <= high else None


def _select_pixel_health(health: Mapping[str, Any]) -> dict[str, Any]:
  failure = _dig(health, "recovery", "lastDesiredRecoveryFailureReason")
  if failure not in (None, ""):
    failure = _enum(failure, RECOVERY_FAILURE_REASONS, "reported")
  return {
    "ok": health.get("ok") if type(health.get("ok")) is bool else None,
    "session_state": _enum(health.get("sessionState"), PIXEL_SESSION_STATES, "other"),
    "stream_active": health.get("streamActive") if type(health.get("streamActive")) is bool else None,
    "stream_verdict": _enum(health.get("streamVerdict"), PIXEL_STREAM_VERDICTS, "other"),
    "visible_frame_age_millis": _safe_int(_dig(health, "visibleFrame", "lastFrameAgoMillis")),
    "hardware_h264": {
      "active": _dig(health, "hardwareH264", "active") if type(_dig(health, "hardwareH264", "active")) is bool else None,
      "available": _dig(health, "hardwareH264", "available") if type(_dig(health, "hardwareH264", "available")) is bool else None,
      "visibility": _enum(_dig(health, "hardwareH264", "lastVisibilityCheckResult"), HARDWARE_VISIBILITY_STATES, "other"),
    },
    "stream_pipeline": {
      "video_clients": _safe_int(_dig(health, "streamPipeline", "videoClients"), 0),
      "encoder_running": _dig(health, "streamPipeline", "encoderRunning") if type(_dig(health, "streamPipeline", "encoderRunning")) is bool else None,
      "last_frame_sent_ago_millis": _safe_int(_dig(health, "streamPipeline", "lastFrameSentAgoMillis")),
    },
    "ticket_state": _enum(_dig(health, "ticketState", "state"), PIXEL_TICKET_STATES, "other"),
    "vivi_state": _enum(_dig(health, "viviState", "state"), VIVI_STATES, "OTHER_VIVI", lowercase=False),
    "recovery": {
      "desired_stage": _enum(_dig(health, "recovery", "desiredRecoveryStage"), RECOVERY_STAGE_STATES, "other"),
      "desired_result": _enum(_dig(health, "recovery", "lastDesiredRecoveryResult"), RECOVERY_RESULT_STATES, "other"),
      "desired_failure": failure,
      "stream_stage": _enum(_dig(health, "recovery", "streamStage"), RECOVERY_STAGE_STATES, "other"),
      "stream_result": _enum(_dig(health, "recovery", "lastStreamRecoveryResult"), RECOVERY_RESULT_STATES, "other"),
    },
  }


def _raw_enum(value: Any, allowed: set[str], lowercase: bool = True) -> bool:
  return isinstance(value, str) and (value.strip().lower() if lowercase else value.strip()) in allowed


def _pixel_health_contract_valid(health: Mapping[str, Any]) -> bool:
  if type(health.get("ok")) is not bool or type(health.get("streamActive")) is not bool:
    return False
  if not _raw_enum(health.get("sessionState"), PIXEL_SESSION_STATES) or not _raw_enum(health.get("streamVerdict"), PIXEL_STREAM_VERDICTS):
    return False
  hardware, recovery = health.get("hardwareH264"), health.get("recovery")
  ticket, vivi = health.get("ticketState"), health.get("viviState")
  if not isinstance(hardware, Mapping) or type(hardware.get("active")) is not bool or type(hardware.get("available")) is not bool or not _raw_enum(hardware.get("lastVisibilityCheckResult"), HARDWARE_VISIBILITY_STATES):
    return False
  if not isinstance(recovery, Mapping) or not all((
    _raw_enum(recovery.get("desiredRecoveryStage"), RECOVERY_STAGE_STATES),
    _raw_enum(recovery.get("lastDesiredRecoveryResult"), RECOVERY_RESULT_STATES),
    _raw_enum(recovery.get("streamStage"), RECOVERY_STAGE_STATES),
    _raw_enum(recovery.get("lastStreamRecoveryResult"), RECOVERY_RESULT_STATES),
    recovery.get("lastDesiredRecoveryFailureReason") is None or isinstance(recovery.get("lastDesiredRecoveryFailureReason"), str),
  )):
    return False
  if not isinstance(ticket, Mapping) or not _raw_enum(ticket.get("state"), PIXEL_TICKET_STATES):
    return False
  if not isinstance(vivi, Mapping) or not _raw_enum(vivi.get("state"), VIVI_STATES, False):
    return False
  if health["streamActive"] is False:
    return True
  frame, pipeline = health.get("visibleFrame"), health.get("streamPipeline")
  return (
    isinstance(frame, Mapping) and _safe_int(frame.get("lastFrameAgoMillis"), 0) is not None
    and isinstance(pipeline, Mapping) and _safe_int(pipeline.get("videoClients"), 0) is not None
    and type(pipeline.get("encoderRunning")) is bool
    and _safe_int(pipeline.get("lastFrameSentAgoMillis"), 0) is not None
  )


def _adb(config: Mapping[str, Any], runner: CommandRunner, *args: str) -> CommandResult:
  return runner([str(config.get("adb_binary", "adb")), "-s", str(config["serial"]), *args], float(config.get("timeout_seconds", 10)))


def _parse_battery(output: str) -> dict[str, Any] | None:
  fields = {}
  for line in output.splitlines()[:MAX_RESOURCE_LINES]:
    match = re.fullmatch(r"\s*(level|status|temperature):\s*(-?\d+)\s*", line)
    if match:
      fields[match[1]] = int(match[2])
  if not 0 <= fields.get("level", -1) <= 100 or "temperature" not in fields or "status" not in fields:
    return None
  return {"level_percent": fields["level"], "temperature_c": round(fields["temperature"] / 10, 1), "status_code": fields["status"]}


def _elapsed_seconds(value: str) -> int | None:
  match = re.fullmatch(r"(?:(\d+)-)?(\d+):(\d+):(\d+)", value)
  if match:
    days, hours, minutes, seconds = (int(item or 0) for item in match.groups())
    if hours < 24 and minutes < 60 and seconds < 60:
      return days * 86400 + hours * 3600 + minutes * 60 + seconds
    return None
  match = re.fullmatch(r"(\d+):(\d+)", value)
  if match:
    minutes, seconds = (int(item) for item in match.groups())
    return minutes * 60 + seconds if seconds < 60 else None
  return None


def _ticket_lifecycle_status(output: str) -> dict[str, Any]:
  lines = [line for line in output.splitlines()[:MAX_RESOURCE_LINES] if line.strip()]
  if not lines or lines[0].split()[:6] != ["PID", "PPID", "ELAPSED", "STAT", "NAME", "ARGS"]:
    return {"ok": False, "stuck_helper_count": 0, "stuck_start_stop_count": 0, "oldest_age_seconds": None}
  lifecycle: dict[int, int] = {}
  helpers: list[tuple[int, int]] = []
  for line in lines[1:]:
    fields = line.split(None, 5)
    if len(fields) != 6:
      continue
    try:
      pid, ppid = int(fields[0]), int(fields[1])
    except ValueError:
      continue
    age = _elapsed_seconds(fields[2])
    if age is None:
      continue
    name, args = fields[4], fields[5]
    if name == "sh" and re.match(r"^sh /data/local/pixel-stack/bin/pixel-ticket-(?:start|stop)\.sh(?:\s|$)", args):
      lifecycle[pid] = age
    elif name == "tr" and re.fullmatch(r"tr (?:\\000|\\0|\\x00)", args):
      helpers.append((ppid, age))
  stuck_parents = {pid: age for pid, age in lifecycle.items() if age > TICKET_LIFECYCLE_STUCK_SECONDS}
  stuck_helpers = [
    age for ppid, age in helpers
    if age > TICKET_LIFECYCLE_STUCK_SECONDS and (ppid in stuck_parents or ppid == 1)
  ]
  ages = [*stuck_parents.values(), *stuck_helpers]
  return {
    "ok": True,
    "stuck_helper_count": len(stuck_helpers),
    "stuck_start_stop_count": len(stuck_parents),
    "oldest_age_seconds": max(ages) if ages else None,
  }


def _pixel_resources(config: Mapping[str, Any], runner: CommandRunner) -> dict[str, Any]:
  battery, thermal, memory, disk = [
    _adb(config, runner, *args) for args in (
      ("shell", "dumpsys", "battery"), ("shell", "dumpsys", "thermalservice"),
      ("shell", "cat", "/proc/meminfo"), ("shell", "df", "-Pk", "/data"),
    )
  ]
  battery_value = _parse_battery(battery.stdout) if battery.returncode == 0 else None
  thermal_match = re.search(r"^\s*Thermal Status:\s*(-?\d+)\s*$", thermal.stdout, re.I | re.M) if thermal.returncode == 0 else None
  thermal_value = int(thermal_match[1]) if thermal_match and int(thermal_match[1]) >= 0 else None
  memory_value = _parse_meminfo(memory.stdout) if memory.returncode == 0 else None
  disk_value = _parse_disk(disk.stdout) if disk.returncode == 0 else None
  return {
    "ok": all(value is not None for value in (battery_value, thermal_value, memory_value, disk_value)),
    "battery": battery_value, "thermal_status": thermal_value, "memory": memory_value, "data_disk": disk_value,
  }


def collect_pixel(config: Mapping[str, Any], runner: CommandRunner) -> dict[str, Any]:
  state = _adb(config, runner, "get-state")
  adb_state = _enum(state.stdout.strip(), ADB_STATES, "other")
  result: dict[str, Any] = {"ok": False, "adb_state": adb_state}
  if state.returncode != 0 or adb_state != "device":
    return {**result, "error": "adb_unreachable"}
  command = f"{shlex.quote(str(config['curl_path']))} -fsS --max-time 5 {shlex.quote(str(config['health_url']))}"
  response = _adb(config, runner, "shell", "su", "-c", command)
  health = _safe_json(response.stdout.strip()) if response.returncode == 0 else None
  if not health:
    return {**result, "error": "pixel_health_unavailable"}
  rotation_results = [
    _adb(config, runner, "shell", "settings", "get", "system", key)
    for key in ("accelerometer_rotation", "user_rotation")
  ]
  rotations = [_as_int(item.stdout.strip(), -1) for item in rotation_results]
  resources = _pixel_resources(config, runner)
  lifecycle_result = _adb(
    config, runner, "shell", "su", "-c", TICKET_LIFECYCLE_PS_COMMAND,
  )
  lifecycle = _ticket_lifecycle_status(lifecycle_result.stdout) if lifecycle_result.returncode == 0 else {
    "ok": False, "stuck_helper_count": 0, "stuck_start_stop_count": 0, "oldest_age_seconds": None,
  }
  contract_ok = _pixel_health_contract_valid(health)
  result.update({
    "ok": health.get("ok") is True and contract_ok and rotations == [0, 0] and resources["ok"] and lifecycle["ok"],
    "health_contract_ok": contract_ok, "health": _select_pixel_health(health),
    "portrait_lock": {"ok": rotations == [0, 0], "accelerometer_rotation": rotations[0], "user_rotation": rotations[1]},
    "resources": resources, "ticket_lifecycle": lifecycle,
  })
  return result


def _metric(value: Any, high: float = 10000) -> float | None:
  return float(value) if not isinstance(value, bool) and isinstance(value, (int, float)) and 0 <= value <= high else None


def _classify(value: float, threshold: Mapping[str, Any], failure: str, warning: str, failures: list[str], warnings: list[str], low: bool = False) -> None:
  failed = value <= float(threshold["failure_below"]) if low else value >= float(threshold["failure"])
  warned = value <= float(threshold["warning_below"]) if low else value >= float(threshold["warning"])
  if failed:
    failures.append(failure)
  elif warned:
    warnings.append(warning)


def _evaluate_resources(host: Mapping[str, Any], pixel: Mapping[str, Any], thresholds: Mapping[str, Any]) -> tuple[list[str], list[str]]:
  failures: list[str] = []
  warnings: list[str] = []
  limits = thresholds["resources"]
  if host.get("ok") is True:
    summary = host.get("resource_summary") if isinstance(host.get("resource_summary"), Mapping) else {}
    memory, disk, docker = summary.get("memory"), summary.get("root_disk"), summary.get("docker")
    values = [
      (_metric(memory.get("used_percent"), 100) if isinstance(memory, Mapping) else None, "memory_used_percent", "host_memory_pressure", "Host memory usage is above its warning threshold."),
      (_metric(disk.get("used_percent"), 100) if isinstance(disk, Mapping) else None, "root_disk_used_percent", "host_root_disk_pressure", "Host root disk usage is above its warning threshold."),
    ]
    if isinstance(docker, Mapping) and docker:
      for row in docker.values():
        values.extend([
          (_metric(row.get("cpu_percent")) if isinstance(row, Mapping) else None, "container_cpu_percent", "host_container_cpu_pressure", "A Ticket container is above its CPU warning threshold."),
          (_metric(row.get("memory_percent"), 100) if isinstance(row, Mapping) else None, "container_memory_percent", "host_container_memory_pressure", "A Ticket container is above its memory warning threshold."),
        ])
    if not isinstance(docker, Mapping) or not docker or any(value is None for value, *_ in values):
      failures.append("host_resource_metrics_invalid")
    else:
      for value, field, failure, warning in values:
        _classify(value, limits["host"][field], failure, warning, failures, warnings)
  if pixel.get("ok") is True:
    summary = pixel.get("resources") if isinstance(pixel.get("resources"), Mapping) else {}
    battery, memory, disk = summary.get("battery"), summary.get("memory"), summary.get("data_disk")
    values = [
      (_metric(battery.get("level_percent"), 100) if isinstance(battery, Mapping) else None, "battery_level_percent", "pixel_battery_critical", "Pixel battery level is below its warning threshold.", True),
      (_metric(battery.get("temperature_c"), 100) if isinstance(battery, Mapping) else None, "battery_temperature_c", "pixel_battery_temperature_critical", "Pixel battery temperature is above its warning threshold.", False),
      (_metric(summary.get("thermal_status"), 6), "thermal_status", "pixel_thermal_critical", "Pixel thermal status is above its warning threshold.", False),
      (_metric(memory.get("used_percent"), 100) if isinstance(memory, Mapping) else None, "memory_used_percent", "pixel_memory_pressure", "Pixel memory usage is above its warning threshold.", False),
      (_metric(disk.get("used_percent"), 100) if isinstance(disk, Mapping) else None, "data_disk_used_percent", "pixel_data_disk_pressure", "Pixel data disk usage is above its warning threshold.", False),
    ]
    if any(value is None for value, *_ in values):
      failures.append("pixel_resource_metrics_invalid")
    else:
      for value, field, failure, warning, low in values:
        _classify(value, limits["pixel"][field], failure, warning, failures, warnings, low)
  return list(dict.fromkeys(failures)), list(dict.fromkeys(warnings))


def _recovery_failed(value: Mapping[str, Any]) -> bool:
  return bool(
    value.get("desired_stage") == "failed" or value.get("desired_result") == "failed"
    or value.get("desired_failure") or value.get("stream_stage") in {"blocked", "failed"}
    or value.get("stream_result") == "failed"
  )


def _ticket_lifecycle_valid(value: Any) -> bool:
  if not isinstance(value, Mapping) or set(value) != {
    "ok", "stuck_helper_count", "stuck_start_stop_count", "oldest_age_seconds",
  } or value.get("ok") is not True:
    return False
  helper_count, lifecycle_count, oldest = (
    value.get("stuck_helper_count"), value.get("stuck_start_stop_count"), value.get("oldest_age_seconds"),
  )
  if any(type(count) is not int or not 0 <= count <= 64 for count in (helper_count, lifecycle_count)):
    return False
  if helper_count + lifecycle_count == 0:
    return oldest is None
  return type(oldest) is int and TICKET_LIFECYCLE_STUCK_SECONDS < oldest <= 315_576_000


def evaluate_snapshot(snapshot: Mapping[str, Any], thresholds: Mapping[str, Any], standby_devices: Sequence[Any]) -> dict[str, Any]:
  surfaces = {name: snapshot.get(name, {}) for name in ("public", "host", "spacetime", "pixel")}
  failures = [f"{name}_unhealthy" for name, value in surfaces.items() if not isinstance(value, Mapping) or value.get("ok") is not True]
  warnings = [] if standby_devices else ["Only one physical Pixel is configured; standby failover cannot be proven without a second device."]
  resource_failures, resource_warnings = _evaluate_resources(surfaces["host"], surfaces["pixel"], thresholds)
  failures.extend(resource_failures)
  warnings.extend(resource_warnings)
  spacetime, pixel = surfaces["spacetime"], surfaces["pixel"]
  spacetime_ok, pixel_ok = spacetime.get("ok") is True, pixel.get("ok") is True
  active: bool | None = None
  mode = "unknown"
  health = pixel.get("health", {}) if isinstance(pixel.get("health"), Mapping) else {}
  if pixel_ok and not pixel.get("portrait_lock", {}).get("ok"):
    failures.append("portrait_unlocked")
  if pixel_ok:
    lifecycle = pixel.get("ticket_lifecycle") if isinstance(pixel.get("ticket_lifecycle"), Mapping) else {}
    if not _ticket_lifecycle_valid(lifecycle):
      failures.append("pixel_ticket_lifecycle_metrics_invalid")
    elif _as_int(lifecycle.get("stuck_helper_count")) > 0 or _as_int(lifecycle.get("stuck_start_stop_count")) > 0:
      failures.append("pixel_ticket_lifecycle_stuck")
  if spacetime_ok:
    desired = bool(spacetime.get("desired_active"))
    viewers = _as_int(spacetime.get("viewer_count"))
    active = desired or viewers > 0
    mode = "active_viewer" if active else "no_active_viewer"
    relay = spacetime.get("relay_status", {}) if isinstance(spacetime.get("relay_status"), Mapping) else {}
    add = lambda condition, name: failures.append(name) if condition else None
    add(_as_int(spacetime.get("pending_stream_commands")) != 0, "pending_stream_commands")
    if active:
      add(not desired or viewers < 1, "viewer_desired_state_mismatch")
      add(spacetime.get("phone_stream_state") != "streaming" or not spacetime.get("phone_desired_active"), "phone_report_not_streaming")
      add(spacetime.get("relay_stream_verdict") != "live" or _as_int(spacetime.get("relay_video_clients")) < 1, "relay_not_live")
      relay_age = _as_int(spacetime.get("relay_last_frame_ago_millis"), -1)
      add(relay_age < 0 or relay_age > _as_int(thresholds.get("relay_frame_age_millis"), 1000), "relay_frame_stale")
      add(not relay.get("phone_connected") or not relay.get("phone_desired") or not relay.get("live"), "relay_phone_state_mismatch")
      add(str(relay.get("phone_stream_state", "")).lower() != "streaming", "relay_phone_not_streaming")
      if pixel_ok:
        add(health.get("session_state") != "live" or not health.get("stream_active") or health.get("stream_verdict") != "live", "pixel_stream_not_live")
        maximum = _as_int(thresholds.get("pixel_frame_age_millis"), 1500)
        age = _as_int(health.get("visible_frame_age_millis"), -1)
        add(age < 0 or age > maximum, "pixel_frame_stale")
        hardware = health.get("hardware_h264", {})
        add(not hardware.get("active") or hardware.get("visibility") != "visible", "pixel_hardware_capture_not_visible")
        pipeline = health.get("stream_pipeline", {})
        add(_as_int(pipeline.get("video_clients")) < 1 or not pipeline.get("encoder_running"), "pixel_pipeline_not_live")
        pipeline_age = _as_int(pipeline.get("last_frame_sent_ago_millis"), -1)
        add(pipeline_age < 0 or pipeline_age > maximum, "pixel_pipeline_frame_stale")
        add(health.get("ticket_state") != "live" or health.get("vivi_state") != "TICKET_DETAIL", "pixel_ticket_state_not_live")
        recovery = health.get("recovery", {})
        add(isinstance(recovery, Mapping) and _recovery_failed(recovery), "pixel_recovery_failed")
      warnings.append("Authenticated browser proof was not collected by the standalone monitor; backend live proof remains authoritative for unattended checks.")
    else:
      add(desired or viewers != 0, "idle_desired_state_mismatch")
      if pixel_ok:
        add(bool(health.get("stream_active")), "idle_pixel_stream_still_active")
        add(health.get("stream_verdict") != "idle", "idle_pixel_stream_verdict")
        session, ticket = health.get("session_state"), health.get("ticket_state")
        add(session not in HEALTHY_IDLE_SESSION_STATES or ticket not in HEALTHY_IDLE_SESSION_STATES or ticket != session, "idle_pixel_state_not_settled")
        hardware = health.get("hardware_h264", {})
        add(hardware.get("active") is not False, "idle_pixel_hardware_capture_active")
        add(hardware.get("available") is not True, "pixel_hardware_capture_unavailable")
        recovery = health.get("recovery", {})
        add(not isinstance(recovery, Mapping) or _recovery_failed(recovery), "pixel_recovery_failed")
      add(spacetime.get("relay_stream_verdict") not in {"idle", "", None} or _as_int(spacetime.get("relay_video_clients")) != 0, "idle_relay_still_active")
  failures = list(dict.fromkeys(failures))
  status = "degraded" if failures else ("healthy_live" if active else "healthy_idle")
  return {
    "status": status, "viewer_mode": mode,
    "stream_verdict": "degraded" if failures else ("live" if active else "idle"),
    "frame_age_millis": health.get("visible_frame_age_millis") if active is True and pixel_ok else None,
    "failures": failures, "warnings": warnings,
  }


def atomic_write_json(path: Path, value: Mapping[str, Any]) -> None:
  path.parent.mkdir(parents=True, exist_ok=True)
  os.chmod(path.parent, 0o755)
  descriptor, name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
  temporary = Path(name)
  try:
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
      json.dump(value, handle, indent=2, sort_keys=True)
      handle.write("\n")
      handle.flush()
      os.fsync(handle.fileno())
    os.chmod(temporary, 0o644)
    os.replace(temporary, path)
  finally:
    temporary.unlink(missing_ok=True)


def _managed_evidence_directories(root: Path) -> list[Path]:
  if not root.exists():
    return []
  return sorted([
    child for child in root.iterdir()
    if child.is_dir() and MANAGED_EVIDENCE_DIRECTORY.fullmatch(child.name)
    and len(list(child.iterdir())) == 1 and (child / "summary.json").is_file()
  ], key=lambda path: path.name, reverse=True)


def prune_evidence_reports(root: Path, maximum: int) -> None:
  if type(maximum) is not int or not 1 <= maximum <= MAX_EVIDENCE_REPORTS:
    raise ValueError("evidence retention is outside the validated range")
  for directory in _managed_evidence_directories(root)[maximum:]:
    (directory / "summary.json").unlink()
    directory.rmdir()


def build_report(config: Mapping[str, Any], runner: CommandRunner = default_runner) -> dict[str, Any]:
  snapshot = {
    "public": collect_public(config["public"]),
    "host": collect_host(config["ssh"], config["containers"], config["container_endpoints"], runner),
    "spacetime": collect_spacetime(config["spacetime"], runner),
    "pixel": collect_pixel(config["pixel"], runner),
  }
  return {
    "timestamp": utc_now(), "monitor_version": VERSION,
    **evaluate_snapshot(snapshot, config["thresholds"], config.get("standby_devices", [])),
    "actions": [], "checked_surfaces": snapshot, "repair_attempted": False,
    "repair_result": "disabled", "no_browser_profile_changes": True,
  }


def write_report(report: dict[str, Any], output: Path, evidence_root: Path, maximum: int) -> None:
  if report["status"] in {"degraded", "blocked"}:
    directory = evidence_root / report["timestamp"].replace("-", "").replace(":", "")
    directory.mkdir(parents=True, exist_ok=True)
    os.chmod(evidence_root, 0o755)
    os.chmod(directory, 0o755)
    report["evidence_directory"] = str(directory)
    atomic_write_json(directory / "summary.json", report)
  prune_evidence_reports(evidence_root, maximum)
  atomic_write_json(output, report)


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("--config", type=Path, required=True)
  parser.add_argument("--output", type=Path, default=Path("state/ticket-health-monitor/latest.json"))
  parser.add_argument("--evidence-root", type=Path, default=Path("ops/evidence/ticket-health-monitor"))
  parser.add_argument("--check-config", action="store_true")
  parser.add_argument("--evaluate-snapshot", type=Path)
  return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
  args = parse_args(argv or sys.argv[1:])
  try:
    config = load_json(args.config)
    validate_config(config)
  except (OSError, ValueError, json.JSONDecodeError) as error:
    print(f"ticket health monitor config error: {error}", file=sys.stderr)
    return 2
  if args.check_config:
    print("Ticket health monitor configuration is valid and enabled." if config.get("enabled") else "Ticket health monitor configuration is valid and remains paused.")
    return 0
  if args.evaluate_snapshot:
    verdict = evaluate_snapshot(load_json(args.evaluate_snapshot), config["thresholds"], config.get("standby_devices", []))
    print(json.dumps(verdict, indent=2, sort_keys=True))
    return 0 if verdict["status"].startswith("healthy") else 1
  if not config.get("enabled"):
    print("Ticket health monitor is paused in product configuration; no probes or repairs were run.")
    return 3
  started = time.monotonic()
  report = build_report(config)
  report["duration_millis"] = int((time.monotonic() - started) * 1000)
  write_report(report, args.output, args.evidence_root, config["reporting"]["max_degraded_evidence_reports"])
  print(f"Ticket health: {report['status']} ({report['viewer_mode']})")
  return 0 if report["status"].startswith("healthy") else 1


if __name__ == "__main__":
  raise SystemExit(main())
