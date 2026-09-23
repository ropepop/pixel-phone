# Ticket monitoring phone release

The Pixel portion of owner-controlled Ticket monitoring was deployed on 22 September
2026 at 02:36 Europe/Riga. Monitoring remained off throughout deployment, then was
temporarily enabled for passive verification and returned to off.

## Change

- Reuse the existing classifier and SpacetimeDB connection. Publish health separately
  from the short-lived permission to act on a ticket.
- Idle checks use one bounded capture in the existing helper, before constructing an
  encoder. They do not wake, navigate or tap the phone. Existing phone-action and
  session ownership serialize capture with actions and cold shutdown.
- Enable epochs, current phone sessions, monotonic report sequences and database-anchored
  observation times reject stale reports. Busy work and physical touch override cached
  classifications. Reconnecting or enabling requires fresh evidence.
- Routine checks produce no operational events; failures retain existing diagnostics.

## Deployment and verification

- Device: Pixel 9a (`tegu`), authenticated SSH at `100.76.50.43:2222`.
- Source commit: `3b42cc52845f66bf0ae875d9f190d2dcd455c5ee`, clean at build time.
- Release: `20260921T233506Z-20394`.
- Served version: `ticket-stream-2026-09-22-monitoring-v391`.
- Command: `./tools/pixel/redeploy.sh --scope ticket_screen --profile standard --transport ssh --ssh-host 100.76.50.43`.
- The standard deployment completed successfully in 80.4 seconds. The installed APK
  hash matches the built APK, and all five owned runtime assets are current.
- Both Android variants passed 513 tests each. Shared modules passed 103 tests.
- Post-deployment: no stream, viewers, encoder, stale capture process, secure-window
  capture bypass, panel-protection lease, control-code request or reauthentication work.
- Host-mirror audit and working-tree checks passed. Generated outputs are owned by the
  regular workspace user. The previous v390 APK is retained locally for rollback.

The existing uncertain navigation result was already terminal before deployment. No
pending command existed, and no navigation, registration, recovery tap or ViVi reset
was issued during this work.

Deployment evidence: `output/pixel/redeploy/20260921T233506Z-20394/summary.json` and
`monitoring-post-health.json` in that directory. Stable behavior is documented in
`docs/architecture/TICKET_STREAMING_ARCHITECTURE.md`.

## Live passive monitoring verification

The coordinating implementation task enabled monitoring through the owner control at
2026-09-21 23:38:45.999 UTC. The first database health observation was ready at
23:38:47.464 UTC, sequence 1. The next was ready at 23:43:52.953 UTC, sequence 2:
305.489 seconds later. Twelve phone samples over 331 seconds all showed the stream
off, the encoder off and zero viewers. The successful routine check added no shared
operational-log event.

Restarting the web service and its database sidecar preserved the ready state and
last-check time. Monitoring was then disabled at 23:45:19.317 UTC. These times and
the twelve-sample comparison were supplied by the coordinating task's live checks;
they are separate from the phone deployment checks above.

A subsequent direct phone check confirmed v391 healthy and idle, with zero capture
helpers, encoder processes, stale capture processes, viewers or active input/cleanup
leases. Secure capture settings were restored. The final host-mirror audit passed.
Runtime directories remained readable/traversable at mode 755; the secret-bearing
runtime environment stayed restricted at mode 600. Local report and evidence files
remain owned by the regular workspace user. Final safe phone health is recorded in
`monitoring-final-health.json` beside the deployment evidence.

## Verification limits

This verifies passive sampling with the encoder stopped. It does not prove a popup
incident, an explicit cold-mode button journey, actual push receipt on an installed
iPhone, or the signed-in browser journey. Resource measurements and those other
acceptance layers belong to the coordinating Ticket verification report.
