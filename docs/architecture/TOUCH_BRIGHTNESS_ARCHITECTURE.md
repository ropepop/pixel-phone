# Touch Brightness Architecture

This is the canonical contract for Pixel touch brightness. Read it before changing phone automation, ticket brightness guards, root input monitors, screen wake behavior, or stream capture.

## 1. Ownership Contract

Touch brightness owns the physical panel brightness whenever the Pixel touch brightness toggle is enabled.

- Panel sleep means the Android display remains awake and interactive, while the real backlight panel brightness is written to `0`.
- Panel sleep is not Android screen-off, doze, keyguard, or a black accessibility overlay.
- The Android app keeps a screen wake hold active while panel sleep is active so normal app processing, ViVi rendering, ticket capture, and health updates continue.
- The public state for this mode is `panel_sleep`. Old stored values such as `blackout_idle` and `dimmed` are compatibility aliases only.

The black accessibility overlay is legacy cleanup surface only. Normal touch brightness sleep must never show it, because stream capture would also see that black overlay.

## 2. Physical Input Sources

Root input monitors are the source of truth:

- `AndroidRootTouchMonitor` watches the selected hardware touchscreen device and emits physical display touch counts.
- `AndroidRootPowerKeyMonitor` watches the physical power-button device, preferring `gpio_keys`/`gpio-keys` over fingerprint fallback devices.
- Remote browser taps, ADB `input tap`, accessibility actions, and other software input do not count as physical display touch and must not reset the timer.

The panel sleep timer is exactly two minutes. It starts when touch brightness enters visible idle, and it restarts when the last physical display touch is lifted or when the power button wakes the panel from panel sleep.

## 3. State Flow

Normal flow:

1. Touch brightness starts while Android is interactive.
2. The panel stays visible at the saved brightness and starts the two-minute physical-touch timer.
3. A physical touch before the deadline keeps or restores visible brightness.
4. When the last physical touch is lifted, the two-minute timer starts again.
5. If the timer expires with no physical touch, the app writes physical panel brightness `0`, keeps Android awake, and publishes `panel_sleep`.

Wake flow:

- Physical display touch while in `panel_sleep` restores saved visible brightness immediately and keeps the screen awake.
- Physical power-button press while in `panel_sleep` restores saved visible brightness and starts a short wake rebound window.
- If Android briefly processes that power-button press as screen-off, the rebound path sends `KEYCODE_WAKEUP`, restores visible brightness again, and continues the two-minute timer.
- Power-button presses outside `panel_sleep` are not hijacked; ordinary Android power behavior remains available.

## 4. Ticket Guard Alignment

When touch brightness is enabled, the ticket brightness guard is parked.

- Ticket code must not write panel brightness, restore ticket brightness, or own a separate ticket wake hold while touch brightness owns the panel.
- The ticket service may hide any stale black overlay defensively, but it must not use that overlay as a normal sleep mechanism.
- Remote ticket viewers should continue seeing live rendered ticket content while the physical Pixel panel is at brightness `0`.
- Ticket health reports the guard as inactive/parked with a message explaining that touch brightness owns panel brightness.

When touch brightness is disabled, the ticket brightness guard may use its normal safe dim behavior.

## 5. Implementation Anchors

Primary code paths:

- `TouchBrightnessRuntime.kt`: two-minute timer, panel sleep state, physical touch reset, power-button rebound, and visible brightness restore.
- `RootTouchMonitor.kt`: physical touchscreen discovery and touch-count tracking.
- `RootPowerKeyMonitor.kt`: physical power-button discovery and press events.
- `TouchScreenPowerController.kt`: ordinary wake and forced wake used during rebound.
- `TicketStreamService.kt`: guard parking while touch brightness owns the panel.

Stable tests should cover the two-minute timer, physical touch reset, software-input non-reset, panel `0`, power-button wake rebound, ordinary screen-off suspension, disable restore, and ticket guard parking.

## Architecture Update Notes

- 2026-05-07: Touch brightness panel sleep became the owner of zero-panel-brightness behavior. Sleep now means panel brightness `0` while Android remains awake, with a two-minute physical-touch timer, root power-button wake rebound, and ticket brightness guard parking whenever touch brightness is enabled. Live Pixel and authenticated Brave evidence is summarized in [Touch Brightness Panel Sleep Verification - 2026-05-07](../../ops/reports/touch-brightness-panel-sleep-20260507.md).
