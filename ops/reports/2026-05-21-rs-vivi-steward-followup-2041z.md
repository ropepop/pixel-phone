# RS/ViVi steward follow-up — 2026-05-21 20:41Z

Safety: this report contains no RS codes, raw Telegram IDs, chat IDs, tokens, cookies, session IDs, or auth headers.

## Inputs checked

- Latest watchdog output from the cron context reported historical RS QR failures for `@aldajo`, `@iamhdzs`, and one hashed actor, plus a safe ticket-session recovery to ViVi `TICKET_DETAIL`.
- Broker analytics were queried from Arbuzas through `arbuzas-phone_broker-1` at `GET /api/v1/analytics`.
- Broker state was checked at `GET /api/v1/state` for live owner/queue information.
- Bridge health was checked through `arbuzas-phone_broker-1` at `GET http://ticket_phone_bridge:9388/api/v1/health`.
- RS bot access state was parsed only into safe actor-hash-to-username mappings.

## Current broker and bridge state

- Broker analytics generated at `2026-05-21T20:41:53Z` showed 349 retained RS QR jobs: 218 succeeded, 125 failed, 6 canceled, 91 retried, and 62 slow successes.
- The latest retained RS job was sequence 349, completed at `2026-05-21T12:54:20Z`; no waiting/running RS jobs were present during this check.
- Broker live state: current owner `ticket`, desired owner `ticket`, ticket active, one ticket viewer, two ticket sockets, queue depth 0.
- A passive bridge snapshot briefly showed `viviState=UNKNOWN_VIVI` from a cheap `fast_empty` guard read while the H.264 stream remained live. I safely revalidated the active ticket session because there were no active RS jobs.
- Final bridge health after revalidation: `sessionState=live`, `streamActive=true`, `streamVerdict=live`, `ticketState=live`, `viviState=TICKET_DETAIL`, hardware H.264 available/active/visible, latest frame age about 0.3s.

## Affected actors and status

| Actor | Recent failure classes | Later outcome | Status |
| --- | --- | --- | --- |
| `@aldajo` (`user:2f7994586c1c`) | `qr_image_missing` on sequences 301, 337, and 340 | Later real-user successes on sequences 347 and 348 after the deployed fix; latest was retried but under 60s total. | Recovered/closed for delivery; latency/retry risk remains. |
| `@iamhdzs` (`user:3d0625f146d5`) | `phone_timeout`, `rs_monthly_ticket_control_missing`, and `qr_image_missing` on sequences 316, 335, 338, and 341 | Later real-user successes on sequences 344 and 349 after the deployed fix; both were retried/slow. | Recovered/closed for delivery; latency/retry risk remains. |
| `@mt_alinka` (`user:5148c2fcad37`) | `generated` failed on sequence 333 | No same-actor retry after the fix in retained analytics. The deployed fix targets this result-boundary class and has live semantic verification. | Fixed class, actor-specific retry still absent; monitor. |
| Unmapped actor (`user:f82debf58b90`) | `generated` failed on sequence 332 | No same-actor retry after the fix in retained analytics. The injected watchdog used a different safe hash for this same historical failure, so use the broker job hash for follow-up. | Fixed class, actor-specific retry still absent; monitor label consistency. |
| Unmapped actor (`user:b4bfa48b2f72`) | `wrong_code` and `qr_image_missing` on sequences 336 and 339 | Later real-user success on sequence 342, but it was retried/slow. | Recovered; latency/retry risk remains. |

## Root cause and fix status

No production code was changed in this follow-up run. The prior same-day steward fix remains the relevant remediation:

1. Android hierarchy dumps could collide or leave `UiAutomation` registered, causing `UNKNOWN_VIVI` / `qr_image_missing` behavior.
2. The RS monthly-ticket runner could reach and semantically verify `TICKET FOR CONTROL` but still report failure if a later shell/tap script exited non-zero, producing failed `generated` jobs without a PNG.

The deployed fixes were:

- guarded `TicketUiautomatorDump` lock/timeout/unregister settling;
- treating semantically verified RS monthly-ticket control screen as success authority for image capture even if a trailing shell action exits non-zero.

## Verification evidence

- Prior fix report recorded focused regressions plus `./gradlew testDebugUnitTest` passing before deployment.
- Prior deployed live fake-Telegram/broker smoke produced `ops/evidence/2026-05-21-rs-vivi-steward/rs-live-smoke-after-fix.png`.
- I visually checked that artifact in this run: it is an RS Passenger-style `TICKET FOR CONTROL` monthly-ticket screen with a QR and 1-month ticket context. I did not decode or repeat the QR/control contents.
- Current broker state is idle with no RS jobs queued/running.
- Current bridge health after safe revalidation is live and back on ViVi `TICKET_DETAIL`.

## Remaining risks

- Historical failed jobs cannot be retro-delivered.
- `@mt_alinka` and the unmapped `generated` failure actor have not personally retried after the fix; their failure class is fixed and verified, but actor-specific success is not present in retained analytics.
- Latency remains a real risk: retained success latency is p50 about 30s and p90 about 193s; the latest `@iamhdzs` success was 118.8s and retried.
- Passive bridge health can still briefly report `UNKNOWN_VIVI` from cheap empty guard snapshots even while the stream is live; on-demand root revalidation returned `TICKET_DETAIL`. Treat repeated watchdog noise from this as a monitoring/revalidation issue unless live stream or ticket state also fails.

## Open incidents

No active real-user delivery incident is open at the end of this run. Monitoring remains open for actor-specific post-fix retries and latency.