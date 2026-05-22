# RS/ViVi steward follow-up — route-home ticket recovery

Generated: 2026-05-21T15:46:16Z

## Incident source

The 24h watchdog reported that `ticket.jolkins` recovery was not healthy after safe recovery: the bridge stream was active but stuck around ViVi `CART_OR_CHECKOUT` / `waiting_keyframe`. The same watchdog output also carried historical RS QR failures for real users, including @aldajo and @iamhdzs.

## Root cause found

The phone was visibly on the ViVi route-planning home screen, not on a cart or ticket detail. Its hierarchy contained a normal service announcement with `Pasažieri...` and a small top-right cart badge. `TicketViviPageEnforcer` checked generic dismissible-blocker/cart heuristics before route-home recovery, so the wake loop treated the route home as a blocker/cart state and tapped/closed the wrong target instead of opening the Tickets tab.

## Fix deployed

Changed ViVi route-home handling so route-planning home is classified as an `open_tickets_tab` recovery state before generic dismissible-blocker/cart heuristics run. The generic `actionForHierarchy` and reset path now also prefer route-home ticket-tab recovery before blocker handling.

Updated architecture notes in `docs/architecture/TICKET_STREAMING_ARCHITECTURE.md` and updated the reusable ticket wake-detection skill reference.

Deployment command used:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" orchestrator/scripts/android/deploy_orchestrator_apk.sh --action redeploy_component --component ticket_screen
```

## Tests

- RED: `./gradlew testDebugUnitTest --tests 'lv.jolkins.pixelorchestrator.app.ticket.TicketViviPageEnforcerTest.routeHomeAnnouncementDoesNotLookLikeDismissibleBlockerOrCart'` failed before the fix.
- GREEN: the same focused regression passed after the fix.
- `./gradlew testDebugUnitTest --tests 'lv.jolkins.pixelorchestrator.app.ticket.TicketViviPageEnforcerTest'` passed.
- `./gradlew testDebugUnitTest` passed.
- The deploy script also ran Android release/debug unit tests and assemble successfully before installing.

## Live verification

- No active RS jobs were waiting/running before the ticket-screen redeploy.
- Starting the ticket session through the bridge path recovered from the route-home failure to `TICKET_DETAIL`.
- Final bridge health: `sessionState=live`, `streamActive=true`, `streamVerdict=live`, `ticketState=live`, `viviState=TICKET_DETAIL`, `wake.lastSucceeded=true`.
- Public ticket page: direct GUI capture of the live Brave window was not usable in this cron context because macOS/Stage Manager exposed only tiny black Brave thumbnails and Apple Events JavaScript timed out. As the documented fallback, I used a copied authenticated Brave profile with Chrome DevTools Protocol, preserving the live profile/cookies without modifying them. The public page at `https://ticket.jolkins.id.lv/` loaded with title `Biļete`, had a 720×1482 non-black stream canvas, no loading/waiting text, and the captured page visibly showed the ViVi ticket detail with high-level markers: `KONTROLES KODS`, `ZONAS`, monthly ticket layout, and route endpoints. Raw screenshot and copied profile were removed after verification.

## RS QR user-impact status

Broker analytics were reachable (`rs-vivi-qr-analytics.v1`) at 2026-05-21T15:45:50Z. Totals remained `jobs=349`, `succeeded=218`, `failed=125`, `canceled=6`, `retried=91`, `slowSuccess=62`; there were no active jobs.

Known recent affected actors:

- @iamhdzs (`user:3d0625f146d5`): historical failures and retries; latest job in analytics was a succeeded generated result at 2026-05-21T12:54:20Z, slow/retried.
- @aldajo (`user:2f7994586c1c`): historical failures and retries; latest job in analytics was a succeeded generated result at 2026-05-21T11:59:46Z, retried.
- @mt_alinka (`user:5148c2fcad37`): latest analytics row remains failed/generated at 2026-05-21T10:03:34Z; no later real-user retry observed in this run.
- `user:f82debf58b90`: latest analytics row remains failed/generated at 2026-05-21T09:43:39Z; no username mapping found in access state and no later real-user retry observed in this run.

## Remaining risk / open items

The ticket.jolkins route-home recovery incident is closed by code fix, deploy, bridge health, and public-page CDP/browser-profile-copy visual verification.

No new RS QR jobs arrived during this follow-up. Historical failed RS jobs cannot be retro-delivered. Real-user RS incident closure for actors whose last row is still failed requires either a later real-user success or a separate allowed live smoke tied to that failure class.
