# Start

This starter assumes you are working from a laptop or desktop and targeting a rooted Pixel that owns the runtime under `/data/local/pixel-stack`.

## 1. Prepare Local Tooling

- Go 1.22+ for `train-bot` and `satiksme-bot`
- Python 3.11+ for `site-notifications`
- Java/Gradle for `orchestrator/android-orchestrator`
- `adb` for device fallback
- `ssh`, `scp`, and `expect` for SSH-first deploys

## 2. Create Local-Only Env Files

Copy only the examples you plan to use:

```bash
cp workloads/train-bot/.env.example workloads/train-bot/.env
cp workloads/satiksme-bot/.env.example workloads/satiksme-bot/.env
cp workloads/site-notifications/.env.example workloads/site-notifications/.env
```

Keep those files untracked.

## 3. Choose a Starting Config

Use `orchestrator/configs/orchestrator-config-v1.example.json` as your baseline.

Recommended first edits:

- set your own public hostnames
- point secret file paths at your private runtime secret locations
- disable modules you are not using yet
- set VPN and SSH values for your environment

## 4. Validate the Repo

```bash
./tools/import/validate_contracts.py
./tools/docs/check_links.sh
cd workloads/train-bot && go test ./...
cd ../satiksme-bot && go test ./...
cd ../site-notifications && python -m pip install -r requirements-dev.txt && PYTHONPATH=. pytest -q
cd ../../automation/task-executor && ./scripts/drain_runner_smoke_test.sh
```

## 5. First Deploy

SSH-first example:

```bash
export PIXEL_TRANSPORT=ssh
export PIXEL_SSH_HOST="<pixel-host-or-tailnet-ip>"
export PIXEL_SSH_PORT=2222
export PIXEL_DEVICE_SSH_PASSWORD="<root-ssh-password>"

./tools/pixel/check_ssh_ready.sh --ssh-host "${PIXEL_SSH_HOST}"
./tools/pixel/redeploy.sh --scope full --transport ssh --ssh-host "${PIXEL_SSH_HOST}"
```

ADB fallback example:

```bash
export PIXEL_TRANSPORT=adb
export ADB_SERIAL="<adb-serial>"

./tools/pixel/redeploy.sh --scope full --transport adb --device "${ADB_SERIAL}"
```
