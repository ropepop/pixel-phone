#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "$SCRIPT_DIR/common.sh"
# shellcheck source=./browser_use.sh
source "$SCRIPT_DIR/browser_use.sh"

log "Using browser-use for Telegram Web smoke flow"

ensure_output_dirs
browser_use_require_cli

out_dir="${BROWSER_USE_SMOKE_OUT_DIR:-$REPO_ROOT/output/browser-use/pixel-bot-smoke}"
session_name="${BROWSER_USE_BOT_SESSION:-${BROWSER_USE_SESSION:-ttb}}"
profile_spec="${BROWSER_USE_PROFILE:-}"
chat_url="${BROWSER_USE_CHAT_URL:-https://web.telegram.org/a/#8792187636}"
mobile_view="${BROWSER_USE_MOBILE_VIEW:-0}"

mkdir -p "$out_dir"
rm -f "$out_dir/smoke-console.log" "$out_dir/smoke-network.log" "$out_dir/e2e-evidence.txt" "$out_dir/bot-smoke.png"

browser_use_prepare_profile "$profile_spec"

cleanup() {
  browser_use_run "$session_name" close >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() {
  log "$1"
  exit 1
}

js_bot_flow="$(cat <<'JS'
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

  const keyboardState = () => {
    const buttons = Array.from(document.querySelectorAll('button, div[role="button"], a'));
    const has = (matcher) => buttons.some((node) => visible(node) && matcher.test(text(node)));
    return {
      checkinVisible: has(/^🚆\s*(Check in|Piesakies)$/i),
      reportVisible: has(/^📣\s*(Report|Ziņot)/i),
      showKeyboardVisible: has(/Show bot keyboard|Parādīt bota tastatūru/i),
      hideKeyboardVisible: has(/Hide bot keyboard|Paslēpt bota tastatūru/i),
    };
  };

  const checkinEntryState = () => {
    const buttons = Array.from(document.querySelectorAll('button, div[role="button"], a'));
    const has = (matcher) => buttons.some((node) => visible(node) && matcher.test(text(node)));
    const textNodes = Array.from(document.querySelectorAll('div, span, p'));
    const hasText = (matcher) => textNodes.some((node) => visible(node) && matcher.test(text(node)));
    return {
      stationOptionVisible: has(/Type station name|Ieraksti stacijas nosaukumu/i),
      timeVisible: has(/Choose by time|Izvēlēties pēc laika/i),
      stationPromptVisible: hasText(/Send the first few letters of your boarding station|Nosūti savas iekāpšanas stacijas pirmos burtus/i),
    };
  };

  const clickMatching = async (matcher) => {
    const node = firstVisible('button, div[role="button"], a', matcher);
    if (!clickNode(node)) {
      return false;
    }
    await sleep(500);
    return true;
  };

  let chatReady = false;
  let state = keyboardState();
  let entry = checkinEntryState();
  let byTimeClicked = false;
  let timeWindowClicked = false;

  for (let attempt = 0; attempt < 6; attempt++) {
    chatReady = await openBotChat();
    state = keyboardState();
    entry = checkinEntryState();
    if (chatReady && state.reportVisible && (state.checkinVisible || (entry.timeVisible && (entry.stationOptionVisible || entry.stationPromptVisible)))) {
      break;
    }
    await clickMatching(/Show bot keyboard|Hide bot keyboard|Parādīt bota tastatūru|Paslēpt bota tastatūru/i);
    await clickMatching(/^(Start|START|Sākt)$/i);
    await clickMatching(/^\/start$/i);
    await clickMatching(/Agree|Piekrītu/i);
    await sleep(600);
  }

  if (!entry.timeVisible && state.checkinVisible) {
    await clickMatching(/^🚆\s*(Check in|Piesakies)$/i);
    await sleep(500);
    entry = checkinEntryState();
  }

  const entryReady = entry.timeVisible && (entry.stationOptionVisible || entry.stationPromptVisible);
  if (entry.timeVisible) {
    byTimeClicked = await clickMatching(/Choose by time|Izvēlēties pēc laika/i);
    await sleep(450);
    timeWindowClicked = await clickMatching(/^(Now|Next hour|Later today|Tagad|Nākamā stunda|Vēlāk šodien)$/i);
    await sleep(400);
  }

  state = keyboardState();
  return `chatReady=${chatReady ? 1 : 0};checkinVisible=${state.checkinVisible ? 1 : 0};reportVisible=${state.reportVisible ? 1 : 0};entryReady=${entryReady ? 1 : 0};stationOptionVisible=${entry.stationOptionVisible ? 1 : 0};timeVisible=${entry.timeVisible ? 1 : 0};stationPromptVisible=${entry.stationPromptVisible ? 1 : 0};byTimeClicked=${byTimeClicked ? 1 : 0};timeWindowClicked=${timeWindowClicked ? 1 : 0}`;
})()
JS
)"

browser_use_run_with_profile "$session_name" "$profile_spec" open "$chat_url"
if [[ "$mobile_view" == "1" ]]; then
  browser_use_run "$session_name" set viewport 390 844 >/dev/null 2>&1 || true
else
  browser_use_run "$session_name" set viewport 1280 1000 >/dev/null 2>&1 || true
fi
if ! browser_use_open_telegram_chat "$session_name" "8792187636" "@vivi_kontrole_bot" "Vivi kontrole bot" 4; then
  fail "browser-use smoke failed: could not open the Telegram bot chat"
fi
browser_use_run "$session_name" console --clear >/dev/null 2>&1 || true
browser_use_run "$session_name" network requests --clear >/dev/null 2>&1 || true

if ! browser_use_wait_for_eval_match "$session_name" "$js_bot_flow" 'chatReady=1.*reportVisible=1.*(checkinVisible=1|timeVisible=1)' 6 1; then
  fail "browser-use smoke failed: Telegram bot controls or guided check-in entry were not visible"
fi

if ! browser_use_output_has 'entryReady=1'; then
  fail "browser-use smoke failed: guided check-in flow did not expose station entry"
fi

if ! browser_use_output_has 'byTimeClicked=1'; then
  fail "browser-use smoke failed: by-time command was not clickable"
fi

browser_use_try_screenshot "$session_name" "$out_dir/bot-smoke.png"
browser_use_try_write_output "$session_name" "$out_dir/smoke-console.log" 15 console
browser_use_try_write_output "$session_name" "$out_dir/smoke-network.log" 15 network requests

{
  echo "session=${session_name}"
  echo "chat_url=${chat_url}"
  echo "required_markers=ok(browser_use_verified)"
} >"$out_dir/e2e-evidence.txt"

log "browser-use smoke completed; artifacts in $out_dir"
