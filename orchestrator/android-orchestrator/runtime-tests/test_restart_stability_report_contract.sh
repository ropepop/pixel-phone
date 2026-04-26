#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
REPORT_SCRIPT="${REPO_ROOT}/scripts/ops/restart-stability-report.sh"

if [[ ! -x "${REPORT_SCRIPT}" ]]; then
  echo "FAIL: restart stability report script is missing or not executable: ${REPORT_SCRIPT}" >&2
  exit 1
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "FAIL: jq is required for restart stability report contract test" >&2
  exit 1
fi

tmpdir="$(mktemp -d)"
trap 'rm -rf "${tmpdir}"' EXIT

mkdir -p "${tmpdir}/output/pixel/redeploy/20260313T050940Z-26362"
mkdir -p "${tmpdir}/output/pixel/redeploy/20260313T054026Z-11111"
mkdir -p "${tmpdir}/output/pixel/redeploy/20260314T235256Z-2097"

cat > "${tmpdir}/output/pixel/redeploy/20260313T050940Z-26362/redeploy.log" <<'EOF_REDEPLOY'
03-13 07:10:26.508 I/OrchestratorActionReceiver(14603): command_accepted action=redeploy_component component=satiksme_bot run_id=20260313T050940Z-26362
EOF_REDEPLOY

cat > "${tmpdir}/output/pixel/redeploy/20260313T054026Z-11111/redeploy.log" <<'EOF_REDEPLOY'
03-13 07:40:26.508 I/OrchestratorActionReceiver(14999): command_accepted action=redeploy_component component=satiksme_bot run_id=20260313T054026Z-11111
EOF_REDEPLOY

cat > "${tmpdir}/output/pixel/redeploy/20260314T235256Z-2097/redeploy.log" <<'EOF_REDEPLOY'
03-15 01:54:29.452 I/OrchestratorActionReceiver(1483): command_accepted action=redeploy_component component=train_bot run_id=20260314T235256Z-2097
EOF_REDEPLOY

cat > "${tmpdir}/dns.log" <<'EOF_DNS'
[2026-03-12T02:42:03+0200] listener health failed 3/3; forcing restart
[2026-03-12T02:44:41+0200] start failed: reason=launch-core-failed rc=12
[2026-03-12T02:46:03+0200] listener health failed 3/3; forcing restart
[2026-03-12T02:47:20+0200] start failed: reason=launch-core-failed rc=12
[2026-03-12T02:49:03+0200] listener health failed 3/3; forcing restart
[2026-03-12T02:52:18+0200] runtime-tail: [2026-03-12T00:49:43+0000] status:listen_https :443 down
[2026-03-12T02:52:25+0200] runtime-tail: [2026-03-12T00:52:10+0000] status:listen_dot :853 down
[2026-03-12T14:14:01+0200] remote listener health failed 3/3; attempting frontend recovery
[2026-03-12T14:20:19+0200] remote listener health failed 3/3; attempting frontend recovery
[2026-03-12T17:41:41+0200] remote listener health failed 3/3; attempting frontend recovery
[2026-03-12T18:56:53+0200] remote listener health failed 3/3; attempting frontend recovery
EOF_DNS

cat > "${tmpdir}/train-loop.log" <<'EOF_TRAIN'
[2026-03-11T21:57:20+0200] train bot exited rc=1
[2026-03-11T21:57:51+0200] train bot exited rc=1
[2026-03-11T21:58:12+0200] train bot exited rc=1
[2026-03-11T22:00:43+0200] train bot exited rc=1
[2026-03-11T22:03:40+0200] train bot exited rc=1
[2026-03-11T22:07:18+0200] train bot exited rc=1
[2026-03-15T01:57:52+0200] train bot exited rc=143
EOF_TRAIN

cat > "${tmpdir}/satiksme.log" <<'EOF_SATIKSME'
2026/03/13 05:11:24 satiksme bot started (fixture)
2026/03/13 05:12:04 satiksme bot started (fixture)
2026/03/13 05:14:04 satiksme bot started (fixture)
2026/03/13 05:41:24 satiksme bot started (fixture)
2026/03/13 05:42:24 satiksme bot started (fixture)
EOF_SATIKSME

: > "${tmpdir}/ssh.log"
: > "${tmpdir}/site-notifier.log"
: > "${tmpdir}/logcat.txt"

json_out="${tmpdir}/restart-stability.json"
md_out="${tmpdir}/restart-stability.md"

bash "${REPORT_SCRIPT}" \
  --json-out "${json_out}" \
  --markdown-out "${md_out}" \
  --days 5 \
  --timezone Europe/Riga \
  --window-minutes 10 \
  --now 2026-03-16T12:00:00+02:00 \
  --redeploy-log-glob "${tmpdir}/output/pixel/redeploy/*/redeploy.log" \
  --dns-log-file "${tmpdir}/dns.log" \
  --train-loop-log-file "${tmpdir}/train-loop.log" \
  --satiksme-log-file "${tmpdir}/satiksme.log" \
  --ssh-log-file "${tmpdir}/ssh.log" \
  --site-notifier-log-file "${tmpdir}/site-notifier.log" \
  --logcat-file "${tmpdir}/logcat.txt" >/dev/null

if [[ ! -f "${json_out}" || ! -f "${md_out}" ]]; then
  echo "FAIL: expected restart stability report outputs to be written" >&2
  exit 1
fi

if ! jq -e '.findings[] | select(.component=="train_bot" and .kind=="crash_loop" and .event_count==6)' "${json_out}" >/dev/null; then
  echo "FAIL: expected train_bot crash loop finding with only non-maintenance rc=1 exits counted" >&2
  exit 1
fi

if ! jq -e '.findings[] | select(.component=="dns" and .kind=="restart_storm" and .event_count==5 and (.down_listeners | index("https:443")) and (.down_listeners | index("dot:853")))' "${json_out}" >/dev/null; then
  echo "FAIL: expected DNS restart storm finding with down listener evidence" >&2
  exit 1
fi

if ! jq -e '.findings[] | select(.component=="dns" and .kind=="remote_recovery_burst" and .event_count==4)' "${json_out}" >/dev/null; then
  echo "FAIL: expected DNS remote recovery burst finding" >&2
  exit 1
fi

if ! jq -e '.maintenance_dominated[] | select(.component=="satiksme_bot" and .excluded_events==5 and .considered_events==0)' "${json_out}" >/dev/null; then
  echo "FAIL: expected satiksme_bot churn to be marked maintenance-dominated" >&2
  exit 1
fi

if ! jq -e '.counts.train_bot.excluded_events == 1 and .counts.train_bot.considered_events == 6' "${json_out}" >/dev/null; then
  echo "FAIL: expected maintenance-adjacent train rc=143 event to be excluded from considered events" >&2
  exit 1
fi

if ! rg -Fq 'train_bot crash loop outside maintenance windows' "${md_out}"; then
  echo "FAIL: expected Markdown report to include the train_bot serious finding" >&2
  exit 1
fi

echo "PASS: restart stability report filters maintenance-induced churn and highlights serious findings"
