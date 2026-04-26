#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "$SCRIPT_DIR/common.sh"
# shellcheck source=./browser_use.sh
source "$SCRIPT_DIR/browser_use.sh"

ensure_output_dirs

browser_use_require_cli

if [[ -f "$REPO_ROOT/.env" ]]; then
  set -a
  # shellcheck source=/dev/null
  . "$REPO_ROOT/.env"
  set +a
fi

public_base_url="${TRAIN_WEB_PUBLIC_BASE_URL:-https://train-bot.jolkins.id.lv}"
out_dir="${BROWSER_USE_SMOKE_OUT_DIR:-$REPO_ROOT/output/browser-use/pixel-public-smoke}"
session_name="${BROWSER_USE_PUBLIC_SESSION:-${BROWSER_USE_SESSION:-$(browser_use_default_session_name "ttb-public-smoke")}}"
train_id=""

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

browser_use_run_timed "$session_name" 10 close >/dev/null 2>&1 || true

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
  const homeok = standalone > 0 || (mapCount > 0 && inline === 0) ? 1 : 0;
  return `homeok=${homeok};mapbutton=${mapCount};legacy=${document.querySelectorAll('#public-stations-map-panel').length};standalone=${standalone};inline=${inline}`;
})()
JS
)"

js_station_search_ready="$(cat <<'JS'
(async () => {
  const roots = [];
  const collectRoots = (root) => {
    if (!root || roots.includes(root)) {
      return;
    }
    roots.push(root);
    if (!root.querySelectorAll) {
      return;
    }
    Array.from(root.querySelectorAll('*')).forEach((node) => {
      if (node && node.shadowRoot) {
        collectRoots(node.shadowRoot);
      }
    });
  };
  const queryOne = (selector) => {
    collectRoots(document);
    for (const root of roots) {
      const node = root.querySelector(selector);
      if (node) {
        return node;
      }
    }
    return null;
  };
  const queryAll = (selector) => {
    collectRoots(document);
    const out = [];
    for (const root of roots) {
      out.push(...Array.from(root.querySelectorAll(selector)));
    }
    return out;
  };
  const setValue = (node, value) => {
    const proto = Object.getPrototypeOf(node);
    const descriptor =
      (proto && Object.getOwnPropertyDescriptor(proto, 'value'))
      || Object.getOwnPropertyDescriptor(window.HTMLInputElement && window.HTMLInputElement.prototype, 'value');
    if (descriptor && typeof descriptor.set === 'function') {
      descriptor.set.call(node, value);
    } else {
      node.value = value;
    }
    node.dispatchEvent(new Event('input', { bubbles: true }));
    node.dispatchEvent(new Event('change', { bubbles: true }));
  };
  const input = queryOne('#public-station-query');
  const searchButton = queryOne('#public-station-search');
  if (!input) {
    return 'ready=0;matches=0;empty=0';
  }
  if ((input.value || '').toLowerCase() !== 'riga') {
    input.focus();
    setValue(input, 'riga');
    if (searchButton && typeof searchButton.click === 'function') {
      searchButton.click();
    }
  }
  await new Promise((resolve) => setTimeout(resolve, 1200));
  const matches = queryAll('[data-action="public-station-departures"]').length;
  const body = document.body ? (document.body.innerText || '') : '';
  const empty = /No stations matched that search/i.test(body) ? 1 : 0;
  return `ready=1;matches=${matches};empty=${empty}`;
})()
JS
)"

js_departures_ready="$(cat <<'JS'
(() => {
  const filter = document.querySelector('#public-filter');
  const links = Array.from(document.querySelectorAll('a'));
  const mapCount = links.filter((node) => {
    const text = (node.textContent || '').trim();
    const href = node.getAttribute('href') || '';
    if (!/^(Map|Mape|Karte)$/i.test(text) || !href) {
      return false;
    }
    const path = new URL(href, window.location.href).pathname;
    return /\/map$/.test(path) && !/\/t\/.+\/map$/.test(path);
  }).length;
  const stopsMapLink = links.find((node) => {
    const text = (node.textContent || '').trim();
    const href = node.getAttribute('href') || '';
    if (!/^(Stops map|Pieturu mape)$/i.test(text) || !href) {
      return false;
    }
    const path = new URL(href, window.location.href).pathname;
    return /^\/t\/.+\/map$/.test(path);
  });
  const statusLink = links.find((node) => {
    const href = node.getAttribute('href') || '';
    if (!href) {
      return false;
    }
    const path = new URL(href, window.location.href).pathname;
    return /^\/t\/.+$/.test(path) && !/\/map$/.test(path);
  });
  const trainIdSource = stopsMapLink || statusLink || null;
  const trainId = trainIdSource ? (new URL(trainIdSource.href, window.location.href).pathname.split('/')[2] || '') : '';
  const stopsMap = stopsMapLink ? 1 : 0;
  return `ready=${filter ? 1 : 0};mapbutton=${mapCount};stopsMap=${stopsMap};trainId=${encodeURIComponent(trainId)}`;
})()
JS
)"

js_bundle_train_fallback="$(cat <<'JS'
(async () => {
  const manifestUrl = (window.TRAIN_APP_CONFIG && window.TRAIN_APP_CONFIG.bundleManifestURL) || '';
  if (!manifestUrl) {
    return 'trainId=';
  }
  try {
    const manifest = await fetch(manifestUrl, { credentials: 'include' }).then((response) => response.json());
    const trainsUrl = new URL((manifest && manifest.slices && manifest.slices.trains) || '', manifestUrl).toString();
    const trains = await fetch(trainsUrl, { credentials: 'include' }).then((response) => response.json());
    const trainId = Array.isArray(trains) && trains.length ? String((trains[0] && trains[0].id) || '') : '';
    return `trainId=${encodeURIComponent(trainId)};count=${Array.isArray(trains) ? trains.length : 0}`;
  } catch (error) {
    return `trainId=;error=${encodeURIComponent(String(error))}`;
  }
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

browser_use_run_timed "$session_name" 20 open "${public_base_url}/" >/dev/null
browser_use_run_timed "$session_name" 15 set viewport 1400 1100 >/dev/null 2>&1 || true
browser_use_run_timed "$session_name" 10 console --clear >/dev/null 2>&1 || true
browser_use_run_timed "$session_name" 10 network requests --clear >/dev/null 2>&1 || true

if ! browser_use_wait_for_eval_match "$session_name" "$js_home_ready" 'homeok=1' 20 1; then
  fail "Public smoke failed: homepage did not expose the network map entry cleanly"
fi
browser_use_try_screenshot "$session_name" "$out_dir/home.png"

browser_use_run_timed "$session_name" 20 open "${public_base_url}/stations" >/dev/null
if ! browser_use_wait_for_eval_match "$session_name" "$js_station_search_ready" 'ready=1.*matches=[1-9][0-9]*.*empty=0' 20 1; then
  fail "Public smoke failed: station search did not return live public results for a plain search"
fi
log "Verified public station search returns live results for query=riga"

browser_use_run_timed "$session_name" 20 open "${public_base_url}/departures" >/dev/null
if ! browser_use_wait_for_eval_match "$session_name" "$js_departures_ready" 'ready=1.*mapbutton=[1-9]' 20 1; then
  fail "Public smoke failed: departures did not expose the public map entry and a train detail path"
fi
train_id="$(python3 - "${BROWSER_USE_LAST_OUTPUT:-}" <<'PY'
import re
import sys
import urllib.parse

text = sys.argv[1]
match = re.search(r"trainId=([^;]+)", text)
if not match:
    raise SystemExit(1)
print(urllib.parse.unquote(match.group(1)))
PY
)" || true
if [[ -z "${train_id}" ]]; then
  browser_use_run_timed "$session_name" 20 eval "$js_bundle_train_fallback" >/dev/null
  train_id="$(python3 - "${BROWSER_USE_LAST_OUTPUT:-}" <<'PY'
import re
import sys
import urllib.parse

text = sys.argv[1]
match = re.search(r"trainId=([^;]+)", text)
if not match:
    raise SystemExit(1)
print(urllib.parse.unquote(match.group(1)))
PY
)" || fail "Public smoke failed: could not extract a fallback train id from the active bundle"
fi
if [[ -z "${train_id}" ]]; then
  fail "Public smoke failed: departures page exposed an empty train id"
fi
if ! browser_use_output_has 'mapbutton=[1-9]'; then
  fail "Public smoke failed: departures did not expose the public map entry"
fi
browser_use_try_screenshot "$session_name" "$out_dir/departures.png"

browser_use_run_timed "$session_name" 20 open "${public_base_url}/t/${train_id}" >/dev/null
if ! browser_use_wait_for_eval_match "$session_name" "$js_has_stops_map_cta" 'cta=[1-9]' 20 1; then
  browser_use_run_timed "$session_name" 20 open "${public_base_url}/t/${train_id}" >/dev/null
  if ! browser_use_wait_for_eval_match "$session_name" "$js_has_stops_map_cta" 'cta=[1-9]' 20 1; then
    fail "Public smoke failed: train detail page did not expose the Stops map CTA"
  fi
fi

browser_use_run_timed "$session_name" 20 open "${public_base_url}/map" >/dev/null
if ! browser_use_wait_for_eval_match "$session_name" "$js_public_network_map_ready" 'map=[1-9].*sightings=[1-9]' 24 1; then
  fail "Public smoke failed: network map did not render the map and sightings card"
fi
browser_use_try_screenshot "$session_name" "$out_dir/network-map.png"

browser_use_run_timed "$session_name" 20 open "${public_base_url}/incidents" >/dev/null
if ! browser_use_wait_for_eval_match "$session_name" "$js_incidents_ready" 'shell=[1-9].*departures=1.*stations=1.*map=1.*panels=[1-9]' 20 1; then
  browser_use_run_timed "$session_name" 20 open "${public_base_url}/incidents" >/dev/null
  if ! browser_use_wait_for_eval_match "$session_name" "$js_incidents_ready" 'shell=[1-9].*departures=1.*stations=1.*map=1.*panels=[1-9]' 20 1; then
    fail "Public smoke failed: incidents view did not render the public shell and detail panels"
  fi
fi
if ! browser_use_output_has '(cards=[1-9].*(badges=[1-9]|detailPrompt=1))|(cards=0.*summaryEmpty=1.*detailPrompt=1)'; then
  fail "Public smoke failed: incidents view did not render either a populated list or the expected empty state"
fi
browser_use_try_screenshot "$session_name" "$out_dir/incidents.png"

browser_use_run_timed "$session_name" 15 set viewport 390 844 >/dev/null 2>&1 || true
browser_use_run_timed "$session_name" 20 open "${public_base_url}/incidents" >/dev/null
if ! browser_use_wait_for_eval_match "$session_name" "$js_incidents_ready" 'shell=[1-9].*departures=1.*stations=1.*map=1.*panels=[1-9]' 20 1; then
  browser_use_run_timed "$session_name" 20 open "${public_base_url}/incidents" >/dev/null
  if ! browser_use_wait_for_eval_match "$session_name" "$js_incidents_ready" 'shell=[1-9].*departures=1.*stations=1.*map=1.*panels=[1-9]' 20 1; then
    fail "Public smoke failed: mobile incidents view did not render the public shell"
  fi
fi
if ! browser_use_output_has '(cards=[1-9].*(badges=[1-9]|detailPrompt=1))|(cards=0.*summaryEmpty=1.*detailPrompt=1)'; then
  fail "Public smoke failed: mobile incidents view did not render either incident cards or the expected empty state"
fi
browser_use_try_screenshot "$session_name" "$out_dir/incidents-mobile.png"

browser_use_run_timed "$session_name" 20 open "${public_base_url}/t/${train_id}/map" >/dev/null
if ! browser_use_wait_for_eval_match "$session_name" "$js_public_map_ready" 'map=[1-9].*stops=[1-9]' 24 1; then
  fail "Public smoke failed: train map page did not render the mapped train and stop list"
fi
browser_use_try_screenshot "$session_name" "$out_dir/train-map.png"

browser_use_try_write_output "$session_name" "$out_dir/public-smoke-console.log" 15 console
browser_use_try_write_output "$session_name" "$out_dir/public-smoke-network.log" 15 network requests

log "Public smoke completed for train ${train_id}; artifacts in ${out_dir}"
