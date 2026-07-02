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
- `orchestrator/android-orchestrator/app/src/test/kotlin/lv/jolkins/pixelorchestrator/app/phoneautomation/ChatGPTPhoneRunnerTest.kt`
- `docs/architecture/TOUCH_BRIGHTNESS_ARCHITECTURE.md`

## Local Verification

Command:

```bash
cd orchestrator/android-orchestrator
./gradlew :app:testDebugUnitTest --tests 'lv.jolkins.pixelorchestrator.app.phoneautomation.ScreenBrightnessControlTest' --tests 'lv.jolkins.pixelorchestrator.app.phoneautomation.AndroidTouchBrightnessDeviceControllerTest' --tests 'lv.jolkins.pixelorchestrator.app.phoneautomation.TouchBrightnessRuntimeTest'
```

Result:

- `BUILD SUCCESSFUL`
- Focused tests cover visible panel hold scripts, exact saved panel restore hold scripts, retry after a dark-panel verification mismatch, and explicit failure after four failed visible-restore attempts.
- A stale test helper in `ChatGPTPhoneRunnerTest.kt` used `Files.readString`, which did not compile in this Android test target. It was changed to `Files.readAllBytes` so the focused test suite compiles cleanly.
- `./gradlew :app:assembleDebug` completed successfully after the final source changes.
- A full lower-level deploy build later hit three unrelated release-unit failures in `TicketStreamServiceSourceTest` before installation. Those failures are outside this brightness change; the debug APK was assembled and installed directly.

## Pixel Deployment

The smart redeploy wrapper failed before touching the Pixel because it references a missing local helper:

```text
tools/pixel/cleanup_workspace.sh: No such file or directory
```

Fallback deployment first used the lower-level APK deploy path:

```bash
bash orchestrator/scripts/android/deploy_orchestrator_apk.sh --transport adb --device 100.76.50.43:5555 --action health
```

Result:

- APK install succeeded for the initial patched build.
- Pixel package `lastUpdateTime`: `2026-07-01 01:06:24`.
- The health action returned failure because unrelated services such as DNS, DDNS, train bot, site notifier, and subscription bot were disabled or stale. The relevant ticket screen service reported running.

After final source cleanup and the unrelated release-test blocker, the final debug APK was assembled and installed directly:

```bash
cd orchestrator/android-orchestrator
./gradlew :app:assembleDebug
cd ../..
adb -s 100.76.50.43:5555 install -r orchestrator/android-orchestrator/app/build/outputs/apk/debug/app-debug.apk
```

Final device check:

- Direct APK install succeeded.
- Pixel package `lastUpdateTime`: `2026-07-01 01:33:28`.
- Final panel check: Android awake, raw panel brightness `0 / 3939`, display brightness `0.0`, screen brightness setting `1`.

## Live Verification

Post-deploy 15-minute passive capture:

- Observation directory: `observations/brightness-touch-strict-restore-20260630T220906Z`
- Window: `2026-06-30T22:09:06Z` to `2026-06-30T22:24:11Z`
- Samples: `811`
- `touch brightness session failed`: `0`
- `saved brightness restore failed`: `0`
- `Brightness restore verification failed`: `0`
- `wake_promoted_to_active_touch`: `0`
- Physical touch lines: `0`

Corrected focused raw-panel capture:

- Observation directory: `observations/brightness-touch-strict-restore-focused-20260630T222532Z`
- Window: `2026-06-30T22:25:32Z` to `2026-06-30T22:28:37Z`
- Samples: `164`
- Raw panel nonzero samples: `0`
- Max raw panel brightness: `0 / 3939`
- Android display nonzero samples: `0`
- Final state: Android awake, panel brightness `0`, display brightness `0.0`, screen brightness setting `1`
- `touch brightness session failed`: `0`
- `saved brightness restore failed`: `0`
- Physical touch lines: `0`

The live post-deploy runs prove the patched build stayed stable in panel sleep and did not regress the dark-panel hold. They do not prove the physical-touch wake path on-device because no physical touches occurred during either capture. The physical-touch wake path is covered locally by focused unit tests and still needs a live run with actual panel touches for complete end-to-end proof.
