#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "$SCRIPT_DIR/common.sh"
# shellcheck source=./browser_use.sh
source "$SCRIPT_DIR/browser_use.sh"

ensure_output_dirs

for cmd in curl python3; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    log "Missing required command: $cmd"
    exit 1
  fi
done
browser_use_require_cli

if [[ -f "$REPO_ROOT/.env" ]]; then
  set -a
  # shellcheck source=/dev/null
  . "$REPO_ROOT/.env"
  set +a
fi

public_base_url="${TRAIN_WEB_PUBLIC_BASE_URL:-https://train-bot.jolkins.id.lv}"
out_dir="${BROWSER_USE_SMOKE_OUT_DIR:-$REPO_ROOT/output/browser-use/pixel-public-smoke}"
session_name="${BROWSER_USE_PUBLIC_SESSION:-${BROWSER_USE_SESSION:-ttb-public-smoke}}"

train_id="$(
  python3 - "${public_base_url}" <<'PY'
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

base_url = sys.argv[1].rstrip("/")
headers = {"User-Agent": "Mozilla/5.0 (pixel-public-smoke)"}

def fetch_json(url: str):
    last_error = None
    for _ in range(10):
        req = urllib.request.Request(url, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=20) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            last_error = exc
            if exc.code not in (502, 503, 504, 520, 522, 524, 530):
                raise
        except Exception as exc:
            last_error = exc
        time.sleep(2)
    raise last_error

dashboard = fetch_json(f"{base_url}/api/v1/public/dashboard")
for item in dashboard.get("trains", []):
    train = item.get("train") or {}
    train_id = (train.get("id") or "").strip()
    if not train_id:
        continue
    stops_url = f"{base_url}/api/v1/public/trains/{urllib.parse.quote(train_id, safe='')}/stops"
    stops_payload = fetch_json(stops_url)
    stops = stops_payload.get("stops") or []
    if any(stop.get("latitude") is not None and stop.get("longitude") is not None for stop in stops):
        print(train_id)
        raise SystemExit(0)
raise SystemExit("no public train with mapped stops found")
PY
)"

station_probe="$(
  python3 - "${public_base_url}" <<'PY'
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

base_url = sys.argv[1].rstrip("/")
headers = {"User-Agent": "Mozilla/5.0 (pixel-public-smoke)"}
fold_table = str.maketrans({
    "ā": "a",
    "č": "c",
    "ē": "e",
    "ģ": "g",
    "ī": "i",
    "ķ": "k",
    "ļ": "l",
    "ņ": "n",
    "š": "s",
    "ū": "u",
    "ž": "z",
})

def normalize(value: str) -> str:
    return " ".join(value.strip().lower().translate(fold_table).replace("-", " ").split())

def fetch_json(url: str):
    last_error = None
    for _ in range(10):
        req = urllib.request.Request(url, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=20) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            last_error = exc
            if exc.code not in (502, 503, 504, 520, 522, 524, 530):
                raise
        except Exception as exc:
            last_error = exc
        time.sleep(2)
    raise last_error

stations = fetch_json(f"{base_url}/api/v1/public/stations").get("stations", [])
target = None
for station in stations:
    station_id = (station.get("id") or "").strip()
    station_name = (station.get("name") or "").strip()
    normalized_name = normalize(station_name)
    if not station_id or not normalized_name:
        continue
    raw_name = " ".join(station_name.strip().lower().replace("-", " ").split())
    if normalized_name != raw_name:
        target = {
            "id": station_id,
            "name": station_name,
            "query": normalized_name,
        }
        break

if target is None:
    raise SystemExit("no accent-bearing public station found for plain-latin search verification")

query_url = f"{base_url}/api/v1/public/stations?q={urllib.parse.quote(target['query'], safe='')}"
matches = fetch_json(query_url).get("stations", [])
if not any((station.get("id") or "").strip() == target["id"] for station in matches):
    raise SystemExit(
        f"plain-latin station query {target['query']!r} did not return {target['name']!r} ({target['id']})"
    )

print(f"{target['id']}\t{target['name']}\t{target['query']}")
PY
)"

IFS=$'\t' read -r station_probe_id station_probe_name station_probe_query <<<"$station_probe"
log "Verified plain-latin public station search: query=${station_probe_query} station=${station_probe_name} (${station_probe_id})"

mkdir -p "$out_dir"
rm -f \
  "$out_dir/public-smoke-console.log" \
  "$out_dir/public-smoke-network.log" \
  "$out_dir/home.png" \
  "$out_dir/departures.png" \
  "$out_dir/incidents.png" \
  "$out_dir/incidents-mobile.png" \
  "$out_dir/network-map.png" \
  "$out_dir/train-map.png"

cleanup() {
  browser_use_run "$session_name" close >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() {
  log "$1"
  exit 1
}

js_home_ready="$(cat <<'JS'
(() => {
  const links = Array.from(document.querySelectorAll('a'));
  const mapCount = links.filter((node) => {
    const text = (node.textContent || '').trim();
    if (!/^(Map|Karte)$/i.test(text)) {
      return false;
    }
    const href = node.getAttribute('href') || '';
    if (!href) {
      return false;
    }
    const path = new URL(href, window.location.href).pathname;
    return /\/map$/.test(path) && !/\/t\/.+\/map$/.test(path);
  }).length;
  const standalone = document.querySelectorAll('#public-network-map-panel').length;
  const inline = document.querySelectorAll('.train-map').length;
  const homeok = (mapCount > 0 && inline === 0) || (standalone > 0 && inline > 0) ? 1 : 0;
  return `homeok=${homeok};mapbutton=${mapCount};legacy=${document.querySelectorAll('#public-stations-map-panel').length};standalone=${standalone};inline=${inline}`;
})()
JS
)"

js_incidents_ready="$(cat <<'JS'
(() => {
  const links = Array.from(document.querySelectorAll('a'));
  const hasLink = (matcher, pathCheck) => links.some((node) => {
    const text = (node.textContent || '').trim();
    if (!matcher.test(text)) {
      return false;
    }
    const href = node.getAttribute('href') || '';
    if (!href) {
      return false;
    }
    const path = new URL(href, window.location.href).pathname;
    return pathCheck(path);
  });
  const emptyTexts = Array.from(document.querySelectorAll('.shell .empty')).map((node) => (node.textContent || '').trim());
  const summaryEmpty = emptyTexts.some((text) => /No incidents/i.test(text)) ? 1 : 0;
  const detailPrompt = emptyTexts.some((text) => /Choose an incident/i.test(text)) ? 1 : 0;
  return `shell=${document.querySelectorAll('.shell').length};departures=${hasLink(/^Departures$/i, (path) => path.endsWith('/departures')) ? 1 : 0};stations=${hasLink(/^Station search$/i, (path) => path === '/stations' || path === '/' || path === '') ? 1 : 0};map=${hasLink(/^Map$/i, (path) => path.endsWith('/map') && !path.includes('/t/')) ? 1 : 0};panels=${document.querySelectorAll('.split .panel').length};cards=${document.querySelectorAll('.incident-card').length};badges=${document.querySelectorAll('.badge').length};selected=${document.querySelectorAll('.incident-card.selected-train-card').length};summaryEmpty=${summaryEmpty};detailPrompt=${detailPrompt}`;
})()
JS
)"

js_public_network_map_ready="$(cat <<'JS'
(() => `map=${document.querySelectorAll('.train-map').length};sightings=${document.querySelectorAll('#public-network-map-sightings-card').length}`)()
JS
)"

js_public_map_ready="$(cat <<'JS'
(() => `map=${document.querySelectorAll('.train-map').length};stops=${document.querySelectorAll('.stop-row').length}`)()
JS
)"

js_has_stops_map_cta="$(cat <<'JS'
(() => {
  const count = Array.from(document.querySelectorAll('a,button')).filter((node) => /Stops map|Pieturu mape/i.test((node.textContent || '').trim())).length;
  return `cta=${count}`;
})()
JS
)"

browser_use_run "$session_name" open "${public_base_url}/"
browser_use_run "$session_name" set viewport 1400 1100 >/dev/null 2>&1 || true
browser_use_run "$session_name" console --clear >/dev/null 2>&1 || true
browser_use_run "$session_name" network requests --clear >/dev/null 2>&1 || true

if ! browser_use_wait_for_eval_match "$session_name" "$js_home_ready" 'homeok=1' 20 1; then
  fail "Public smoke failed: homepage did not expose the network map entry cleanly"
fi
browser_use_try_screenshot "$session_name" "$out_dir/home.png"

browser_use_run "$session_name" open "${public_base_url}/departures"
if ! browser_use_wait_for_eval_match "$session_name" "$js_home_ready" 'mapbutton=[1-9]' 20 1; then
  fail "Public smoke failed: departures did not expose the public map entry"
fi
browser_use_try_screenshot "$session_name" "$out_dir/departures.png"

browser_use_run "$session_name" open "${public_base_url}/t/${train_id}"
if ! browser_use_wait_for_eval_match "$session_name" "$js_has_stops_map_cta" 'cta=[1-9]' 20 1; then
  fail "Public smoke failed: train detail page did not expose the Stops map CTA"
fi

browser_use_run "$session_name" open "${public_base_url}/map"
if ! browser_use_wait_for_eval_match "$session_name" "$js_public_network_map_ready" 'map=[1-9].*sightings=[1-9]' 24 1; then
  fail "Public smoke failed: network map did not render the map and sightings card"
fi
browser_use_try_screenshot "$session_name" "$out_dir/network-map.png"

browser_use_run "$session_name" open "${public_base_url}/incidents"
if ! browser_use_wait_for_eval_match "$session_name" "$js_incidents_ready" 'shell=[1-9].*departures=1.*stations=1.*map=1.*panels=[1-9]' 20 1; then
  fail "Public smoke failed: incidents view did not render the public shell and detail panels"
fi
if ! browser_use_output_has '(cards=[1-9].*(badges=[1-9]|detailPrompt=1))|(cards=0.*summaryEmpty=1.*detailPrompt=1)'; then
  fail "Public smoke failed: incidents view did not render either a populated list or the expected empty state"
fi
browser_use_try_screenshot "$session_name" "$out_dir/incidents.png"

browser_use_run "$session_name" set viewport 390 844 >/dev/null 2>&1 || true
browser_use_run "$session_name" open "${public_base_url}/incidents"
if ! browser_use_wait_for_eval_match "$session_name" "$js_incidents_ready" 'shell=[1-9].*departures=1.*stations=1.*map=1.*panels=[1-9]' 20 1; then
  fail "Public smoke failed: mobile incidents view did not render the public shell"
fi
if ! browser_use_output_has '(cards=[1-9].*(badges=[1-9]|detailPrompt=1))|(cards=0.*summaryEmpty=1.*detailPrompt=1)'; then
  fail "Public smoke failed: mobile incidents view did not render either incident cards or the expected empty state"
fi
browser_use_try_screenshot "$session_name" "$out_dir/incidents-mobile.png"

browser_use_run "$session_name" open "${public_base_url}/t/${train_id}/map"
if ! browser_use_wait_for_eval_match "$session_name" "$js_public_map_ready" 'map=[1-9].*stops=[1-9]' 24 1; then
  fail "Public smoke failed: train map page did not render the mapped train and stop list"
fi
browser_use_try_screenshot "$session_name" "$out_dir/train-map.png"

browser_use_try_write_output "$session_name" "$out_dir/public-smoke-console.log" 15 console
browser_use_try_write_output "$session_name" "$out_dir/public-smoke-network.log" 15 network requests

log "Public smoke completed for train ${train_id}; artifacts in ${out_dir}"
