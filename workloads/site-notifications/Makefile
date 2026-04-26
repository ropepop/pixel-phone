.PHONY: test pixel-deploy pixel-restart pixel-release-check

test:
	PYTHONPATH=. pytest -q

pixel-deploy:
	../../tools/pixel/redeploy.sh --scope site_notifier

pixel-restart:
	./scripts/pixel/restart_service.sh

pixel-release-check:
	./scripts/pixel/release_check.sh
