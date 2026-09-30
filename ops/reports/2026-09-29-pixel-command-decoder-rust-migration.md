# Ticket command decoder Rust candidate — 2026-09-29

## Change and finishing criteria

Move the bounded command-message decoder and three row selectors from
`TicketSpacetimeCommandSubscription.kt` into the existing `pixel_health` library.
Keep the current ticket/backend scopes, table/type allowlists, pending status,
TTL, public DTOs and size/capacity limits. Completion requires actual host JNI
comparison with the former owner, existing expiry/result tests, and real socket
sequence/close/reconnect checks, followed by a scoped source checkpoint. Root
owns exact APK packaging, coordinated installation and live acceptance.

The production callers are `TicketSpacetimeCommandSubscription` and its
`TicketSpacetimeWorker` owner. Transport callbacks pass their existing message,
scope, JSON policy and current `Instant` into the adapter. The subscription still
owns protocol validation, identity-before-initial admission, subscription request
zero, reconnect/backoff and atomic `TicketCommandInbox` updates. The worker still
owns current-snapshot expiry revalidation, execution and `TicketCommandResults`.
Command IDs remain unchanged opaque server IDs; desired/monitoring rows retain
the existing `ticketId:backendId` key. No persisted record or wire schema changes.

`NativeTicketCommand` uses the already-packaged library. Rust selects the same
object/array rows and returns the unchanged DTOs; Kotlin remains the only owner
of OkHttp, coroutines, queues, result publication and phone effects. V3 journals,
activation checkpoints and the RAM control-code outbox were traced and left in
their existing owners. Telemetry extraction is a separate pending unit.

No crate or global dependency feature was added. The decoder uses existing
`serde_json` and `chrono`. Its small literal adapter preserves quotedness and
unquoted spelling while serde validates JSON structure/escapes. All original
string values acquire an internal string tag, so input cannot forge a literal
tag; command fields still require quoted strings. Tags never enter returned
DTOs. Identity bodies and raw source text never enter returned error messages.

## Verification

- `bash orchestrator/android-orchestrator/pixel-health/tests/run-command-decoder-parity.sh`:
  **221** executable comparisons against the frozen Kotlin owner at `c66dc93`,
  through the actual host JNI adapter. **13 tests**, zero failures/errors:
  one comparison matrix, two real WebSocket tests and ten existing expiry/result
  tests. Generated legacy code stays only under ignored app build output.
- Comparisons cover object/array rows, table and ticket/backend scope, command
  allowlists, all primitive field types, optional encodings, pending status,
  malformed rows, duplicate keys, forged literal tags, UTF-16/UTF-8 bounds,
  128/129-command capacity and nanosecond deadline boundaries.
- Actual differences found and repaired rather than weakening assertions:
  Java's midnight/expanded-year parsing, negative-zero/exponent integer spelling,
  Kotlin's retained nonfinite/unknown literal values and Java's maximum
  18-hour timezone offset. Invalid ±23-hour offsets cannot admit a command.
- Real loopback OkHttp WebSockets exercise identity/initial/update/delete,
  close/reconnect, clearing stale authority, foreign rows, wrong request ID,
  missing identity, early update, binary messages and wrong protocol. The
  production retained-results owner preserves an already-recorded outcome
  after disconnect without acknowledgement and removes it only after an
  authoritative deletion. This does not execute a worker or physical effect.
- `./gradlew test --console=plain`: successful in **34 seconds**; **1,156** checks
  across both app variants and five supporting modules, zero failures/errors.
  This included root's separately-owned health-resume source edit. The final
  offset guard was subsequently verified by the full 221-case JNI comparison.
- `cargo clippy --locked --offline --manifest-path orchestrator/android-orchestrator/pixel-health/Cargo.toml --lib -- -D warnings`,
  Cargo formatting, shell syntax and `git diff --check`: passed.

The native monitor source was unchanged. The live-accepted development binary
`c27568ee8c77fd2414a6f1eeabc8a6f0f62e5adcab7a1cf737828100990cf59a`
passed all **55** monitor tests in **38.120 seconds**. It had been built with the
temporary arbitrary-precision dependency feature used during decoder development.
That unnecessary global feature was removed. Rebuilding with the final original
manifest produced the original monitor binary
`3c34c94bab8a0a3f955b49d2eaafe25615073967068dfd5c90958d6fab8acbb0`;
all **55** monitor tests passed again in **37.580 seconds** (44 verdict and 22
configuration comparisons, 49 prior contracts and bounded executable collector,
error, privacy, timeout, interruption and atomic-report checks). Root must use
the final hash for the canonical invocation acceptance.

## Limits and ownership

No APK packaging, device probe, production browser action or phone input was
performed in this lane. Local JNI/loopback checks do not prove installed Android
receipt, durable server retirement, physical effect or final quiet state.
Those remain separate root acceptance layers. No additional timer, snapshot
owner, runtime fallback or recovery action was introduced. Timing above describes
checks, not a product performance claim.

New source, tests, report and generated outputs are owned by the workspace user.
Tracked text remains 0644 and the comparison runner is 0755. Root's facade,
Pixel-stack architecture and checkpoint live-proof report edits are excluded
from this scoped checkpoint.

## Root packaging checkpoint

Root subsequently captured the combined decoder/lifecycle APK from exact Android
inputs at `7c7b4ac`: release `rust-command-decoder-20260929`, recorded build time
19:44, source metadata dirty=false. Its 35-second build produced 1,156 test
records with zero failures, errors or skips, including the final offset guard.
Saved APK SHA-256 is
`3f869f57599125f6a6fe0f01503499a7f98264016c3e29bc34474561f573522c`
(39,312,654 bytes); test APK SHA-256 is
`967e30589a87281a109f760072f0f9df1191410c3036d2146c18e8157a2f3267`.
These are root-supplied packaging observations, not installed-device proof from
this lane. The Android/APK inputs stayed exact while concurrently dirty monitor
launcher/tests/docs remained outside APK inputs; the whole checkout was not
clean throughout that capture.
