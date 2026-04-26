#!/usr/bin/env bash

browser_use_require_cli() {
  if [[ -n "${BROWSER_USE_CLI:-}" ]]; then
    if [[ ! -x "${BROWSER_USE_CLI}" ]]; then
      log "browser-use CLI is not executable: ${BROWSER_USE_CLI}"
      exit 1
    fi
    return
  fi

  if ! command -v browser-use >/dev/null 2>&1; then
    log "Missing required command: browser-use"
    exit 1
  fi

  BROWSER_USE_CLI="$(command -v browser-use)"
  export BROWSER_USE_CLI
}

browser_use_session_token() {
  if [[ -z "${BROWSER_USE_SESSION_TOKEN:-}" ]]; then
    BROWSER_USE_SESSION_TOKEN="$(date +%s)-$$"
    export BROWSER_USE_SESSION_TOKEN
  fi
  printf '%s\n' "${BROWSER_USE_SESSION_TOKEN}"
}

browser_use_default_session_name() {
  local base="${1:-ttb}"
  printf '%s-%s\n' "${base}" "$(browser_use_session_token)"
}

browser_use__set_last_output() {
  local output="$1"
  BROWSER_USE_LAST_OUTPUT="${output}"
  export BROWSER_USE_LAST_OUTPUT
}

browser_use_last_text() {
  printf '%s\n' "${BROWSER_USE_LAST_OUTPUT:-}" \
    | sed \
      -e '1{s/^"//;s/"$//;}' \
      -e '1s/^result:[[:space:]]*//' \
      -e '1s/^url:[[:space:]]*//'
}

browser_use_output_has() {
  local pattern="$1"
  printf '%s\n' "$(browser_use_last_text)" | grep -Eq "${pattern}"
}

browser_use_output_value() {
  local key="$1"
  printf '%s\n' "$(browser_use_last_text)" | sed -n "s/.*${key}=\\([^;[:space:]]*\\).*/\\1/p" | tail -n 1
}

browser_use__profile_list_json() {
  browser_use_require_cli
  if [[ -z "${BROWSER_USE_PROFILE_LIST_JSON:-}" ]]; then
    BROWSER_USE_PROFILE_LIST_JSON="$("${BROWSER_USE_CLI}" profile list --json 2>/dev/null || true)"
    export BROWSER_USE_PROFILE_LIST_JSON
  fi
  printf '%s' "${BROWSER_USE_PROFILE_LIST_JSON}"
}

browser_use__profile_names() {
  PROFILE_LIST_JSON="$(browser_use__profile_list_json)" python3 - <<'PY'
import json
import os

raw = os.environ.get("PROFILE_LIST_JSON", "")
try:
    items = json.loads(raw) if raw else []
except json.JSONDecodeError:
    items = []

names = []
for item in items:
    name = (item.get("ProfileName") or "").strip()
    if name and name not in names:
        names.append(name)

print(", ".join(names))
PY
}

browser_use_resolve_profile() {
  local raw_profile="${1:-${BROWSER_USE_PROFILE:-}}"
  browser_use_require_cli

  if [[ -z "${raw_profile}" ]]; then
    log "Missing browser-use profile. Set BROWSER_USE_PROFILE to a named profile from \`browser-use profile list\`."
    return 1
  fi

  local resolved=""
  if resolved="$(
    PROFILE_RAW="${raw_profile}" PROFILE_LIST_JSON="$(browser_use__profile_list_json)" python3 - <<'PY'
import json
import os

raw = os.environ.get("PROFILE_RAW", "").strip()
profile_list_json = os.environ.get("PROFILE_LIST_JSON", "")

try:
    profiles = json.loads(profile_list_json) if profile_list_json else []
except json.JSONDecodeError:
    profiles = []

for profile in profiles:
    name = (profile.get("ProfileName") or "").strip()
    display_name = (profile.get("DisplayName") or "").strip()
    if raw == name or raw == display_name:
        print(name)
        raise SystemExit(0)

raise SystemExit(1)
PY
  )"; then
    printf '%s\n' "${resolved}"
    return 0
  fi

  local available_names=""
  available_names="$(browser_use__profile_names)"
  log "Unknown browser-use profile: ${raw_profile}"
  log "Available browser-use profiles: ${available_names:-<none detected>}"
  return 1
}

browser_use_prepare_profile() {
  if [[ -n "${BROWSER_USE_CDP_URL:-}" || "${BROWSER_USE_CONNECT:-0}" == "1" ]]; then
    return 0
  fi
  if browser_use_has_saved_telegram_session; then
    return 0
  fi
  browser_use_resolve_profile "$1" >/dev/null
}

browser_use__telegram_session_candidates() {
  if [[ -n "${BROWSER_USE_TELEGRAM_SESSION_DIR:-}" ]]; then
    printf '%s\n' "${BROWSER_USE_TELEGRAM_SESSION_DIR}"
    return 0
  fi

  if [[ -n "${WORKSPACE_ROOT:-}" ]]; then
    printf '%s\n' "${WORKSPACE_ROOT}/state/browser-use/tgweb-session"
    printf '%s\n' "${WORKSPACE_ROOT}/output/browser-use/tgweb-session"
  fi
  printf '%s\n' "${REPO_ROOT}/state/browser-use/tgweb-session"
  printf '%s\n' "${REPO_ROOT}/output/browser-use/tgweb-session"
}

browser_use_resolve_telegram_session_dir() {
  local dir=""
  while IFS= read -r dir; do
    [[ -z "${dir}" ]] && continue
    if [[ -f "${dir}/cookies.json" && -f "${dir}/storage.json" ]]; then
      printf '%s\n' "${dir}"
      return 0
    fi
  done < <(browser_use__telegram_session_candidates)

  return 1
}

browser_use_has_saved_telegram_session() {
  browser_use_resolve_telegram_session_dir >/dev/null 2>&1
}

browser_use_saved_telegram_session_dir() {
  if [[ -n "${BROWSER_USE_TELEGRAM_SESSION_DIR:-}" ]]; then
    printf '%s\n' "${BROWSER_USE_TELEGRAM_SESSION_DIR}"
    return 0
  fi

  if [[ -n "${WORKSPACE_ROOT:-}" ]]; then
    printf '%s\n' "${WORKSPACE_ROOT}/state/browser-use/tgweb-session"
    return 0
  fi

  printf '%s\n' "${REPO_ROOT}/state/browser-use/tgweb-session"
}

browser_use__telegram_storage_restore_python() {
  local storage_file="$1"
  python3 - "${storage_file}" <<'PY'
import json
import pathlib
import sys

storage_file = pathlib.Path(sys.argv[1])
data = json.loads(storage_file.read_text())

js = f"""() => {{
  const local = {json.dumps(data.get('localStorage', {}))};
  const session = {json.dumps(data.get('sessionStorage', {}))};
  localStorage.clear();
  for (const [key, value] of Object.entries(local)) {{
    localStorage.setItem(key, String(value));
  }}
  sessionStorage.clear();
  for (const [key, value] of Object.entries(session)) {{
    sessionStorage.setItem(key, String(value));
  }}
  return 'localCount=' + localStorage.length + ';sessionCount=' + sessionStorage.length;
}}"""

print("page = browser._run(browser._session.must_get_current_page())")
print(f"result = browser._run(page.evaluate({json.dumps(js)}))")
print("print(result)")
PY
}

browser_use__telegram_storage_export_python() {
  local storage_file="$1"
  python3 - "${storage_file}" <<'PY'
import json
import sys

storage_file = sys.argv[1]
js = """() => {
  const dump = (storage) => Object.fromEntries(
    Array.from({ length: storage.length }, (_, index) => {
      const key = storage.key(index);
      return [key, storage.getItem(key)];
    }),
  );
  return {
    url: window.location.href,
    title: document.title,
    localStorage: dump(window.localStorage),
    sessionStorage: dump(window.sessionStorage),
  };
}"""

print("import json")
print(f"storage_file = {json.dumps(storage_file)}")
print("page = browser._run(browser._session.must_get_current_page())")
print(f"payload = browser._run(page.evaluate({json.dumps(js)}))")
print("if isinstance(payload, str):")
print("    payload = json.loads(payload)")
print("with open(storage_file, 'w', encoding='utf-8') as handle:")
print("    json.dump(payload, handle, indent=2, sort_keys=True)")
print("print(f'storageSaved={storage_file}')")
PY
}

browser_use_restore_telegram_session() {
  local session="$1"
  local url="${2:-https://web.telegram.org/a/}"
  local timeout_s="${3:-20}"
  local session_dir=""
  session_dir="$(browser_use_resolve_telegram_session_dir)" || {
    log "Saved Telegram browser-use session files were not found"
    return 1
  }

  local cookies_file="${session_dir}/cookies.json"
  local storage_file="${session_dir}/storage.json"
  local base_url=""
  base_url="$(
    python3 - "${storage_file}" "${url}" <<'PY'
import json
import pathlib
import sys
from urllib.parse import urlsplit, urlunsplit

storage_file = pathlib.Path(sys.argv[1])
fallback = sys.argv[2]
data = json.loads(storage_file.read_text())
raw_url = data.get("url") or fallback
parts = urlsplit(raw_url)
print(urlunsplit((parts.scheme, parts.netloc, parts.path, "", "")))
PY
  )"

  browser_use_run_timed "${session}" "${timeout_s}" close >/dev/null 2>&1 || true
  browser_use_run_timed "${session}" "${timeout_s}" open "${base_url}" >/dev/null
  browser_use_run_timed "${session}" "${timeout_s}" cookies import "${cookies_file}" >/dev/null
  browser_use__run_python "${session}" "$(browser_use__telegram_storage_restore_python "${storage_file}")" >/dev/null
  browser_use_run_timed "${session}" "${timeout_s}" eval "(() => { window.location.reload(); return 'reloading=1'; })()" >/dev/null || true
  sleep 3
  browser_use_run_timed "${session}" "${timeout_s}" open "${url}" >/dev/null

  browser_use_wait_for_eval_match \
    "${session}" \
    "(() => { const body = document.body ? (document.body.innerText || '') : ''; const loggedIn = /Log in to Telegram by QR Code/i.test(body) ? 0 : 1; return 'loggedIn=' + loggedIn + ';title=' + document.title + ';hash=' + window.location.hash; })()" \
    'loggedIn=1' \
    12 \
    1
}

browser_use_save_telegram_session() {
  local session="$1"
  local url="${2:-https://web.telegram.org/a/}"
  local timeout_s="${3:-20}"
  local session_dir=""
  session_dir="$(browser_use_saved_telegram_session_dir)"

  mkdir -p "${session_dir}"
  chmod 700 "${session_dir}" >/dev/null 2>&1 || true

  local cookies_file="${session_dir}/cookies.json"
  local storage_file="${session_dir}/storage.json"
  local readme_file="${session_dir}/README.txt"
  local exported_at=""

  browser_use_run_timed "${session}" "${timeout_s}" open "${url}" >/dev/null
  browser_use_run_timed "${session}" "${timeout_s}" cookies export "${cookies_file}" >/dev/null
  browser_use__run_python "${session}" "$(browser_use__telegram_storage_export_python "${storage_file}")" >/dev/null

  exported_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'session=%s\nurl=%s\nexported_at=%s\n' "${session}" "${url}" "${exported_at}" >"${readme_file}"
  printf '%s\n' "${session_dir}"
}

browser_use_open_telegram_authenticated() {
  local session="$1"
  local profile_spec="$2"
  local url="$3"
  local timeout_s="${4:-20}"

  if browser_use_has_saved_telegram_session; then
    if browser_use_restore_telegram_session "${session}" "${url}" "${timeout_s}"; then
      return 0
    fi
    browser_use_run_timed "${session}" "${timeout_s}" close >/dev/null 2>&1 || true
    sleep 2
    browser_use_restore_telegram_session "${session}" "${url}" "$(( timeout_s + 10 ))"
    return $?
  fi

  if browser_use_run_with_profile_timed "${session}" "${timeout_s}" "${profile_spec}" open "${url}"; then
    return 0
  fi
  browser_use_run_timed "${session}" "${timeout_s}" close >/dev/null 2>&1 || true
  sleep 2
  browser_use_run_with_profile_timed "${session}" "$(( timeout_s + 10 ))" "${profile_spec}" open "${url}"
}

browser_use__invoke() {
  local session="$1"
  local profile_name="$2"
  shift 2

  browser_use_require_cli

  local -a cmd=("${BROWSER_USE_CLI}" --session "${session}")
  if [[ -n "${BROWSER_USE_CDP_URL:-}" ]]; then
    cmd+=(--cdp-url "${BROWSER_USE_CDP_URL}")
  elif [[ "${BROWSER_USE_CONNECT:-0}" == "1" ]]; then
    cmd+=(--connect)
  elif [[ -n "${profile_name}" ]]; then
    cmd+=(--profile "${profile_name}")
  fi
  cmd+=("$@")

  local output=""
  if output="$("${cmd[@]}" 2>&1)"; then
    browser_use__set_last_output "${output}"
    printf '%s\n' "${output}"
    return 0
  fi

  local rc=$?
  browser_use__set_last_output "${output}"
  printf '%s\n' "${output}"
  return "${rc}"
}

browser_use__run_python() {
  local session="$1"
  local code="$2"
  browser_use__invoke "${session}" "" python "${code}"
}

browser_use__eval_script() {
  local session="$1"
  local script="$2"
  local python_code=""

  python_code="$(python3 - "${script}" <<'PY'
import json
import sys

script = sys.argv[1]
wrapped = f"(...args) => Promise.resolve(({script}))"
wrapped_json = json.dumps(wrapped)

print("page = browser._run(browser._session.must_get_current_page())")
print(f"value = browser._run(page.evaluate({wrapped_json}))")
print("if value is None:")
print("    print('result: None')")
print("else:")
print("    print(f'result: {value}')")
PY
)"

  browser_use__run_python "${session}" "${python_code}"
}

browser_use__normalize_ref() {
  local value="$1"
  if [[ "${value}" =~ ^@?e([0-9]+)$ ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
    return 0
  fi
  if [[ "${value}" =~ ^[0-9]+$ ]]; then
    printf '%s\n' "${value}"
    return 0
  fi
  return 1
}

browser_use__state_ref_for_pattern() {
  local state_output="$1"
  local pattern="$2"
  local mode="${3:-line}"

  STATE_OUTPUT="${state_output}" PATTERN="${pattern}" MODE="${mode}" python3 - <<'PY'
import os
import re
import sys

state_output = os.environ.get("STATE_OUTPUT", "")
pattern = os.environ.get("PATTERN", "")
mode = os.environ.get("MODE", "line")

lines = state_output.splitlines()
needle = re.compile(pattern)
ref_re = re.compile(r"\[(\d+)\]")
clickable_re = re.compile(r"\[(\d+)\]<(?:a|button)\b|\[(\d+)\]<div\s+role=button\b")

def ref_from_line(line: str) -> str:
    match = ref_re.search(line)
    return match.group(1) if match else ""

def clickable_ref_from_line(line: str) -> str:
    match = clickable_re.search(line)
    if not match:
        return ""
    return match.group(1) or match.group(2) or ""

for index, line in enumerate(lines):
    if not needle.search(line):
        continue
    if mode == "near_clickable":
        for radius in range(0, 9):
            positions = [index] if radius == 0 else [index - radius, index + radius]
            for pos in positions:
                if pos < 0 or pos >= len(lines):
                    continue
                ref = clickable_ref_from_line(lines[pos])
                if ref:
                    print(ref)
                    raise SystemExit(0)
    ref = ref_from_line(line)
    if ref:
        print(ref)
        raise SystemExit(0)

raise SystemExit(1)
PY
}

browser_use_find_ref_by_pattern() {
  local session="$1"
  local pattern="$2"
  local mode="${3:-line}"
  local state_output=""

  state_output="$(browser_use_run_timed "${session}" 15 state || true)"
  browser_use__state_ref_for_pattern "${state_output}" "${pattern}" "${mode}"
}

browser_use_open_telegram_chat() {
  local session="$1"
  local bot_id="$2"
  local bot_handle="$3"
  local bot_name_pattern="${4:-Vivi kontrole bot}"
  local attempts="${5:-4}"
  local attempt

  for ((attempt = 0; attempt < attempts; attempt++)); do
    browser_use_run_timed "${session}" 15 eval "window.location.href" >/dev/null || true
    if browser_use_output_has "#${bot_id}"; then
      return 0
    fi

    local search_ref=""
    search_ref="$(browser_use_find_ref_by_pattern "${session}" 'id=telegram-search-input' 'line' || true)"
    if [[ -z "${search_ref}" ]]; then
      sleep 1
      continue
    fi

    browser_use__invoke "${session}" "" input "${search_ref}" "${bot_handle}" >/dev/null || true
    sleep 1

    local result_ref=""
    result_ref="$(
      browser_use_find_ref_by_pattern \
        "${session}" \
        "peer-story${bot_id}" \
        'near_clickable' \
        || true
    )"

    if [[ -z "${result_ref}" ]]; then
      browser_use__invoke "${session}" "" keys "Enter" >/dev/null || true
      sleep 1
      result_ref="$(
        browser_use_find_ref_by_pattern \
          "${session}" \
          "peer-story${bot_id}|Here it is: ${bot_name_pattern}" \
          'near_clickable' \
          || true
      )"
    fi

    if [[ -n "${result_ref}" ]]; then
      browser_use__invoke "${session}" "" click "${result_ref}" >/dev/null || true
      sleep 1
      browser_use_run_timed "${session}" 15 eval "window.location.href" >/dev/null || true
      if browser_use_output_has "#${bot_id}"; then
        return 0
      fi
    fi
  done

  return 1
}

browser_use__wait() {
  local value="${1:-1}"
  local seconds=""

  seconds="$(python3 - "${value}" <<'PY'
import sys

raw = sys.argv[1]
try:
    value = float(raw)
except ValueError:
    raise SystemExit(1)

if value >= 100:
    value /= 1000.0

print(value)
PY
  )" || return 1

  sleep "${seconds}"
  browser_use__set_last_output ""
}

browser_use__viewport_code() {
  local width="$1"
  local height="$2"
  printf 'browser._run(browser._session._cdp_set_viewport(%s, %s, mobile=False))\n' "${width}" "${height}"
}

browser_use__focus_selector_script() {
  python3 - "$1" <<'PY'
import json
import sys

selector = json.dumps(sys.argv[1])
print(
    f"""(() => {{
  const selector = {selector};
  const node = document.querySelector(selector);
  if (!node) {{
    return 'focusOk=0;reason=missing';
  }}
  node.focus();
  if ('value' in node) {{
    node.value = '';
    node.dispatchEvent(new Event('input', {{ bubbles: true }}));
    node.dispatchEvent(new Event('change', {{ bubbles: true }}));
    return 'focusOk=1;mode=value';
  }}
  if (node.isContentEditable) {{
    node.textContent = '';
    try {{
      node.dispatchEvent(new InputEvent('input', {{ bubbles: true, data: '', inputType: 'insertText' }}));
    }} catch (_) {{
      node.dispatchEvent(new Event('input', {{ bubbles: true }}));
    }}
    return 'focusOk=1;mode=contenteditable';
  }}
  return 'focusOk=0;reason=unsupported';
}})()"""
)
PY
}

browser_use__click_selector_script() {
  python3 - "$1" <<'PY'
import json
import sys

pattern = json.dumps(sys.argv[1])
print(
    f"""(() => {{
  const matcher = new RegExp({pattern}, 'i');
  const visible = (node) => Boolean(
    node &&
    node.isConnected &&
    node.getClientRects &&
    node.getClientRects().length > 0 &&
    window.getComputedStyle(node).visibility !== 'hidden' &&
    window.getComputedStyle(node).display !== 'none'
  );
  const text = (node) => String((node && (node.textContent || node.innerText)) || '').trim();
  const clickNode = (node) => {{
    if (!visible(node)) {{
      return false;
    }}
    if (typeof node.click === 'function') {{
      node.click();
      return true;
    }}
    node.dispatchEvent(new MouseEvent('mouseover', {{ bubbles: true, cancelable: true }}));
    node.dispatchEvent(new MouseEvent('mousedown', {{ bubbles: true, cancelable: true }}));
    node.dispatchEvent(new MouseEvent('mouseup', {{ bubbles: true, cancelable: true }}));
    node.dispatchEvent(new MouseEvent('click', {{ bubbles: true, cancelable: true }}));
    return true;
  }};
  const node = Array.from(document.querySelectorAll('button, a, div[role="button"], span'))
    .find((candidate) => visible(candidate) && matcher.test(text(candidate)));
  return 'clicked=' + (clickNode(node) ? 1 : 0);
}})()"""
)
PY
}

browser_use__type_selector() {
  local session="$1"
  local selector="$2"
  local text="$3"
  local focus_script=""

  focus_script="$(browser_use__focus_selector_script "${selector}")"
  browser_use__eval_script "${session}" "${focus_script}" >/dev/null || return 1
  if ! browser_use_output_has 'focusOk=1'; then
    return 1
  fi
  browser_use__invoke "${session}" "" type "${text}"
}

browser_use__snapshot() {
  local session="$1"
  browser_use__run_python "${session}" $'print(browser._run(browser._session.get_state_as_text()))'
}

browser_use__console_dump() {
  local session="$1"
  browser_use__run_python "${session}" $'print("browser-use compatibility capture: console log export is unavailable; current page state follows.")\nprint(f"url={browser.url}")\nprint(f"title={browser.title}")\nprint(browser._run(browser._session.get_state_as_text()))'
}

browser_use__network_dump() {
  local session="$1"
  browser_use__run_python "${session}" $'print("browser-use compatibility capture: network request export is unavailable; current page HTML follows.")\nprint(f"url={browser.url}")\nprint(f"title={browser.title}")\nprint(browser.html)'
}

browser_use_run() {
  local session="$1"
  shift
  local command="${1:-}"
  shift || true

  case "${command}" in
    open|close|back|dblclick|rightclick|hover|select|upload|get|state)
      browser_use__invoke "${session}" "" "${command}" "$@"
      ;;
    click)
      local ref=""
      if ref="$(browser_use__normalize_ref "${1:-}")"; then
        browser_use__invoke "${session}" "" click "${ref}"
      else
        browser_use__eval_script "${session}" "$(browser_use__click_selector_script "${1:-}")"
      fi
      ;;
    eval)
      browser_use__eval_script "${session}" "${1:-}"
      ;;
    fill)
      local ref=""
      if ref="$(browser_use__normalize_ref "${1:-}")"; then
        browser_use__invoke "${session}" "" input "${ref}" "${2:-}"
      else
        browser_use__type_selector "${session}" "${1:-}" "${2:-}"
      fi
      ;;
    type)
      browser_use__type_selector "${session}" "${1:-}" "${2:-}"
      ;;
    press)
      browser_use__invoke "${session}" "" keys "${1:-}"
      ;;
    screenshot)
      browser_use__invoke "${session}" "" screenshot "${1:-}"
      ;;
    set)
      if [[ "${1:-}" == "viewport" ]]; then
        browser_use__run_python "${session}" "$(browser_use__viewport_code "${2:-1280}" "${3:-900}")"
      else
        log "Unsupported browser-use adapter command: set $*"
        return 1
      fi
      ;;
    wait)
      browser_use__wait "${1:-1}"
      ;;
    console)
      if [[ "${1:-}" == "--clear" ]]; then
        browser_use__set_last_output ""
        return 0
      fi
      browser_use__console_dump "${session}"
      ;;
    network)
      if [[ "${1:-}" == "requests" && "${2:-}" == "--clear" ]]; then
        browser_use__set_last_output ""
        return 0
      fi
      if [[ "${1:-}" == "requests" ]]; then
        shift
      fi
      browser_use__network_dump "${session}"
      ;;
    snapshot)
      browser_use__snapshot "${session}"
      ;;
    keys)
      browser_use__invoke "${session}" "" keys "${1:-}"
      ;;
    "")
      log "Missing browser-use adapter command"
      return 1
      ;;
    *)
      browser_use__invoke "${session}" "" "${command}" "$@"
      ;;
  esac
}

browser_use_run_with_profile() {
  local session="$1"
  local profile_spec="$2"
  shift 2

  if [[ -n "${BROWSER_USE_CDP_URL:-}" || "${BROWSER_USE_CONNECT:-0}" == "1" || -z "${profile_spec}" ]]; then
    browser_use__invoke "${session}" "" "$@"
    return $?
  fi

  local profile_name=""
  profile_name="$(browser_use_resolve_profile "${profile_spec}")" || return 1
  browser_use__invoke "${session}" "${profile_name}" "$@"
}

browser_use_wait_for_eval_match() {
  local session="$1"
  local script="$2"
  local pattern="$3"
  local loops="${4:-20}"
  local delay_s="${5:-1}"
  local eval_timeout_s="${BROWSER_USE_EVAL_TIMEOUT_S:-30}"
  local i

  for ((i = 0; i < loops; i++)); do
    browser_use_run_timed "${session}" "${eval_timeout_s}" eval "${script}" || true
    if browser_use_output_has "${pattern}"; then
      return 0
    fi
    sleep "${delay_s}"
  done
  return 1
}

browser_use_write_output() {
  local session="$1"
  local output_file="$2"
  shift 2
  browser_use_run "${session}" "$@" >"${output_file}" 2>&1
}

browser_use_run_timed() {
  local session="$1"
  local timeout_s="$2"
  shift 2

  browser_use_require_cli

  local output_file=""
  output_file="$(mktemp)"
  local pid=""
  local elapsed=0
  local output=""

  (
    browser_use_run "${session}" "$@" >"${output_file}" 2>&1
  ) &
  pid=$!

  while kill -0 "${pid}" >/dev/null 2>&1; do
    if (( elapsed >= timeout_s )); then
      kill "${pid}" >/dev/null 2>&1 || true
      wait "${pid}" >/dev/null 2>&1 || true
      output="$(cat "${output_file}" 2>/dev/null || true)"
      browser_use__set_last_output "${output}"
      printf '%s\n' "${output}"
      rm -f "${output_file}"
      return 124
    fi
    sleep 1
    ((elapsed += 1))
  done

  wait "${pid}"
  local rc=$?
  output="$(cat "${output_file}" 2>/dev/null || true)"
  browser_use__set_last_output "${output}"
  printf '%s\n' "${output}"
  rm -f "${output_file}"
  return "${rc}"
}

browser_use_run_with_profile_timed() {
  local session="$1"
  local timeout_s="$2"
  local profile_spec="$3"
  shift 3

  browser_use_require_cli

  local output_file=""
  output_file="$(mktemp)"
  local pid=""
  local elapsed=0
  local output=""

  (
    browser_use_run_with_profile "${session}" "${profile_spec}" "$@" >"${output_file}" 2>&1
  ) &
  pid=$!

  while kill -0 "${pid}" >/dev/null 2>&1; do
    if (( elapsed >= timeout_s )); then
      kill "${pid}" >/dev/null 2>&1 || true
      wait "${pid}" >/dev/null 2>&1 || true
      output="$(cat "${output_file}" 2>/dev/null || true)"
      browser_use__set_last_output "${output}"
      printf '%s\n' "${output}"
      rm -f "${output_file}"
      return 124
    fi
    sleep 1
    ((elapsed += 1))
  done

  wait "${pid}"
  local rc=$?
  output="$(cat "${output_file}" 2>/dev/null || true)"
  browser_use__set_last_output "${output}"
  printf '%s\n' "${output}"
  rm -f "${output_file}"
  return "${rc}"
}

browser_use_try_screenshot() {
  local session="$1"
  local output_file="$2"
  local timeout_s="${3:-15}"

  if ! browser_use_run_timed "${session}" "${timeout_s}" screenshot "${output_file}" >/dev/null; then
    log "Skipping screenshot after browser-use timeout (${timeout_s}s): ${output_file}"
  fi
}

browser_use_try_write_output() {
  local session="$1"
  local output_file="$2"
  local timeout_s="${3:-15}"
  shift 3

  if ! browser_use_run_timed "${session}" "${timeout_s}" "$@" >"${output_file}" 2>&1; then
    log "Skipping browser-use output capture after timeout (${timeout_s}s): ${output_file}"
  fi
}

browser_use_session_exists() {
  local session="$1"

  browser_use_require_cli

  "${BROWSER_USE_CLI}" sessions 2>/dev/null \
    | awk 'NR > 1 {print $1}' \
    | grep -Fxq "${session}"
}
