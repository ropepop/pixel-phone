# Pixel native core

Rust owns the existing health probe construction, marker parsing, decisions,
module status and evidence fields. Android retains the single root-command
execution and the public Kotlin configuration/snapshot types. No new process,
polling loop or recovery action is introduced. The library also owns the existing
StackStore file reads/atomic replacement, legacy alias decisions and inline
token masking. The deployed library name remains pixel_health so this migration
does not add another loader or native package.

Kotlin retains synchronized StackStore methods, DTOs and JSON serialization.
AppGraph still supplies its existing app-private stack-store paths; external
root config import remains in OrchestratorFacade. Rust reads UTF-8 and writes a
0600 same-directory temporary file, then atomically replaces the destination.
Read errors and corrupt serialized data preserve the existing default behavior
without repairing files. Native loading/bridge failures remain visible.
Failed writes preserve the destination and remove only their own temporary
file; process death may leave a staging file that reads ignore. No directory
sweep, cross-process lock or new fsync guarantee is introduced.

Legacy aliases return key selections to Kotlin, which retains the original
values, unknown fields, key order and caller's formatting behavior. The current
nonblank key wins. Secret masking keeps includeSecrets behavior and only masks
the existing inline DoH token. No DTO, config identity, secret location or
stored-state schema changed.

Rust owns supervisor state updates and network observations in this same
library. The Kotlin engine still calls controllers in the existing order,
checks health again before a restart, and owns coroutines, restart/backoff
policy, locks and persistence. Native calls are pure: they receive typed JSON
and an explicit epoch, then return the updated state and network flags. No
service action is planned in advance or dispatched from Rust. Each effect's
result is recorded at its original call position. Existing DTOs, map order,
100-event retention, status/count/timestamp rules and file identities remain.
serde_json's preserve_order option retains existing saved collection order.
Health results explicitly retain their prior sorted object order, so this
option does not reorder the already-deployed health snapshot maps.
Disabled services are excluded from this migration. Their existing policies,
including DDNS scheduling and remote health observation, remain in Kotlin.

Rust also owns Ticket access-unit assembly and the unchanged THF1/TSF3 frame
headers. Java retains its record DTO, InputStream/OutputStream transfers and
payload copying. The root encoder still owns MediaCodec, Surface, capture and
network behavior. The two-MiB bounds, cached SPS/PPS authority, partial-buffer
flags, overflow recovery, validation order and timestamp limits are unchanged.
Native calls use byte/long arrays rather than JSON for media.

The assembler has one native allocation owned by the Java AutoCloseable adapter.
Every operation and close is synchronized; close zeros its private handle, and
later calls fail before JNI. EncodingOwner wraps the entire codec session in
try-with-resources, including codec-creation failure and teardown exceptions.
There is no global native registry, finalizer, worker or second media owner.

Normal Android loading uses the already-packaged pixel_health library. The
root app_process VM receives pixel.media.native.library naming the uncompressed
library inside the exact same installed APK as its CLASSPATH. System.load reads
that APK entry directly; no library is extracted or separately deployed.

Rust also owns Ticket date glyphs, action-state and route proofs, popup/result
classification, proved input/close/slider bounds and signature quantization.
The Java classes retain their public result types, wire formatting, LocalDate
clock, private date-anchor salt file, and process-private signature salt/SHA-256.
The same library and root VM loader are reused. Native recognition is stateless;
it adds no capture, action, thread, input authority or persistent store. The
existing component, palette, confidence, ambiguity and four-phase header gates
are unchanged. Malformed cleanup arrays now return unknown, matching the other
entry points; valid frames retain exact compatibility.

Rust owns the Ticket activation checkpoint transitions, dispatch ordinal
admission, failure phase, matching identity and conclusive terminal-retirement
decisions. The Kotlin store retains the single SharedPreferences slot, unchanged
file/key identities, synchronous commit and exact readback at the original
effect positions. Native calls are stateless and return either the candidate
checkpoint or a preserved conclusive checkpoint; they perform no input or
persistence. A failed commit or mismatching readback never proves dispatch.
Dispatching and generic attention checkpoints remain durable replay fences
after terminal delivery. The physical-touch observations, executor, visual
consensus and phone-control session/publication owner remain Android adapters
or separate pending migration units.

Rust owns the bounded Ticket command subscription message decoding and row
selection in this same library. It receives the existing ticket/backend scope
and an explicit nanosecond clock, then returns the unchanged Kotlin message,
command, desired-state and monitoring types. The three table names, six command
types, pending status, expiry, option encodings, 128-command capacity and original
UTF-16/UTF-8 message, row and payload limits are unchanged. Quoted command fields
remain mandatory; literal spelling, duplicate-key order and Java Instant edge
semantics are retained. Identity-token contents never cross back or enter errors.
Kotlin still owns OkHttp, the subscription handshake, reconnect policy, atomic
current inbox, expiry revalidation, retained result delivery and every effect or
durable journal. The decoder creates no socket, timer, queue or phone action.

The native interface accepts default-inclusive StackConfigV1 JSON. Probe
construction returns the same shell command used by the installed Android
helpers. Interpretation takes command success, complete stdout and an explicit
epoch second; missing required sections invalidate the whole sample. Invalid
configuration and native bridge errors throw, while incomplete probe output
retains the existing failed-probe snapshot.

The interface exports buildProbe and interpretProbe on
lv.jolkins.pixelorchestrator.health.NativeHealth. JSON values and raw probe
contents are never included in native error messages.

## Verification

Run `bash tests/run-command-decoder-parity.sh` for the former decoder at
`c66dc93` and the current adapter through actual host JNI. Generated legacy code
stays only in ignored app build output. The comparison covers both row shapes,
scopes, allowlists, deadlines down to a nanosecond, byte/character/capacity bounds,
malformed rows, numeric/literal and optional values, duplicate fields and forged
literal tags. Existing expiry/result checks and real loopback WebSockets cover
identity/initial/update/delete/close/reconnect, stale snapshot removal, wrong
protocol and sequence rejection, and retaining a result after a lost
acknowledgement until authoritative deletion. These checks perform no phone
effect and do not prove installed Android receipt or physical execution. See
`ops/reports/2026-09-29-pixel-command-decoder-rust-migration.md`.

Run `bash tests/run-checkpoint-parity.sh` for the previous checkpoint owner at
`d6a6bbb` and the current Android adapter through actual host JNI. Generated
legacy code stays only in ignored app build output. Comparisons cover every
stage and ordinal, transition rejection, Unicode/whitespace identities, exact
checkpoint fields, durable effect order, failed commits, lost acknowledgements,
readback mismatch and every terminal identity/outcome fence. Existing checkpoint
tests remain. A lost dispatch acknowledgement retains the dispatching checkpoint
and rejects both a repeat and a second stroke; no physical input is performed.

After coordinated deployment, run only
`lv.jolkins.pixelorchestrator.app.ticket.TicketActivationCheckpointInstrumentedTest`
with `checkpoint_phase=write`, then `checkpoint_phase=read` in a separate
instrumentation invocation. This uses installed JNI and the production
preferences adapter remapped to the disposable `ticket_checkpoint_native_acceptance`
store. Distinct process IDs prove the uncertain dispatch survives Android
restart without admitting another stroke. Read removes its own fixture;
`checkpoint_phase=cleanup` removes only that named fixture after interruption.
The live checkpoint, ticket capture and phone input are never used. Restore the
normal supervisor afterward, and verify normal service, signed-in page and final
quiet-state behavior separately. Local comparisons and APK builds do not prove
those deployed layers. See `ops/reports/2026-09-29-pixel-checkpoint-rust-migration.md`.

Run bash tests/run-visual-parity.sh for the former three Java classifiers at
85fa84f against actual host JNI. Generated legacy copies remain only under
app/build/rust-visual-parity. The tests use retained synthetic fixtures and all
embedded digit masks, compare complete states/wire/bounds/opaque identities,
exercise faint and ambiguous dates, all native header sample phases, multi-card
selection, popup/value/keyboard and sparse high-resolution cleanup shapes, and
check identity continuity within a process and invalidation after restart.
They never capture or operate a phone. PIXEL_VISUAL_MEASURE=1 adds separate JVM
Java/Rust/Java measurements of full classification + route + date calls.
After coordinated deployment, run only
`lv.jolkins.pixelorchestrator.app.ticket.TicketRootVisualInstrumentedTest`. It
uses synthetic pixels in two root VMs to check the installed JNI, existing
persistent date salt and changed process identity, and performs no capture or
input. Restore the normal supervisor afterward.
Host parity is not deployed acceptance: the same APK must pass actual root VM
loading and live capture-to-proof, browser control-code/cleanup, restart and
quiet-state checks before this replacement is accepted. Do not repeat an
uncertain action or use registration as a test. Current evidence and remaining
gates are in ops/reports/2026-09-29-pixel-visual-rust-migration.md.

Run bash tests/run-supervisor-parity.sh for the previous Kotlin engine at
ea2e6df and the current engine with identical test-only fixed clocks. Generated
copies exist only under supervisor/build/rust-parity; production has no clock
override. Comparisons exercise actual JNI and include exact serialized state,
all service statuses, restart-count overflow, log retention, Unicode/missing
values, network changes, convergence boundaries, active controller recovery,
success/failure outcomes and the exact order of controller calls. The existing
supervisor suite remains. PIXEL_SUPERVISOR_MEASURE=1 measures fresh-process and
500 paired warm policy cycles through disposable state save/reload.

After deployment, run only
lv.jolkins.pixelorchestrator.app.health.SupervisorStateInstrumentedTest.
It uses the installed JNI, actual engine and private disposable store, while
its test Ticket controller performs no network, phone or service effect. It
verifies a failed start, health recheck, stop/start recovery, recorded outcomes,
heartbeat, active resume, manual health, stop and stopped-resume behavior. Live config bytes remain
unchanged and removes its fixture. Restore the normal supervisor after the
instrumentation runner exits, then verify normal and saved health, existing
active service continuity separately. This fixture does not prove an actual
network transition or recovery of a failed live service.

Run cargo fmt --check and cargo clippy --locked -- -D warnings in this directory.
Run bash tests/run-parity.sh from any directory for the original Kotlin checker
versus Rust comparison and the existing health suite. It reads the original
checker from the committed baseline (default 2dc9085), materializes it only under
ignored health/build/rust-parity, and uses a fixed clock. No root command is
executed. Tests execute the public JNI boundary and compare every snapshot
field, command text, disabled-module contracts, incomplete reports, timing
boundaries, Unicode input and local/public remote health distinctions.

Run bash tests/run-store-parity.sh for the former configuration owners at
d953ff9 versus the native replacement, plus the core-config suite. The former
sources exist only in ignored build output. Cases cover alias precedence,
non-string/Unicode values, unknown large numbers, formatting, malformed data,
exact saved bytes and populated records. StackStorePersistenceTest checks
private permissions, unreadable/corrupt data, strict UTF-16 writing, denied
writes, symlink replacement, concurrent atomic readers, fresh JVM reloads and
a real OS file-size limit interrupting a partial write. Set
PIXEL_STORE_MEASURE=1 for fresh-process and 250 paired warm full config/state
save/read measurements using disposable files. No live state is used.

After deployment, run only
lv.jolkins.pixelorchestrator.app.health.StackStoreInstrumentedTest with
store_phase=write, then in a separate instrumentation invocation with
store_phase=read. Distinct reported process IDs prove that synthetic config and
populated state survived a process restart. Tests check installed JNI loading,
exact serialization, permissions, corruption defaults, ignored partial staging,
legacy config and secret masking in app-private disposable files. The read
phase removes its fixture. store_phase=cleanup removes only this named fixture
if an interrupted test requires cleanup. The default roundtrip phase is a
single-process check and does not establish restart proof.

Instrumentation stops the supervisor owner. Restore the existing
SupervisorService null-action resume path after testing, then require normal
service health and the AppGraph-owned saved health to agree. A HEALTH action
alone does not resume supervision. Verify the test package and fixture are
removed. Until that device acceptance passes, this persistence migration is
locally verified only.

Run bash tests/run-media-parity.sh to compare the former Java owners at 3ec2e93
with the native media interfaces. Golden frame bytes, partial/zero-progress
reads, EOF/error precedence, size/flag/timestamp limits, malformed/ambiguous
NALs, every configuration split, deterministic fragment sequences, overflow,
reset and closed-owner rejection are covered. The existing assembler tests
remain and now close their native owners. PIXEL_MEDIA_MEASURE=1 adds fresh-JVM
and 500 paired warm synthetic 64-KiB assembler -> THF1 -> TSF3 journeys.

The test-only TicketRootNativeLoadInstrumentedTest proved loading the earlier
packaged library in the actual root app_process before migration. For each new
media deployment, run TicketRootMediaInstrumentedTest: the root fixture uses
the production VM property and installed symbols, verifies synthetic frame
bytes, and repeats 256 allocation/partial-frame/close cycles with a native-heap
growth limit. It emits no real pixels and performs no capture or input. This
does not replace signed-in live-stream, reconnect, process restart and final
quiet-state verification after deployment. Restore supervision after Android
instrumentation as described above.

Set PIXEL_HEALTH_MEASURE=1 to also measure separate fresh-JVM first calls and
500 paired warm calls. These measurements include the complete checker and
JNI/JSON conversion but substitute a synthetic root response. They exclude
the actual device/root-command latency and are not phone resource evidence.

After installing the product and test APK, run only
lv.jolkins.pixelorchestrator.app.health.RuntimeHealthInstrumentedTest with
the existing AndroidJUnitRunner. It reads the installed config, invokes the
real root probe once, checks root/report integrity and observed listener state,
and verifies config bytes remain unchanged. Android instrumentation stops the
target app and its Ticket listener, so this test does not require that stopped
owner to be healthy. Normal running-service health must pass separately after
instrumentation exits. Passing health_parity_capture=true returns a redacted sample in
the instrumentation status, without writing it on the phone. Save the JSON
value from health_parity_sample to a caller-owned restricted file, then run
PIXEL_HEALTH_PARITY_SAMPLE=/absolute/sample.json bash tests/run-parity.sh to
compare the installed snapshot, original checker and native host library.
Without that captured file, the actual-device comparison is explicitly skipped.

The old implementation is retained in Git for rollback and parity only; it
must not remain a second production health owner after integration. The
baseline can be selected with tests/run-parity.sh COMMIT.

This local comparison does not establish actual-device acceptance. APK library
loading, root probe execution, supervisor/displayed health, restart continuity,
and the final quiet state must be verified on the Pixel after deployment.

Command inbox/expiry/start/ACK policy and monitoring observation/cadence state now
use this same library. Android keeps socket admission, synchronized calls, clocks,
RAM retained-result DTOs, capture and delivery. Reproduce the former-owner checks
with `tests/run-command-state-parity.sh` and `tests/run-monitoring-parity.sh`.
`TicketNativePolicyInstrumentedTest` checks installed Android JNI with synthetic
state and no network, capture or input. The dated service report records actual
browser/device acceptance separately.

Telemetry metadata policy shares this library: validation, eviction/expiry,
in-flight exclusion, due priority and retry arithmetic. Payload DTOs, the current
lock, clock/entropy and transport await stay Kotlin-owned and RAM-only. Run
`tests/run-telemetry-parity.sh` for full former-owner queue effects; installed
`OrchestratorTelemetryNativeInstrumentedTest` uses synthetic RAM events and sends
nothing to a network.

Capture and phone-control previous-owner checks:
`tests/run-capture-parity.sh` and `tests/run-phone-control-parity.sh`.

Visual action decision comparison: `tests/run-visual-policy-parity.sh`.
