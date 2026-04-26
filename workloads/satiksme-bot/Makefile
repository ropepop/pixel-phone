.PHONY: run test test-go test-js build catalog-sync tidy \
	pixel-native-test pixel-native-build pixel-native-deploy pixel-release-check pixel-public-smoke pixel-miniapp-smoke pixel-native-validate pixel-native-all \
	pixel-deploy

LDFLAGS := $(shell bash ./scripts/ldflags.sh)

run:
	go run -ldflags "$(LDFLAGS)" ./cmd/bot

test: test-go test-js

test-go:
	go test ./...

test-js:
	node --test ./internal/web/static/*.test.js

build:
	go build -ldflags "$(LDFLAGS)" -o ./bin/satiksme-bot ./cmd/bot

catalog-sync:
	go run ./cmd/catalogsync

tidy:
	go mod tidy

pixel-native-test:
	./scripts/pixel/run_native_tests.sh

pixel-native-build:
	./scripts/pixel/prepare_native_release.sh

pixel-native-deploy:
	../../tools/pixel/redeploy.sh --scope satiksme_bot

pixel-release-check:
	./scripts/pixel/release_check.sh

pixel-public-smoke:
	./scripts/pixel/public_smoke.sh

pixel-miniapp-smoke:
	./scripts/pixel/miniapp_smoke.sh

pixel-native-validate:
	./scripts/pixel/validate_prod_readiness.sh

pixel-native-all:
	./scripts/pixel/pixel_native_all.sh

pixel-deploy:
	@echo "pixel-deploy retired: use ../../tools/pixel/redeploy.sh --scope satiksme_bot." >&2
	@exit 1
