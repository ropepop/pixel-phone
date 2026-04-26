#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
mkdir -p bin

GOOS=android GOARCH=arm64 CGO_ENABLED=0 \
  go build -ldflags "$(bash ./scripts/ldflags.sh)" \
  -o ./bin/satiksme-bot-android-arm64 \
  ./cmd/bot

echo "Built ./bin/satiksme-bot-android-arm64"
