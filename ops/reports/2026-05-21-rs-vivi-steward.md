# RS/ViVi steward report — 2026-05-21

## Incident sources

- Watchdog reported real RS QR failures for @aldajo, @iamhdzs, and hashed actors.
- Broker analytics were queried from `arbuzas-phone_broker-1` at `GET /api/v1/analytics`.
- Bridge health was queried from `arbuzas-phone_broker-1` at `GET http://ticket_phone_bridge:9388/api/v1/health`.

## Root causes found

1. Android `uiautomator dump` probes could collide or release the lock before Android had unregistered `UiAutomation`, producing `UiAutomationService already registered` and intermittent `UNKNOWN_VIVI`/`qr_image_missing` failures.
2. The RS monthly-ticket runner treated a semantically verified `rs_monthly_ticket_control_screen` as failed if the shell tap script exited non-zero, so the broker saw generated/control-screen state but no PNG.

## Fixes deployed

- `TicketUiautomatorDump` now reserves lock-held cleanup/unregister time inside the outer timeout and refuses too-small dump budgets.
- `runRigasSatiksmeMonthlyTicketFlow` now treats verified `rs_monthly_ticket_control_screen` as success for image capture even when the shell script exits non-zero.
- Architecture notes were updated in `docs/architecture/TICKET_STREAMING_ARCHITECTURE.md`.
- Deployed `ticket_screen` with `orchestrator/scripts/android/deploy_orchestrator_apk.sh --action redeploy_component --component ticket_screen`.

## Tests and live evidence

- Added focused source regressions for both fixes.
- `./gradlew testDebugUnitTest` passed.
- Live fake-Telegram/broker smoke passed after deploy and delivered an RS Passenger `TICKET FOR CONTROL` monthly-ticket PNG.
- Artifact: `ops/evidence/2026-05-21-rs-vivi-steward/rs-live-smoke-after-fix.png`.
- Post-smoke Android logs showed `rigassatiksme_qr_result ok=true sourceApp=com.flutter.rspassenger ticketFlow=rigas_satiksme_android_monthly_ticket_control` and cleanup back to ViVi ticket detail.
- Final bridge health showed `sessionState=live`, `streamActive=true`, `streamVerdict=live`, `ticketState=live`, `viviState=TICKET_DETAIL`.

## Remaining notes

Historical failed real-user jobs cannot be retro-delivered. @aldajo and @iamhdzs did not have a post-fix real-user retry during this run, but the incident class was root-caused, fixed, deployed, and verified through the broker/Telegram-equivalent path.
