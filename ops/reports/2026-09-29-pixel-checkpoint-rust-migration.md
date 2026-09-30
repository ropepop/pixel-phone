# Pixel activation checkpoint Rust migration, 2026-09-29

## Scope and completion criteria

This candidate moves one bounded pure owner: activation checkpoint transition,
ordinal admission, identity matching, failure classification and conclusive
terminal retirement. Done locally means the former owner and the actual host
JNI agree on full results and effect ordering; existing JVM checks and both
Android APK builds pass; package/signing and packaged symbol are inspected;
the remaining deployed acceptance layers are stated separately.

The canonical checkout is `Documents/pixel-phone`; former code is at
`d6a6bbb`. The old implementation exists only in Git and generated ignored
test build output. `pixel_health` remains the one packaged native library.
No phone, browser, deployment, capture, gesture or registration was used by
this local batch. Disabled services are outside this migration.

## Preserved boundaries

- Kotlin retains the single durable preference slot, its exact name and all six
  stored keys, synchronous commit, readback equality and original call order.
- A failed commit or mismatching readback returns no proved dispatch checkpoint.
  A successful write whose acknowledgement is lost retains its admitted ordinal.
- Rust is stateless. It returns the next checkpoint and whether the existing
  adapter must write it; the adapter does not write conclusive fresh/no-transition
  stages when a generic attention update would erase stronger certainty.
- No second stroke follows a dispatching or uncertain stage. The second ordinal
  still requires conclusive no-transition proof, and neither ordinal can replay.
- Final server delivery cannot clear dispatch uncertainty or generic attention.
  Retirement requires the original matching command/attempt and the exact
  conclusive local stage/outcome.
- Phone-control session generations, freshness, agreeing visual observations,
  physical-touch watermark, protected input, executor and network publication
  remain with their existing owners in this bounded batch.

## Verification

`bash orchestrator/android-orchestrator/pixel-health/tests/run-checkpoint-parity.sh`
passes with the retained baseline and the actual host JNI. It runs three
comparison/continuity tests plus all nine existing checkpoint tests. The
comparison cases include 1,375 stage/ordinal/durable-backend operation pairs
with exact outputs, retained values and load/save/clear ordering; 1,800
terminal candidate/command comparisons; all failure/no-transition phases;
and Unicode plus Kotlin whitespace identity normalization. Rejection type and
message agree. Commit failure, lost acknowledgement, readback mismatch and
absent durable state are distinct inputs.

The lost-acknowledgement test writes dispatching state but reports failed
commit delivery, constructs a fresh store facade over that retained backend,
and proves that neither stroke ordinal nor a replacement activation is admitted.
This is host failure-path proof; actual Android persistence across process death
requires the separate installed test below.

Product Kotlin and Android-test Kotlin compilation passed. Rust library
`cargo clippy --locked --lib -- -D warnings`, individual Rust formatting and
`git diff --check` passed. The full `./gradlew test :app:assembleDebug
:app:assembleDebugAndroidTest` run passed in 34 seconds: 521 app tests in each
of debug and release, plus 110 tests across configuration, health, root execution,
installer and supervisor (1,152 total executions, none failed or skipped).
Both Android APKs built successfully. The Rust crate's separate library test
target has zero Rust unit tests; the meaningful decision proof is the actual
JNI comparison above, rather than a duplicate native suite.

The product APK still reports package `lv.jolkins.pixelorchestrator`, version
code 1, minimum SDK 29 and target SDK 35. Its signing certificate SHA-256 is
`a52b49620dbfe650b8bd23dfed9d402874670058c21169f24cd9a700945dcee9`, exactly
matching the retained visual-migration APK. The packaged uncompressed arm64
`libpixel_health.so` exactly matches the built library (920,240 bytes,
SHA-256 `c3d514c1976ba47e9cb82f7b2f3c1ee0c794c5b65fd31465aa7c702d6bc5943b`)
and exports `Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketCheckpoint_decide`.
This build used the working checkout with the optional monitor CLI being
developed concurrently; it is local compilation proof, not an exact committed
release artifact for deployment. The coordinating agent must build from the
final scoped source revision before installation.

Local output is under `output/rust-migration/pixel-checkpoint-{host-comparison,kotlin-compile,android-build}.log`.
These files remain accessible to the regular workspace user.

## Installed acceptance

The new `TicketActivationCheckpointInstrumentedTest` uses installed JNI and the
production preferences adapter redirected only to the disposable
`ticket_checkpoint_native_acceptance` preference file. Run only this class
with `checkpoint_phase=write`, then `checkpoint_phase=read` in a distinct
instrumentation invocation. The returned process IDs must differ. It proves
that an admitted, uncertain dispatch survives Android restart without another
stroke admission, retains the existing stored wire keys, refuses uncertain
terminal retirement and allows retirement after matching conclusive proof.
It never accesses the live checkpoint or performs a phone input.
The read phase removes its named fixture; `checkpoint_phase=cleanup` removes
only that fixture after an interrupted acceptance run.

The coordinating agent owns deployment and actual-device verification.
After any instrumentation run, restore the existing normal supervisor resume
path. Check ordinary service and saved health, signed-in live Ticket presentation,
safe non-registration flows and final quiet state separately. APK compilation,
host comparisons and the disposable checkpoint test are not substitutes for
these layers. Consequential tests require an explicitly authorized target;
never replay an uncertain phone effect.

The root owner built and installed the saved `rust-checkpoint-20260929` APK from
clean source `076d0ffc3b7684d5a013f2be04257c071af2e93d`, built at 19:02 UTC.
APK SHA-256 is `8f5e316f9dbbc7171ec1e97788ebe7d60e0d6a38694981ae5bbffe3297c7ea8f`;
the unchanged signing certificate is
`a52b49620dbfe650b8bd23dfed9d402874670058c21169f24cd9a700945dcee9`.
The full build completed in 38 s: 1,152 test executions, zero failures/skips,
application and instrumentation APKs. Canonical installation and health passed
in 24.926 s; the installed APK hash matched the saved artifact.

Actual installed JNI write/read invocations each passed in separate Android
processes (5618 and 5705). The uncertain first ordinal survived, another stroke
was refused, uncertain terminal state stayed retained, and matching conclusive
proof allowed retirement. The read phase removed its disposable preference file;
an independent root read confirmed it absent and both actual production journal
and checkpoint empty before the real action. All workspace proof artifacts are
regular-user owned and mode 0644; no private command payload was exported.

The first post-instrument full-health action failed only its stale supervisor
heartbeat (446 s, strict 80 s limit), while Ticket, SSH, VPN and management were
healthy. The existing null-intent resume restored the retained supervisor without
component starts; the subsequent canonical health passed in 23.118 s. A separate
one-line full-health lifecycle repair is awaiting its own installed restart proof.

The user explicitly authorized registration. Through the clean deployed Ticket
server `rust-ticket-runtime-20260929-r2` and existing signed-in Brave profile,
one current-ticket request `ticket_c2857a18-0d06-43b2-a1d3-4e2f24fbf8ce` ran
19:29:45.169075→19:29:48.838388 UTC, succeeded with `activation_proven`,
`activated_current`, and `ticket_action_registered`. The returned actual phone
picture displayed its registered checkmark and matching local time; the button
was disabled afterward. SDR/HDR rendering and the restored saved display
preference passed without browser warnings/errors. No uncertain action was replayed.
The later no-viewer cleanup cycle remains unverified; this component is not yet
marked complete.

## Next pure owners, inventoried without rewriting them

| Area | Current owner and bounded candidate | Adapter/effect to retain | Required distinctive proof |
| --- | --- | --- | --- |
| Phone-control evidence | `TicketPhoneControlState.kt`: ordered capture watermark, context revision, two-observation registration fence, explicit monotonic clock arithmetic | synchronized state publication, UUID lifetime, coroutines and network transport | repeated/out-of-order probes, context invalidation, TTL boundaries, session replacement and publication loss |
| Visual action decisions | `TicketVisualAction.kt`: observation agreement/consensus, revision aliases, request validation, navigation-journal reconciliation and retained terminal/fingerprint | public DTOs, platform clock parsing, durable journal write/readback, physical executor | unknown animation frames, distinct probes, capture-generation changes, ambiguous navigation, legacy terminal geometry and lost settlement ACK |
| Command inbox and results | `TicketSpacetimeCommandSubscription.kt`: one atomic command/desired/monitoring snapshot, capacity and expiry; `TicketSpacetimeWorker.kt`: command expiry and stable start-first ordering | synchronized snapshot, conflated signal, socket callbacks, cancellation and effect receipt positions | disconnect discards all snapshot parts, old socket generation cannot revive work, terminal delivery retries never repeat phone effects |
| Code outbox | `TicketSpacetimePhoneOutbox.kt`: coalescing, TTL, progress priority, exact ACK identity and reducer-argument validation | single delivery owner and existing reducer I/O | an old in-flight ACK cannot erase a newer publication; generated-result watermark and cleanup semantic proof remain mandatory |
| Telemetry | `OrchestratorTelemetry.kt`: bounded payload validation/format, priority/expiry/queue and backoff; `SpacetimeOrchestratorTelemetryTransport.kt`: HTTP result classification; `OrchestratorTelemetryRuntime.kt`: environment parser | private reducer destination, token consumption, network I/O and process-memory queue lifetime | sanitized byte limits, overflow, rejection/retry distinction, process death discards queue; logging cannot fail healthy Ticket work |
| Installer | `ArtifactSyncer.kt`: local-source admission and cache/release decisions; `RuntimeManifestVerifier.kt`: existing signature contract | file copy, exact filesystem ownership, Java security provider, root effects and active/rollback manifests | malformed/remote sources, hash mismatch, interrupted copy, tampered manifest, path containment and active/rollback protection |
| Root-command construction | `ShellEscaper.kt` and `SuRootExecutor.kt`: quoting, admitted-command and PID/start-time cleanup script construction | existing process execution/timeout/cancellation and proved process-tree teardown | exact shell behavior for quotes/newlines, cancelled/timed-out commands, PID reuse; no duplicated executor or blind process kill |

The next port should select one row and trace every production caller before
moving it. It must keep the effect owner and its actual receipt positions.
This inventory does not authorize unbounded rewrites or disabled-service ports.
