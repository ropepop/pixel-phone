# RS/ViVi steward report — watchdog fast-empty health sampling

Generated: 2026-05-21T23:32Z

## Summary

The latest watchdog alert combined real historical RS QR incidents with a ticket-bridge health warning. The ticket bridge warning was reproduced as a transient `viviState=UNKNOWN_VIVI` / `viviSource=fast_empty` foreground-guard sample while the hardware stream and ticket session were live. A bounded follow-up health wait confirmed `viviState=TICKET_DETAIL` from `source=root`, so the immediate bridge recovery issue is closed as a watchdog sampling false positive rather than a phone-left-ticket-detail failure.

## Changes

- Updated `~/.hermes/scripts/rs_vivi_watchdog.py` to include `viviSource` in compact bridge health.
- Added bounded settle polling before a live ticket stream with `UNKNOWN_VIVI`/`fast_empty` starts safe recovery or emits a bridge-unhealthy alert.
- Added regression tests for transient fast-empty settling and for not delaying real recovery on `root_empty` or non-live states.
- Updated the RS/ViVi incident analytics architecture doc and the Pixel ticket automation skill reference.

## Verification

- `python3 -m py_compile ~/.hermes/scripts/rs_vivi_watchdog.py ~/.hermes/scripts/test_rs_vivi_watchdog.py` passed.
- `python3 -m unittest ~/.hermes/scripts/test_rs_vivi_watchdog.py -v` passed 6 tests.
- Live bridge settle check at 2026-05-21T23:30:21Z:
  - initial: `sessionState=live`, `streamActive=true`, `streamVerdict=live`, `ticketState=live`, `viviState=UNKNOWN_VIVI`, `viviSource=fast_empty`;
  - final after bounded wait: `sessionState=live`, `streamActive=true`, `streamVerdict=live`, `ticketState=live`, `viviState=TICKET_DETAIL`, `viviSource=root`;
  - the patched watchdog would not start recovery after this settle.
- Broker state at 2026-05-21T23:32:10Z: `queueDepth=0`, `runningQRJob=false`, `ticketSockets=2`, `ticketViewers=1`.
- Recent Android log scan found no new `UiAutomationService already registered` entries and no remaining `uiautomator` processes.

## Incident status

- Recovered with latency risk: `@aldajo`, `@iamhdzs`, and `user:b4bfa48b2f72` had later real successful RS QR jobs after failures.
- Still open because no later real-user success was found in the inspected window: `@mt_alinka`/`user:5148c2fcad37`, `user:f82debf58b90`, `user:412aee9a400f`, `user:635ba440feef`, and `user:dc893276f5fe`.

No Telegram IDs, chat IDs, QR/control codes, tokens, cookies, or credentials were recorded.
