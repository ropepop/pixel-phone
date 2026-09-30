# Pixel media-byte Rust verification — 2026-09-29

The replacement is deployed and the installed media JNI fixture, signed-in
live viewing, reconnect, cold opening and final natural quiet-state proof passed.
Do not treat the earlier library-loading test as media acceptance.

## Deployed evidence so far

The phone-owning task deployed clean product commit `08783fb` as
`rust-media-20260929` through the standard orchestrator scope in 57.432 seconds.
The product APK SHA-256 is
`ff21d9b1a99475efa8285c100875c007190f3b42aa8e290f340cf3e50b6487e9`;
the test APK is
`43f732ee9276bc5fbf10ad291f8a5ff8dc2995df5d5a576bcf4ee7aafb4150fc`.
The root media fixture passed in 1.776 seconds with 256 allocation/close rounds
and zero measured native heap growth. The test package was removed,
supervision resumed and normal health passed in 27.077 seconds.

The existing signed-in browser showed a visually correct actual phone frame
after 1.588 seconds and received ten frames over 10.603 seconds without errors.
A warm opening reached `LIVE_FRESH` with zero reconnects. The task-owned browser
tab then recovered automatically from offline/online to `LIVE_FRESH` at sequence
86, reconnect count 1 and age 1,019 ms, with no errors. A normal reload reached
the warm view in 0.511 seconds and received ten frames in 9.462 seconds through
sequence 147, again without errors. These observations
prove the real encoder, native assembly, THF1 pipe and TSF3 browser path. The original observation did
not yet prove the natural 30-minute quiet-state expiry; the later proof below
closes that gap. The viewer closed at
10:41:03.475 UTC; at 11:09:50 the existing warm helper was still parked, before
that expiry deadline. It was not killed to manufacture cleanup proof.
Test-only follow-up `ea2e6df` closes the assembler in the pre-existing codec
buffer-reuse regression; its four tests pass and product source is unchanged.

## Boundary and provenance

Rust now owns bounded H.264 access-unit assembly, THF1 record validation/headers
and TSF3 packet validation/headers in the existing `pixel_health` library. Java
retains record objects, stream reads/writes and payload copying. MediaCodec,
Surface, capture, classification, command execution, network and browser
adapters remain their existing owners. No frame format or protocol version
changed. The former Java implementation is retained in Git at `3ec2e93` and is
materialized only into ignored differential-test output.

The root launcher supplies the native entry inside the same installed APK as
its CLASSPATH. It loads that entry directly without extraction or a separate
release artifact. Before implementation, the phone-owning root task ran the
test-only loader from clean `3ec2e93`. It passed in 1.748 s and matched the then
installed library SHA-256
`7adaad0b8cc5b3034310a1022ac48bb5a5c5dca14ba06cbacb4d7263d6545b10`.
The root removed the test package and resumed supervision afterward. This was
proof of the packaging/loading path, before the new media symbols existed.

Each native assembler allocation has one Java AutoCloseable owner. Calls and
close are synchronized, the handle is cleared before release, and operations
after close fail before JNI. The entire codec session uses try-with-resources,
so creation failure, normal end, interruption and teardown exceptions release
the native state. There is no global registry, finalizer or new worker.

## Local verification

- All 1,042 existing app tests passed with the native media implementation.
- Four existing assembler tests and four differential/lifetime tests passed
  together through real host JNI. The differential run compares golden packet
  bytes, key/delta flags, timestamp bounds, safe-integer limits, null/zero/maximum
  payloads, malformed headers, fragmented/zero-progress stream reads, every EOF
  position and the precedence of payload truncation over timestamp errors.
- The stateful comparison covers every split of both configuration formats,
  10,000 deterministic mixed fragment events, malformed/ambiguous framing,
  parameter-set caching, flags, overflow/discard recovery, exact final two-MiB
  boundary, reset, and closed-owner rejection.
- Fresh shared-library checks passed all 12 core-config, 45 health and 29
  supervisor tests. Product and Android test APK builds passed.
- Rust formatting, Clippy with warnings denied, shell syntax and Git whitespace
  checks passed. The packaged library matches the generated library; ELF and
  APK ZIP alignment checks passed at 16 KiB.

The library is 717,264 bytes, SHA-256
`4d0057f38443554d6b31bd33ccf9deb8030c17b727e9571443f044dd84bd8aa4`,
28,808 bytes larger than the configuration/persistence release. No dependency
or second native library was added for media.

## Measurements and limits

The local measurement covers a complete synthetic 64-KiB access unit through
assembly, THF1 write/read and TSF3 output. Bytes are compared before timing.
Separate fresh JVM first-call wall/CPU times were 22.514/12.185 ms for the former
Java owner and 5.564/5.580 ms for Rust. Across 500 alternating warm journeys:

| Implementation | Success | Wall p50 | Wall p95 | CPU p50 |
| --- | ---: | ---: | ---: | ---: |
| Former Java | 500/500 | 0.075 ms | 0.101 ms | 0.076 ms |
| Rust | 500/500 | 0.100 ms | 0.120 ms | 0.101 ms |

The approximately 0.026 ms warm median increase reflects the added JNI calls
and byte-array transfer for assembly. Header-only calls keep THF1/TSF3 payload
copying in the existing Java stream adapter. This is a measured local boundary
cost, not a phone-frame latency or CPU improvement claim. Real capture and
network latency, whole-phone memory and actual native-state reclamation remain
part of deployment acceptance below.

Reproduce with
`PIXEL_MEDIA_MEASURE=1 bash orchestrator/android-orchestrator/pixel-health/tests/run-media-parity.sh`.

## Required deployed acceptance

1. Build and deploy the clean committed product through the established scoped
   entrypoint, retaining the previous accepted release for rollback.
2. Run only `lv.jolkins.pixelorchestrator.app.ticket.TicketRootMediaInstrumentedTest`.
   Its root app_process fixture uses the production VM property and exact APK.
   It calls every media JNI interface with synthetic bytes, proves partial
   assembly/THF1/TSF3/error/reset behavior and performs 256 create/partial-buffer/
   close cycles. Native heap growth must remain below eight MiB after warm-up;
   failure to release each 512-KiB pending buffer would retain over 128 MiB.
   The fixture performs no capture, input, registration or network request.
3. Remove the test package and restore supervision via the existing null-action
   service path; require normal and saved health to agree.
4. Verify the existing signed-in Brave live viewer, cold capture/open, ongoing
   frames and reconnect through the actual root encoder, THF1 pipe, Android
   service, relay and browser TSF3 path. Preserve sessions and input fences.
   No new registration is required for this byte-processing scope.
5. Verify final quiet state and cleanup under the existing owner. Mark missing
   browser, physical-frame, reconnect, memory or cleanup evidence explicitly.

## Natural quiet, cold return and final cleanup

At 13:01:50 UTC the actual media release naturally reached client-disconnected
state: streaming false, clients zero, hardware encoder inactive, encoder and
stale-capture process counts zero, no wrapper processes, cleanup successful.
The action dark lease was inactive with its last software verifier proven. No
helper was killed or warm hold shortened to produce this result.

A new task-owned signed-in Brave viewer opened at 13:02:58.964 UTC from that
quiet state. A visually correct actual ViVi picture was observed within 15.213
seconds (an observation upper bound, not precise first-frame latency). The
page reached LIVE_FRESH, sequence 68, rendered visual age 1,906 ms, live media
and command recovery, and a live Spacetime connection. No JavaScript errors
were observed. The owned viewer closed at 13:04:10.119 UTC.

At 13:35:57.965 UTC the second cycle naturally reached final quiet state:
client_disconnected, streaming false, clients zero, inactivity remaining zero,
encoder inactive, encoder/stale-capture/wrapper counts zero, cleanup_ok. The
action dark lease was inactive with no failure and last verifier proven. This
was observed before any following supervisor deployment or cleanup action.
The regular user can read the evidence under the canonical ops checkout's
`output/rust-migration/pixel-media-health-1301.json` and
`output/rust-migration/pixel-media-final-health.json`. A task-created ADB
forward on tcp:51359 remains temporarily available for the next authorized
phone acceptance; its owner must remove it at task cleanup.

The standalone monitor continues to classify the unauthenticated health 401 as
a public-health failure although root/livez are 200 and the actual signed-in
viewer passes; that monitor outcome is not relabelled as a full pass. This
media acceptance establishes actual displayed capture and software cleanup,
not a new human observation of physical panel brightness.
