# Pixel runtime health Rust acceptance — 2026-09-29

## Scope and provenance

The deployed health replacement is commit `5c37748`. The installed product APK
SHA-256 is `dc76f332c4b0e18348e6d903ff33275f59ee0f7183686ff689b9f1dbd4997923`.
The corrected device acceptance test is commit `d953ff9`, test APK SHA-256
`9aefe4dfae8c0fa94ddfe2333aaf29bbf1a81bf671150944a7aa042bb697eee3`.
The former Kotlin owner is retained in Git at `2dc9085` for differential checks
and rollback; it is not a second production implementation.

Rust now builds the existing single atomic root probe and interprets its output.
Kotlin retains root execution, public health data types, and callers. Public
fields, module requirements, evidence, timeouts, configuration and secrets
redaction remain unchanged. This slice changes no phone gestures, capture,
registration or recovery policy.

## Verification

| Scenario | Expected | Observed |
| --- | --- | --- |
| Existing health tests through host JNI | Preserve health and management report contracts | All 45 passed. |
| Existing supervisor tests | Preserve health consumer behavior | All 29 passed. |
| Old Kotlin/Rust differential fixture | Equal probe command bytes and complete snapshots across configuration, marker, malformed-input and required-section cases | Passed. |
| Captured installed-device probe at a fixed timestamp | Installed snapshot, host Rust and old Kotlin agree | All 5 parity tests passed, including the actual-device sample; the 45 existing health tests also passed in that run. |
| Installed native library executing the actual root probe | One probe; intact config; valid required markers; decisions match that captured probe | Corrected instrumentation passed in 6.882 s; probe took 6,700 ms. |
| Normal service health after deployment | Required running-service scope passes | Passed at approximately 09:34:07 UTC. |
| Normal service health after instrumentation and supervision restore | Running service and supervisor loop healthy | Existing null-action service path requested supervisor resume at 09:40:02.668 UTC; final normal health action succeeded at approximately 09:40:38 UTC. Action wait was 7,144 ms; full command took 17.56 s. |
| Test cleanup | No installed test package | Test APK removed. |
| Final quiet state | No capture or keyboard helper remains after the service lifecycle | Root process check at 09:47 UTC found neither `TicketH264CaptureMain` nor `ticket-root-keyboard`. |
| Persisted UI source | AppGraph-owned saved health agrees with normal-service success | At 09:50:12 UTC (epoch 1790675412), the existing app-private state reported root access, deployment health, supervisor-loop health and Ticket health all true. Only these allowed fields were inspected; no live state file was exported. |

Device/deployment observations in this report were collected by the root task
that owned the phone lane. The source/test task performed the local parity,
build and packaging checks. No additional physical action was needed for this
read-only health migration.

The first device test failed after 6.944 s because it required a healthy Ticket
listener while Android instrumentation had stopped the application that owns
that listener. Root, required-marker and unchanged-config checks had already
passed. The test emitted its sample too late to aid diagnosis. `d953ff9` emits
the redacted sample first and compares Ticket health to the listener state in
the very same captured probe. Its deployment-health result was correctly false
while the application was stopped. That instrumentation result does not replace
the separate normal-service acceptance above.

An initial normal HEALTH action restored/observed the Ticket listener but did
not resume the supervisor loop. Restoration therefore used the existing
`SupervisorService` null-action path, which calls `resumeSupervision`, before
the final normal-health verification. The test runner lifecycle was accounted
for explicitly; no production health rule was relaxed.

## Measurements and limits

The packaged arm64 library is 579,720 bytes, SHA-256
`be79c192aea68f3ffcf4538baaba0858201ff2129aa530df0ea0886dce9a5e32`.
ELF load alignment and APK ZIP alignment passed the 16-KiB checks. The packaged
library matches the generated library. Formatting, Clippy with warnings denied,
and Git whitespace checks passed.

Local measurements cover the complete checker with a representative captured
response supplied by the existing command boundary; they exclude root process
execution and network work. Separate fresh JVMs measured first check wall/CPU
times of 40.624/39.761 ms for Kotlin and 21.911/21.790 ms for Rust. Across 500
warm paired checks, Kotlin p50/p95 was 0.158/0.261 ms and Rust 0.350/0.481 ms;
median CPU was 0.159/0.351 ms respectively. The approximately 0.2 ms warm cost
comes from the JNI/JSON boundary and is small relative to the measured device
probe. This is not a claim of whole-device speed or memory savings.

One pre-instrumentation resource point for the deployed Rust application was
71,064 KiB PSS, 169,844 KiB RSS and 4,590 KiB swap PSS. No comparable old-version
whole-application memory sample was recorded. Long-running whole-device CPU,
tail latency and memory regression measurements remain unverified.

A second running-app point at 09:47 UTC was 69,988 KiB PSS, 167,268 KiB RSS and
4,845 KiB swap PSS. These are noisy point samples from the same Rust release;
they do not establish a resource improvement.

Reproduce local differential checks with
`orchestrator/android-orchestrator/pixel-health/tests/run-parity.sh`; provide a
restricted, redacted sample through `PIXEL_HEALTH_PARITY_SAMPLE` for the device
comparison. See the crate README for the capture procedure and measurement
command. Live config or secrets must not be committed as fixture data.
