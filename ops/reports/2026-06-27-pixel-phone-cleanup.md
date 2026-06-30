# Pixel Phone Cleanup - 2026-06-27

Evidence directory: `ops/evidence/pixel-phone-cleanup-20260627/`

## Result

The cleanup completed.

Main storage moved from about `54G used / 56G free` to about `20G used / 89G free`, based on `df -h /data /storage/emulated/0`.

Approximate user-visible reclaim: `34G`.

## Removed

- Old generated deployment/build files from `/data/local/tmp`, including old rootfs tarballs, temporary orchestrator runtime dirs, old site-notifier bundles, old train/satiksme bundles, debug APKs, ticket poll captures, and other planned generated artifacts.
- Generated Termux artifacts under:
  - `/data/user/0/com.termux/files/home/telegram-train-app/workloads/site-notifications/.artifacts`
  - `/data/user/0/com.termux/files/home/telegram-train-app/orchestrator/.artifacts`
- Root/superuser log database:
  - `/data/user_de/0/giilmkonhuutj.ih.wb/databases/sulogs.db`
  - matching `-wal` and `-shm` files
- Truncated these runtime logs in place:
  - `/data/local/pixel-stack/vpn/logs/tailscaled.log`
  - `/data/local/pixel-stack/ssh/logs/dropbear.log`
  - `/data/local/pixel-stack/apps/ticket-screen/logs/ticket-screen-cloudflared.log`

## Left Intact

- `/data/local/pixel-stack/conf/runtime/artifacts`
- `/data/local/pixel-stack/chroots`
- `/data/local/pixel-stack/apps/*/current`
- `/data/local/pixel-stack/state`
- `/data/local/pixel-stack/run`
- `/data/local/pixel-stack/conf`
- `/data/local/pixel-stack/ssh`
- `/data/local/pixel-stack/vpn`
- `/data/app`
- Termux home and the `telegram-train-app` repository itself

## Verification

- Root access still works: `su -c id` returned root.
- SSH port `2222` is reachable on `100.76.50.43`.
- ADB port `5555` is reachable on `100.76.50.43`.
- Tailscale is still running.
- Dropbear SSH is still running.
- Pixel orchestrator process is still running.
- Ticket Cloudflared process is still running.
- Ticket Cloudflared metrics returned HTTP `200` from `127.0.0.1:20388/metrics`.
- Active Pixel Stack app `current` symlinks remain present.
- Active runtime directories remain present.

DNS note: AdGuardHome/DNS ports were not listening during the post-cleanup check, and the chroot helper did not expose a working status mode. The cleanup did not remove the AdGuardHome chroot, active runtime manifest artifacts, or Pixel Stack config, so this is recorded as "not active / not verified running" rather than a cleanup-induced deletion.

## Evidence Files

- `ops/evidence/pixel-phone-cleanup-20260627/before.txt`
- `ops/evidence/pixel-phone-cleanup-20260627/cleanup.log`
- `ops/evidence/pixel-phone-cleanup-20260627/after.txt`
- `ops/evidence/pixel-phone-cleanup-20260627/verification.txt`
- `ops/evidence/pixel-phone-cleanup-20260627/ssh_port_check.txt`
- `ops/evidence/pixel-phone-cleanup-20260627/listeners.txt`
- `ops/evidence/pixel-phone-cleanup-20260627/ticket-cloudflared-metrics.txt`
