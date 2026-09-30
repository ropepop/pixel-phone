# Pixel configuration and persistence Rust verification — 2026-09-29

The replacement is deployed and its Android restart and normal-service
acceptance passed. Local verification and the deployment observations supplied
by the root task that owned the phone lane are recorded separately below.

## Deployed acceptance

Release `rust-store-20260929` used clean product source `72df229`. Product APK
SHA-256 is `3dde80972ac3f0dc4a07458f8b9211f535f8eaab8ff78e998e4e87b1ea417c80`;
the test APK SHA-256 is
`dc4d36d7df1b20b3f4028bcd365eaec804d8542f4fc8a35107e7a56dc7275e1d`.
The scoped deployment took 59.113 s and normal health succeeded.

| Scenario | Expected | Observed |
| --- | --- | --- |
| Installed native store writes synthetic populated records | Exact bytes and private permissions | Write phase passed in process 24860 in 0.168 s; config 7,279 bytes, state 3,619 bytes. |
| Separate Android process reloads persisted records | Same records and serialized bytes after process restart | Read phase passed in process 24897 in 0.137 s; all fixture assertions passed. |
| Corruption, staging, aliases, masking and live-config preservation | Disposable damage defaults without repair; partial staging ignored; existing alias/masking contracts preserved | Both installed test phases passed; live config SHA-256 remained `73b2720705fb6a3e1c1eb3724ea3b6595e179f700ead3e93ab4e71f60455dec5`. |
| Restore the real owner after Android instrumentation | Resume supervision, then pass normal health | Null-action SupervisorService resumed; standard `rust-store-final-20260929` health succeeded in 26.311 s total. |
| Persisted live UI source | AppGraph-owned saved state reflects healthy service | Root/deployment/supervisor-loop/supervisor fields true; heartbeat epoch 1790676303. Live config/state both mode 0600 and app UID/GID 10205. |
| Cleanup | Test fixture/package and temporary physical helpers gone | Fixture and test APK removed; no keyboard or capture helper remained. |

The additional saved-state observation at epoch 1790676346 also reported
`moduleHealth.ticket_screen.healthy=true`, status `running` and listener `1`,
alongside true root/deployment/supervisor-loop fields.
No live state or secret file was exported. No phone input, registration or
capture implementation changed in this slice. These short acceptance timings
do not establish whole-phone memory or performance improvement.

## Ownership and compatibility

The existing `pixel_health` native library now owns StackStore UTF-8 file reads,
same-directory atomic replacement, legacy remote-key selection and inline DoH
token masking. Kotlin keeps its synchronized public store methods, DTOs and
JSON serialization. AppGraph keeps the existing app-private
`files/stack-store/orchestrator-config-v1.json` and
`orchestrator-state-v1.json` identities. The facade's root-config import,
supervisor state producer and support exporter remain the callers.

The comparison baseline is `d953ff9`. Missing, unreadable, malformed UTF-8,
malformed JSON and incorrectly typed data still load defaults without rewriting
the files. Native library or bridge failures remain visible instead of causing
a silent config reset. Writes retain 0600 permissions, reject malformed UTF-16,
preserve the destination on failure and replace a destination symlink rather
than overwrite its referent. Normal failures now remove only their own staging
file; process death can leave staging, which reads ignore. No new sweep, writer,
lock, schema, secret location or power-loss/fsync guarantee was introduced.

Alias decisions return key selections rather than round-tripping the entire
configuration through another JSON implementation. This preserves the caller's
formatting, unknown data and old serializer behavior, including its failure on
an out-of-range floating-point value when rewriting an alias.

## Local checks

- All 15 core-config tests passed: six existing tests, six persistence tests and
  three differential tests. Alias comparisons cover 2,025 combinations plus
  malformed/lenient/duplicate-key cases; masking includes Unicode blank strings.
- Populated config/state writes match old serialized bytes. Separate fresh JVMs
  load the saved records. Concurrent readers see only complete old/new versions.
- A real process file-size limit interrupts a write after at most 128 KiB. The
  native write reports `IOException: store write failed: file too large`, the
  former state remains byte-identical and a fresh process reloads it. A partial
  orphan is ignored and preserved rather than swept by another writer.
- Missing-library, denied-write, destination-directory, malformed UTF-16,
  invalid UTF-8 and unreadable-file checks passed. No production test seam was
  introduced.
- All 45 existing health tests plus four local differential health tests passed.
  The actual-device health sample comparison was explicitly skipped in this
  local run; the earlier health rollout report records its previous acceptance.
- All 29 supervisor and 1,042 app unit tests passed.
- Product APK and Android test APK built. Formatting, Clippy with warnings
  denied, shell syntax and Git whitespace checks passed.
- The packaged library equals the generated library. ELF load segments and APK
  ZIP packaging passed 16-KiB alignment checks.

The native library is now 688,456 bytes, SHA-256
`7adaad0b8cc5b3034310a1022ac48bb5a5c5dca14ba06cbacb4d7263d6545b10`,
an increase of 108,736 bytes over the health-only library. The established
tempfile library implements safe private staging and replacement; no additional
native library or framework is packaged.

## Measurements

Measurements use disposable local files and the complete config-plus-state
save/read journey. Fresh JVM first-call wall/CPU times were 46.588/43.027 ms for
the old implementation and 40.563/40.312 ms for Rust. Across 250 alternating
paired warm journeys, each implementation succeeded 250/250 times:

| Implementation | Wall p50 | Wall p95 | CPU p50 |
| --- | ---: | ---: | ---: |
| Old Kotlin | 0.553 ms | 0.751 ms | 0.551 ms |
| Rust | 0.469 ms | 0.609 ms | 0.469 ms |

These are local supporting measurements, not phone memory, CPU or performance
claims. Device whole-application resource comparison remains unverified.

## Reproducing device acceptance

Use the clean committed product through the established scoped deployment.
Run `StackStoreInstrumentedTest` with `store_phase=write`, then
`store_phase=read` in a separate instrumentation invocation. Require distinct
process IDs, exact populated records/serialized bytes, 0600 permissions,
unchanged live configuration and fixture removal. The fixture writes only
synthetic data in its own app-private directory. It performs no root action,
gesture or registration.

After instrumentation, restore supervision through the existing null-action
SupervisorService path, then require normal health and the AppGraph-owned
saved-health fields to agree. Remove the test package and verify final quiet
state. The native README documents cleanup if a test is interrupted.

Reproduce local comparisons with
`PIXEL_STORE_MEASURE=1 bash orchestrator/android-orchestrator/pixel-health/tests/run-store-parity.sh`.
The old sources are fetched from Git into ignored build output and are not
retained as a second production owner.
