# Touch Brightness Architecture

This is the canonical contract for Pixel touch brightness. Read it before changing phone automation, ticket brightness guards, root input monitors, screen wake behavior, or stream capture.

## 1. Ownership Contract

Touch brightness owns the physical panel brightness whenever the Pixel touch brightness toggle is enabled.

- Panel sleep means the Android display remains awake and interactive, while the real backlight panel brightness is written to `0`.
- While `panel_sleep` is active, a one-pixel fully transparent, non-touchable application overlay requests window brightness `0`. This prevents the visible ViVi activity from overriding the system and raw-panel zero between guard writes without changing captured pixels or intercepting input. The runtime grants its overlay app-op through root, removes the shield before every physical visible restore or screen-off suspension, and refuses to restore brightness if shield removal fails. Enabled runtime stops preserve the process-shared shield so a recreated supervisor adopts it without a bright handoff gap; explicit disable removes it before restoring brightness. Raw panel writes remain the authority and fallback if the shield cannot be shown.
- Panel sleep is not Android screen-off, doze, keyguard, or a black accessibility overlay.
- The Android app keeps a screen wake hold active while panel sleep is active so normal app processing, ViVi rendering, ticket capture, and health updates continue.
- Restoring visible brightness after panel sleep must restore both Android display brightness and raw panel brightness. A visible wake or restore is not valid if Android display brightness remains at `0`, even when the raw panel file briefly reports the expected visible value.
- Destroying or replacing the supervision service while touch brightness remains enabled releases runtime jobs without restoring visible brightness or clearing the saved visible restore state. On the next start, an existing saved restore state identifies a restart: the runtime requests panel brightness `0` before readiness preparation and preserves the saved state even when preparation enters error backoff. The early clamp is best-effort so readiness preparation can repair transient root or sysfs failures. Explicitly disabling touch brightness still restores visibility and clears the saved state.
- Visible restore must actively reassert the Android display brightness and raw panel brightness until the post-restore read agrees with the saved or fallback target, then fail explicitly after a bounded retry budget if the panel remains dark or mismatched.
- The public state for this mode is `panel_sleep`. Old stored values such as `blackout_idle` and `dimmed` are compatibility aliases only.

The black accessibility overlay is legacy cleanup surface only. Normal touch brightness sleep must never show it, because stream capture would also see that black overlay.

## 2. Physical Input Sources

Root input monitors are the source of truth:

- `AndroidRootTouchMonitor` watches the selected hardware touchscreen device and emits physical display touch counts.
- `AndroidRootPowerKeyMonitor` watches the physical power-button device, preferring `gpio_keys`/`gpio-keys` over fingerprint fallback devices.
- Remote browser taps, ADB `input tap`, accessibility actions, and other software input do not count as physical display touch and must not reset the timer.
- Root-confirmed physical display touch always wins over software/non-touch suppression. Non-touch suppression only applies to touch-like events that do not have raw physical touchscreen evidence.

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
- A screen-off transition from `panel_sleep` without an actual root power-button event is not treated as physical intent. The runtime forces Android and raw panel brightness to zero before waking the display for continued rendering, reasserts zero afterward, and marks the resulting screen-on as non-touch so it cannot restore visible brightness. Only an observed power-button press or physical touch may enter visible mode.
- Power-button presses outside `panel_sleep` are not hijacked; ordinary Android power behavior remains available.

## 4. Ticket Guard Alignment

When touch brightness is enabled, the ticket brightness guard is parked.

- Ticket code must not write panel brightness, restore ticket brightness, or own a display-bright wake hold while touch brightness owns the panel.
- Ticket code may keep a CPU-only partial wake hold while touch brightness owns the panel; display wake/brightness remains touch-brightness-owned.
- Ticket root input and wake paths must mark software/non-touch input through `PhoneAutomationServiceBridge` before and after the root action so touch brightness can reassert panel sleep immediately. Ticket-prefixed non-touch events are authoritative only when no active physical touch is present: if a misclassified software touch has already pushed runtime into visible idle, the runtime cancels that timer and returns to `panel_sleep` instead of waiting two minutes, but non-touch work must not clear or override an active root-confirmed physical touch. Long bulk root scripts that can switch apps or run delayed taps must continue publishing non-touch intent while active, currently every 250 ms, so the 100/250/500/1000/1500 ms runtime reassertion bursts keep covering the full transition instead of only script start/end.
- Ticket control-code automation taps are not real user taps. While touch brightness owns the panel, control-code open/type/submit/cleanup scripts must hold the raw panel brightness at `0` before, during, and after the operation, and should avoid Android brightness-setting writes on the hot path when direct panel sysfs writes are available. Physical display touch still restores visible brightness through the normal wake flow.
- Once the raw panel already reads `0`, routine panel-sleep reassertions should stay cheap: the quiet guard verifies brightness on a 30-second cadence, skips the write when the panel is still dark, and uses a short sysfs-only hold only when a write is needed. Failed guard writes still retry quickly. Keep the longer hold for the initial transition into panel sleep and for visible restore, where Android or panel rebound is still being actively overcome.
- The ticket service may hide any stale black overlay defensively, but it must not use that overlay as a normal sleep mechanism.
- Remote ticket viewers should continue seeing live rendered ticket content while the physical Pixel panel is at brightness `0`.
- Ticket health reports the guard as inactive/parked with a message explaining that touch brightness owns panel brightness.

When touch brightness is disabled, the ticket brightness guard may use its normal safe dim behavior.

## 5. Implementation Anchors

Primary code paths:

- `TouchBrightnessRuntime.kt`: two-minute timer, panel sleep state, physical touch reset, non-touch reassertion bursts, power-button rebound, and visible brightness restore.
- `PanelSleepBrightnessShieldController.kt`: root-self-healed, one-pixel transparent application overlay that neutralizes activity window-brightness overrides only during `panel_sleep`.
- `RootTouchMonitor.kt`: physical touchscreen discovery and touch-count tracking.
- `RootPowerKeyMonitor.kt`: physical power-button discovery and press events.
- `TouchScreenPowerController.kt`: ordinary wake and forced wake used during rebound; panel-sleep wake holds must use a non-bright/non-wake-causing display hold (`SCREEN_DIM_WAKE_LOCK`), not `SCREEN_BRIGHT_WAKE_LOCK` or `ACQUIRE_CAUSES_WAKEUP`.
- `PhoneAutomationBridge.kt`: software/non-touch input suppression events shared from ticket/automation code into touch brightness.
- `TicketStreamService.kt`: guard parking, non-touch root input/script marking, active long-script reassert heartbeats, rooted panel/display clamp wrappers around wake/app-switch/control-code scripts, hot-path sysfs-only panel clamps when available, and CPU-only ticket wake hold while touch brightness owns the panel.

An enabled touch-brightness runtime in persisted `STOPPED` or `ERROR` state starts immediately during supervision sync, even when Speedtest recovery owns its own lane. Only an explicit destructive touch-brightness `RESTART` requested by prerequisite recovery may remain deferred until the automation recovery lane is free. Ticket brightness fallback, read, and enforcement failures use the `ticket_brightness_` Spacetime trace allowlist.

Stable tests should cover the two-minute timer, physical touch reset, software-input non-reset, panel `0`, visible Android display brightness restore, restore verification that fails while Android display brightness remains `0`, non-touch panel-sleep reassertion, long-script non-touch heartbeats, power-button wake rebound, ordinary screen-off suspension, disable restore, and ticket guard parking.

## Architecture Update Notes

- 2026-07-11: A root-authorized, one-pixel alpha-zero application overlay now holds the WindowManager brightness override at zero while `panel_sleep` is active. It is non-focusable and non-touchable, and it is removed before any physical visible restore or screen-off suspension. Failure to show it leaves the existing raw sysfs clamp and 30-second guard in control; failure to remove it blocks visible restore and stays tracked for retry. Its view reference is process-shared and remains attached across enabled runtime stops, so supervisor recreation adopts it without exposing ViVi's brightness override; explicit disable still removes it.
- 2026-07-11: Service replacement and reboot now retain panel-sleep ownership while the toggle remains enabled: shutdown does not restore or discard saved brightness, restart best-effort clamps the panel to zero before readiness checks, and initial runtime start is not deferred behind Speedtest recovery. Explicit recovery restarts remain serialized. An uncorrelated screen-off while already in `panel_sleep` now stays dark across the forced wake used to keep remote rendering alive; the old fallback that guessed a missed physical power press and restored visible brightness was removed.
- 2026-07-02/03: Active ticket-stream profiling found repeated already-dark panel-sleep reassertions running the same 1.5-second raw panel loop used for first darkening, then still showing short 250 ms writes during passive viewing. Routine already-dark guard ticks now read first and skip the write when the panel is still dark; the passive read cadence is 30 seconds because the root read itself shows up in phone cost. If a write fails, the guard retries at 500 ms. Initial panel sleep and visible restore keep their 1.5-second convergence holds.
- 2026-07-01: The physical-touch wake path now strict-converges visible brightness after `panel_sleep`: saved restores and safe visible fallback writes hold raw panel brightness for 1.5 seconds, re-read both Android display brightness and raw panel brightness, and retry up to four times before reporting a restore failure. This keeps the 2026-06-30 finding as a restore convergence problem, not a raw touchscreen detection problem. Local verification added focused tests for the observed mismatch where Android reports visible brightness while the raw panel stays dark.
- 2026-06-27: Ticket control-code automation now treats popup taps, digit entry, submit, and cleanup as software/non-touch work within this scope. When direct raw panel brightness sysfs is present, the control path uses short sysfs bursts and active busy clamps instead of Android brightness-setting writes on the hot path, then keeps a post-operation panel clamp to prevent delayed rebounds. Verified on the Pixel with three back-to-back direct control-code requests plus delayed tail and idle sampling at 150 ms: zero nonzero panel-brightness samples in `ops/evidence/ticket-remote/20260627T144049Z-post-busy-clamp-final-flicker-watch`.
- 2026-05-25: Visible wake/restore after `panel_sleep` now means Android display brightness and raw panel brightness both come back above zero. A saved brightness value that rounds Android display brightness back to `0` fails verification and falls through to a safe visible fallback. Physical root touchscreen evidence overrides ticket/RS non-touch suppression, and non-touch reassertion cannot clear an active physical touch. The former uncorrelated screen-on fallback was retired on 2026-07-11; visible restore now requires an observed physical power-button or touch event.
- 2026-05-19/20: Ticket/RS software wake and root-input paths now publish non-touch input events to touch brightness before/after root work. While already in `panel_sleep`, touch brightness immediately rewrites panel brightness `0` and runs a short 100/250/500/1000/1500 ms reassertion burst to fight Android brightness ramps caused by wake, app-switch, or root input. Later active research found remaining spikes inside long RS root scripts and touch-brightness wake-hold refreshes, so `TicketStreamService` now wraps bulk `inputRootExecutor.runScript` flows with a rooted clamp that writes sysfs panel `0` every 5 ms via `/system/bin/usleep`, keeps Android display brightness/settings at `0`, and continues for 2.5 s after the script exits. Runtime panel-sleep holds now use `SCREEN_DIM_WAKE_LOCK` without `ACQUIRE_CAUSES_WAKEUP`; when touch brightness owns the panel, ticket uses a CPU-only partial wake hold instead of a display-bright wake lock. Evidence is summarized in [Active Brightness Research - 2026-05-19](../../ops/reports/active-brightness-research-20260519.md) and [Touch Brightness Ticket/RS Enforcement - 2026-05-19](../../ops/reports/touch-brightness-ticket-rs-enforcement-20260519.md).
- 2026-05-07: Touch brightness panel sleep became the owner of zero-panel-brightness behavior. Sleep now means panel brightness `0` while Android remains awake, with a two-minute physical-touch timer, root power-button wake rebound, and ticket brightness guard parking whenever touch brightness is enabled. Live Pixel and authenticated Brave evidence is summarized in [Touch Brightness Panel Sleep Verification - 2026-05-07](../../ops/reports/touch-brightness-panel-sleep-20260507.md).
