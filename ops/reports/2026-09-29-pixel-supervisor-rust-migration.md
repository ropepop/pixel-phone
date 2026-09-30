# Pixel supervisor state Rust verification — 2026-09-29

The replacement passed local comparisons, installed-device supervisor,
persistence, cleanup, active-service and signed-in browser checks. The browser
journey's natural final quiet-state proof remains with the coordinating task;
it is not implied by the device checks below. No phone actions were performed while
implementing the source change.

## Boundary

The existing `pixel_health` library now owns service state/count/timestamp
updates, the last-100 operation history, supervisor status and heartbeat,
module-health merge, network fingerprint/IP/convergence decisions, direct-public
transition records and observed management status. The old state owner is
retained in Git at `ea2e6df` for comparison and rollback, not as a second
production implementation.

The Kotlin engine keeps the same public APIs, DTOs, config, saved state identity,
coroutine loop, root/Android controllers and persistence call positions. Each
controller health recheck and stop/start sequence is unchanged. Management
recovery, counters and restart backoff remain their existing Kotlin owner.
Disabled services are excluded from the migration; their existing policies,
including DDNS scheduling and remote health observation, remain unchanged.
Rust does not precompute an action batch, call services, read a clock, create a native
state handle or add a worker. Explicit epochs make the pure comparison stable.

Existing details were intentionally retained: RUNNING refresh updates the
service start/healthy timestamps; supervisor RUNNING refreshes the successful
boot timestamp; repeated direct-public failure refreshes its failure timestamp;
only transitions append the corresponding network/health event. A manual health
check does not restart a service or resume a stopped supervisor.

## Local proof

- All 29 existing supervisor tests and one malformed-JNI test passed.
- Four differential groups compare more than 2,500 state/decision scenarios
  against the actual previous Kotlin owner using the same fixed clocks. They
  compare complete state values, saved JSON ordering, network observation flags,
  active controller call order, and success/failure results.
- Cases cover absent services, every status, count overflow, signed clock
  extremes, retained module details, last-100 event truncation, Unicode/blank
  evidence, `none` normalization, prior versus current snapshots, convergence
  expiry, failed/recovered controller checks, stop/start outcomes and backward
  clock jumps.
- The exact-byte comparison first failed because serde_json sorted map keys.
  Enabling its existing `preserve_order` option fixed that difference. Its
  three transitive packages are locked; no second native library or framework
  was introduced. The health bridge explicitly retains its previous sorted
  object order so this option does not reorder existing health snapshot maps.
- One existing async test initially read saved recovery history immediately
  after the controller's start callback. That callback precedes state saving.
  It now waits for the actual saved recovery event within the existing bounded
  deadline. The tested production order is unchanged.
- All 521 app tests passed in each of the debug and release variants, together
  with 12 core-config and 45 health tests. Product and instrumentation APK builds
  passed, as did shared health, store
  and media differential checks after the JSON-order feature change. ELF and
  packaged ZIP alignment pass at 16 KiB. The library is 763,872 bytes (46,608
  bytes above the media release), SHA-256
  `a5faddf3d9eaebb243e27fcc28e00c3ff82acc38db4adc5309b6664e4e7b4be0`.

Reproduce with
`bash orchestrator/android-orchestrator/pixel-health/tests/run-supervisor-parity.sh`.
Only ignored build output contains the old implementation and fixed-clock
engine copies; the production source has no test clock or alternate policy.

## Measured cost

The local measurement includes a representative full policy cycle, a populated
100-event state, healthy active controller and actual disposable save/reload. It
substitutes fixed health observations and a test controller, so it excludes real
root/network work. Both paths use the same already-migrated native store.

| Implementation | Fresh JVM wall/CPU | Warm success | Warm wall p50 | Warm wall p95 | Warm CPU p50 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Previous Kotlin state owner | 24.906 / 24.622 ms | 500/500 | 0.376 ms | 0.559 ms | 0.372 ms |
| Rust state owner | 37.631 / 36.974 ms | 500/500 | 1.570 ms | 2.141 ms | 1.566 ms |

The additional approximately 1.2 ms warm cost comes from repeated complete
state serialization/validation across JNI at the existing effect boundaries.
Combining service actions would change ownership/order, so this port retains
those boundaries. This is a measurable local regression, not a performance
improvement claim. Spread over the configured 15-second poll period, that
synthetic extra CPU time is approximately 0.008% of one core; this is arithmetic,
not measured phone CPU.
Controlled device cold/warm comparisons remain unverified. The deployed point
observations below do not isolate the supervisor's cost. Set
`PIXEL_SUPERVISOR_MEASURE=1` to reproduce the host measurement.

## Installed-device proof

On 2026-09-29, the coordinating task proved the preceding media release quiet at
13:35:57.965 UTC, then handed over exclusive Pixel ownership. The source task
installed the preserved, already-built supervisor candidate from `85fa84f` on
the authenticated Pixel 9a. The later recognition candidate was not built or
installed during this verification.

- Product APK SHA-256:
  `c4f23d78e5da93b1155b723c99736ab8cd2ca1c7dc13577c1020aa5254129cc9`.
  The installed package file was independently hashed and matched.
- Test APK SHA-256:
  `32f7fdf83448f2ff7659725f23ea0957ddca6d56b197d0d177bbf18bbb0864bb`.
- The preserved prior media APK remains
  `output/rust-migration/orchestrator-rust-media.apk` for rollback.

| Scenario | Expected | Observed |
| --- | --- | --- |
| Canonical scoped installation | Install the exact saved APK, preserve runtime/config inputs, pass health | `tools/pixel/redeploy.sh --scope orchestrator --profile fast --skip-build --transport adb --device 100.76.50.43:5555` passed in 29.968 s under run `rust-supervisor-20260929T1336Z`. Runtime assets were fresh; no config/bootstrap/component release change occurred. |
| Installed native supervisor and store | One simulated failed start followed by one recovery; durable state; no repeated starts on resume or manual health | `SupervisorStateInstrumentedTest` passed in 0.943 s: starts=2, stops=2, one saved recovery. The test checked live config unchanged and fixture state mode 0600. All controller effects were disposable test effects. |
| Cleanup and normal owner restoration | Test package and fixture absent; actual supervisor resumes | Both absent after the test. The ordinary shell was denied access to the non-exported service before any effect; the existing root-authorized null-action `SupervisorService` path then succeeded. |
| Standard health after restoration | Real active services and supervision healthy | Run `rust-supervisor-restored-20260929T1337Z` passed standard HEALTH in 18.383 s, with a successful durable action receipt. |
| Persisted state agrees | Root, SSH, VPN, management, supervisor loop and deployment true; heartbeat advances | At 13:38:05 UTC every listed saved field was true. Heartbeat advanced from 1790689064 to 1790689110 and then 1790689156 by 13:39:19 UTC. Saved `ticket_screen_healthy` was true. Only these allowed fields were read, not the full live state file. |
| Active interfaces | Authenticated management remains reachable; Ticket listener intact | Canonical SSH readiness passed, including key authentication and management consistency. SSH port 2222 and the existing loopback Ticket port 9388 were listening. No VPN/SSH failure was induced. |
| Permissions | Private state stays private; local proof remains user-accessible | Live config/state both mode 0600, UID/GID 10205. Local saved APKs and proof files belong to the regular workspace user and are readable without root. |
| Existing signed-in Brave session | Actual ticket picture and stream recover after supervisor replacement | Coordinating task verified the existing session at approximately 13:43 UTC: correct unused ViVi ticket picture and matching registration oval; `LIVE_FRESH` sequence 55, age 1,509 ms; command/media recovery and Spacetime connection live. No physical action was submitted. |

Application resource points were PSS/RSS/swap PSS 93,332/175,872/3,283 KiB
before installation and 88,276/168,548/5,045 KiB after restoration. These are
noisy whole-application points, not a controlled improvement claim. Device
ownership returned to the coordinating task at 13:39 UTC before browser work.

Raw proof is under `output/rust-migration/pixel-supervisor-{deploy,
instrumentation,restored-health}.log`, `pixel-supervisor-saved-proof.txt` and
`pixel-supervisor-ssh-proof.txt`. The canonical wrapper report is
`output/pixel/redeploy/rust-supervisor-20260929T1336Z/summary.json`.

## Acceptance boundaries

Acceptance covers active services and required shared state only. Disabled
services are excluded, not pending migrations or acceptance blockers.

The device steps and browser flow passed as recorded above. Natural final
quiet-state proof remains separate: the established 30-minute warm hold must
expire without being forced, and later server acceptance may legitimately
extend it. Natural network-change and real failure recovery have not been
manufactured or claimed.

1. Deploy a clean committed product through the existing scoped orchestrator
   path, preserving the last accepted rollback APK/release.
2. Run only `SupervisorStateInstrumentedTest`. It exercises the installed Rust
   library through the actual supervisor and private disposable files. Its Ticket
   controller is a test target; it never accesses the network,
   executes root commands, changes phone settings or starts another live writer.
3. Verify the fixture and test package are gone. Restore normal supervision via
   the existing null-action SupervisorService path, then normal HEALTH and the
   actual AppGraph-owned saved health/heartbeat must agree.
4. Verify active SSH/VPN/Ticket supervision and saved results through their
   existing interfaces. Do not induce a VPN/SSH outage or change production
   settings to manufacture a case.
5. Record exact source/APK versions, latency, resource observations and unchanged
   existing service behavior. Natural network-change and failure recovery remain
   explicitly unverified unless an appropriate actual journey is observed.
