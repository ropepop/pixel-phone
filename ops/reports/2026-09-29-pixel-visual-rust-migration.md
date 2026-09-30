# Pixel visual recognition: installed, full acceptance in progress

## Scope and acceptance boundary

The existing `pixel_health` library now owns the pixel and decision logic from
`TicketVisualDateGlyphRecognizer`, `TicketVisualActionClassifier` and
`TicketControlCodeVisualClassifier`. No additional native library, process,
thread, capture owner, dispatcher, input authority or store was introduced.
The Android/Java adapters retain public DTOs, wire formatting, the local calendar
clock, the existing date-salt file and its private atomic write, and the
process-private control salt, SHA-256 and epoch. Existing capture, freshness,
physical-touch, action-clock, command ownership and no-replay fences are unchanged.

The date core uses `chrono` with default features disabled and `std` enabled
for proleptic Gregorian dates and the existing two-year, clamped leap-day bound.
Embedded digit masks, palette thresholds, normalized scores, topology checks,
row conflict rejection, list eligibility, control-state precedence and geometry
limits are preserved. The two existing luminance formulas remain separate.

One intentional malformed-input change is approved: `classifyForCleanup` now
returns `unknown` for null or incorrectly sized arrays instead of potentially
throwing an array exception. It grants no action. Every valid-size image in the
comparison must match exactly; other invalid-input contracts remain compared.

The installed recognition release is recorded below; full acceptance remains
in progress. The supervisor rollback APK built from `85fa84f` remains preserved as
`output/rust-migration/orchestrator-rust-supervisor.apk` (SHA-256
`c4f23d78e5da93b1155b723c99736ab8cd2ca1c7dc13577c1020aa5254129cc9`), alongside its
matching test APK. Its native library SHA remains
`a5faddf3d9eaebb243e27fcc28e00c3ff82acc38db4adc5309b6664e4e7b4be0`.
The recognition build has not replaced those saved rollback APKs.
Original Java source
remains in Git at `85fa84f`; parity materializes it only in ignored test output.

## Completed host verification

`pixel-health/tests/run-visual-parity.sh` generates the three original Java
classifiers from `85fa84f` and runs the actual current JNI library against them.
Only synthetic images and existing retained fixtures are used. Host fixture
salts are aligned in memory for exact opaque-identity comparison; no phone salt,
key, screenshot or live ticket content is read or exported.

- 996 date image cases: retained image shapes, impossible/reversed/leap/year-zero
  dates, threshold neighbors, all 36 embedded font variants, two scales, both
  polarities, deterministic noise and missing/dimension-mismatched input. Results
  compare exact date values, order, vertical centers and salted anchors.
- Full action, detail-only and route results compare complete wire strings,
  all returned bounds, card identity and latest/ambiguous eligibility. Actual
  exercised states include activated/unactivated detail, home, profile, other
  tab, ticket list, login, blocked and unknown. Missing peers, competing selected
  tabs, overlays, partial input, multiple cards and date expiry are included.
- The header-alias fixture first establishes a reduced close-shaped glyph
  inside the Time label while all four original sample phases lack a real X.
  That narrow alias permits the proved opposite tab. Real X shapes at the same
  coordinates or elsewhere do not. Java and Rust agree on the full result.
- 3,347 control image cases: 2,538 ordinary/high-resolution frames, 581 popup/
  value/caret/keyboard frames, 84 static-signature images and 144 sparse
  high-resolution close shapes. There are 354 actual close targets, 1,381
  nonempty salted code signatures and 19 high-resolution-only targets whose
  compact close proof is absent. All states, bounds and signature bytes match.
- Two separate JVMs prove stable detail identity within each process and changed
  identity after process restart. This is process-salt proof, not persistence
  proof for the phone's date-salt file.
- All 23 retained idle-refresh tests pass against the new implementation.
- The full app JVM suite passed 528 tests across 74 suites, with no failures,
  errors or skips. The subsequently added sparse-close test also passed in the
  focused comparison; no product semantics changed between these runs.
- Locked Cargo all-target Clippy with warnings denied, formatting and compilation
  pass. The crate's primary behavior coverage is actual JNI, not duplicate Rust
  unit tests.

The tested optimized host library SHA-256 is
`115a71a41d739a134586b5651d52d4729ddb4448e5460e9f36b756d82c4ee771`.
Raw local output is in `output/rust-migration/pixel-visual-host-comparison.log`,
`pixel-visual-final-comparison.log` and `pixel-visual-full-app.log`.

## Host cost comparison

`PIXEL_VISUAL_MEASURE=1` runs Java, Rust and Java again in separate fresh JVMs.
Each engine measures a first complete call, 24 warmup calls and 120 timed
classification + route + date journeys over the same four synthetic images.
All three runs consumed the same results. The loop excludes capture, Android
scheduling, encoding, network delivery and physical actions.

| Metric | Java before | Rust | Java after |
| --- | ---: | ---: | ---: |
| First call, ms | 75.105 | 38.587 | 68.105 |
| Warm median, ms | 14.344 | 12.672 | 13.193 |
| Warm p95, ms | 18.104 | 14.187 | 17.081 |
| Warm p99, ms | 18.484 | 14.434 | 17.256 |
| Process CPU for 120 calls, ms | 2238.779 | 1532.435 | 2191.796 |
| Process RSS after calls, KiB | 324960 | 92480 | 324816 |

This small local-JVM sample found no performance regression. RSS is an observed
point including each VM and its collector, not native-only allocation or a
controlled Pixel resource result. Cold means a fresh JVM/library invocation;
it does not mean cold filesystem caches or a rebooted phone.

## Android package verification before installation

The canonical standard build completed in 38.045 s from clean source
`83de7cd744841ea3e45095b7092456ee026461c1` (recognition implementation checkpoint
`e3dca91`), with release `rust-visual-20260929`, build time
`2026-09-29T13:42:15Z` and dirty-source flag false. Both normal app variants passed
521 tests each, together with 110 shared-module tests. The previous comparison
harness's extra tests are intentionally added only by its disposable Gradle
source set; they are not included in these normal-suite counts. The prepared
Android instrumentation APK also compiled successfully using the same provenance.

- Product APK: `output/rust-migration/orchestrator-rust-visual.apk`, SHA-256
  `8db9c0b503d29de120158566eecfb1fb0a11fbaa10d2245741d39a6b84a7c718`.
- Test APK: `output/rust-migration/orchestrator-rust-visual-test.apk`, SHA-256
  `f29c05246e9bfd14952997a1504122b142a2ecf4a9f0ebf38c8404de23086b9d`.
- Packaged ARM64 library: 908,120 bytes, SHA-256
  `9e6f8991615e714379b5843a4704a2e359ee452a02b90226bd5d52d7f52e55fa`;
  bytes match the generated library exactly. Every one of the 25 existing/new
  `NativeTicketMedia` methods has its corresponding exported JNI entry point.
- All ELF load segments and the APK ZIP pass 16-KiB alignment checks. Product,
  test and preserved supervisor APK signatures verify and use the same existing
  signing certificate. Saved candidates and proof files are owned by the regular
  workspace user, mode 0644. Supervisor rollback hashes remain unchanged.

Build logs are `output/rust-migration/pixel-visual-android-build.log` and
`pixel-visual-android-test-build.log`; package checks are summarized in
`pixel-visual-package-proof.json`. This is packaging proof, not device execution.

## Instrumentation design before installation

The subsequently executed `TicketRootVisualInstrumentedTest` launches two
separate root `app_process` VMs using the
installed product APK's existing native-library property. Synthetic pixels
exercise date recognition, full/current action DTOs, exact close geometry,
control signatures, missing-input rejection and bounded repeated calls. Only
opaque identities pass within the on-device test; final instrumentation output
reports the equality/change results and timing, not the identities themselves.
It creates no image, state or fixture file and performs no capture or input.
The expected existing date-salt file must survive both VMs, while the process
control identity must change. Run only this test after the coordinated APK
build/install; restore the normal supervisor after instrumentation exits.

The installed root-VM, live capture, code, restart, reconnect and registration
results are recorded below. Human physical-device observation and natural final
quiet state remain separate. Never repeat an uncertain phone action for proof.

## Installed device and live journeys

The exact saved recognition APK above was installed through the canonical
orchestrator-only fast/skip-build path at 14:03:01 UTC, in 28.743 seconds
(`rust-visual-20260929T1406Z`; the run label is not its actual timestamp).
The installed package hash matches the saved APK. Config/state remain mode
0600, UID/GID 10205; the existing 32-byte date salt remains root-owned 0600.
`TicketRootVisualInstrumentedTest` passed in 4.027 seconds on the actual Pixel:
date identity survived separate root VMs while detail identity changed. Cold
calls were 31.387/24.085 ms; warm p95 19.561/19.953 ms; measured CPU
299.426/264.830 ms; native heaps 864,672/863,856 bytes. These are synthetic
recognition journeys on the device, excluding capture/network/physical action.

The test package was removed and the normal supervisor restored. Canonical
standard health passed in 28.359 seconds at 14:04:24 UTC. APK verification and
private permissions passed. A later actual process interruption recovered via
the same health path in 21.425 seconds at 17:45:26 UTC. Saved logs are
`output/rust-migration/pixel-visual-{deploy,instrumentation,restored-health}.log`,
`pixel-visual-installed-proof.txt` and `pixel-input-recovery-live.log`.

The existing signed-in Brave viewer showed the correct real ticket. One
open-latest-unregistered request completed in 7.005 seconds at 14:05:54 UTC,
with durable success and the expected visible phone result. At 17:36 UTC one
inspection-code request completed through actual phone input, displayed result,
browser acknowledgement and phone cleanup; its terminal durable row has
`captureAcknowledged=true`, `captureRequired=false`, `cleanupPending=false`.
Closing its displayed result restored live video. Browser cold reset and the
separate Android process restart also returned the same signed-in page to live
media and controls. The corresponding IDs and observations are in the ops
checkout `workloads/ticket-remote/rust/LIVE_VERIFICATION.md`.

Instrumentation had left the already-enabled Android input service unbound;
the previous host health helper checked its setting without checking the live
connection. This correctly disabled phone action controls despite successful
visual recognition. Rebinding the same service restored `ready=true`.
The deployment helper now requires both permission and binding for the ADB
orchestrator health path, rebinds only its existing service entry when needed,
and retains all other services. A persistent failure stays closed. The new
executable regression fails with the original helper and passes with the fix;
the actual force-stop/health recovery above exercised the corrected path.

Whole-app PSS/RSS after restoration were 80,248/179,448 KiB with 3,665 KiB swap
PSS, a noisy point rather than a controlled comparison. Switching both ways,
slider registration and open-latest-and-register each subsequently completed
once through the real phone, durable terminal result and visibly registered
picture. The timed search executed at 17:57 UTC and finished in 6.128 seconds.
Exact action IDs/times remain in the ops live verification record. No human
observation of emitted brightness or physical touch is claimed. Full feature
testing remains in progress; final natural quiet proof passed at 18:26:14 UTC:
stream inactive, zero clients/video clients, idle capture, encoder inactive,
zero encoder/stale-capture processes and wrappers, `cleanup_ok`, inactive dark
lease, proven last verifier and no failures. No hold expiry was forced and no
service was recreated during that quiet cycle;
retain the verified supervisor rollback APK until these gates are complete.
