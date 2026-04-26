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

python3 - <<'PY' "$base_url"
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

base = sys.argv[1].rstrip("/")
opener = urllib.request.build_opener()
opener.addheaders = [
    ("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"),
]

for path in ("/", "/incidents", "/-incidents", "/app", "/api/v1/health", "/bundles/active.json", "/api/v1/public/live-vehicles"):
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
web = health.get("web") or {}
bundle_health = health.get("bundle") or {}
if "liveDepartures" in health:
    raise SystemExit("health still exposes the removed liveDepartures payload")
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

with opener.open(base + "/bundles/active.json", timeout=20) as response:
    active_bundle = json.loads(response.read().decode("utf-8"))
if not active_bundle.get("version") or not active_bundle.get("manifestPath"):
    raise SystemExit("active bundle metadata is incomplete")

manifest_url = urllib.parse.urljoin(base + "/", active_bundle["manifestPath"].lstrip("/"))
with opener.open(manifest_url, timeout=20) as response:
    manifest = json.loads(response.read().decode("utf-8"))
bundle_prefix = active_bundle["manifestPath"].rsplit("/", 1)[0] + "/"
stops_path = bundle_prefix + ((manifest.get("slices") or {}).get("stops") or "stops.json")
routes_path = bundle_prefix + ((manifest.get("slices") or {}).get("routes") or "routes.json")
with opener.open(urllib.parse.urljoin(base + "/", stops_path.lstrip("/")), timeout=20) as response:
    stops = json.loads(response.read().decode("utf-8"))
with opener.open(urllib.parse.urljoin(base + "/", routes_path.lstrip("/")), timeout=20) as response:
    routes = json.loads(response.read().decode("utf-8"))
if not stops:
    raise SystemExit("bundle stops slice is empty")
if not isinstance(routes, list):
    raise SystemExit("bundle routes slice did not decode as a list")
if bundle_health:
    if bundle_health.get("version") != active_bundle.get("version"):
        raise SystemExit("health bundle version does not match active.json")
    if bundle_health.get("manifestPath") != active_bundle.get("manifestPath"):
        raise SystemExit("health bundle manifest path does not match active.json")

with opener.open(base + "/app", timeout=20) as response:
    shell = response.read().decode("utf-8")
if not re.search(r"/assets/app\.js\?v=[0-9a-f]{64}", shell):
    raise SystemExit("mini app shell is missing a versioned app.js URL")
if not re.search(r"/assets/app\.css\?v=[0-9a-f]{64}", shell):
    raise SystemExit("mini app shell is missing a versioned app.css URL")
if '"/bundles/active.json"' not in shell:
    raise SystemExit("mini app shell is missing the active bundle URL")
if web.get("spacetimeDirectOnly") and '"spacetimeDirectOnly":true' not in shell:
    raise SystemExit("mini app shell is missing the direct-only flag")

with opener.open(base + "/assets/app.js", timeout=20) as response:
    app_js = response.read().decode("utf-8")
for marker in ('pathFor("/incidents")', 'mapRootPath()', '>Notiekošais<'):
    if marker not in app_js:
        raise SystemExit(f"app.js is missing incidents navigation marker: {marker}")
if '>Karte<' not in app_js and '>Mape<' not in app_js:
    raise SystemExit("app.js is missing the map navigation marker")
if "/-incidents" in app_js:
    raise SystemExit("app.js still exposes the legacy incidents path")
if "/api/v1/live/departures" in app_js:
    raise SystemExit("app.js still references the removed live departures endpoint")
if "departures2.php" in app_js:
    raise SystemExit("app.js still references the removed departures source")

if web.get("spacetimeEnabled"):
    with opener.open(base + "/oidc/.well-known/openid-configuration", timeout=20) as response:
        discovery = json.loads(response.read().decode("utf-8"))
    with opener.open(base + "/oidc/jwks.json", timeout=20) as response:
        jwks = json.loads(response.read().decode("utf-8"))
    if not discovery.get("issuer") or not discovery.get("jwks_uri"):
        raise SystemExit("OIDC discovery is incomplete")
    if not isinstance(jwks.get("keys"), list) or not jwks["keys"]:
        raise SystemExit("JWKS response is missing keys")

if web.get("spacetimeDirectOnly"):
    for path in (
        "/api/v1/public/catalog",
        "/api/v1/public/sightings",
        "/api/v1/public/incidents",
        "/api/v1/public/map",
        "/api/v1/public/map-live",
    ):
        try:
            opener.open(base + path, timeout=20)
            raise SystemExit(f"{path} unexpectedly remained available in direct-only mode")
        except urllib.error.HTTPError as exc:
            if exc.code != 404:
                raise

print("public smoke ok")
PY
