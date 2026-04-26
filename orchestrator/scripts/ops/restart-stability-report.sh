#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ORCHESTRATOR_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
WORKSPACE_ROOT="$(cd "${ORCHESTRATOR_ROOT}/.." && pwd)"

cd "${WORKSPACE_ROOT}"

TRANSPORT_SH="${WORKSPACE_ROOT}/tools/pixel/transport.sh"
LEGACY_TRANSPORT_SH="${WORKSPACE_ROOT}/legacy/pixel-runtime/tools/pixel/transport.sh"
if [[ -r "${TRANSPORT_SH}" ]]; then
  # shellcheck source=../../../tools/pixel/transport.sh
  source "${TRANSPORT_SH}"
elif [[ -r "${LEGACY_TRANSPORT_SH}" ]]; then
  # shellcheck source=../../../legacy/pixel-runtime/tools/pixel/transport.sh
  source "${LEGACY_TRANSPORT_SH}"
else
  pixel_transport_require_adb_serial() {
    local adb_bin="${ADB_BIN:-adb}"
    local serials=()
    local line=""

    command -v "${adb_bin}" >/dev/null 2>&1 || return 1

    while IFS= read -r line; do
      [[ -n "${line}" ]] || continue
      serials+=("${line}")
    done < <("${adb_bin}" devices | awk 'NR>1 && $2=="device" {print $1}')

    if (( ${#serials[@]} == 1 )); then
      printf '%s\n' "${serials[0]}"
      return 0
    fi
    if (( ${#serials[@]} > 1 )); then
      echo "Multiple adb devices found; pass --adb-serial." >&2
    fi
    return 1
  }
fi

ADB_SERIAL="${RESTART_STABILITY_ADB_SERIAL:-}"
OUTPUT_DIR="${RESTART_STABILITY_OUTPUT_DIR:-ops/evidence/orchestrator}"
DAYS="${RESTART_STABILITY_DAYS:-5}"
TIMEZONE_NAME="${RESTART_STABILITY_TIMEZONE:-Europe/Riga}"
WINDOW_MINUTES="${RESTART_STABILITY_WINDOW_MINUTES:-10}"
REDEPLOY_LOG_GLOB="${RESTART_STABILITY_REDEPLOY_LOG_GLOB:-output/pixel/redeploy/*/redeploy.log}"
NOW_ISO="${RESTART_STABILITY_NOW_ISO:-}"
JSON_OUT=""
MARKDOWN_OUT=""
DNS_LOG_FILE=""
TRAIN_LOOP_LOG_FILE=""
SATIKSME_LOG_FILE=""
SSH_LOG_FILE=""
SITE_NOTIFIER_LOG_FILE=""
LOGCAT_FILE=""

usage() {
  cat <<EOF_USAGE
Usage: $(basename "$0") [options]

Generate a filtered Pixel restart-stability report and exclude maintenance-induced churn.

Options:
  --adb-serial SERIAL          adb serial/device (default: first "device")
  --output-dir DIR             report directory (default: ${OUTPUT_DIR})
  --json-out PATH              explicit JSON output path (requires --markdown-out)
  --markdown-out PATH          explicit Markdown output path (requires --json-out)
  --days N                     analysis window in days (default: ${DAYS})
  --timezone TZ                timezone for local correlation (default: ${TIMEZONE_NAME})
  --window-minutes N           maintenance exclusion window in minutes (default: ${WINDOW_MINUTES})
  --redeploy-log-glob GLOB     host redeploy-log glob (default: ${REDEPLOY_LOG_GLOB})
  --now ISO8601                override analysis end timestamp for tests
  --dns-log-file PATH          use local DNS loop log instead of adb
  --train-loop-log-file PATH   use local train loop log instead of adb
  --satiksme-log-file PATH     use local satiksme app log instead of adb
  --ssh-log-file PATH          use local SSH loop log instead of adb
  --site-notifier-log-file PATH use local site-notifier loop log instead of adb
  --logcat-file PATH           use local logcat excerpt instead of adb
  -h, --help                   show this help text
EOF_USAGE
}

while (( $# > 0 )); do
  case "$1" in
    --adb-serial) shift; ADB_SERIAL="${1:-}" ;;
    --output-dir) shift; OUTPUT_DIR="${1:-}" ;;
    --json-out) shift; JSON_OUT="${1:-}" ;;
    --markdown-out) shift; MARKDOWN_OUT="${1:-}" ;;
    --days) shift; DAYS="${1:-}" ;;
    --timezone) shift; TIMEZONE_NAME="${1:-}" ;;
    --window-minutes) shift; WINDOW_MINUTES="${1:-}" ;;
    --redeploy-log-glob) shift; REDEPLOY_LOG_GLOB="${1:-}" ;;
    --now) shift; NOW_ISO="${1:-}" ;;
    --dns-log-file) shift; DNS_LOG_FILE="${1:-}" ;;
    --train-loop-log-file) shift; TRAIN_LOOP_LOG_FILE="${1:-}" ;;
    --satiksme-log-file) shift; SATIKSME_LOG_FILE="${1:-}" ;;
    --ssh-log-file) shift; SSH_LOG_FILE="${1:-}" ;;
    --site-notifier-log-file) shift; SITE_NOTIFIER_LOG_FILE="${1:-}" ;;
    --logcat-file) shift; LOGCAT_FILE="${1:-}" ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

for val in "${DAYS}" "${WINDOW_MINUTES}"; do
  if [[ ! "${val}" =~ ^[0-9]+$ ]] || (( val < 1 )); then
    echo "--days and --window-minutes must be positive integers" >&2
    exit 2
  fi
done

if [[ -n "${JSON_OUT}" || -n "${MARKDOWN_OUT}" ]]; then
  if [[ -z "${JSON_OUT}" || -z "${MARKDOWN_OUT}" ]]; then
    echo "--json-out and --markdown-out must be passed together" >&2
    exit 2
  fi
else
  mkdir -p "${OUTPUT_DIR}"
  stamp="$(date '+%Y%m%d-%H%M%S')"
  JSON_OUT="${OUTPUT_DIR}/restart-stability-${stamp}.json"
  MARKDOWN_OUT="${OUTPUT_DIR}/restart-stability-${stamp}.md"
fi

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

require_cmd python3

tmpdir="$(mktemp -d)"
trap 'rm -rf "${tmpdir}"' EXIT

require_adb=0
for file_path in \
  "${DNS_LOG_FILE}" \
  "${TRAIN_LOOP_LOG_FILE}" \
  "${SATIKSME_LOG_FILE}" \
  "${SSH_LOG_FILE}" \
  "${SITE_NOTIFIER_LOG_FILE}" \
  "${LOGCAT_FILE}"; do
  if [[ -z "${file_path}" ]]; then
    require_adb=1
    break
  fi
done

resolve_adb_target() {
  if [[ -n "${ADB_SERIAL}" ]]; then
    printf '%s' "${ADB_SERIAL}"
    return 0
  fi

  pixel_transport_require_adb_serial
}

adb_target=""
if (( require_adb == 1 )); then
  require_cmd adb
  adb_target="$(resolve_adb_target)"
  if [[ -z "${adb_target}" ]]; then
    echo "No adb target available. Pass --adb-serial or provide local log files." >&2
    exit 1
  fi
  if ! adb -s "${adb_target}" get-state >/dev/null 2>&1; then
    echo "ADB target unreachable: ${adb_target}" >&2
    exit 1
  fi
fi

adb_su() {
  local cmd="$1"
  local encoded
  encoded="$(printf '%s\n' "${cmd}" | base64 | tr -d '\n')"
  adb -s "${adb_target}" shell "echo '${encoded}' | base64 -d | su -c sh" 2>/dev/null || true
}

copy_or_collect() {
  local provided_path="$1"
  local remote_path="$2"
  local destination="$3"
  local mode="${4:-su_cat}"

  if [[ -n "${provided_path}" ]]; then
    if [[ ! -f "${provided_path}" ]]; then
      echo "Input file not found: ${provided_path}" >&2
      exit 2
    fi
    cp "${provided_path}" "${destination}"
    return 0
  fi

  case "${mode}" in
    su_cat)
      adb_su "cat '${remote_path}' 2>/dev/null || true" > "${destination}"
      ;;
    logcat)
      adb -s "${adb_target}" shell "logcat -d -v time -s OrchestratorActionReceiver BootReceiver SupervisorService 2>/dev/null || true" > "${destination}"
      ;;
    *)
      echo "Unknown collection mode: ${mode}" >&2
      exit 2
      ;;
  esac
}

dns_log_path="${tmpdir}/dns.log"
train_loop_log_path="${tmpdir}/train-loop.log"
satiksme_log_path="${tmpdir}/satiksme.log"
ssh_log_path="${tmpdir}/ssh.log"
site_notifier_log_path="${tmpdir}/site-notifier.log"
logcat_path="${tmpdir}/logcat.txt"

copy_or_collect "${DNS_LOG_FILE}" "/data/local/pixel-stack/logs/adguardhome-service-loop.log" "${dns_log_path}"
copy_or_collect "${TRAIN_LOOP_LOG_FILE}" "/data/local/pixel-stack/apps/train-bot/logs/service-loop.log" "${train_loop_log_path}"
copy_or_collect "${SATIKSME_LOG_FILE}" "/data/local/pixel-stack/apps/satiksme-bot/logs/satiksme-bot.log" "${satiksme_log_path}"
copy_or_collect "${SSH_LOG_FILE}" "/data/local/pixel-stack/ssh/logs/pixel-ssh-service-loop.log" "${ssh_log_path}"
copy_or_collect "${SITE_NOTIFIER_LOG_FILE}" "/data/local/pixel-stack/apps/site-notifications/logs/service-loop.log" "${site_notifier_log_path}"
copy_or_collect "${LOGCAT_FILE}" "" "${logcat_path}" "logcat"

export RS_ADB_SERIAL="${adb_target}"
export RS_DAYS="${DAYS}"
export RS_TIMEZONE_NAME="${TIMEZONE_NAME}"
export RS_WINDOW_MINUTES="${WINDOW_MINUTES}"
export RS_REDEPLOY_LOG_GLOB="${REDEPLOY_LOG_GLOB}"
export RS_NOW_ISO="${NOW_ISO}"
export RS_DNS_LOG_PATH="${dns_log_path}"
export RS_TRAIN_LOOP_LOG_PATH="${train_loop_log_path}"
export RS_SATIKSME_LOG_PATH="${satiksme_log_path}"
export RS_SSH_LOG_PATH="${ssh_log_path}"
export RS_SITE_NOTIFIER_LOG_PATH="${site_notifier_log_path}"
export RS_LOGCAT_PATH="${logcat_path}"
export RS_JSON_OUT="${JSON_OUT}"
export RS_MARKDOWN_OUT="${MARKDOWN_OUT}"

python3 <<'PY'
import glob
import json
import os
import re
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo


@dataclass
class Event:
    component: str
    kind: str
    timestamp: datetime
    line: str
    details: dict
    excluded: bool = False
    exclusion_hits: list | None = None


def env(name: str, default: str = "") -> str:
    return os.environ.get(name, default)


tz_name = env("RS_TIMEZONE_NAME", "Europe/Riga")
analysis_tz = ZoneInfo(tz_name)
window_minutes = int(env("RS_WINDOW_MINUTES", "10"))
days = int(env("RS_DAYS", "5"))
now_iso = env("RS_NOW_ISO")

if now_iso:
    now_local = datetime.fromisoformat(now_iso)
    if now_local.tzinfo is None:
        now_local = now_local.replace(tzinfo=analysis_tz)
    else:
        now_local = now_local.astimezone(analysis_tz)
else:
    now_local = datetime.now(analysis_tz)

analysis_start = now_local - timedelta(days=days)
maintenance_delta = timedelta(minutes=window_minutes)
year_hint = now_local.year


def iso(dt: datetime | None) -> str | None:
    return None if dt is None else dt.isoformat()


def in_window(dt: datetime) -> bool:
    return analysis_start <= dt <= now_local


def parse_bracket_ts(raw: str) -> datetime:
    return datetime.strptime(raw, "%Y-%m-%dT%H:%M:%S%z").astimezone(analysis_tz)


def parse_mmdd_ts(raw: str) -> datetime:
    dt = datetime.strptime(f"{year_hint}-{raw}", "%Y-%m-%d %H:%M:%S.%f").replace(tzinfo=analysis_tz)
    if dt > now_local + timedelta(days=1):
        dt = dt.replace(year=dt.year - 1)
    return dt


def parse_satiksme_ts(raw: str) -> datetime:
    return datetime.strptime(raw, "%Y/%m/%d %H:%M:%S").replace(tzinfo=timezone.utc).astimezone(analysis_tz)


def read_text(path_env: str) -> list[str]:
    path = Path(env(path_env))
    if not path.exists():
        return []
    return path.read_text(encoding="utf-8", errors="ignore").splitlines()


redeploy_logs = sorted(glob.glob(env("RS_REDEPLOY_LOG_GLOB")))
maintenance_actions: list[dict] = []
action_pattern = re.compile(
    r"(?P<ts>\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}).*command_accepted action=(?P<action>[a-z_]+) component=(?P<component>\S*) run_id=(?P<run_id>\S+)"
)

for path in redeploy_logs:
    for line in Path(path).read_text(encoding="utf-8", errors="ignore").splitlines():
        match = action_pattern.search(line)
        if not match:
            continue
        action = match.group("action")
        if action not in {"bootstrap", "redeploy_component", "restart_component"}:
            continue
        dt = parse_mmdd_ts(match.group("ts"))
        if not (analysis_start - maintenance_delta <= dt <= now_local + maintenance_delta):
            continue
        component = match.group("component")
        maintenance_actions.append(
            {
                "timestamp": dt,
                "action": action,
                "component": component if component else "all",
                "run_id": match.group("run_id"),
                "source": path,
                "window_start": dt - maintenance_delta,
                "window_end": dt + maintenance_delta,
            }
        )

boot_pattern = re.compile(r"(?P<ts>\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}).*(BOOT_COMPLETED|MY_PACKAGE_REPLACED|ACTION_BOOT_START)")
for line in read_text("RS_LOGCAT_PATH"):
    match = boot_pattern.search(line)
    if not match:
        continue
    dt = parse_mmdd_ts(match.group("ts"))
    if not (analysis_start - maintenance_delta <= dt <= now_local + maintenance_delta):
        continue
    maintenance_actions.append(
        {
            "timestamp": dt,
            "action": "boot_or_update",
            "component": "all",
            "run_id": None,
            "source": env("RS_LOGCAT_PATH"),
            "window_start": dt - maintenance_delta,
            "window_end": dt + maintenance_delta,
        }
    )

maintenance_actions.sort(key=lambda item: item["timestamp"])


def exclusion_hits(timestamp: datetime, component: str) -> list[dict]:
    hits = []
    for item in maintenance_actions:
        if item["window_start"] <= timestamp <= item["window_end"]:
            if item["component"] == "all" or item["component"] == component:
                hits.append(item)
    return hits


events: list[Event] = []
dns_statuses: list[dict] = []

bracket_line = re.compile(r"^\[(?P<ts>[^\]]+)\] (?P<body>.*)$")
for line in read_text("RS_DNS_LOG_PATH"):
    match = bracket_line.match(line)
    if not match:
        continue
    dt = parse_bracket_ts(match.group("ts"))
    if not in_window(dt):
        continue
    body = match.group("body")
    if "listener health failed 3/3; forcing restart" in body:
        events.append(Event("dns", "forcing_restart", dt, line, {}))
    elif body.startswith("start failed:"):
        events.append(Event("dns", "start_failed", dt, line, {"reason": body}))
    elif "remote listener health failed 3/3; attempting frontend recovery" in body:
        events.append(Event("dns", "remote_recovery", dt, line, {}))
    if "status:listen_" in body and body.endswith(" down"):
        listener_match = re.search(r"status:listen_([a-z_]+) :(\d+) down", body)
        if listener_match:
            dns_statuses.append(
                {
                    "timestamp": dt,
                    "listener": listener_match.group(1),
                    "port": listener_match.group(2),
                    "line": line,
                }
            )

train_exit = re.compile(r"^\[(?P<ts>[^\]]+)\] train bot exited rc=(?P<rc>\d+)")
for line in read_text("RS_TRAIN_LOOP_LOG_PATH"):
    match = train_exit.match(line)
    if not match:
        continue
    dt = parse_bracket_ts(match.group("ts"))
    if not in_window(dt):
        continue
    events.append(Event("train_bot", "exit", dt, line, {"rc": int(match.group("rc"))}))

satiksme_start = re.compile(r"^(?P<ts>\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}) satiksme bot started(?: \((?P<build>.*)\))?$")
for line in read_text("RS_SATIKSME_LOG_PATH"):
    match = satiksme_start.match(line)
    if not match:
        continue
    dt = parse_satiksme_ts(match.group("ts"))
    if not in_window(dt):
        continue
    events.append(Event("satiksme_bot", "started", dt, line, {"build": match.group("build") or ""}))

ssh_exit = re.compile(r"^\[(?P<ts>[^\]]+)\] dropbear exited rc=(?P<rc>\d+)")
ssh_duplicate = "another pixel-ssh-service-loop instance is already running"
for line in read_text("RS_SSH_LOG_PATH"):
    match = ssh_exit.match(line)
    if match:
        dt = parse_bracket_ts(match.group("ts"))
        if in_window(dt):
            events.append(Event("ssh", "exit", dt, line, {"rc": int(match.group("rc"))}))
        continue
    if ssh_duplicate in line:
        match = bracket_line.match(line)
        if not match:
            continue
        dt = parse_bracket_ts(match.group("ts"))
        if in_window(dt):
            events.append(Event("ssh", "duplicate_loop", dt, line, {}))

site_exit = re.compile(r"^\[(?P<ts>[^\]]+)\] site notifier exited rc=(?P<rc>\d+)")
for line in read_text("RS_SITE_NOTIFIER_LOG_PATH"):
    match = site_exit.match(line)
    if not match:
        continue
    dt = parse_bracket_ts(match.group("ts"))
    if not in_window(dt):
        continue
    events.append(Event("site_notifier", "exit", dt, line, {"rc": int(match.group("rc"))}))

for event in events:
    hits = exclusion_hits(event.timestamp, event.component)
    event.excluded = bool(hits)
    event.exclusion_hits = [
        {
            "action": item["action"],
            "component": item["component"],
            "timestamp": iso(item["timestamp"]),
            "source": item["source"],
        }
        for item in hits
    ]


def group_events(candidates: list[Event], gap_minutes: int) -> list[list[Event]]:
    if not candidates:
        return []
    ordered = sorted(candidates, key=lambda item: item.timestamp)
    groups = [[ordered[0]]]
    max_gap = timedelta(minutes=gap_minutes)
    for event in ordered[1:]:
        if event.timestamp - groups[-1][-1].timestamp <= max_gap:
            groups[-1].append(event)
        else:
            groups.append([event])
    return groups


def summarize_cluster(component: str, kind: str, severity: str, title: str, cluster: list[Event], note: str, extra: dict | None = None) -> dict:
    payload = {
        "component": component,
        "kind": kind,
        "severity": severity,
        "title": title,
        "window_start": iso(cluster[0].timestamp),
        "window_end": iso(cluster[-1].timestamp),
        "event_count": len(cluster),
        "note": note,
        "evidence_lines": [event.line for event in cluster[:5]],
    }
    if extra:
        payload.update(extra)
    return payload


findings: list[dict] = []
maintenance_dominated: list[dict] = []
counts = defaultdict(lambda: {"total_events": 0, "excluded_events": 0, "considered_events": 0})
for event in events:
    counts[event.component]["total_events"] += 1
    if event.excluded:
        counts[event.component]["excluded_events"] += 1
    else:
        counts[event.component]["considered_events"] += 1

train_rc1 = [event for event in events if event.component == "train_bot" and event.kind == "exit" and event.details.get("rc") == 1 and not event.excluded]
for cluster in group_events(train_rc1, 15):
    if len(cluster) >= 5:
        findings.append(
            summarize_cluster(
                "train_bot",
                "crash_loop",
                "serious",
                "train_bot crash loop outside maintenance windows",
                cluster,
                f"{len(cluster)} rc=1 exits in a tight burst without a matching redeploy/update window.",
            )
        )

dns_restart = [
    event
    for event in events
    if event.component == "dns"
    and event.kind in {"forcing_restart", "start_failed"}
    and not event.excluded
]
for cluster in group_events(dns_restart, 15):
    if len(cluster) < 3:
        continue
    forced = sum(event.kind == "forcing_restart" for event in cluster)
    launch_core = sum("launch-core-failed" in event.line for event in cluster)
    nearby_statuses = sorted(
        {
            f"{item['listener']}:{item['port']}"
            for item in dns_statuses
            if cluster[0].timestamp - timedelta(minutes=2) <= item["timestamp"] <= cluster[-1].timestamp + timedelta(minutes=5)
        }
    )
    findings.append(
        summarize_cluster(
            "dns",
            "restart_storm",
            "serious",
            "dns restart storm with listener failures",
            cluster,
            f"{forced} forced restarts and {launch_core} launch-core failures outside maintenance windows.",
            {"down_listeners": nearby_statuses},
        )
    )

dns_remote = [
    event
    for event in events
    if event.component == "dns" and event.kind == "remote_recovery" and not event.excluded
]
for cluster in group_events(dns_remote, 240):
    if len(cluster) < 3:
        continue
    findings.append(
        summarize_cluster(
            "dns",
            "remote_recovery_burst",
            "warning",
            "dns remote frontend recovery burst",
            cluster,
            f"{len(cluster)} remote frontend recovery attempts succeeded, but indicate recurring remote instability.",
        )
    )

satiksme_starts = [event for event in events if event.component == "satiksme_bot" and event.kind == "started"]
satiksme_non_maintenance = [event for event in satiksme_starts if not event.excluded]
for cluster in group_events(satiksme_non_maintenance, 15):
    if len(cluster) >= 5:
        findings.append(
            summarize_cluster(
                "satiksme_bot",
                "restart_burst",
                "warning",
                "satiksme_bot repeated starts outside maintenance windows",
                cluster,
                f"{len(cluster)} autonomous starts detected outside redeploy windows.",
            )
        )

for component, metrics in sorted(counts.items()):
    total = metrics["total_events"]
    excluded = metrics["excluded_events"]
    if total >= 5 and excluded >= int(total * 0.75):
        maintenance_dominated.append(
            {
                "component": component,
                "total_events": total,
                "excluded_events": excluded,
                "considered_events": metrics["considered_events"],
                "note": "Most restart-like events align with maintenance windows and should not drive stability priorities.",
            }
        )

severity_order = {"serious": 0, "warning": 1, "info": 2}
findings.sort(key=lambda item: (severity_order.get(item["severity"], 9), item["window_start"] or ""))

report = {
    "timestamp": iso(now_local),
    "analysis_window": {
        "days": days,
        "timezone": tz_name,
        "window_minutes": window_minutes,
        "started_at": iso(analysis_start),
        "ended_at": iso(now_local),
    },
    "inputs": {
        "adb_serial": env("RS_ADB_SERIAL") or None,
        "redeploy_log_glob": env("RS_REDEPLOY_LOG_GLOB"),
        "dns_log_file": env("RS_DNS_LOG_PATH"),
        "train_loop_log_file": env("RS_TRAIN_LOOP_LOG_PATH"),
        "satiksme_log_file": env("RS_SATIKSME_LOG_PATH"),
        "ssh_log_file": env("RS_SSH_LOG_PATH"),
        "site_notifier_log_file": env("RS_SITE_NOTIFIER_LOG_PATH"),
        "logcat_file": env("RS_LOGCAT_PATH"),
    },
    "maintenance_actions": [
        {
            "timestamp": iso(item["timestamp"]),
            "action": item["action"],
            "component": item["component"],
            "run_id": item["run_id"],
            "source": item["source"],
            "window_start": iso(item["window_start"]),
            "window_end": iso(item["window_end"]),
        }
        for item in maintenance_actions
    ],
    "counts": {component: dict(metrics) for component, metrics in counts.items()},
    "findings": findings,
    "maintenance_dominated": maintenance_dominated,
}

json_out = Path(env("RS_JSON_OUT"))
json_out.parent.mkdir(parents=True, exist_ok=True)
json_out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

md_lines = [
    "# Pixel Restart Stability Report",
    "",
    f"- Generated: {report['timestamp']}",
    f"- Window: {report['analysis_window']['started_at']} to {report['analysis_window']['ended_at']}",
    f"- Timezone: {tz_name}",
    f"- Maintenance exclusion window: {window_minutes} minutes",
]
if report["inputs"]["adb_serial"]:
    md_lines.append(f"- ADB serial: {report['inputs']['adb_serial']}")
md_lines.extend(
    [
        "",
        "## Serious Findings",
        "",
    ]
)

if findings:
    for finding in findings:
        md_lines.extend(
            [
                f"### [{finding['severity']}] {finding['title']}",
                "",
                f"- Component: {finding['component']}",
                f"- Window: {finding['window_start']} to {finding['window_end']}",
                f"- Event count: {finding['event_count']}",
                f"- Note: {finding['note']}",
            ]
        )
        if finding.get("down_listeners"):
            md_lines.append(f"- Down listeners observed nearby: {', '.join(finding['down_listeners'])}")
        md_lines.append("- Evidence:")
        for line in finding.get("evidence_lines", []):
            md_lines.append(f"  - {line}")
        md_lines.append("")
else:
    md_lines.extend(["No non-maintenance restart findings met the configured heuristics.", ""])

md_lines.extend(["## Maintenance-Dominated Churn", ""])
if maintenance_dominated:
    for item in maintenance_dominated:
        md_lines.extend(
            [
                f"- {item['component']}: total={item['total_events']} excluded={item['excluded_events']} considered={item['considered_events']}",
                f"  {item['note']}",
            ]
        )
else:
    md_lines.append("- none")

md_lines.extend(["", "## Event Counts", "", "| Component | Total | Excluded | Considered |", "| --- | ---: | ---: | ---: |"])
for component in sorted(counts):
    metrics = counts[component]
    md_lines.append(
        f"| {component} | {metrics['total_events']} | {metrics['excluded_events']} | {metrics['considered_events']} |"
    )

markdown_out = Path(env("RS_MARKDOWN_OUT"))
markdown_out.parent.mkdir(parents=True, exist_ok=True)
markdown_out.write_text("\n".join(md_lines) + "\n", encoding="utf-8")
PY

echo "JSON report written: ${JSON_OUT}"
echo "Markdown report written: ${MARKDOWN_OUT}"
