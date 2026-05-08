# Touch Brightness Panel Sleep Verification - 2026-05-07

## Scope

Implemented and verified touch brightness panel sleep on the Pixel. Panel sleep means the physical panel brightness is written to `0`, while Android stays awake and continues normal app, ticket, and capture work.

## Implementation Summary

- Touch brightness now enters `panel_sleep` after two minutes without physical touchscreen activity.
- Physical touch wakes the panel and restarts the two-minute timer.
- Remote/software input does not reset the physical-touch timer.
- A physical power-button event during `panel_sleep` restores visible brightness and rebounds Android awake if needed.
- The ticket brightness guard parks whenever touch brightness is enabled.
- The legacy black overlay is retained only for defensive cleanup and is not used for normal panel sleep.

## Verification

Local checks:

- Full Android app unit test suite passed with `./gradlew :app:testDebugUnitTest`.
- Debug APK assembled and installed on the Pixel.
- Root grant, battery whitelist, touch-brightness setting, ticket service readiness, and tunnel readiness were restored after reinstall.

Pixel live checks:

- After the two-minute timer expired, the Pixel reported panel brightness `0`, actual panel brightness `0`, Android wakefulness `Awake`, and active wake/display holds.
- ADB/software tap while in `panel_sleep` did not wake the panel or reset the timer.
- A root event on the selected `gpio_keys` power-button path woke the panel from `panel_sleep` and restored visible brightness.
- The ticket brightness guard reported parked/inactive while touch brightness owned the panel.

Public Brave check:

- The existing Brave profile was used and re-authenticated through the latest ticket magic-link email.
- The authenticated public page at `ticket.jolkins.id.lv` rendered the live ticket while the Pixel panel stayed at brightness `0`.
- Pixel health at the same time reported `streamActive=true`, two connected ticket clients, live H.264 frames being sent, ViVi on `TICKET_DETAIL`, Android awake, and physical panel brightness `0`.

## Evidence

Generated files are under:

- `ops/evidence/touch-brightness-panel-sleep-20260507/`

Key proof files:

- `brave-ticket-after-allow-click-w80386.png`: authenticated Brave showing the live ticket.
- `pixel-panel-state-live-ticket.txt`: panel `0`, actual `0`, Android awake, wake/display holds active during live public viewing.
- `pixel-health-live-ticket.json`: live stream, connected clients, parked ticket brightness guard, ViVi ticket detail.
- `pixel-health-after-close-ticket-tabs.json`: verification tab closed and ticket stream parked down.

## Final State

The ticket service remained ready after verification. The temporary public verification tab was closed so the stream could park when no viewer was connected.
