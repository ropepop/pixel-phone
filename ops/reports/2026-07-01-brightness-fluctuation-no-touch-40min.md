# Brightness Fluctuation No-Touch 40-Minute Capture - 2026-07-01

## Purpose

Run a passive 40-minute capture while the user was not touching the Pixel, to identify whether background actions can lift the screen brightness from dark panel sleep.

The observer did not inject touches, taps, app-control commands, brightness writes, or power commands. It only read device state, streamed logs, and captured raw input events.

## Evidence

- Observation directory: `observations/brightness-fluctuation-no-touch-20260630T232622Z`
- Detailed analysis: `observations/brightness-fluctuation-no-touch-20260630T232622Z/analysis_summary.json`
- Event correlation: `observations/brightness-fluctuation-no-touch-20260630T232622Z/segment_correlation.md`
- Capture window: `2026-06-30T23:26:22Z` to `2026-07-01T00:06:31Z`
- Duration: `2,405s`
- Brightness samples: `1,752`
- Median sample interval: `1,143ms`
- Action snapshots: `434`
- Log lines: `555`

Captured files include:

- `samples_panel.tsv`: raw panel brightness, Android screen brightness setting, and display brightness percentage.
- `samples_context.jsonl`: per-sample power/display summary.
- `samples_actions.jsonl`: foreground app, resumed activity, display state, and input state every five seconds.
- `logcat_relevant.txt`: touch brightness, supervisor, ticket, power/display, activity, and input logs.
- `getevent_touch.log`: raw touchscreen events from `/dev/input/event2`.
- `getevent_gpio.log`: raw power-button events from `/dev/input/event0`.

## Result

No brightness fluctuation was reproduced during this 40-minute no-touch window.

- Nonzero raw panel brightness samples: `0`
- Nonzero Android display brightness samples: `0`
- Max raw panel brightness: `0 / 3939`
- Max Android display brightness: `0.0`
- Max screen brightness setting: `0`
- Raw touchscreen event lines: `0`
- Raw power-button event lines: `0`
- Touch-brightness session failures: `0`
- Brightness restore or verification failures: `0`
- Wake-promoted-to-active-touch events: `0`

Final device state:

```text
panel_brightness=0
panel_actual_brightness=0
panel_max_brightness=3939
screen_brightness_mode=0
screen_brightness=0
display_percentage=0.0
mWakefulness=Awake
mHoldingDisplaySuspendBlocker=true
```

## Background Actions Observed

The phone was not idle internally. Several actions continued while the panel stayed dark:

- ChatGPT foreground launch attempts: `8`
- Ticket start-server syncs: `25`
- Ticket service ready checks: `23`
- Phone automation refresh markers: `20`
- Touch brightness panel-sleep guards: `384`
- Panel-sleep reassertions: `6`

All `samples_actions.jsonl` input snapshots reported no display touch.

The foreground stayed on ChatGPT for all action snapshots:

- `com.openai.chatgpt/.MainActivity`: `434 / 434`

ChatGPT launch attempts were logged at:

- `2026-06-30T23:39:57.097Z`
- `2026-06-30T23:40:35.065Z`
- `2026-06-30T23:41:35.687Z`
- `2026-06-30T23:42:35.737Z`
- `2026-06-30T23:43:37.482Z`
- `2026-06-30T23:53:25.301Z`
- `2026-06-30T23:57:23.646Z`
- `2026-07-01T00:01:07.141Z`

Every sampled raw panel/display brightness row stayed at zero around these events.

## Interpretation

This run confirms that background activity is happening while the phone is untouched, especially ChatGPT foreground launch attempts and ticket-service readiness/start-server cycles.

However, those actions did not lift brightness in this build during the 40-minute window. Touch brightness kept reasserting panel sleep, raw touch stayed empty, power-button input stayed empty, and the raw panel remained at zero throughout.

So for this run, the best conclusion is:

- The observed background actions are real and should remain on the suspect list.
- The currently installed strict-restore/panel-sleep build resisted those actions for 40 minutes.
- The brightness-up-from-dark issue was not reproduced in this no-touch capture.

The next useful capture should intentionally wait for or trigger the specific suspected background flow that previously caused the visible bump, while keeping raw touch/power capture active.
