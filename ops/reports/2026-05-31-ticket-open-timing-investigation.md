# Ticket Open Timing Investigation - 2026-05-31

## Summary

Investigated the slow public ticket-stream open path for `ticket.jolkins.id.lv` using the existing Brave Work profile and Pixel-side health.

The latest bad sample available at investigation start showed a 9.94 second phone wake/proof phase. The delay was not in H.264 frame capture, browser decode, or steady stream delivery. It was in the phone-side ViVi readiness path: after launching ViVi, the fast recent-ticket readiness check missed, Pixel classified the state as `UNKNOWN_VIVI`, relaunched ViVi, and only then proved `TICKET_DETAIL`.

A follow-up direct Brave open was fast because recent ticket-detail memory was fresh. That run proved the same path can complete in 216 ms when the fast-readiness path succeeds.

## Evidence

Evidence directory:

- `ops/evidence/ticket-open-timing-20260531T043954Z/`

Key files:

- `pixel-health-before.json`: initial Pixel health containing the slow 9.94 s sample.
- `direct-brave-run-1/`: direct Brave Work-profile open plus 0.5 s Pixel health polling.
- `pixel-health-after-close.json`: post-probe health snapshot.
- `ticket-logcat-tail.txt`: TicketStreamService logs captured after the run.

## Timing Findings

Slow sample from initial Pixel health:

- Total phone wake/proof time: 9,940 ms.
- Slowest phase: `vivi_foreground`, 9,904 ms.
- Wake command: 32 ms.
- Screen interactive: 36 ms.
- Events showed `wake_recent_ticket_detail_fast_ready_after_launch_missed`, then `wake_recovery_relaunch state=UNKNOWN_VIVI`, then final `TICKET_DETAIL`.
- H.264 frames were only allowed after this proof completed, so the viewer waited on phone readiness before the stream could be shown.

Fast direct Brave run:

- Direct Brave tab opened to `ticket.jolkins.id.lv` and reported title `Biļete`.
- Pixel stream was live with one control client and one video client.
- Total phone wake/proof time: 216 ms.
- Slowest phase: `vivi_foreground`, 181 ms.
- Event showed `wake_recent_ticket_detail_fast_ready age_ms=320908`.
- Visible frame age stayed well below the live threshold.

## Conclusion

The bad timing is caused by the phone-side ViVi readiness/proof path, specifically the fallback taken when recent ticket-detail memory is not accepted immediately after launching ViVi and the first root proof returns `UNKNOWN_VIVI`.

The browser/public side did not show evidence of being the cause in this run. The relay and H.264 stream were healthy once Pixel allowed frames. Browser screenshot capture was not usable on this Mac display state: direct `screencapture` produced black frames, and Peekaboo could list the Brave window but failed to capture it. The Brave tab URL/title plus Pixel health were used as the closest available public evidence.

## Follow-up Target

Future fixes should focus on the post-launch ViVi readiness decision:

- why the post-launch fast-readiness check misses when ViVi is already recoverable,
- why the first root proof becomes `UNKNOWN_VIVI`,
- whether that `UNKNOWN_VIVI` should trigger a full relaunch or a shorter settle/recheck first,
- whether recent ticket-detail memory plus focused ViVi should be trusted earlier in this path.
