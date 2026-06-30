# Pixel Phone Space By Week - 2026-06-27

Evidence directory: `ops/evidence/pixel-phone-space-by-week-20260627/`

## Method

This groups files larger than 10 MB from the known space-heavy areas by their file modification week. Android does not reliably expose original creation time for every file, so modification time is the closest available "appeared around then" signal.

Scanned areas:

- `/data/local`
- `/data/user/0/com.termux`
- `/data/user_de/0/giilmkonhuutj.ih.wb`
- `/data/app`

## Largest Weeks

| Week | Large-file total | Main cause |
| --- | ---: | --- |
| `2026-W10` | `21.27 GB` | Termux `telegram-train-app` build/artifact tarballs, plus Pixel Stack temp runtime bundles |
| `2026-W26` | `6.16 GB` | Superuser log database `sulogs.db`, plus current VPN/SSH/ticket logs |
| `2026-W14` | `5.37 GB` | Pixel Stack runtime artifacts, duplicated site-notifier bundles, Google Play services package files |
| `2026-W09` | `2.06 GB` | Pixel Stack temp rootfs bundles and early rooted stack files |
| `2025-W51` | `1.45 GB` | Termux/runtime libraries copied into several site-notifier build/runtime directories |
| `2026-W11` | `1.43 GB` | Pixel Stack temp AdGuardHome runtime bundle |
| `2026-W17` | `1.35 GB` | Pixel Stack temp AdGuardHome rootfs bundle |
| `2023-W12` | `1.07 GB` | Older Pi-hole chroot libraries and packages |

## Notable Examples

- `2026-W10`: repeated `site-notifier-bundle-*.tar` files in Termux under `telegram-train-app/workloads/site-notifications/.artifacts/`, each around `602 MB`.
- `2026-W10`: two AdGuardHome rootfs tarballs under Termux orchestrator artifacts, around `1.25 GB` each.
- `2026-W26`: `/data/user_de/0/giilmkonhuutj.ih.wb/databases/sulogs.db`, around `6.09 GB`.
- `2026-W14`: Pixel Stack runtime AdGuardHome rootfs copies and site-notifier tarballs.
- `2026-W09`, `2026-W11`, `2026-W17`: older Pixel Stack temp rootfs tarballs in `/data/local/tmp`.

## Raw Tables

- `ops/evidence/pixel-phone-space-by-week-20260627/by-week-summary.tsv`
- `ops/evidence/pixel-phone-space-by-week-20260627/top-files-by-week.tsv`
- `ops/evidence/pixel-phone-space-by-week-20260627/large-files-mtime.tsv`

## Cleanup Implication

The best cleanup candidates are old build artifacts and temporary rootfs bundles, especially under Termux `telegram-train-app` artifacts and `/data/local/tmp`. The `sulogs.db` file is also a large single-file target, but it should be handled as an app/root-log cleanup rather than deleted blindly.
