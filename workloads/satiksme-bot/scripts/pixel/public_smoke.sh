#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

ensure_output_dirs

if [[ -f "$REPO_ROOT/.env" ]]; then
  set -a
  # shellcheck source=/dev/null
  . "$REPO_ROOT/.env"
  set +a
fi

base_url="${SATIKSME_WEB_PUBLIC_BASE_URL:-https://satiksme-bot.jolkins.id.lv}"
official_live_url="${SATIKSME_LIVE_DEPARTURES_URL:-https://saraksti.rigassatiksme.lv/departures2.php}"

python3 - <<'PY' "$base_url" "$official_live_url"
import json
import re
import sys
import urllib.error
import urllib.request

base = sys.argv[1].rstrip("/")
official_live_url = sys.argv[2].rstrip("/")
opener = urllib.request.build_opener()
opener.addheaders = [
    ("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"),
]

for path in ("/", "/app", "/api/v1/health", "/api/v1/public/catalog", "/api/v1/public/sightings", "/api/v1/public/map"):
    with opener.open(base + path, timeout=20) as response:
        if response.status != 200:
            raise SystemExit(f"{path} returned {response.status}")

with opener.open(base + "/api/v1/health", timeout=20) as response:
    health = json.loads(response.read().decode("utf-8"))
    headers = {key.lower(): value for key, value in response.headers.items()}

version = health.get("version") or {}
runtime = health.get("runtime") or {}
assets = health.get("assets") or {}
catalog_health = health.get("catalog") or {}
live_mode = health.get("liveDepartures", {}).get("mode")
if live_mode not in {"browser_direct", "proxy"}:
    raise SystemExit(f"unexpected health.liveDepartures.mode: {live_mode!r}")
if health.get("liveDepartures", {}).get("baseUrl") != official_live_url:
    raise SystemExit("health.liveDepartures.baseUrl does not match the official source URL")
if not version.get("commit") or not runtime.get("instanceId"):
    raise SystemExit("structured health payload is missing version/runtime metadata")
for key in ("appJsSha256", "appCssSha256"):
    if not re.fullmatch(r"[0-9a-f]{64}", str(assets.get(key) or "")):
        raise SystemExit(f"health.assets.{key} missing or invalid")
if catalog_health.get("loaded") is not True:
    raise SystemExit("health.catalog.loaded is not true")
expected_headers = {
    "X-Satiksme-Bot-Instance": runtime.get("instanceId"),
    "X-Satiksme-Bot-App-Js": assets.get("appJsSha256"),
    "X-Satiksme-Bot-App-Css": assets.get("appCssSha256"),
}
for header, expected in expected_headers.items():
    actual = headers.get(header.lower())
    if actual != expected:
        raise SystemExit(f"release header mismatch for {header}: {actual!r} != {expected!r}")

with opener.open(base + "/app", timeout=20) as response:
    shell = response.read().decode("utf-8")
if official_live_url not in shell:
    raise SystemExit("mini app shell does not point at the official live departures URL")
if not re.search(r"/assets/app\.js\?v=[0-9a-f]{64}", shell):
    raise SystemExit("mini app shell is missing a versioned app.js URL")
if not re.search(r"/assets/app\.css\?v=[0-9a-f]{64}", shell):
    raise SystemExit("mini app shell is missing a versioned app.css URL")

with opener.open(base + "/assets/app.js", timeout=20) as response:
    app_js = response.read().decode("utf-8")
if "Live departures are temporarily unavailable from the official Riga Satiksme source." not in app_js:
    raise SystemExit("app.js is missing the degraded live departures message")

with opener.open(base + "/api/v1/public/catalog", timeout=20) as response:
    etag = response.headers.get("ETag")
if not etag:
    raise SystemExit("public catalog response is missing an ETag")
conditional = urllib.request.Request(base + "/api/v1/public/catalog", headers={"If-None-Match": etag})
try:
    opener.open(conditional, timeout=20)
    raise SystemExit("conditional public catalog request unexpectedly returned 200")
except urllib.error.HTTPError as exc:
    if exc.code != 304:
        raise

with opener.open(base + "/api/v1/public/map", timeout=20) as response:
    payload = json.loads(response.read().decode("utf-8"))
if not payload.get("stops"):
    raise SystemExit("map payload missing stops")

print("public smoke ok")
PY
