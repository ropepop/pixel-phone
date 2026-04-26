#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

ensure_output_dirs
ensure_local_env

if [[ -f "$REPO_ROOT/.env" ]]; then
  set -a
  # shellcheck source=/dev/null
  . "$REPO_ROOT/.env"
  set +a
fi

base_url="${SATIKSME_WEB_PUBLIC_BASE_URL:-https://satiksme-bot.jolkins.id.lv}"
bot_token="${BOT_TOKEN:-}"
if [[ -z "${bot_token}" ]]; then
  log "BOT_TOKEN is required for miniapp smoke"
  exit 1
fi

python3 - <<'PY' "$base_url" "$bot_token"
import hashlib
import hmac
import json
import random
import sys
import time
import urllib.parse
import urllib.request
from http.cookiejar import CookieJar

base = sys.argv[1].rstrip("/")
bot_token = sys.argv[2]
now = int(time.time())
user_id = now + random.randint(1000, 9999)
user_agent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
public_opener = urllib.request.build_opener()
public_opener.addheaders = [("User-Agent", user_agent)]

with public_opener.open(base + "/app", timeout=20) as response:
    if response.status != 200:
        raise SystemExit("/app did not load")

with public_opener.open(base + "/api/v1/health", timeout=20) as response:
    health = json.loads(response.read().decode("utf-8"))
live_mode = health.get("liveDepartures", {}).get("mode")
if live_mode not in {"browser_direct", "proxy"}:
    raise SystemExit(f"unexpected health.liveDepartures.mode: {live_mode!r}")

with public_opener.open(base + "/api/v1/public/catalog", timeout=20) as response:
    catalog = json.loads(response.read().decode("utf-8"))

stops = catalog.get("stops") or []
if not stops:
    raise SystemExit("catalog has no stops for miniapp smoke")
stop_id = str(stops[0]["id"])

user_payload = json.dumps(
    {"id": user_id, "first_name": "Smoke", "language_code": "lv"},
    separators=(",", ":"),
)
pairs = [
    ("auth_date", str(now)),
    ("query_id", f"smoke-{user_id}"),
    ("user", user_payload),
]
data_check = "\n".join(f"{key}={value}" for key, value in pairs)
secret = hmac.new(b"WebAppData", bot_token.encode("utf-8"), hashlib.sha256).digest()
signature = hmac.new(secret, data_check.encode("utf-8"), hashlib.sha256).hexdigest()
init_data = urllib.parse.urlencode(pairs + [("hash", signature)])

cookie_jar = CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookie_jar))
opener.addheaders = [("User-Agent", user_agent)]

auth_req = urllib.request.Request(
    base + "/api/v1/auth/telegram",
    data=json.dumps({"initData": init_data}).encode("utf-8"),
    headers={"Content-Type": "application/json"},
    method="POST",
)
with opener.open(auth_req, timeout=20) as response:
    auth_payload = json.loads(response.read().decode("utf-8"))
    if response.status != 200:
        raise SystemExit(f"auth returned {response.status}")
if int(auth_payload.get("userId") or 0) != user_id:
    raise SystemExit("authenticated user id mismatch")
if not any(cookie.name == "satiksme_app_session" for cookie in cookie_jar):
    raise SystemExit("telegram auth did not return a session cookie")

stop_req = urllib.request.Request(
    base + "/api/v1/reports/stop",
    data=json.dumps({"stopId": stop_id}).encode("utf-8"),
    headers={"Content-Type": "application/json"},
    method="POST",
)
with opener.open(stop_req, timeout=20) as response:
    stop_payload = json.loads(response.read().decode("utf-8"))
if not stop_payload.get("accepted"):
    raise SystemExit(f"stop report was not accepted: {stop_payload}")

vehicle_destination = f"Smoke Destination {user_id}"
vehicle_req = urllib.request.Request(
    base + "/api/v1/reports/vehicle",
    data=json.dumps(
        {
            "stopId": stop_id,
            "mode": "bus",
            "routeLabel": "SMOKE",
            "direction": "a-b",
            "destination": vehicle_destination,
            "departureSeconds": 86340,
            "liveRowId": f"smoke-{user_id}",
        }
    ).encode("utf-8"),
    headers={"Content-Type": "application/json"},
    method="POST",
)
with opener.open(vehicle_req, timeout=20) as response:
    vehicle_payload = json.loads(response.read().decode("utf-8"))
if not vehicle_payload.get("accepted"):
    raise SystemExit(f"vehicle report was not accepted: {vehicle_payload}")

for _ in range(10):
    with public_opener.open(base + f"/api/v1/public/sightings?stopId={urllib.parse.quote(stop_id)}&limit=200", timeout=20) as response:
        sightings = json.loads(response.read().decode("utf-8"))

    stop_seen = any(item.get("stopId") == stop_id for item in sightings.get("stopSightings") or [])
    vehicle_seen = any(item.get("destination") == vehicle_destination for item in sightings.get("vehicleSightings") or [])
    if stop_seen and vehicle_seen:
        break
    time.sleep(1)

if not stop_seen:
    raise SystemExit("submitted stop report is not visible in public sightings")
if not vehicle_seen:
    raise SystemExit("submitted vehicle report is not visible in public sightings")

print("miniapp smoke ok")
PY
