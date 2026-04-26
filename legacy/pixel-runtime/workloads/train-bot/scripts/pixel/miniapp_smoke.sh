#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "$SCRIPT_DIR/common.sh"
# shellcheck source=./browser_use.sh
source "$SCRIPT_DIR/browser_use.sh"

ensure_output_dirs
ensure_local_env

for cmd in python3; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    log "Missing required command: $cmd"
    exit 1
  fi
done
browser_use_require_cli

set -a
# shellcheck source=/dev/null
. "$REPO_ROOT/.env"
set +a

public_base_url="${TRAIN_WEB_PUBLIC_BASE_URL:-https://train-bot.jolkins.id.lv}"
chat_url="${BROWSER_USE_CHAT_URL:-https://web.telegram.org/a/#8792187636}"
profile_spec="${BROWSER_USE_PROFILE:-}"
out_dir="${BROWSER_USE_SMOKE_OUT_DIR:-$REPO_ROOT/output/browser-use/pixel-miniapp-smoke}"
chat_session="${BROWSER_USE_MINIAPP_CHAT_SESSION:-${BROWSER_USE_SESSION:-$(browser_use_default_session_name "ttb-miniapp-smoke-chat")}}"
app_session="${BROWSER_USE_MINIAPP_APP_SESSION:-$(browser_use_default_session_name "ttb-miniapp-smoke-app")}"
smoke_user_id="${BROWSER_USE_SMOKE_USER_ID:-900000001}"
smoke_lang="${BROWSER_USE_SMOKE_LANG:-lv}"
app_url="${public_base_url%/}/app"

mkdir -p "$out_dir"
rm -f \
  "$out_dir/chat-console.log" \
  "$out_dir/chat-network.log" \
  "$out_dir/app-console.log" \
  "$out_dir/app-network.log" \
  "$out_dir/chat-bootstrap.png" \
  "$out_dir/app-dashboard.png" \
  "$out_dir/app-map.png" \
  "$out_dir/app-checkin.png"

browser_use_prepare_profile "$profile_spec"

cleanup() {
  browser_use_run "$chat_session" close >/dev/null 2>&1 || true
  browser_use_run "$app_session" close >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() {
  log "$1"
  exit 1
}

phase="setup"

signed_init_data="$(
  python3 - "$BOT_TOKEN" "$smoke_user_id" "$smoke_lang" <<'PY'
import hashlib
import hmac
import json
import sys
import time
import urllib.parse

bot_token = sys.argv[1]
user_id = int(sys.argv[2])
language_code = sys.argv[3]

values = {
    "auth_date": str(int(time.time())),
    "query_id": "pixel-smoke",
    "user": json.dumps(
        {
            "id": user_id,
            "first_name": "Pixel Smoke",
            "language_code": language_code,
        },
        separators=(",", ":"),
    ),
}
data_check_string = "\n".join(f"{key}={values[key]}" for key in sorted(values))
secret = hmac.new(b"WebAppData", bot_token.encode("utf-8"), hashlib.sha256).digest()
values["hash"] = hmac.new(secret, data_check_string.encode("utf-8"), hashlib.sha256).hexdigest()
print(urllib.parse.urlencode(values))
PY
)"
signed_init_data_json="$(
  python3 - "$signed_init_data" <<'PY'
import json
import sys

print(json.dumps(sys.argv[1]))
PY
)"
smoke_app_url="$(
  python3 - "$app_url" "$signed_init_data" <<'PY'
import json
import sys
import urllib.parse

app_url = sys.argv[1]
init_data = sys.argv[2]
theme = {
    "bg_color": "#ffffff",
    "text_color": "#000000",
    "hint_color": "#707579",
    "link_color": "#3390ec",
    "button_color": "#3390ec",
    "button_text_color": "#ffffff",
    "secondary_bg_color": "#f4f4f5",
    "header_bg_color": "#ffffff",
    "accent_text_color": "#3390ec",
    "section_bg_color": "#ffffff",
    "section_header_text_color": "#707579",
    "subtitle_text_color": "#707579",
    "destructive_text_color": "#e53935",
}
theme_json = json.dumps(theme, separators=(",", ":"))
print(
    f"{app_url}#tgWebAppData={urllib.parse.quote(init_data, safe='')}"
    f"&tgWebAppVersion=9.1"
    f"&tgWebAppPlatform=weba"
    f"&tgWebAppThemeParams={urllib.parse.quote(theme_json, safe='')}"
)
PY
)"

js_chat_bootstrap="$(cat <<'JS'
(async () => {
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const text = (node) => String((node && (node.textContent || node.innerText)) || '').trim();
  const visible = (node) => Boolean(
    node
    && node.isConnected
    && node.getClientRects
    && node.getClientRects().length > 0
    && window.getComputedStyle(node).visibility !== 'hidden'
    && window.getComputedStyle(node).display !== 'none'
  );
  const clickNode = (rawNode) => {
    const node = rawNode && typeof rawNode.closest === 'function'
      ? (rawNode.closest('button, a, div[role="button"]') || rawNode)
      : rawNode;
    if (!visible(node)) {
      return false;
    }
    if (typeof node.click === 'function') {
      node.click();
      return true;
    }
    node.dispatchEvent(new MouseEvent('mouseover', { bubbles: true, cancelable: true }));
    node.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
    node.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true }));
    node.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    return true;
  };
  const firstVisible = (selector, matcher) => Array.from(document.querySelectorAll(selector)).find((node) => visible(node) && matcher.test(text(node)));
  const firstVisibleNode = (selector, predicate) => Array.from(document.querySelectorAll(selector)).find((node) => visible(node) && predicate(node));
  const setInputValue = (node, value) => {
    if (!node) {
      return false;
    }
    const proto = Object.getPrototypeOf(node);
    const descriptor =
      (proto && Object.getOwnPropertyDescriptor(proto, 'value'))
      || Object.getOwnPropertyDescriptor(window.HTMLInputElement && window.HTMLInputElement.prototype, 'value')
      || Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement && window.HTMLTextAreaElement.prototype, 'value');
    node.focus();
    if (descriptor && typeof descriptor.set === 'function') {
      descriptor.set.call(node, value);
    } else {
      node.value = value;
    }
    node.dispatchEvent(new InputEvent('input', { bubbles: true, data: value, inputType: 'insertText' }));
    node.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  };
  const isBotResult = (node) => {
    const label = String((node && node.getAttribute && node.getAttribute('aria-label')) || '').trim();
    const combined = `${label} ${text(node)}`.replace(/\s+/g, ' ').trim();
    return /Vivi kontrole bot/i.test(combined) && !/news/i.test(combined);
  };
  const botId = '8792187636';
  const botHandle = '@vivi_kontrole_bot';

  const openBotChat = async () => {
    if ((window.location.hash || '').includes(`#${botId}`)) {
      return true;
    }
    const directHref = document.querySelector(`a[href="#${botId}"]`);
    if (clickNode(directHref)) {
      await sleep(700);
      return (window.location.hash || '').includes(`#${botId}`);
    }
    const byName = firstVisible('a, button, div[role="button"], span', /Vivi kontrole bot|Report Bot/i);
    if (clickNode(byName)) {
      await sleep(700);
      return (window.location.hash || '').includes(`#${botId}`);
    }
    const searchInput = document.querySelector('input[type="text"], input[placeholder*="Search"], [contenteditable="true"][data-placeholder*="Search"]');
    if (visible(searchInput)) {
      if ('value' in searchInput) {
        setInputValue(searchInput, botHandle);
      } else {
        searchInput.focus();
        searchInput.textContent = botHandle;
        searchInput.dispatchEvent(new InputEvent('input', { bubbles: true, data: botHandle, inputType: 'insertText' }));
      }
      await sleep(900);
      const searchedHref = document.querySelector(`a[href="#${botId}"]`);
      if (clickNode(searchedHref)) {
        await sleep(900);
        return (window.location.hash || '').includes(`#${botId}`);
      }
      const searchedNode = firstVisibleNode('a, button, div[role="button"], h3[role="button"], span, [aria-label]', isBotResult);
      if (clickNode(searchedNode)) {
        await sleep(900);
        return (window.location.hash || '').includes(`#${botId}`);
      }
      searchInput.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      searchInput.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }));
      await sleep(900);
      if ((window.location.hash || '').includes(`#${botId}`)) {
        return true;
      }
    }
    window.location.hash = `#${botId}`;
    await sleep(1000);
    return (window.location.hash || '').includes(`#${botId}`);
  };

  const clickMatching = async (matcher) => {
    const node = firstVisible('button, div[role="button"], a', matcher);
    if (!clickNode(node)) {
      return false;
    }
    await sleep(600);
    return true;
  };

  let chatReady = false;
  let launcherCount = 0;
  let launcherClicked = false;
  for (let attempt = 0; attempt < 5; attempt++) {
    chatReady = await openBotChat();
    const launcherButtons = Array.from(document.querySelectorAll('button, a, div[role="button"]')).filter((node) => visible(node) && /Atvērt lietotni|Open app|Mini App/i.test(text(node)));
    launcherCount = launcherButtons.length;
    if (launcherButtons.length) {
      clickNode(launcherButtons[launcherButtons.length - 1]);
      launcherClicked = true;
      await sleep(1200);
      break;
    }
    await clickMatching(/Show bot keyboard|Hide bot keyboard|Parādīt bota tastatūru|Paslēpt bota tastatūru/i);
    await clickMatching(/^(Start|START|Sākt)$/i);
    await clickMatching(/^\/start$/i);
    await clickMatching(/Agree|Piekrītu/i);
    await sleep(800);
  }

  const deepLinkVisible = /https:\/\/t\.me\/vivi_kontrole_bot\/app/i.test(document.body ? document.body.innerText || '' : '')
    || Boolean(Array.from(document.querySelectorAll('a[href], button, div[role="button"], span'))
      .find((node) => visible(node) && /https:\/\/t\.me\/vivi_kontrole_bot\/app/i.test(String((node.getAttribute && node.getAttribute('href')) || '') + ' ' + text(node))));
  const iframeCount = document.querySelectorAll('iframe').length;
  return `chatReady=${chatReady ? 1 : 0};launcherVisible=${launcherCount > 0 ? 1 : 0};launcherCount=${launcherCount};launcherClicked=${launcherClicked ? 1 : 0};deepLinkVisible=${deepLinkVisible ? 1 : 0};iframeCount=${iframeCount};url=${encodeURIComponent(window.location.href)}`;
})()
JS
)"

js_app_shell_ready="$(cat <<'JS'
(() => {
  const visible = (node) => Boolean(
    node
    && node.isConnected
    && node.getClientRects
    && node.getClientRects().length > 0
    && window.getComputedStyle(node).visibility !== 'hidden'
    && window.getComputedStyle(node).display !== 'none'
  );
  const buttons = Array.from(document.querySelectorAll('button')).filter(visible);
  const dashboardTab = buttons.find((node) => /^(Dashboard|Panelis)$/i.test((node.textContent || '').trim()));
  const mapTab = buttons.find((node) => /^(Map|Mape|Karte)$/i.test((node.textContent || '').trim()));
  const settingsTab = buttons.find((node) => /^(Settings|Iestatījumi)$/i.test((node.textContent || '').trim()));
  const initDataLen = ((window.Telegram && window.Telegram.WebApp && window.Telegram.WebApp.initData) || '').length;
  return `tabsReady=${dashboardTab && mapTab && settingsTab ? 1 : 0};initDataLen=${initDataLen}`;
})()
JS
)"

js_app_authenticate="$(
  python3 - "$signed_init_data_json" <<'PY'
import sys

init_data_json = sys.argv[1]
print(
    f"""(async () => {{
  const initData = {init_data_json};
  const basePath = (window.TRAIN_APP_CONFIG && window.TRAIN_APP_CONFIG.basePath) || '';
  window.Telegram = window.Telegram || {{}};
  window.Telegram.WebApp = window.Telegram.WebApp || {{}};
  window.Telegram.WebApp.initData = initData;

  try {{
    const authResponse = await fetch(`${{basePath}}/api/v1/auth/telegram`, {{
      method: 'POST',
      credentials: 'include',
      headers: {{ 'Content-Type': 'application/json' }},
      body: JSON.stringify({{ initData }}),
    }});
    const payload = await authResponse.json().catch(() => null);
    const session = payload && payload.spacetime ? payload.spacetime : null;
    window.__trainSmokeSpacetime = session;
    return `authOk=${{authResponse.ok ? 1 : 0}};authStatus=${{authResponse.status}};sessionOk=${{session && session.host && session.database && session.token && session.expiresAt ? 1 : 0}};tokenLen=${{session && session.token ? session.token.length : 0}};initDataLen=${{initData.length}}`;
  }} catch (error) {{
    return `authOk=0;authStatus=0;sessionOk=0;tokenLen=0;error=${{encodeURIComponent(String(error))}};initDataLen=${{initData.length}}`;
  }}
}})()"""
)
PY
)"

js_verify_direct_app="$(cat <<'JS'
(async () => {
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const text = (node) => String((node && (node.textContent || node.innerText)) || '').trim();
  const visible = (node) => Boolean(
    node
    && node.isConnected
    && node.getClientRects
    && node.getClientRects().length > 0
    && window.getComputedStyle(node).visibility !== 'hidden'
    && window.getComputedStyle(node).display !== 'none'
  );
  const clickNode = (rawNode) => {
    const node = rawNode && typeof rawNode.closest === 'function'
      ? (rawNode.closest('button, a, div[role="button"]') || rawNode)
      : rawNode;
    if (!visible(node)) {
      return false;
    }
    if (typeof node.click === 'function') {
      node.click();
      return true;
    }
    node.dispatchEvent(new MouseEvent('mouseover', { bubbles: true, cancelable: true }));
    node.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
    node.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true }));
    node.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    return true;
  };
  const waitFor = async (fn, loops = 24, delay = 400) => {
    for (let i = 0; i < loops; i++) {
      const value = await fn();
      if (value) {
        return value;
      }
      await sleep(delay);
    }
    return null;
  };
  const visibleButtons = (selector) => Array.from(document.querySelectorAll(selector)).filter(visible);
  const visibleCheckoutButton = (trainId) => {
    const buttons = visibleButtons("button[data-action='checkout']");
    if (!trainId) {
      return buttons[0] || null;
    }
    return buttons.find((node) => (node.getAttribute('data-train-id') || '') === trainId) || null;
  };
  const state = {
    tabsReady: false,
    cleanupSucceeded: false,
    departuresLoaded: false,
    selectorVisible: false,
    selectorOptionCount: 0,
    registerVisible: false,
    mapActionVisible: false,
    sightingsShortcutVisible: false,
    registerMetricsVisible: false,
    registerMetricsMatch: false,
    checkinSucceeded: false,
    checkoutVisible: false,
    popupCheckinVisible: false,
    popupSource: '',
    myRideVisible: false,
    riderCountVisible: false,
    riderCountPositive: false,
    rideTrainMatched: false,
    selectedTrainId: '',
    mapLoaded: false,
    postCleanupSucceeded: false,
  };

  const tabs = await waitFor(() => {
    const buttons = Array.from(document.querySelectorAll('button')).filter(visible);
    const dashboardTab = buttons.find((node) => /^(Dashboard|Panelis)$/i.test(text(node)));
    const mapTab = buttons.find((node) => /^(Map|Mape|Karte)$/i.test(text(node)));
    const settingsTab = buttons.find((node) => /^(Settings|Iestatījumi)$/i.test(text(node)));
    return dashboardTab && mapTab && settingsTab ? { dashboardTab, mapTab, settingsTab } : null;
  }, 30, 500);
  if (!tabs) {
    return 'tabsReady=0';
  }
  state.tabsReady = true;

  const cleanupButton = await waitFor(() => visibleCheckoutButton(''), 10, 300);
  if (cleanupButton) {
    clickNode(cleanupButton);
    state.cleanupSucceeded = Boolean(await waitFor(() => visibleButtons("button[data-action='checkout']").length === 0 ? true : null, 20, 400));
  } else {
    state.cleanupSucceeded = true;
  }
  if (!state.cleanupSucceeded) {
    return 'tabsReady=1;cleanupSucceeded=0';
  }

  const globalRefreshButton = await waitFor(() => document.querySelector('#global-refresh'), 10, 300);
  if (visible(globalRefreshButton)) {
    clickNode(globalRefreshButton);
    await sleep(700);
  }

  const currentTabs = await waitFor(() => {
    const buttons = Array.from(document.querySelectorAll('button')).filter(visible);
    const dashboardTab = buttons.find((node) => /^(Dashboard|Panelis)$/i.test(text(node))) || null;
    const mapTab = buttons.find((node) => /^(Map|Mape|Karte)$/i.test(text(node))) || null;
    return dashboardTab && mapTab ? { dashboardTab, mapTab } : null;
  }, 10, 300);
  if (currentTabs && currentTabs.dashboardTab) {
    clickNode(currentTabs.dashboardTab);
  }
  await sleep(700);

  const stationQueryInput = await waitFor(() => document.querySelector('#station-query'), 20, 300);
  let windowButtons = Array.from(document.querySelectorAll("[data-action='window']")).filter(visible);
  let checkinButtons = Array.from(document.querySelectorAll("[data-action='checkin']")).filter(visible);
  let mapButtons = Array.from(document.querySelectorAll("[data-action='open-map']")).filter(visible);
  let primarySource = 'dashboard';

  if ((!checkinButtons.length || !mapButtons.length) && currentTabs && currentTabs.mapTab) {
    const fallbackMapTab = await waitFor(() => {
      const buttons = Array.from(document.querySelectorAll('button')).filter(visible);
      return buttons.find((node) => /^(Map|Mape|Karte)$/i.test(text(node))) || null;
    }, 10, 300);
    if (fallbackMapTab) {
      clickNode(fallbackMapTab);
    }
    const mapActions = await waitFor(() => {
      const nextCheckinButtons = Array.from(document.querySelectorAll("[data-action='checkin']")).filter(visible);
      const nextMapButtons = Array.from(document.querySelectorAll("[data-action='open-map']")).filter(visible);
      return nextCheckinButtons.length && nextMapButtons.length
        ? { nextCheckinButtons, nextMapButtons }
        : null;
    }, 20, 400);
    if (mapActions) {
      checkinButtons = mapActions.nextCheckinButtons;
      mapButtons = mapActions.nextMapButtons;
      primarySource = 'map';
    }
  }

  if (!stationQueryInput || !windowButtons.length || !checkinButtons.length || !mapButtons.length) {
    return 'tabsReady=1;departuresLoaded=0';
  }

  state.departuresLoaded = true;
  state.selectorVisible = visible(stationQueryInput);
  state.selectorOptionCount = checkinButtons.length;
  state.registerVisible = true;
  state.mapActionVisible = true;
  state.sightingsShortcutVisible = visible(document.querySelector("[data-action='tab-sightings']"));

  const primaryCard = checkinButtons[0].closest('article');
  const metrics = primaryCard
    ? Array.from(primaryCard.querySelectorAll('.meta span')).map(text).filter(Boolean)
    : [];
  state.registerMetricsVisible = metrics.length >= 1;
  state.registerMetricsMatch = metrics.some((value) => (value.match(/\b\d{1,2}:\d{2}\b/g) || []).length >= 2);
  state.selectedTrainId = checkinButtons[0].getAttribute('data-train-id') || '';

  clickNode(checkinButtons[0]);
  const checkoutButton = await waitFor(() => visibleCheckoutButton(state.selectedTrainId), 20, 400);
  state.checkoutVisible = Boolean(checkoutButton);
  state.checkinSucceeded = Boolean(checkoutButton);

  const postCheckinRefreshButton = await waitFor(() => document.querySelector('#global-refresh'), 10, 300);
  if (visible(postCheckinRefreshButton)) {
    clickNode(postCheckinRefreshButton);
    await sleep(700);
  }

  const myRideTab = await waitFor(
    () => document.querySelector("button[data-action='tab'][data-tab='my-ride']"),
    10,
    300
  );
  if (visible(myRideTab)) {
    clickNode(myRideTab);
    await sleep(700);
  }

  const myRideCheckoutButton = await waitFor(() => visibleCheckoutButton(state.selectedTrainId), 20, 400);
  state.myRideVisible = Boolean(myRideCheckoutButton);
  state.rideTrainMatched = Boolean(myRideCheckoutButton);
  const riderCountNode = await waitFor(() => {
    const node = document.querySelector("[data-detail-key='riders']");
    return visible(node) ? node : null;
  }, 10, 300);
  const riderCountMatch = riderCountNode ? text(riderCountNode).match(/(\d+)/) : null;
  const riderCount = riderCountMatch ? Number(riderCountMatch[1]) : 0;
  state.riderCountVisible = Boolean(riderCountNode);
  state.riderCountPositive = riderCount >= 1;

  const stopMapButton = await waitFor(() => {
    const buttons = Array.from(document.querySelectorAll('button')).filter(visible);
    return buttons.find((node) => /^(Stops map|Pieturu mape)$/i.test(text(node))) || null;
  }, 20, 400);
  state.popupCheckinVisible = Boolean(stopMapButton);
  state.popupSource = stopMapButton ? primarySource : '';
  if (stopMapButton) {
    clickNode(stopMapButton);
    await sleep(700);
  }

  state.mapLoaded = Boolean(await waitFor(() => {
    const orderedStops = document.querySelectorAll('button[expanded], .stop-row').length > 0;
    const mapCanvas = Boolean(document.querySelector('.train-map, #mini-train-map, .leaflet-container'));
    return orderedStops && mapCanvas;
  }, 24, 500));

  const finalDashboardTab = await waitFor(() => {
    const buttons = Array.from(document.querySelectorAll('button')).filter(visible);
    return buttons.find((node) => /^(Dashboard|Panelis)$/i.test(text(node))) || null;
  }, 10, 300);
  if (finalDashboardTab) {
    clickNode(finalDashboardTab);
    await sleep(500);
  }
  const finalCheckoutButton = await waitFor(() => visibleCheckoutButton(state.selectedTrainId), 12, 300);
  if (finalCheckoutButton) {
    clickNode(finalCheckoutButton);
    state.postCleanupSucceeded = Boolean(await waitFor(() => visibleCheckoutButton(state.selectedTrainId) ? null : true, 20, 400));
  } else {
    state.postCleanupSucceeded = true;
  }

  return `tabsReady=${state.tabsReady ? 1 : 0};cleanupSucceeded=${state.cleanupSucceeded ? 1 : 0};departuresLoaded=${state.departuresLoaded ? 1 : 0};selectorVisible=${state.selectorVisible ? 1 : 0};selectorOptionCount=${state.selectorOptionCount};registerVisible=${state.registerVisible ? 1 : 0};mapActionVisible=${state.mapActionVisible ? 1 : 0};sightingsShortcutVisible=${state.sightingsShortcutVisible ? 1 : 0};registerMetricsVisible=${state.registerMetricsVisible ? 1 : 0};registerMetricsMatch=${state.registerMetricsMatch ? 1 : 0};selectedTrainId=${encodeURIComponent(state.selectedTrainId)};mapLoaded=${state.mapLoaded ? 1 : 0};popupCheckinVisible=${state.popupCheckinVisible ? 1 : 0};popupSource=${encodeURIComponent(state.popupSource)};checkinSucceeded=${state.checkinSucceeded ? 1 : 0};checkoutVisible=${state.checkoutVisible ? 1 : 0};myRideVisible=${state.myRideVisible ? 1 : 0};riderCountVisible=${state.riderCountVisible ? 1 : 0};riderCountPositive=${state.riderCountPositive ? 1 : 0};rideTrainMatched=${state.rideTrainMatched ? 1 : 0};postCleanupSucceeded=${state.postCleanupSucceeded ? 1 : 0}`;
})()
JS
)"

phase="telegram-chat"
if ! browser_use_open_telegram_authenticated "$chat_session" "$profile_spec" "$chat_url" 20; then
  fail "Mini app smoke failed during ${phase}: could not restore or open the Telegram session"
fi
browser_use_run_timed "$chat_session" 15 set viewport 1280 1400 >/dev/null 2>&1 || true
if ! browser_use_open_telegram_chat "$chat_session" "8792187636" "@vivi_kontrole_bot" "Vivi kontrole bot" 4; then
  fail "Mini app smoke failed during ${phase}: could not open the Telegram bot chat"
fi
browser_use_run_timed "$chat_session" 10 console --clear >/dev/null 2>&1 || true
browser_use_run_timed "$chat_session" 10 network requests --clear >/dev/null 2>&1 || true

if ! browser_use_wait_for_eval_match "$chat_session" "$js_chat_bootstrap" 'chatReady=1.*(launcherVisible=1|deepLinkVisible=1|launcherClicked=1)' 5 1; then
  fail "Mini app smoke failed during ${phase}: app entry was not visible through the launcher button or deep link"
fi
browser_use_try_screenshot "$chat_session" "$out_dir/chat-bootstrap.png"
browser_use_try_write_output "$chat_session" "$out_dir/chat-console.log" 15 console
browser_use_try_write_output "$chat_session" "$out_dir/chat-network.log" 15 network requests
browser_use_run_timed "$chat_session" 10 close >/dev/null 2>&1 || true

phase="miniapp-auth"
browser_use_run_timed "$app_session" 10 close >/dev/null 2>&1 || true
browser_use_run_with_profile_timed "$app_session" 20 "$profile_spec" open "$smoke_app_url"
browser_use_run_timed "$app_session" 15 set viewport 1400 1100 >/dev/null 2>&1 || true
browser_use_run_timed "$app_session" 10 console --clear >/dev/null 2>&1 || true
browser_use_run_timed "$app_session" 10 network requests --clear >/dev/null 2>&1 || true

if ! browser_use_wait_for_eval_match "$app_session" "$js_app_shell_ready" 'tabsReady=1.*initDataLen=[1-9][0-9]*' 20 1; then
  fail "Mini app smoke failed during ${phase}: Telegram-style app shell did not boot"
fi

if ! browser_use_wait_for_eval_match "$app_session" "$js_app_authenticate" 'authOk=1.*sessionOk=1.*tokenLen=[1-9][0-9]*' 5 1; then
  fail "Mini app smoke failed during ${phase}: browser session did not establish Telegram auth"
fi

browser_use_try_screenshot "$app_session" "$out_dir/app-dashboard.png"

phase="checkin"
if ! browser_use_wait_for_eval_match "$app_session" "$js_verify_direct_app" 'tabsReady=1.*cleanupSucceeded=1.*departuresLoaded=1.*selectorVisible=[01].*selectorOptionCount=[1-9][0-9]*.*registerVisible=1.*mapActionVisible=1.*registerMetricsVisible=1.*registerMetricsMatch=1.*mapLoaded=1.*popupCheckinVisible=1.*popupSource=(dashboard|map).*checkinSucceeded=1.*checkoutVisible=1.*myRideVisible=1.*riderCountVisible=1.*riderCountPositive=1.*rideTrainMatched=1.*postCleanupSucceeded=1' 4 1; then
  log "Mini app smoke debug state: $(browser_use_last_text)"
  fail "Mini app smoke failed during ${phase}: authenticated app flow did not complete the expected dashboard check-in and map assertions"
fi

browser_use_try_screenshot "$app_session" "$out_dir/app-checkin.png"
browser_use_try_write_output "$app_session" "$out_dir/app-console.log" 15 console
browser_use_try_write_output "$app_session" "$out_dir/app-network.log" 15 network requests

log "Mini app smoke completed; artifacts in $out_dir"
