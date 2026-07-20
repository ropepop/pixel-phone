# Pixel Orchestrator Polish And Cleanup — 2026-07-13

## Result

The redesigned Material 3 orchestrator, RAM-only general event sender, residue-prevention changes, and private SpacetimeDB history service were deployed before cleanup. DNS and the earlier Pi-hole runtime remained disabled throughout.

Six active non-DNS deployment packages were hash-verified and copied into the shared content-addressed store before old paths were removed. The local-first mirror and phone manifests now reference only those six shared files. No active or rollback manifest referenced a deleted path.

## Measured Cleanup

| Category | Files | Logical bytes reclaimed |
| --- | ---: | ---: |
| Consumed action results | 211 | 3,051,021 |
| Retired DNS and Pi-hole history and service logs | 272 | 925,044,994 |
| Duplicate and retired deployment archives | 10 | 3,420,568,064 |
| App-private deployment staging | 4 | 1,382,248,448 |
| Root-command history rotation | 3 | 1,188,428,496 |
| Managed log rotation and old backup | 12 | 44,078,385 |
| **Total** | **512** | **6,963,419,408** |

Actual used filesystem blocks fell from 24,018,828 KiB to 18,014,696 KiB, a net drop of 6,004,132 KiB (6.15 GB). The difference from logical file sizes reflects filesystem allocation and the temporary addition of the 737,647,104-byte canonical active-package store before old copies were removed.

After the final APK installs, reboot, browser proof, and temporary work settled, the 24-hour growth baseline measured 17,916,520 KiB used. That is 6,102,308 KiB (6.25 GB) below the original baseline.

## Post-Cleanup State

- The six manifest-referenced active packages total 737,647,104 bytes.
- Root-command history is 792,984 bytes across its database, journal, and shared-memory files, below the 32 MiB limit; root access still returns UID 0.
- No managed active log or rotation exceeds 1 MiB, and the measured managed-log total is below 32 MiB.
- No retired DNS/Pi-hole history file, DNS listener, or DNS process remains.
- The final two inert top-level AdGuard service logs were also removed after the user confirmed that DNS is retired. Future cleanup treats those exact files as retired residue only when no DNS process exists.
- No known Ticket hierarchy XML or support archive remains.
- One redacted cleanup summary remains locally; it contains totals only and no path lists.
- Two recent action results remain inside the 24-hour interruption window; confirmed consumers now delete their result immediately.
- Cleanup category totals were recorded in the private SpacetimeDB table with database-clock 24-hour expiry and no paths or free-form text.
- After a full phone reboot and normal settling, the enabled stack returned healthy: root, SSH, VPN, management, supervisor, cleanup scheduling, and Ticket passed while DNS and the old public-remote entry remained disabled.
- The reopened orchestrator restored its saved health instead of returning to an unknown dashboard. The authenticated Chrome page drew the live ViVi ticket, and the matching read-only monitor reported a healthy live phone/relay path with no failures. The exact Brave check redirected to the Ticket login screen, so authenticated Brave proof still requires the user to sign in there.

## Evidence

- [Before manifest and live open-file proof](../evidence/pixel-orchestrator-cleanup-20260713T000034Z/cleanup-manifest.tsv)
- [After manifest](../evidence/pixel-orchestrator-cleanup-20260713T000034Z/after/cleanup-manifest.tsv)
- [Filesystem and limit checks](../evidence/pixel-orchestrator-cleanup-20260713T000034Z/storage-after.txt)
- [Final cleanup summary](../evidence/pixel-orchestrator-cleanup-20260713T000034Z/cleanup-actual-report.json)
- [Final retired-DNS residue cleanup](../evidence/pixel-orchestrator-cleanup-20260713T000034Z/retired-dns-final-cleanup.json)
- [24-hour growth baseline](../evidence/pixel-orchestrator-cleanup-20260713T000034Z/growth-baseline-20260713T011500Z.txt)
- [SpacetimeDB cleanup rows](../evidence/pixel-orchestrator-cleanup-20260713T000034Z/spacetime-cleanup-events.txt)
- [Telemetry privacy and no-disk-queue audit](../evidence/pixel-orchestrator-cleanup-20260713T000034Z/telemetry-privacy-audit.txt)
- [Live orchestrator screenshots](../evidence/pixel-orchestrator-polish-20260712T233820Z/)
- [Post-reboot stack health](../evidence/pixel-orchestrator-polish-20260712T233820Z/post-reboot-health-state-redacted.json)
- [Authenticated Ticket browser proof](../evidence/pixel-orchestrator-polish-20260712T233820Z/ticket-browser-verification.txt)
- [Matching live phone and relay health](../evidence/pixel-orchestrator-polish-20260712T233820Z/ticket-live-health-after-reboot.json)

## Verification

- Android: 540 unit tests passed; the instrumented-test source compiled; the APK assembled; Android lint passed using the supported Java 17 runtime.
- Phone runtime: 17 shell syntax checks and 41 runtime contract tests passed, including retired-DNS cleanup and interrupted safety cases.
- Spacetime module: formatting, 7 module tests, strict linting, and the production module build passed before generated build output was removed.
- Repository integrity: both repositories pass whitespace/error checks. The unrelated existing Arbuzas mirror edits in the sibling repository were left untouched.

Two acceptance gates remain: an authenticated Brave screenshot after the user signs in to Ticket there, and a one-time 24-hour growth measurement using the same after-manifest categories. A continuation card is prepared for 2026-07-14 at 04:20 Europe/Riga so the growth result can be added to this report in the same task.
