# Satiksme Bot

Standalone Riga Satiksme mini app and public map workload.

This workload is intentionally self-contained under `workloads/satiksme-bot`. It does not modify orchestrator wiring. The runtime includes:
- a Telegram bot with a minimal `/start` menu
- a web app with Telegram-authenticated report submission
- a public read-only map and sightings feed
- local mirroring of Riga Satiksme `stops.txt`, `routes.txt`, and GTFS fallback data
- SQLite-backed stop and vehicle sightings with cooldown, dedupe, visibility, retention, and report-dump queueing

## Local Development

```bash
cp .env.example .env
go test ./...
go run ./cmd/catalogsync
go run ./cmd/bot
```

The web app serves:
- public site: `SATIKSME_WEB_PUBLIC_BASE_URL`
- mini app shell: `SATIKSME_WEB_PUBLIC_BASE_URL/app`

## Key Environment Variables

Required:
- `BOT_TOKEN`

Important:
- `SATIKSME_WEB_ENABLED=true`
- `SATIKSME_WEB_PUBLIC_BASE_URL=https://satiksme-bot.jolkins.id.lv`
- `SATIKSME_WEB_SESSION_SECRET_FILE=/absolute/path/to/session.secret`
- `REPORT_DUMP_CHAT=@satiksme_bot_reports`

Catalog defaults:
- `SATIKSME_SOURCE_STOPS_URL=https://saraksti.rigassatiksme.lv/riga/stops.txt`
- `SATIKSME_SOURCE_ROUTES_URL=https://saraksti.rigassatiksme.lv/riga/routes.txt`
- `SATIKSME_SOURCE_GTFS_URL=https://data.gov.lv/dati/dataset/6d78358a-0095-4ce3-b119-6cde5d0ac54f/resource/c576c770-a01b-49b0-bdc4-0005a1ec5838/download/marsrutusaraksti02_2026.zip`

## Pixel-Oriented Commands

```bash
make pixel-native-test
make pixel-native-build
make pixel-release-check
make pixel-public-smoke
make pixel-miniapp-smoke
```

`make pixel-native-build` produces a local satiksme-bot artifact bundle with:
- `bin/satiksme-bot`
- mirrored source files under `data/catalog/source`
- generated compact catalog JSON under `data/catalog/generated/catalog.json`

## Notes

- Public mode is read-only. Report endpoints require a valid Telegram init-data session.
- Live departures are fetched via server proxy from `departures2.php` when
  `SATIKSME_WEB_DIRECT_PROXY_ENABLED=true` (default in this repo), with the
  browser-direct mode available as a fallback when disabled.
- `REPORT_DUMP_CHAT` accepts either `@channel_username` or a numeric Telegram chat id.
