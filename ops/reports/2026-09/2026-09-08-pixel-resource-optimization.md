# Pixel resource optimization, 8 September 2026

Status: implemented, deployed and live-verified. Warm-session expiry and settled-idle comparison were excluded at the user’s request. RAM savings were not demonstrated.

## Scope and baseline

The approved pass covers our Pixel services and demonstrably retired installations. Picture quality, frame freshness, responsiveness, the thirty-minute page-opening warm hold, and safety checks remain unchanged. Brightness and physical-touch testing remain excluded as requested in the preceding Ticket acceptance.

Baseline source and installed Ticket release: Pixel commit `4b81977`. The checkout was clean before this work. Server/browser release remains `ticket-v2-v181-20260907-r5`; no server or database changes are planned.

Three verified sixty-second active windows delivered 60, 59, and 60 frames, with fresh final-frame ages of 272, 725, and 834 ms. Capture used 41.82–42.36% of one CPU core; the orchestrator used 20.31–22.81%. The separate Android video-codec service used 38.39–40.97%. Whole-phone CPU varied from 31.67% to 40.44% of eight-core capacity. This variability and transient system processes make whole-phone figures less useful than matched component measurements.

Three warm/no-active-viewer windows retained the loaded capture helper without producing new frames. The relay connection remained present, so its client count alone did not identify active viewing. Capture used approximately 0.4% of one core, while orchestration used 13.81–14.58%. The samples record memory, temperatures, pressure, and observation overhead. An initial mixed-state measurement is retained separately and excluded from matched comparisons.

A separate thirty-second native CPU profile recorded 7,373 samples with none lost. The video-codec hotspot was RGBA-to-YUV conversion. Shell processes, formatting commands, and report parsing were another substantial cost. The profiler issued a non-fatal trace-file warning but completed recording and reporting. Only the sanitized summary is retained; the device profile was deleted.

Initial disk allocation: approximately 6.27 GiB under the managed runtime, 92.17 GiB available on `/data`, approximately 1 MiB runtime logs, and a 44 KiB superuser-history database. The 7 September scheduled cleanup completed with zero failures and no eligible artifacts under its thirty-day retention policy.

## Changes

- Orientation maintenance and Ticket startup share the existing verify-and-repair transaction instead of always rewriting settings and then performing a separate check. Its ten-second maintenance interval is unchanged.
- A shared readable-picture copy was implemented and tested, then rejected after deployment: the first three active samples used 120.5–145.1 MiB for capture versus 93.7–101.4 MiB before, for only a small CPU reduction. Commit `f8aa35c` restores the original picture ownership and copy behavior. The rejected measurements are retained separately and excluded from final comparisons.
- General health omits disabled workload probes and converts its management report in one pass instead of repeatedly launching formatting and filtering pipelines. Existing report fields, first-value semantics, literal data handling, and failure defaults are preserved. Live validation caught that the parser still required sections deliberately omitted for disabled services; parsing now follows the same configuration and retains strict checks for enabled and always-required sections. A regression test covers both cases.
- The old cleanup option alias, deprecated GitHub-release command, and unused Pi-hole bootstrap-copy fallback are removed. Current cleanup callers already use `--frequent`; normal bootstrap already excludes DNS artifacts.

## DNS retirement evidence

The live configuration disables DNS. No AdGuard Home, Pi-hole FTL, or dnsmasq process was found. Neither DNS environment appeared in any inspected process mount namespace, process root, working directory, executable, or open file. No DNS listener or boot-hook reference was found. The installed runtime manifest contains only two platform artifacts and no DNS references. Current bootstrap supplies no rootfs artifact, and the active component registry excludes DNS.

Retirement was restricted to the two retired chroots, their dedicated old configuration files, and five old DNS entrypoints. Active and rollback application releases, platform artifacts, SSH, VPN, and unrelated configuration are retained. A recovery archive containing 73,079 entries was verified against the device SHA-256 before deletion. A failed first transfer was rejected. Retirement completed for exactly eleven roots; the temporary device archive was removed, and the local restricted recovery copy is retained. The mirror was pulled again and now contains 35 managed files. Runtime allocation fell from 6,571,266 KiB to 2,800,298 KiB, a reduction of about 3.60 GiB. SSH management health remained successful.

## Verification and closeout

Final local checks passed: 447 app tests in each of debug and release, 45 health tests, 6 configuration tests, 13 installer tests, 10 root-execution tests, and 29 supervisor tests. Debug/release assemblies and release lint passed. Runtime cleanup and platform-only bootstrap contract checks passed.

The user subsequently excluded waiting for warm-session expiry. Settled-idle comparison and natural shutdown observation are therefore omitted; the thirty-minute product behavior remains unchanged.

The final implementation is committed at `8ff29de`; its installed APK matches the tested local build byte for byte. The final deployment was `20260908T061214Z-14369`, followed by a successful full health action in validation run `20260908T061313Z-2868`. The existing supervisor loop was resumed after APK replacement and its fresh heartbeat and healthy status were separately verified. SSH, VPN, management, Ticket and cleanup are healthy; disabled components remain disabled. The server remains on `ticket-v2-v181-20260907-r5`.

Three matched active windows, taken after supervision resumed and before physical actions, measured whole-phone CPU at 27.15%, 29.59% and 27.15% versus 32.36%, 40.44% and 31.67% before. Means were 27.96% after and 34.82% before. These are observational whole-phone measurements, not a controlled attribution of every saved CPU cycle. Capture CPU was essentially unchanged at 42.75% of one core versus 42.16%; orchestration averaged 20.66% versus 21.31%. The windows produced 59, 60 and 59 frames, preserving the approximately one-picture-per-second cadence. Thermal status was normal.

RAM savings were not demonstrated. Matched combined resident proportional memory for capture and orchestration was 188–216 MiB after versus 166–178 MiB before. The picture-copy experiment was removed; capture code is restored to its baseline implementation, and higher resident readings also occurred after that restoration, so the readings do not establish the discarded copy change as their cause. No sustained memory pressure was observed. No cache clearing, quality reduction or forced memory reclamation was used to improve the numbers.

The signed-in Brave page displayed the live ticket. Following deployment interruption, the visible reconnect button restored live pictures in 365 ms and controls in 1,024 ms. One real browser slider drag produced a successful, visually proved registration in 12.61 seconds. A control-code request displayed the exact requested digits in HDR; dismissal returned to the registered live ticket. The durable code result acknowledged capture and completed phone cleanup. No keyboard checkpoint, action journal entry, active panel lease, running/queued action, pending stream command or pending code cleanup remained. Maintenance is off. Brightness and physical-touch interruption were not tested.

The retained implementation has 154 fewer production-source lines: 255 removed and 101 added, excluding tests and documentation. Reports are normal-user-owned and readable; the verified recovery archive remains user-owned with restricted permissions. The mirror audit is clean.

The active memory observation completed 15 one-minute windows (900.08 seconds). Combined resident proportional memory remained within 188.4–216.4 MiB; it started at 191.9 MiB and ended at 216.3 MiB, with comparable high points earlier in the run. This bounded observation does not prove absence of all leaks or a RAM improvement. All thermal samples were normal, one encoder remained active, and the final picture age was 159 ms. The page was live with a fresh registered-ticket state and no visible code result when this task’s tab was closed. The normal warm session was left alone; there was no wait for it to expire. All eleven retired roots remained absent after deployment and maintenance.

Detailed sanitized measurements are under `output/resource-pass-20260908/`. Recovery material is separately restricted under its `recovery/` directory and must not be included in shared reports or public syncs.
