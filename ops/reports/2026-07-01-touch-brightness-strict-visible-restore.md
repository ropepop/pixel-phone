# Touch Brightness Strict Visible Restore - 2026-07-01

## Purpose

Fix the 2026-06-30 physical-touch investigation finding where raw touchscreen events were detected, but the panel returned to dark sleep because visible brightness restore did not converge.

## Source Finding

The prior 15-minute capture showed repeated cycles where:

1. The panel was in `panel_sleep`.
2. Raw physical touch was detected.
3. Touch brightness promoted wake to active touch.
4. Visible brightness restore or fallback failed verification.
5. The touch brightness session restarted and reasserted `panel_sleep`.

This pointed to brightness restore convergence, not touchscreen hardware detection.

Source report: `ops/reports/2026-06-30-touch-brightness-physical-touch-sleep-investigation.md`

## Change

Visible brightness operations now keep asserting the raw panel brightness while also restoring Android display brightness:

- Safe visible fallback writes hold the raw panel target for 1.5 seconds.
- Saved brightness restore writes hold the exact captured raw panel value for 1.5 seconds when panel data exists.
- After each write, the controller re-reads Android display brightness and raw panel brightness.
- If either side is still dark or mismatched, the controller retries up to four times.
- If the retry budget is exhausted, the failure remains explicit instead of silently accepting an inconsistent brightness state.

The strict success rule did not change: visible restore still requires Android display brightness and raw panel brightness to agree with the intended visible target.

## Files Updated

- `orchestrator/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/ScreenBrightnessControl.kt`
- `orchestrator/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/TouchBrightnessRuntime.kt`
- `orchestrator/android-orchestrator/app/src/test/kotlin/lv/jolkins/pixelorchestrator/app/phoneautomation/ScreenBrightnessControlTest.kt`
- `orchestrator/android-orchestrator/app/src/test/kotlin/lv/jolkins/pixelorchestrator/app/phoneautomation/AndroidTouchBrightnessDeviceControllerTest.kt`
- `docs/architecture/TOUCH_BRIGHTNESS_ARCHITECTURE.md`

## Local Verification

Command:

```bash
cd orchestrator/android-orchestrator
./gradlew :app:testDebugUnitTest --tests 'lv.jolkins.pixelorchestrator.app.phoneautomation.ScreenBrightnessControlTest' --tests 'lv.jolkins.pixelorchestrator.app.phoneautomation.AndroidTouchBrightnessDeviceControllerTest'
```

Result:

- `BUILD SUCCESSFUL`
- Focused tests cover visible panel hold scripts, exact saved panel restore hold scripts, retry after a dark-panel verification mismatch, and explicit failure after four failed visible-restore attempts.

## Remaining Live Verification

The next live proof should redeploy the Android orchestrator to the Pixel and repeat the same 15-minute physical-touch capture from `2026-06-30`, requiring zero touch-brightness session failures while raw physical touches are present.
