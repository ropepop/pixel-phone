# RS/ViVi Incident Analytics Architecture

This document is the stable architecture contract for the shared Pixel phone incidents that cross:

- Telegram Rīgas Satiksme monthly-ticket QR bot
- `phone_broker`
- Android `ticket_screen` / `TicketStreamService`
- `ticket_phone_bridge`
- public `ticket.jolkins.id.lv` ViVi stream
- Hermes watchdog/steward cron jobs

It exists because aggregate health and synthetic smokes repeatedly hid real-user failures. A system can be green while a real Telegram user has a failed RS QR request, or while the phone returned from RS to the wrong ViVi screen and the public ticket page is effectively broken.

Product-flow contracts live in [Ticket Streaming Architecture](./TICKET_STREAMING_ARCHITECTURE.md). This document is for incident/stewardship behavior: how to prove real-user impact, root cause, and close-out without merging separate product outcomes.

## Architecture problems learned from incidents

1. **Health was component-local instead of user-impact centric.** Broker queue depth, Pixel health, or a passing fake smoke did not answer: which user asked, did they get a real RS monthly-ticket PNG, and did ViVi return to `TICKET_DETAIL`?
2. **The causal chain was split across stores.** Telegram user/access state lived in the bot, job attempts lived in `phone_broker`, Android phase failures lived in bridge health/logs, images lived behind broker `/image`, and public verification lived in a browser profile. Agents had to manually stitch this together.
3. **Synthetic success could mask real failures.** A steward could run a fake-Telegram smoke after the app recovered and report success, while earlier real users such as `@iamhdzs` still had failed jobs with no root-cause closure.
4. **Failure names were not enough.** Reasons like `qr_image_missing`, `rs_monthly_ticket_image_capture_failed`, `rs_monthly_ticket_flow_timeout`, `stale_code`, and `UNKNOWN_VIVI` need phase timing and cleanup state to point at the right component.
5. **Monitoring output was ad hoc.** Watchdog messages were evolving by patch rather than by a formal incident schema, so future agents could miss safe actor labels, retry counts, or closure requirements.

## New incident model

Every RS/ViVi production incident must be treated as a **user incident trace**, not as a service-health blip.

Required trace fields:

- **Trace ID:** safe, non-secret identifier (`rsqr:<sequence>` for broker rollups; no raw broker job ID in public/steward reports unless it is strictly needed locally).
- **Safe actor:** Telegram username from RS bot access state when available (`@name`), otherwise a stable hash (`user:<hex>`). Never print raw numeric Telegram IDs.
- **Broker job summary:** sequence, status, reason, attempts, created/started/completed timestamps, queue seconds, final-attempt seconds, total seconds.
- **Lifecycle phases:** Telegram request, broker job creation, broker attempt, phone control connection, RS navigation, code submit, RS control screen verification, PNG capture, Telegram photo send, ViVi cleanup, ViVi ticket-detail restoration, public ticket verification when relevant.
- **Outcome:** open / mitigated / fixed / recovered / failed / monitoring, with root cause, tests, deploy evidence, and live verification evidence.
- **Safety marker:** trace artifacts must declare they contain no secrets, no raw user IDs, and no RS codes.

Canonical schema files:

- `standards/schemas/rs-vivi-incident-trace.v1.schema.json`
- `standards/schemas/rs-vivi-qr-analytics.v1.schema.json`

The same schema files are mirrored in the Ops repo because `phone_broker` and the Telegram bot live there.

## New broker analytics surface

`phone_broker` exposes a safe rollup endpoint:

```text
GET /api/v1/analytics
```

For RS monthly-ticket jobs, analytics should preserve Pixel's phase-specific visual automation reasons. `phone_timeout` means the broker received no final phone outcome inside the deadline; it is not a substitute for Pixel reasons such as `rs_app_launch_failed`, `rs_app_foreground_failed`, `code_rejected_by_rs`, `rs_monthly_ticket_missing`, `rs_manual_code_field_missing`, `rs_monthly_ticket_stale_code`, `rs_app_attention_required`, `rs_monthly_ticket_unknown_state`, `rs_auth_blocked`, or `rs_monthly_ticket_image_capture_failed`. Batch RS work should also distinguish the user-visible result time from later ViVi cleanup time; non-critical cleanup may be delayed briefly so a rapidly arriving next RS job can continue in the warm RS app.

Public ticket viewer presence and ticket leases are hard phone-priority signals. They keep broker ownership on `ticket` for their full active duration, pause queued RS jobs, and preempt running RS jobs without burning RS retry budget. While ticket priority is active, the phone should remain on ViVi/ticket work rather than RS. Current operational targets are public `ticket.jolkins.id.lv` live load in 5 seconds or less, RS final image or named final failure in 15 seconds or less on average, and no regression of stream delay beyond the existing public live-frame freshness threshold.

Response shape:

```json
{
  "ok": true,
  "analytics": {
    "schema": "rs-vivi-qr-analytics.v1",
    "generatedAt": "...",
    "rsQr": {
      "totals": {
        "jobs": 0,
        "waiting": 0,
        "running": 0,
        "succeeded": 0,
        "failed": 0,
        "canceled": 0,
        "retried": 0,
        "slowSuccess": 0
      },
      "byReason": {},
      "successLatencySec": { "count": 0 },
      "userImpact": [
        {
          "actorHash": "user:<hash>",
          "jobs": 0,
          "failed": 0,
          "retried": 0,
          "lastStatus": "failed",
          "lastReason": "...",
          "lastAt": "..."
        }
      ],
      "recentIncidents": []
    }
  }
}
```

Safety constraints:

- The endpoint does **not** expose RS five-digit codes.
- It does **not** expose raw Telegram chat IDs or user IDs.
- It does **not** expose raw broker job IDs.
- It uses `actorHash` only when actor correlation is needed.
- The Telegram bot access state remains the only place agents may map known safe usernames.

Semantics:

- `retried` means `attempts > 1`; it includes slow successes that should still be investigated.
- `slowSuccess` currently means successful RS QR delivery took at least 15 seconds end-to-end; this is the per-job incident signal that supports the 15-second average target.
- `userImpact` groups all retained broker jobs by safe actor hash so watchdog/steward runs can start from affected users instead of aggregate health.
- `recentIncidents` includes failures, non-user cancellations, running jobs, retried jobs, and slow successes.
- Broker analytics are a starting point; they do not replace image semantics or live ViVi/public verification.

## Watchdog contract

The 5-minute watchdog is cheap and silent when healthy. When it emits output, it must include:

- safe recovery action attempted, if any;
- real RS QR failures or slow/retried successes with safe actor label/hash;
- compact bridge health, including ViVi state source when available (`fast_empty`, `root`, `root_empty`) so operators can tell an inconclusive fast foreground-guard sample from authoritative root evidence;
- broker analytics summary when available.

When compact health is otherwise live (`sessionState=live`, stream/ticket live) but ViVi is only `UNKNOWN_VIVI` from `fast_empty`, the watchdog should perform a bounded settle/poll for a confirming `TICKET_DETAIL` sample before starting recovery or emitting a bridge-unhealthy alert. The current watchdog cadence is 24 attempts at 2-second intervals, chosen to catch sparse authoritative `root` confirmations without phase-locking on inconclusive fast-empty samples. Do not change Android to trust stale ticket-detail memory for this; the empty fast/root observations remain important safety evidence against stale detail.

The watchdog may safely request `POST /api/v1/session/start` only when no RS QR job is queued/running. It must not interrupt active real-user QR generation.

## Steward contract

Hourly steward runs must begin from user-impact data, not from synthetic smokes:

1. Read latest watchdog output and broker `/api/v1/analytics`.
2. Inspect persisted broker jobs only if more detail is required; redact codes, raw user IDs, tokens, cookies, sessions, and connection strings.
3. Map actors to known Telegram usernames via RS bot access state when available.
4. Group incidents by real affected user and failure class.
5. Root-cause unresolved failures before patching.
6. If code changes are needed, add a focused failing regression first, implement a minimal fix, run targeted and relevant full tests, deploy only affected components.
7. Verify product semantics as separate close-out checks:
   - RS Telegram closure requires broker/bot delivery evidence plus Pixel root phase evidence proving the intended RS monthly-ticket/control PNG came from the RS app flow.
   - Public viewer closure requires `sessionState=live`, `streamActive=true`, `streamVerdict=live`, `ticketState.state=live`, `viviState.state=TICKET_DETAIL`, `visibleFrame.lastFrameAgoMillis<=1500`, active rooted H.264 capture, a connected public relay, an active relay video client, and `directStream.lastFrameAgoMillis<=1500`.
   - Public `ticket.jolkins.id.lv` must be verified for ticket viewer changes with the authenticated Brave page and matching root-sourced Pixel/relay health. A browser screenshot without matching health is not closure; it is `public_ticket_split_brain`.
8. Do not close an incident solely because a fake smoke passes after the user failure.

If RS delivery passes but public viewer health fails, close or mitigate the RS delivery incident and open/continue the public viewer incident. If public viewer verification is unavailable, it may block public viewer close-out, but it must not turn a valid RS Telegram delivery into failure. If public visual proof passes while root/relay health fails, report `public_ticket_split_brain` instead of merging it into RS status.

## Analytics thresholds

Current alerting thresholds:

- waiting RS QR job: 90 seconds
- running RS QR job: 85 seconds
- slow success: 15 seconds total
- retried success: any success with more than one attempt
- failed/canceled job: always an incident unless cancellation is explicit user cancellation

These thresholds are operational and may be tightened as latency work improves, but changes must be recorded here and covered by tests or watchdog verification.

## Architecture update notes

- 2026-05-26: Automated RS Telegram stress tests must be target-locked. Agents must prove the selected Telegram chat header is the `rs biļete` bot before every typed send; if proof fails, the test must abort and save evidence. Coordinate-only Telegram typing is not allowed for RS QR testing.
- 2026-05-26: RS manual-code entry has one bounded safe re-entry attempt. Pixel may refocus and re-enter while the manual popup remains proven open, but it still must not tap OK until the requested digits are visible. If the popup disappears before digit proof, the failure is `rs_app_attention_required`; if digits remain unproven after the retry, the failure is `rs_manual_code_entry_unverified`.
- 2026-05-26: RS monthly-ticket startup treats an already-open old control-ticket QR as a recoverable app state, not as proof of a stale final result. Pixel must try a visible RS close/back control, then Back, then no-data-loss relaunch, rechecking the UI after every action. It must not enter digits until a safe register/manual-entry path is proven. Only a stale QR observed after a verified new-code submit remains `rs_monthly_ticket_stale_code`; startup screens that cannot be cleared are `rs_app_attention_required`.
- 2026-05-25: Persistent unknown RS app states now report `rs_app_attention_required` after one no-data-loss force-stop/relaunch recovery attempt. Broker retry policy must not blindly retry that attention-required class, and bot/steward messaging should tell the user/operator to open the RS app once and retry.
- 2026-05-23: RS QR analytics now carry safe phone phase summaries from Pixel result messages. Recent incidents may include source app, ticket flow, total phone duration, and named phase timings such as RS start-state proof and final proof timing; they still must not expose RS codes, raw Telegram IDs, chat IDs, tokens, cookies, or session values.
- 2026-05-23: RS/ticket operational verification must preserve split outcomes: public ticket priority was proven by an RS job waiting with zero attempts while an authenticated viewer owned the phone, RS final outcomes were measured below the 15-second target after release, final clean authenticated public loads were below 5 seconds, and stream visual age stayed below the live-frame freshness threshold. Evidence is summarized in [RS/Ticket Priority Operational Verification - 2026-05-23](../../ops/reports/2026-05-23-rs-ticket-priority-operational-verification.md).
- 2026-05-23: Broker ticket priority is hard while public viewers or ticket leases are active. The old bounded viewer-priority window is not the production contract; RS must wait or be preempted for the full ticket presence/lease duration, and `ticket_lease_active` preemptions must not burn RS retry attempts.
- 2026-05-23: RS monthly-ticket generation now uses the visual tap/pixel driver instead of the previous accessibility semantic driver. Pixel captures fast plain RS screenshots and downscales them for visual state classification, taps RS targets through the isolated lightweight RS root input gateway, always re-registers visible matching tickets, reads the final ticket code from the bottom control-ticket pixels, and accepts success only when the visual code matches the submitted digits. ViVi/public ticket controls keep the protected screen-sleep wrapper; RS visual input does not pay that wrapper cost. The final Telegram image remains the separate secure RS screenshot artifact. Stale-ticket proof, manual-code rejection, unknown visual states, and image-capture failures remain named user-visible outcomes. Broker batching, ticket-priority preemption, and delayed non-critical ViVi cleanup remain unchanged.
- 2026-05-23: RS monthly-ticket generation is no longer a blind coordinate-first operation. The earlier same-day implementation used the orchestrator accessibility service and bounded semantic branches; that has been superseded by visual tap/pixel driving because live Telegram batches still showed unreliable semantic state and stale Flutter labels.
- 2026-05-22: Architecture and implementation were reorganized around ownership boundaries. RS Telegram delivery, public ViVi viewing, cleanup, and verification are separate contracts with separate success authorities; steward close-out must report split outcomes explicitly instead of merging RS delivery and public viewer health.
- 2026-05-22: Public ticket close-out now requires visual proof plus root-sourced health proof. Steward/watchdog verification must fail fast when Brave shows a ticket but Pixel or relay health reports `needs_attention`, `capture_blocked`, stale frames, missing rooted H.264 capture, or `UNKNOWN_VIVI`; that mismatch is `public_ticket_split_brain`, not success.
- 2026-05-22: Public viewer incidents should account for the guarded root hierarchy timeout itself. The active public-health proof uses the longer guarded timeout because the short default leaves too little real `uiautomator` time after wrapper cleanup reserve and can falsely report `UNKNOWN_VIVI` while rooted video is live.
- 2026-05-22: Pixel RS monthly-ticket generation was simplified to one coordinate drive, one root semantic proof, and one screenshot capture. This was superseded on 2026-05-23 first by bounded semantic start-state handling and then by the visual tap/pixel driver after live batches still showed unreliable RS app state.
- 2026-05-22: Live root evidence showed the RS monthly-ticket operation uses RS `REGISTER A TRIP`, `ENTER THE CODE MANUALLY`, `CONFIRM`, the `Trip is registered` modal, and then `TICKET FOR CONTROL`; the earlier coordinate correction had trusted misleading Flutter hierarchy bounds for the first tap. Steward reviews should treat the generated RS ticket-control surface with the submitted digits and monthly-ticket markers as the Pixel proof for RS Telegram delivery; public Brave viewer evidence remains a separate close-out check.
- Added a broker analytics endpoint and schemas so agents no longer need to parse raw job state for first-pass triage.
- Watchdog output now carries broker analytics summaries when available.
- Watchdog compact bridge health now carries ViVi state source and treats `UNKNOWN_VIVI` from `fast_empty` as an inconclusive sample that requires bounded settle polling before recovery/action alerts, preserving Android's stale-detail safety behavior while reducing false bridge-unhealthy reports. See `ops/reports/2026-05-21-rs-vivi-steward-watchdog-fast-empty.md`.
- The steward close-out bar is raised from "current health green" to "real affected users explained, fixed/recovered, and product semantics verified." 
