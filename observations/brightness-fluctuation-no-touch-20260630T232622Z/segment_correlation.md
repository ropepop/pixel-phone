# No-Touch Brightness Fluctuation Analysis - 2026-07-01

- Observation directory: `observations/brightness-fluctuation-no-touch-20260630T232622Z`
- Capture window UTC: `2026-06-30T23:26:22.653771Z` to `2026-07-01T00:06:31.539258Z`
- Samples: `1752` brightness rows, median interval `1143` ms
- User condition recorded by observer: no physical touch; observer injected no touch, tap, brightness, or app-control commands

## Brightness Result

- Nonzero raw/display brightness samples: `0`
- Nonzero segments: `0`
- Max raw panel brightness: `0 / 3939`
- Max Android display percentage: `0.0`
- Max screen brightness setting: `0`

## Physical Input

- Raw touchscreen log lines: `0`
- Raw power-button log lines: `0`
- Action snapshots all reported no display touch: `True`

## Background Actions Seen

- ChatGPT foreground launch attempts: `8`
- Ticket start-server syncs: `25`
- Ticket service ready checks: `23`
- Touch brightness panel-sleep guards: `384`
- Touch brightness start/reassert events: `6`
- Touch count changes: `0`
- Wake-promoted-to-active-touch events: `0`
- Brightness restore/verification failures: `0`
- Touch brightness session failures: `0`

## ChatGPT Launch Timeline

- `2026-06-30T23:39:57.097000Z` - `1782862797.097  1384  1569 I ActivityTaskManager: START u0 {act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10200000 cmp=com.openai.chatgpt/.MainActivity} with LAUNCH_SINGLE_TOP from uid 0 (BAL_ALLOW_ALLOWLISTED_UID) result code=3`
- `2026-06-30T23:40:35.065000Z` - `1782862835.065  1384  2886 I ActivityTaskManager: START u0 {act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10200000 cmp=com.openai.chatgpt/.MainActivity} with LAUNCH_SINGLE_TOP from uid 0 (BAL_ALLOW_ALLOWLISTED_UID) result code=3`
- `2026-06-30T23:41:35.687000Z` - `1782862895.687  1384  2771 I ActivityTaskManager: START u0 {act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10200000 cmp=com.openai.chatgpt/.MainActivity} with LAUNCH_SINGLE_TOP from uid 0 (BAL_ALLOW_ALLOWLISTED_UID) result code=3`
- `2026-06-30T23:42:35.737000Z` - `1782862955.737  1384  2910 I ActivityTaskManager: START u0 {act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10200000 cmp=com.openai.chatgpt/.MainActivity} with LAUNCH_SINGLE_TOP from uid 0 (BAL_ALLOW_ALLOWLISTED_UID) result code=3`
- `2026-06-30T23:43:37.482000Z` - `1782863017.482  1384  2576 I ActivityTaskManager: START u0 {act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10200000 cmp=com.openai.chatgpt/.MainActivity} with LAUNCH_SINGLE_TOP from uid 0 (BAL_ALLOW_ALLOWLISTED_UID) result code=3`
- `2026-06-30T23:53:25.301000Z` - `1782863605.301  1384  6111 I ActivityTaskManager: START u0 {act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10200000 cmp=com.openai.chatgpt/.MainActivity} with LAUNCH_SINGLE_TOP from uid 0 (BAL_ALLOW_ALLOWLISTED_UID) result code=3`
- `2026-06-30T23:57:23.646000Z` - `1782863843.646  1384  4124 I ActivityTaskManager: START u0 {act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10200000 cmp=com.openai.chatgpt/.MainActivity} with LAUNCH_SINGLE_TOP from uid 0 (BAL_ALLOW_ALLOWLISTED_UID) result code=3`
- `2026-07-01T00:01:07.141000Z` - `1782864067.141  1384  6111 I ActivityTaskManager: START u0 {act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10200000 cmp=com.openai.chatgpt/.MainActivity} with LAUNCH_SINGLE_TOP from uid 0 (BAL_ALLOW_ALLOWLISTED_UID) result code=3`

## Nearby Brightness Around Actions

Every sampled raw panel/display brightness row stayed at zero, including around the ChatGPT launch and ticket-service cycles above. Therefore there are no nonzero brightness segments to correlate in this run.

## Final Snapshot

```text
panel_path=/sys/class/backlight/panel0-backlight
panel_brightness=0
panel_actual_brightness=0
panel_max_brightness=3939
screen_brightness_mode=0
screen_brightness=0
display_percentage=0.0
power_summary=  mWakefulness=Awake;  mWakeLockSummary=0x25;  mHoldingDisplaySuspendBlocker=true;Display Power: com.android.server.power.PowerManagerService$1@56f892e;mWakeLockSummary=0x25;mWakefulness=1;
```
