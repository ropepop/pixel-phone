# Pixel media resource lifetime optimization — 8 September 2026

## Scope and acceptance

Phone capture, preparation and encoding only. Preserve the 1 FPS cadence, 8 Mbps target,
994×2046 visible output, source crop, colour correction, SDR signalling and existing HDR appearance.
No server/browser implementation, database or wire-format changes.

Capture results now have one asynchronous ownership boundary, including late callback and wrapping
failure cleanup. Copied codec output is released before assembly or delivery waits. Independent
teardown attempts preserve pinned pictures until codec release succeeds; uncertain native release
ends the helper so the existing process owner can clean it up before replacement. Complete frame
writes own flushing; redundant flushes are removed. Startup priming is retained.

## Baseline

Local source baseline: `567bec2693cbe1c576bf60a15849c500c790379a`.
Installed rollback APK SHA-256: `43ab153b0f9c87f7d3f6249d77cf080840c6e816ff89b12ac5d33a4dcfcacb89`.
Rollback artifact: `output/pixel/media-optimization-20260908/previous.apk`.
Live service identity: `ticket-remote-2026-09-08-warm-reuse-cold-restart-v182`,
assets `ticket-presence-20260908-r1`. Phone media identity remains
`ticket-stream-2026-09-06-parallel-picture-readers-v372`; actual encoder is
`c2.exynos.h264.encoder`, Baseline Level 4, CBR, fixed 1 FPS and 8,000,000 bits/s.
Source checkout identity is recorded separately from the installed APK hash. A bounded scan of
the saved APK's DEX provenance strings also found the exact baseline source commit above.

Signed-in Brave Work profile; HDR enabled at 4×. Ten cold openings and ten warm page reconnects
all reached ten presented pictures, with zero opening reconnects. Cold mode was requested through
the owner UI, and completion was observed before the next run. Times below are navigation to first
presentation, not the entire owner cold-stop operation.

- Cold first-picture milliseconds: 2686, 2499, 2927, 2678, 2605, 2675, 2682, 2301, 2736, 2386.
- Warm first-picture milliseconds: 293, 301, 301, 426, 308, 314, 315, 320, 308, 322.
- Cold ten-picture milliseconds: 11344, 11482, 11377, 11153, 11539, 11333, 11165, 11269, 11669, 11320.
- Warm ten-picture milliseconds: 8376, 8901, 8943, 8566, 8925, 8631, 8415, 8828, 8983, 8878.

A 325.5-second active observation retained 225 numeric TSF3 frame samples. The browser debugger
event buffer truncated older events twice while local work ran; these are sampled timings and
picture sizes, not a complete wire-bandwidth measurement. No raw pictures or private values were
retained in the report.

| Stage | Median | p95 | Maximum |
|---|---:|---:|---:|
| Capture | 25.400 ms | 30.394 ms | 36.672 ms |
| Capture complete to codec input, including handoff/drawing | 50.012 ms | 140.884 ms | 157.758 ms |
| Codec input to complete encoded picture | 502.213 ms | 563.012 ms | 584.511 ms |
| Complete picture to pipe emission | 101.695 ms | 298.190 ms | 384.738 ms |
| Encoded picture payload | 138,727 bytes | 139,528 bytes | 139,618 bytes |

Baseline spot observations: app PSS 79,844 KiB / RSS 181,084 KiB; actual encoder helper PSS
197,611 KiB / RSS 325,992 KiB. These are observations at different moments, not a leak slope.

## Local validation

Focused asynchronous ownership, copied-output and teardown tests pass, including 200 timeout/
success races. Regression tests cover shared-picture retention, newest-frame replacement,
partial output surviving codec-buffer reuse and delayed primer siblings.
The full Android test run and debug/release/test-APK builds pass with Java 17. Java 25 caused
the Android release lint tool to fail before rerunning successfully with the installed Java 17.
The native crop instrumented fixture now uses the actual production output dimensions.

## Deployment and live acceptance

Deployed source: `1c34afd84dcb7cb1c5745a537d58b37e369364ca`, clean build.
Release: `20260908T160555Z-19185`; installed APK SHA-256:
`3f9e4641c8e91ac3ec8ad924c9e3c9fcc90646ec3ac65649e934ab31e93bc7a7`, verified equal to the
candidate artifact. The scoped standard ADB deployment and five bundled asset freshness checks
passed. Server and browser were not deployed or modified.

1,059 local test executions passed (478 app debug, 478 app release, 103 supporting-module tests).
Debug/release APK builds passed. The actual native crop test passed on Pixel at production
dimensions. Its temporary test package was uninstalled, and the existing scoped restart owner
restored normal operation. An explicit no-viewer cold stop before that test proved capture off,
secure capture released, zero encoders and zero stale capture processes.

Ten candidate cold openings and ten warm reconnects all reached ten pictures with zero opening
reconnects. Candidate cold first-picture milliseconds:
2911, 2249, 2117, 2428, 2714, 2677, 2211, 2169, 2668, 2597.
Candidate warm first-picture milliseconds:
471, 307, 296, 320, 312, 310, 309, 311, 305, 300.
Cold median changed from 2676.5 to 2512.5 ms; warm median from 311 to 309.5 ms.
Cold maximum changed from 2927 to 2911 ms; warm maximum from 426 to 471 ms.
These small sequential samples do not establish a material end-to-end speed gain or a controlled
physical-display benchmark. Browser viewport height differed between the initial and replacement
task-owned tabs; decoded SDR and HDR canvases both remained exactly 994×2046.

The candidate steady sample covered 321.1 seconds and retained 308 numeric frame samples.
The debugger event feed had a gap during setup and one truncation during sampling; neither sample
is a complete traffic accounting. Raw content was not retained. Debug network observation was
disabled after the measurement.

| Candidate stage | Median | p95 | Maximum |
|---|---:|---:|---:|
| Capture | 25.767 ms | 34.190 ms | 40.418 ms |
| Capture complete to codec input, including handoff/drawing | 66.430 ms | 138.901 ms | 144.798 ms |
| Codec input to complete encoded picture | 495.181 ms | 562.570 ms | 581.045 ms |
| Complete picture to pipe emission | 108.784 ms | 272.243 ms | 323.456 ms |
| Encoded picture payload | 138,575 bytes | 139,413 bytes | 139,812 bytes |

Encoding tail latency and picture sizes remained effectively comparable; output-wait p95 was
26 ms lower in this sample, while capture-to-input median was higher. There is no demonstrated
material bandwidth increase and no claim of a significant speedup or RAM saving.

Five registrations passed: two `register_current` (one through an actual browser slider drag)
and three `open_latest_and_register`. Each saved outcome was `succeeded`, `activation_proven`,
`activated_current`. One preparatory `open_latest_unactivated` also passed. Durable registration
durations were 7.49, 13.81, 7.62, 15.22 and 15.31 seconds. No ambiguous physical action was replayed.

Five control-code journeys reached `exact_result_presented` with HDR active and a visible result.
Each was dismissed through the UI and returned to live viewing. Each saved result was checked
before retention expiry: `succeeded`, `phone_visual_cleanup_complete`, capture acknowledged,
cleanup not pending, and exact equality of generated/captured epoch and sequence. Values and
private images are omitted. A disabled-button wait after the third code caused no admission;
the next registration began only after the control became enabled.

## Thirty-minute observation and final cleanup

The active observation completed in 1,801.8 seconds with 58 bounded health/memory samples,
including a sample at exactly 1,800 seconds. All samples showed active, ready capture, the fixed
media profile, one encoder process, zero stale capture processes, zero restarts, zero blank-frame
failures and zero unexpected delta frames. The same helper PID served the whole observation.
The frame count advanced from 271 to 2,066: 1,795 pictures over the thirty-minute interval,
consistent with the unchanged one-picture-per-second cadence without catch-up bursts.

| Memory observation | Minimum | Maximum | First 10 samples mean | Last 10 samples mean |
|---|---:|---:|---:|---:|
| Encoder helper PSS | 164,603 KiB | 207,862 KiB | 180,951 KiB | 181,201 KiB |
| Encoder helper RSS | 295,304 KiB | 338,476 KiB | 310,180 KiB | 311,956 KiB |
| Main app PSS | 70,465 KiB | 83,477 KiB | 76,439 KiB | 80,716 KiB |
| Main app RSS | 169,768 KiB | 185,572 KiB | 176,949 KiB | 182,856 KiB |

Helper memory oscillated rather than accumulating; the first/last group means were effectively
level. Main-app memory rose during warm-up and then stayed within its observed range. This run
found no sustained accumulation, but the baseline spot readings do not support a RAM-saving claim.

After closing the task-owned viewer, explicit cold mode reported asleep. Independent health
confirmed capture inactive, secure capture released, zero encoders and zero stale processes.
A fresh signed-in opening was classified cold: first picture 2,729 ms, ten pictures 11,621 ms,
zero reconnects, live/fresh/ready state, and idle control-code state. Both decoded canvases
remained 994×2046 with the HDR surface visible. Visual inspection found no changed framing,
colour or sharpness; screenshots are a visual check, not a calibrated HDR/display measurement.
No physical panel-brightness claim is made.

The reopened viewer was closed and cold mode requested again. Final health and the owner UI
confirmed the same fully stopped state. Only task-owned viewer/admin tabs were closed; the
existing user tabs and signed-in profile were preserved. The temporary test package and browser
debug network observation were removed. The report, architecture documentation and rollback APK
are owned by the regular workspace user and readable without elevated access.

The candidate is retained: resource-lifetime tests and live journeys passed, latency remained
within the observed baseline variation, and sampled encoded sizes showed no material increase.
The result is a cleanup/recovery improvement, not a demonstrated material speed or memory saving.
