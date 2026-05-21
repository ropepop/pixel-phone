# RS/ViVi cron steward follow-up — 2026-05-21 12:44Z

## Scope

Follow-up to the RS/ViVi watchdog output that reported real-user RS QR failures/slow successes and current ticket bridge health.

## Current broker/analytics state

- Broker analytics endpoint was reachable from the production Docker network.
- Latest analytics generation checked: 2026-05-21T12:43:58Z.
- Persisted job count: 348.
- Totals: 217 succeeded, 125 failed, 6 canceled, 90 retried, 61 slow successes.
- Broker state at final check: queueDepth=0, no running QR job, currentOwner=ticket, ticketViewers=1.

## Real-user incident status

- @aldajo: historical failures and slow/retried successes were followed by real-user successes, latest seq 348 succeeded after 2 attempts. Status: recovered, latency/retry monitoring remains.
- @iamhdzs: historical phone/image-capture failures were followed by real-user successes, latest seq 346 succeeded after 1 attempt in 13.9s. Status: recovered.
- @mt_alinka: latest observed job seq 333 failed after 3 attempts with generated/no-delivered-result semantics. No later real-user success was found. Status: remains open for that actor until a later real-user retry succeeds.
- user:f82debf58b90: latest observed job seq 332 failed after 5 attempts with generated/no-delivered-result semantics. No username mapping or later success was found. Status: remains open.
- user:b4bfa48b2f72: failures were followed by a successful real-user job seq 342. Status: recovered, but slow/retry monitoring remains.
- user:7578ff473740: watchdog reported a historical slow/retried success, not a failed delivery. No later job was needed to close a failure; keep monitoring latency.

## Ticket/ViVi bridge incident during this run

During verification the bridge briefly regressed to needs_attention with stream capture blocked and ViVi classified as OTHER_VIVI_TAB/TICKET_LIST_WITH_CARD. A phone screenshot first showed ViVi loading/splash; after a bridge stop/start recovery the phone reached the ViVi ticket-detail view and health settled.

Recovery action used the deployed path only:

1. POST /api/v1/session/start returned active but did not settle the stale active session.
2. POST /api/v1/session/stop, short wait, then POST /api/v1/session/start forced a new wake/capture pass.
3. Final bridge health: sessionState=live, streamActive=true, streamVerdict=live, ticketState=live, viviState=TICKET_DETAIL.

The transient ticket-detail screenshot contained visible ticket/QR material and was deleted instead of being kept as durable evidence.

## Verification limitations

- Recent real-user RS image endpoints had already expired under the broker's short image TTL, so exact recent real-user PNGs were not available for visual reinspection.
- The earlier same-day deployed-fix report remains the durable visual RS semantics evidence: live smoke artifact plus Android source-app/ticket-flow log marker.
- Direct authenticated Brave verification was attempted read-only but could not be completed: background capture returned blank/zero-size Brave windows, and a read-only AppleScript tab query timed out. No browser windows were raised or modified from cron.

## Changes made in this follow-up

- No product code was changed or deployed.
- The steward skill reference was updated to document bridge stop/start recovery when session/start alone cannot settle a stuck active ticket session and no RS job is queued.
