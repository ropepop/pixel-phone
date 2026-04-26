# gribu.lv Pixel Notifier (Telegram controlled)

Telegram-controlled notifier for new gribu.lv messages.

## Features

- Telegram commands: `/on`, `/off`, `/status`, `/debug`, `/checknow`, `/reauth`, `/help`
- Persistent Telegram navigation buttons: `Enable`, `Pause`, `Status`, `Check now`, `Reauth`, `Help`
- Adaptive polling cadence (fast when healthy, idle/backoff otherwise)
- Auto-discovery of a usable check route with confidence scoring
- Dedicated high-confidence chat-badge parsing from the menu `Čats` tab
- Disabled by default
- Sends Telegram alert only when unread count increases
- Confidence gates: low-confidence parses do not mutate unread baseline
- Logs in automatically with your credentials
- Refreshes session cookies automatically on expiry
- Persists refreshed `GRIBU_COOKIE_HEADER` back to `.env`
- Pauses checks only when auto-reauth fails
- Single-instance daemon lock (`DAEMON_LOCK_FILE`)
- In-process watchdog for scheduler and Telegram polling loops
- In-process supervisor with exponential restart backoff
- `healthcheck` command for liveness validation
- `diag-telegram` command for Telegram API + heartbeat diagnostics

## Reliability model

- Inside Python daemon:
  - daemon startup is allowed only when `RUNTIME_CONTEXT_POLICY=orchestrator_root`
  - non-orchestrator daemon launch exits with code `11`
  - watchdog updates and checks heartbeats for `daemon`, `telegram`, `scheduler`, and `command_worker`
  - if a worker thread dies, daemon exits with code `20`
  - if a worker heartbeat is stale, daemon exits with code `21`
  - if lock is already held, daemon exits with code `10`
  - worker exits `20/21` are restarted by the daemon supervisor with exponential backoff
  - backoff resets after a stable 10-minute worker run

## Requirements

- Python 3.10+
- Telegram bot token and your Telegram chat ID
- gribu.lv login credentials (username/email + password)

## Install (Local)

```bash
pkg update
pkg install python git
git clone <your-repo-url> site-notifications
cd site-notifications
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
chmod 600 .env
```

For local test/development tooling:

```bash
pip install -r requirements-dev.txt
```

Edit `.env` with your real values:

- `TELEGRAM_BOT_TOKEN`
- `TELEGRAM_CHAT_ID`
- `GRIBU_LOGIN_ID`
- `GRIBU_LOGIN_PASSWORD`

Optional reliability settings:

- `DAEMON_LOCK_FILE=./state/daemon.lock`
- `WATCHDOG_CHECK_SEC=10`
- `WATCHDOG_STALE_SEC=120`
- `SUPERVISOR_RESTART_BASE_SEC=2`
- `SUPERVISOR_RESTART_MAX_SEC=30`
- `PARSE_LOW_CONFIDENCE_DELTA_LIMIT=20`
- `ROUTE_DISCOVERY_TTL_SEC=21600`
- `PARSE_MIN_CONFIDENCE_BASELINE=0.8`
- `PARSE_MIN_CONFIDENCE_UPDATE=0.7`
- `PARSE_MIN_CONFIDENCE_ROUTE_SELECTION=0.7`
- `CHECK_INTERVAL_FAST_SEC=20`
- `CHECK_INTERVAL_IDLE_SEC=60`
- `CHECK_INTERVAL_ERROR_BACKOFF_MAX_SEC=180`
- `TELEGRAM_NAV_BUTTONS_ENABLED=true`
- `GRIBU_CHECK_URL=/cats`
- `GRIBU_CHECK_URL_CANDIDATES=GRIBU_CHECK_URL,/cats,/zinas,/user-activities,/messages,/lv/messages`

Chat badge semantics:

- If the menu `Čats` badge is present, its number is treated as unopened chat count.
- If the `Čats` menu tab is present but badge is absent, unopened chat count is treated as `0`.

Path behavior:

- Relative `STATE_FILE` and `DAEMON_LOCK_FILE` paths are resolved from the `.env` file directory.
- Absolute paths are used as-is.

If you do not know `TELEGRAM_CHAT_ID`:

1. Send `/start` to your bot in Telegram.
2. Run:

```bash
TOKEN='<your-bot-token>'
curl -s "https://api.telegram.org/bot${TOKEN}/getUpdates" | python -m json.tool
```

3. Take `message.chat.id` from the latest update and put it into `.env`.

## Run

Start daemon:

```bash
source .venv/bin/activate
python app.py daemon
```

Daemon ownership gate:

- `python app.py daemon` requires `RUNTIME_CONTEXT_POLICY=orchestrator_root`.
- In production this is set by the root orchestrator (`site_notifier` component).
- If missing or different, daemon exits with code `11`.

Run single check:

```bash
python app.py check-once
```

Run daemon health check (`0` = healthy, `1` = unhealthy):

```bash
python app.py healthcheck
```

Run Telegram diagnostics (`getMe`, `getWebhookInfo`, local heartbeat snapshot):

```bash
python app.py diag-telegram
```

Inspect local state:

```bash
python app.py status-local
```

Run tests:

```bash
PYTHONPATH=. pytest -q
```

Collect deployment evidence from Pixel via ADB:

```bash
scripts/collect_pixel_deploy_data.sh
```

Artifacts are stored under `./state/pixel-diagnostics/<timestamp>/`.

## Telegram Control

- Use buttons for quick navigation: `Enable`, `Pause`, `Status`, `Check now`, `Reauth`, `Help`
- Send `/on` to start polling
- Send `/off` to stop polling
- Send `/status` for compact state summary
- Send `/debug` for full diagnostics
- Send `/checknow` for immediate check (runs asynchronously and sends result)
- Send `/reauth` to force re-login and refresh session cookie now (runs asynchronously and sends result)

Only the configured `TELEGRAM_CHAT_ID` can control the bot.

## Legacy Autostart (Deprecated)

This path is deprecated. Production lifecycle is managed by the root orchestrator in
the `site_notifier` component.

Manual daemon launch outside orchestrator is unsupported and now hard-blocked by runtime policy.
Use orchestrator actions to start/stop/restart `site_notifier`.

## Linux systemd Autostart

Install as a system service (requires `sudo`):

```bash
chmod 700 scripts/install_linux_systemd.sh
./scripts/install_linux_systemd.sh
```

Manage service:

```bash
sudo systemctl status gribu-notifier.service
sudo systemctl restart gribu-notifier.service
sudo journalctl -u gribu-notifier.service -f
```

## Session refresh flow

Normal lifecycle:

1. Bot authenticates at startup using `GRIBU_LOGIN_ID` + `GRIBU_LOGIN_PASSWORD`.
2. Bot writes a fresh `GRIBU_COOKIE_HEADER` into `.env`.
3. During checks, if the session expires, bot auto-reauths and retries once.

If auto-reauth fails:

1. Bot sends a session-expired alert with `/reauth` instructions.
2. Checks are paused automatically.
3. Fix credentials if needed and send `/reauth`.
4. On successful `/reauth`, checks resume automatically.

## Notes

- State file defaults to `./state/state.json`.
- Keep `.env` private.
- `GRIBU_COOKIE_HEADER` is runtime-managed by the bot.
- This project intentionally avoids logging token/cookie values.
- Reboot restores the previous bot state: if checks were enabled before reboot, they resume enabled; if disabled, they stay disabled.
