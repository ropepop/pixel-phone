#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
mkdir -p bin

GOOS=android GOARCH=arm64 go build -ldflags "$(bash ./scripts/ldflags.sh)" -o ./bin/train-bot-android-arm64 ./cmd/bot

echo "Built ./bin/train-bot-android-arm64"
