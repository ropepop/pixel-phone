# RS Batch Rehaul Verification - 2026-05-23

## Finishing Criteria

- A three-code RS burst must produce a QR image or a named final failure for every request within 15 seconds of enqueue.
- RS must run as one phone-side batch, not three independent phone jobs.
- Ticket viewers and ticket leases must keep priority over RS, leaving unfinished RS work waiting without burning attempts.
- Authenticated `ticket.jolkins.id.lv` must still load a live ViVi ticket in 5 seconds or less.
- Stream freshness must stay inside the live-frame threshold.

## What Changed

- Broker RS jobs now use the batch phone command for both single and burst requests. The public QR job API stayed unchanged.
- Pixel processes an RS batch request-by-request inside the RS app and emits one result per request before any ViVi cleanup.
- RS no longer uses the shared ViVi control-code health bucket as result truth. Broker reconciliation uses RS-specific batch health.
- Public ticket priority remains above RS: active viewers/leases pause RS instead of letting RS steal the phone from ViVi.
- RS proof now re-registers every code and treats a mismatched post-submit control ticket as transient until bounded proof expires.
- RS secure screenshot capture still uses the secure Pixel screen source, but the final artifact is encoded at a bounded readable width so successful QR captures do not consume the whole batch budget.

## Automated Checks

- `./gradlew :app:testDebugUnitTest --rerun-tasks`
- `go test ./... -count=1` in `workloads/phone-broker`
- `go test ./... -count=1` in `workloads/rigassatiksme-qr-bot`
- `go test ./... -count=1` in `workloads/ticket-remote`

All checks passed.

## Deploys

- Pixel Android orchestrator debug APK was rebuilt, reinstalled, and the ticket service was restarted on the rooted Pixel.
- Broker was deployed to Arbuzas with release `20260523T154758Z`.
- Post-deploy service check showed `phone_broker`, `ticket_phone_bridge`, `ticket_remote`, tunnels, and bot services running.

## Final Live RS Burst

Evidence:

- `ops/evidence/rs-batch-rehaul-20260523T154758Z/live-rs-burst-after-stale-tight.json`
- `ops/evidence/rs-batch-rehaul-20260523T154758Z/pixel-health-after-stale-tight-burst.json`

Run id: `codex-post-stale-tight-20260523T1556`

| Code | Final outcome | Enqueue to final | Pixel per-job phase |
| --- | --- | ---: | ---: |
| `68803` | `code_rejected_by_rs` | 3.99 s | 3.731 s |
| `58011` | `rs_monthly_ticket_stale_code` | 7.76 s | 3.720 s |
| `27515` | `code_rejected_by_rs` | 14.06 s | 6.329 s |

Result: all three requests left waiting state with named final outcomes under 15 seconds. Pixel health showed one completed RS batch with three completed jobs and no generic `phone_timeout`.

## Public Ticket Verification

Evidence:

- `ops/evidence/rs-batch-rehaul-20260523T154758Z/brave-ticket-final-fresh/rs-rehaul-final-fresh-1605-summary.json`
- `ops/evidence/rs-batch-rehaul-20260523T154758Z/brave-ticket-final-fresh/rs-rehaul-final-fresh-1605-initial-page.png`
- `ops/evidence/rs-batch-rehaul-20260523T154758Z/brave-ticket-final-fresh/rs-rehaul-final-fresh-1605-reload-page.png`
- `ops/evidence/rs-batch-rehaul-20260523T154758Z/brave-ticket-final-fresh/stale-public-paths.json`
- `ops/evidence/rs-batch-rehaul-20260523T154758Z/brave-ticket-final/rs-rehaul-final-1556-summary.json`
- `ops/evidence/rs-batch-rehaul-20260523T154758Z/brave-ticket-final/rs-rehaul-final-1556-initial-page.png`
- `ops/evidence/rs-batch-rehaul-20260523T154758Z/brave-ticket-final/rs-rehaul-final-1556-reload-page.png`
- `ops/evidence/rs-batch-rehaul-20260523T154758Z/brave-ticket-final/stale-public-paths.json`

Authenticated Brave verification at `ticket.jolkins.id.lv`:

| Check | Live load | Relay frame age | Result |
| --- | ---: | ---: | --- |
| Initial load | 0.660 s | 239 ms | live ViVi ticket |
| Reload | 0.427 s | 230 ms | live ViVi ticket |

The served public script was still `ticket-remote-2026-05-23-pixel-only-hardware-control-capture-frame-fallback-v98`. Stale public-viewer paths checked clean: `claimDialog`, `showModal`, `claim-dialog`, `confirmClaim`, and the old private-control warning did not appear in the served public page/scripts.

## Ticket Priority Check

During a controlled authenticated public viewer hold:

- Broker owner stayed on `ticket`.
- A new RS job stayed `waiting`.
- RS attempts stayed at `0`.
- No phone batch was running while the public ticket viewer owned the phone.

Evidence:

- `ops/evidence/rs-batch-rehaul-20260523T154758Z/priority-check-after-final.json`

This confirms the broker still pauses RS for active ticket viewers instead of letting RS preempt ViVi.

## Notes

- The final RS live check used direct broker jobs for repeatable timing and phone/broker verification. Bot delivery was covered by the QR bot tests, including waiting messages and named failure propagation.
- An intermediate post-deploy run missed the 15-second target when successful QR captures consumed too much of the batch window; that led to the bounded secure-capture artifact and post-submit empty-snapshot fixes above.
- The final RS outcomes were named failures, not QR successes, because the supplied reusable codes were rejected or proved stale by RS during this run. That still satisfies the timing contract because clear final failures count; generic phone timeouts do not.
