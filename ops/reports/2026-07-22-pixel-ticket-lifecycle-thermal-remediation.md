# Pixel Ticket Lifecycle Thermal Remediation - 2026-07-22

## Outcome

The sustained warmth was caused by two stuck Ticket start/stop lifecycle paths, not by a browser continuously watching the phone.

Two exact `tr \000` command readers had been running for about 12.5 hours. Each was consuming roughly one full CPU core while attached to a stuck `pixel-ticket-start.sh` or `pixel-ticket-stop.sh` wrapper and reading a stale `/proc/.../cmdline` descriptor. At the same time, live state showed zero viewers, zero video clients, no active encoder, and hardware H.264 available but inactive. That rules out a continuously live Ticket stream as the cause of the sustained load.

Charging and recent streaming can add ordinary short-lived warmth, but they do not explain two long-lived helpers continuously consuming this much CPU. The defect was in the current Ticket lifecycle support path, not in ViVi ticket rendering or normal idle streaming.

The two processes were revalidated by exact identity and ownership before they were frozen and stopped. No lifecycle lock directory remained afterward.

## Baseline Evidence

The pre-repair health-monitor record is [`ops/evidence/ticket-health-monitor/20260722T143857Z/summary.json`](../evidence/ticket-health-monitor/20260722T143857Z/summary.json).

At `2026-07-22T14:38:57Z` it reported:

- overall status `degraded`, with the single failure `pixel_ticket_lifecycle_stuck`;
- two stuck lifecycle helpers and two stuck Ticket start/stop wrappers;
- oldest age 45,228 seconds;
- no active viewer, zero video clients, no running encoder, and inactive hardware H.264;
- battery temperature 37.8 C and thermal status 1;
- 79.8% memory use and 17% data-disk use.

This isolates the abnormal heat source from normal phone memory, storage, and live-stream activity.

## Repair Scope

Twelve implementation, test, and architecture files were changed or added before this report. The scope is limited to Ticket lifecycle safety, trustworthy desired-state reporting, health detection, regression coverage, and the matching architecture notes.

1. `orchestrator/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-ticket-lifecycle-lock.sh`
   - Lock-owner command-line inspection is bounded to one second through the configured timeout helper, the system `timeout`, or `/system/bin/timeout`.
   - A dead owner or a live PID that has been reused by an unrelated process may be recovered only after the owner record is reread unchanged.
   - An unreadable or timed-out owner that is still alive keeps the lock conservatively.
   - An optional process-root override lets the regression test reproduce the blocking-reader case without changing the production default.
2. `orchestrator/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-ticket-stop.sh`
   - A successful stop now exits successfully and an actually listening runtime still exits as a failure.
3. `orchestrator/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketSpacetimeWorker.kt`
   - Every successful Spacetime client connection invalidates the old desired-state cache.
   - Commands remain first priority, then the worker performs a canonical desired-state refresh when entering the active stream lane.
   - If the relay's asynchronous desired-state write has not landed yet, a false or missing first value gets at most four one-second delayed follow-up reads. A true value, leaving the active lane, or reaching the cap stops them, so stable 75-millisecond cycles do not become desired-state polling.
   - Every successful refresh forces the next phone report to use the new value.
4. `orchestrator/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketSpacetimeDesiredRefreshState.kt`
   - A small state object makes reconnect, active-lane entry, command deferral, cache invalidation, delayed negative follow-up, and the hard retry cap explicit and testable.
5. `orchestrator/android-orchestrator/app/src/test/kotlin/lv/jolkins/pixelorchestrator/app/ticket/TicketSpacetimeDesiredRefreshStateTest.kt`
   - Reproduces idle-to-command-to-live, reconnect-to-live, and live-lane re-entry, including the adverse ordering where the first live read is false before a delayed true value arrives.
   - Protects command-first ordering, phone-report invalidation, the one-second delay, and the hard follow-up cap.
6. `tools/observability/ticket_health_monitor.py`
   - Monitor version 4 detects exact Ticket start/stop shells and their exact linked command readers after 60 seconds.
   - An exact reader reparented to init remains detectable.
   - The saved result contains only the two counts and oldest age. It never stores process arguments.
   - A stuck lifecycle raises `pixel_ticket_lifecycle_stuck`; missing or malformed lifecycle collection fails closed instead of producing a false healthy result.
7. `tools/observability/tests/test_ticket_health_monitor.py`
   - Adds coverage for safe aggregation, degradation, orphan detection, strict validation, and collection failure.
8. `orchestrator/android-orchestrator/runtime-tests/test_ticket_lifecycle_lock_recovery.sh`
   - Reproduces a blocked command-line reader and proves it returns within the bounded window while retaining the live owner safely.
9. `orchestrator/android-orchestrator/runtime-tests/test_deployment_speed_healthy_path_contract.sh`
   - Protects the successful-stop exit contract.
10. `orchestrator/android-orchestrator/app/src/test/kotlin/lv/jolkins/pixelorchestrator/app/RuntimeEntrypointSpeedSourceTest.kt`
   - Protects the same packaged stop behavior in the Android asset tests.
11. `docs/architecture/PIXEL_STACK_ARCHITECTURE.md`
   - Records the bounded, conservative lifecycle ownership and cleanup rules.
12. `docs/architecture/TICKET_STREAMING_ARCHITECTURE.md`
   - Records the monitor's 60-second lifecycle threshold and safe persistence, plus the edge-triggered and capped desired refresh that keeps active phone reports aligned without adding a hot-loop read.

### Successful-stop defect

The stop script had a separate reliability defect: its final conditional test returned status 1 even when the runtime had stopped correctly. That could make a successful shutdown look like a failure to callers. The final check is now explicit: it returns failure only if the runtime is still listening, and otherwise returns success.

## Local Verification

All completed local checks passed:

- shell syntax passed for all four relevant lifecycle and runtime test scripts;
- lifecycle lock recovery passed, including the real blocking-reader case;
- deployment-speed and runtime-freshness contracts passed;
- all five expected Ticket runtime assets were present;
- Python compilation passed;
- all 36 health-monitor tests passed;
- all eight focused desired-refresh regressions passed, including delayed true recovery, command deferral, the retry cap, lane exit, and re-entry;
- all 549 Android application tests passed across 67 suites;
- the final clean offline Android test build completed in 15.58 seconds;
- repository whitespace and patch integrity checks passed.

Only existing Gradle deprecation warnings remained; no test or build failure was hidden.

## Device Deployment And Reliability Verification

The lifecycle repair was first deployed to the configured Pixel through the canonical Ticket Screen component redeploy. That deployment run, `20260722T144409Z-15424`, exited successfully.

Post-deployment checks proved:

- all five deployed runtime inputs were fresh;
- the phone's lifecycle helper matched the local repaired asset;
- three complete stop/start/health cycles succeeded;
- every stop completed in 1 second;
- every start completed in 4 seconds;
- health returned ready in 4 to 5 seconds;
- every cycle left zero lifecycle wrappers and zero command-reader residue.

A controlled full phone reboot then completed in 66 seconds. Ticket was healthy as soon as boot completion was reported, its persistent service remained enabled, and there was no lifecycle residue. The idle stream stayed stopped with hardware H.264 available but inactive.

The post-reboot monitor at `2026-07-22T14:54:57Z` reported `healthy_idle` with no failures:

- zero stuck helpers and zero stuck start/stop wrappers;
- battery temperature 33.3 C, down 4.5 C from the degraded sample;
- thermal status 0;
- memory use 72.8%;
- data-disk use unchanged at 17%;
- public root HTTP 200 and unauthenticated health correctly protected with HTTP 401.

The first authenticated live verification then exposed a separate desired-state reporting race: every real stream layer was live, but the Pixel kept publishing a fresh cached `desiredActive=false` value while its fast command lane was active. The monitor correctly rejected that mismatch. The producer was repaired rather than weakening the monitor, and the first reporting-correction APK was deployed as release `local-20260722T163004Z`, run `20260722T163004Z-1807`.

That deployment completed in about 30 seconds. Its build and install succeeded, all five Ticket runtime assets were fresh, and its immediate post-deploy monitor was `healthy_idle`.

A final independent review then found an adverse ordering that the successful live pass had not forced: the relay publishes desired viewer state asynchronously while it immediately wakes the phone path, so the first hot-edge read could still observe the old false value. The worker now keeps the immediate edge read but, only when that value is false or missing, permits at most four one-second delayed follow-ups. Commands remain first; a true value, leaving the live lane, reconnecting, or reaching the cap stops the follow-ups. This adds at most four reads per live entry rather than polling the database every 75 milliseconds.

The corrected final APK was deployed as release `local-20260722T170713Z`, run `20260722T170713Z-30899`. The canonical fast Ticket redeploy completed successfully in 30.247 seconds, including build, install, action proof, and freshness checks. All five Ticket runtime assets were fresh. The installed APK SHA-256 exactly matched the local build, the package update time was `2026-07-22 20:07:26` local, and the immediate monitor at `17:08:22Z` was `healthy_idle` with no failures.

## Upstream Maincloud Interruption And Recovery

At about `2026-07-22T15:28Z`, after the phone repair and reboot checks had passed, the production SpacetimeDB Maincloud path began returning HTTP 502. The Ticket sidecar reported disconnected and unsubscribed, and independent SQL probes failed for Ticket, operational logging, and the unrelated Train database. The cross-service failure shows this was a broad upstream dependency outage, not a phone regression or a result of this patch.

Production containers remained running, so container health alone was not accepted as user-flow proof. No local database failover exists, and no server restart or unrelated deployment was used to mask the upstream fault.

Seventeen consecutive checks from `2026-07-22T15:45:07Z` through `15:53:13Z` reproduced the upstream HTTP 502. Recovery then occurred without local intervention:

- at `15:53:43Z`, the Ticket sidecar was connected, subscribed, and free of errors;
- at `15:55:01Z`, a safe Ticket database read succeeded again;
- at `15:55:55Z`, central operational logging recorded a successful post-recovery write.

The live browser verification began only after these independent dependency checks had recovered.

## Authenticated Browser-To-Phone Experience

The production flow passed in both browser paths after Maincloud recovered.

In the dedicated Chrome verifier:

- the authenticated production page rendered the current `arrow` interface and was confirmed live within six seconds of navigation;
- the canvas painted at 720 by 1,482 pixels, the loading indicator was hidden, and the browser reported a rendered live frame;
- the old claim-dialog identifiers and class were absent;
- the visible control-code button opened the dialog, accepted a four-digit test entry, and submitted through production to the phone;
- the returned phone frame was visibly painted in about 12.1 seconds, below the 15-second acceptance limit;
- the browser proof recorded an accepted phone-visual result, a candidate frame at or after the phone marker, trusted post-submit phone proof, and a non-provisional generated-code result;
- closing the result through the visible interface restored the live ticket, hid the result, and left the request button and hotspot available, proving that no request still occupied the queue.

The corresponding live phone snapshot at `15:55:52Z` showed one viewer, one video client, one encoder, active hardware H.264, live Ticket/session state, and zero stale capture processes. A broader sample at `15:56:44Z` measured a 138-millisecond frame age, 28.7 C battery temperature, thermal status 0, and zero lifecycle residue. All live browser, relay, phone, encoder, and frame-freshness surfaces agreed. The standalone monitor nevertheless marked that first sample degraded because the Pixel's freshly written Spacetime phone report still carried cached `desiredActive=false`. That mismatch led to the edge-triggered desired refresh and capped delayed negative follow-up described above.

The user-facing proof was then repeated through Computer Use in the existing Brave profile. A fresh normal sign-in completed, the public page left authentication/loading, and the visible browser window showed the live ticket image and the control-code button. The normal Brave profile and its unrelated tabs were preserved.

After the first reporting correction was deployed, the real viewer was repeated on release `local-20260722T163004Z`. The monitor at `2026-07-22T16:34:04Z` returned `healthy_live` with no failures: canonical desired state true, one viewer, Pixel phone report desired true and streaming, one relay client, live Pixel/session/ticket state, active encoder and hardware H.264, 93-millisecond frame age, 28.2 C battery temperature, thermal status 0, and zero lifecycle residue.

Because the correction shares the fast command worker, the visible control-code roundtrip was also repeated on that build rather than inferred from tests. Brave opened the dialog, submitted a fresh four-digit test entry, visibly painted the generated phone result, and returned to the ordinary live ticket with the request button available. A simultaneous monitor remained `healthy_live`. The temporary result screenshot was deleted immediately and the digits are not retained.

The complete user flow was then repeated again on corrected final release `local-20260722T170713Z`. The dedicated Chrome runtime completed a fresh normal magic-link sign-in, returned to the production Ticket page, left loading, and painted the live phone frame on its 720 by 1,482 canvas. The monitor at `17:14:51Z` was `healthy_live` with no failures: canonical desired true, one viewer, phone report desired true and streaming, one relay video client, live Pixel/session/ticket state, active encoder and hardware H.264, a 185-millisecond frame age, 27.8 C battery temperature, thermal status 0, and zero lifecycle residue.

On that exact final build, the visible control dialog accepted a fresh four-digit test entry, production delivered it to the phone, and the browser visibly painted the returned generated-code phone result. Closing the visible result and dialog restored the normal live ticket canvas with the request button enabled, proving the request no longer occupied the queue. The temporary visual proof was deleted immediately; the test digits, raw ticket image, one-time sign-in link, and authentication material are not retained in this report.

Only the temporary Ticket and sign-in tabs were closed. The dedicated Chrome verifier was returned to a blank tab, and Brave had already returned to the user's pre-existing tab. On the pre-final reporting build, the full control path had settled by `16:09:01Z` to zero desired viewers, zero relay clients, an idle Pixel stream, inactive encoder and H.264, no pending stream command, no occupying control request or cleanup, and zero stuck lifecycle processes. Release `local-20260722T163004Z` reached the same gate at `16:49:25Z` after its repeated control-code roundtrip. The corrected final build then passed that full gate after its `17:14Z` live/control pass, before any final cooldown time was credited.

The temporary screenshots used for visual inspection were deleted immediately. The test digits, raw ticket image, authentication links, and tokens are not present in this report or retained evidence.

## Final Post-Viewer Cooldown

The first complete post-viewer watch, before the desired-report correction, ran from `16:09:52Z` through `16:29:51Z` with 41 samples and no violations:

- CPU averaged 11.3% and peaked at 21.6%;
- battery temperature fell from 29.1 C to 27.8 C;
- Android thermal status remained 0 in every sample;
- the Ticket stream, encoder, hardware H.264, viewer/client counts, pending control work, and lifecycle residue stayed fully idle/zero.

This independently confirms the thermal repair. A later watch on release `local-20260722T163004Z` was intentionally stopped at 14 minutes when the final source-ordering review required one more code correction; its 29 accepted samples were all idle, thermal 0, 28.9 C down to 27.8 C, with 11.52% average and 21.54% peak CPU, but it is not counted as final acceptance.

The exact-final acceptance watch for corrected release `local-20260722T170713Z` ran uninterrupted from `17:22:16Z` through `17:42:20Z`, after the final live viewer and control-code roundtrip. It completed the full 1,200-second requirement with 41 state samples and 40 CPU intervals:

- whole-phone CPU averaged 11.37% and peaked at 24.30%;
- battery temperature fell from 28.3 C to 27.5 C and never exceeded 28.3 C;
- Android thermal status was 0 in every sample, including the complete final ten-minute window;
- canonical desired state stayed false with zero viewers;
- relay clients stayed zero and the relay remained idle/disconnected;
- the phone report stayed desired-false and idle/disconnected;
- pending stream commands and occupying or cleanup control requests stayed zero;
- the local stream, encoder, and hardware H.264 stayed inactive with zero clients, encoder processes, and stale capture processes;
- lifecycle helper and start/stop wrapper counts stayed zero.

The installed APK hash still matched the local final build after the watch. The closing repository monitor at `17:42:52Z` returned `healthy_idle` with no failures; its only warning was the expected limitation that a second physical standby Pixel is not configured. No raw ticket content was retained.

## Privacy And Configuration Boundaries

- No raw ticket image, control code, authentication link, token, private process argument list, or broad device log was written to this report or the health-monitor evidence.
- No raw screenshot from private ticket content was retained.
- No SSH, VPN, wake-lock, brightness, phone configuration, or application secret was changed.
- No normal Chrome or Brave cookies, local storage, or saved browser profile was cleared.
- The full Computer Use service and the dedicated Chrome automation runtime were restarted so desktop verification could resume cleanly. If macOS is locked, its security boundary still requires a manual unlock before automation can continue.
- The exact-final browser rerun used that restarted dedicated Chrome runtime through its existing debugging connection because the bundled Chrome control bridge was not exposed in this tool session; the user's normal Chrome profile was not substituted or modified.
- Peekaboo was not used for the verification workflow. The operating path is Computer Use for desktop applications and the dedicated Chrome integration for browser work.

## Completion State

Complete. The heat source has been isolated and removed, the lifecycle and successful-stop defects are repaired, the desired-state reporting race and its asynchronous edge case are corrected without adding hot-loop polling, and the monitor will detect recurrence without retaining sensitive process details. All local tests pass, the corrected final APK is deployed and hash-verified, three lifecycle cycles and a full reboot passed, the exact-final `healthy_idle`, `healthy_live`, and visible control-code roundtrip passed, and the exact-final 20-minute cooldown closed `healthy_idle` with normal thermal status throughout.
