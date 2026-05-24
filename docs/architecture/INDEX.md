# Architecture Index

Read architecture in this order:

1. [Pixel Stack Architecture](./PIXEL_STACK_ARCHITECTURE.md)
2. [Touch Brightness Architecture](./TOUCH_BRIGHTNESS_ARCHITECTURE.md)
3. [Ticket Streaming Architecture](./TICKET_STREAMING_ARCHITECTURE.md)
4. [RS/ViVi Incident Analytics Architecture](./RS_VIVI_INCIDENT_ANALYTICS_ARCHITECTURE.md)
5. [ROOT_OPERATIONS](../runbooks/ROOT_OPERATIONS.md)
6. Module runbook overlays in `docs/runbooks/`
7. Dated evidence in `ops/reports/` when a doc links to a measurement

Architecture docs are canonical source material. Reports and evidence explain how a conclusion was measured, but they should not be the only place stable architecture is recorded.

## Ownership Map

| Owner | Owns | Success authority | Must not depend on |
| --- | --- | --- | --- |
| Pixel root state | Phone app state, rooted input, root hierarchy proof, rooted capture health | Fresh root-owned Pixel evidence | Browser canvas state, relay summaries, stale health memory |
| RS Telegram pipeline | Batch-capable RS monthly-ticket image generation and delivery | `rigassatiksme_qr_result` with generated monthly-ticket image bytes from RS visual tap/pixel proof and secure capture, or a named Pixel RS failure | Brave/public visual proof, public relay video clients, ViVi cleanup success, accessibility hierarchy state |
| Public ticket pipeline | Live ViVi viewer health at `ticket.jolkins.id.lv` | Authenticated Brave visual proof plus Pixel root health plus relay frame freshness | RS Telegram result state, optimistic browser-only canvas checks |
| Broker/bot | Queueing, retries, user messaging, timeout naming, image retention | Broker job state and Pixel final RS result messages | Public viewer verification or cleanup completion before reporting an RS result |
| Cleanup | Post-result restoration to ViVi ticket detail | Separate cleanup completion event and health state | Changing, delaying, or revoking an already delivered RS image result |
| Verification | Operator close-out evidence | Flow-specific evidence bundle after product result decisions | Acting as an in-flow product success gate |

## Do Not Couple

- RS Telegram success must not wait for Brave, public canvas state, public relay video clients, or public viewer health.
- Public ticket viewer presence and ViVi control-code leases own the shared phone for their full active duration. RS Telegram work must wait or be preempted during ticket priority, then resume without charging RS retry budget.
- Public viewer success must not be inferred from screenshot-only or stale browser canvas state when Pixel root or relay health is degraded.
- Cleanup must not delay or revoke a valid delivered RS image; cleanup failures are separate attention/health outcomes. Non-critical RS cleanup runs through the idle cleanup path so rapidly arriving RS work can stay in the RS app unless public ticket priority interrupts it.
- Fast paths may use only their owned source-of-truth inputs. Cross-path checks are allowed only after the product result has already been decided.
- `phone_timeout` is only appropriate when the owner of a request receives no final Pixel/broker outcome inside the relevant deadline; specific Pixel failure reasons should not be collapsed into it.

## Control Domains

- Orchestrator control plane: `orchestrator/android-orchestrator`
- Runtime shell/control scripts: `orchestrator/scripts`
- Runtime templates/configs: canonical templates in `orchestrator/templates` and Android runtime assets, plus `orchestrator/configs`
- Managed workloads: `workloads/train-bot`, `workloads/satiksme-bot`, `workloads/site-notifications`, `workloads/subscription-bot`, `workloads/ticket-screen`
- External automation driver: `automation/task-executor`
- Observability/evidence: `ops/evidence`, `standards/schemas`

## Key Contracts

- Module registry: `orchestrator/modules/registry/modules.yaml`
- Module manifest schema: `orchestrator/modules/schemas/module-manifest.v1.schema.json`
- Component redeploy metadata lives in module manifests and the registry; every managed component declares whether it is an `artifact_release`, `asset_refresh`, `job`, or `derived` surface
- Observability event schema: `standards/schemas/observability-event.v1.schema.json`
- Observability health schema: `standards/schemas/observability-health.v1.schema.json`
- RS/ViVi user incident trace schema: `standards/schemas/rs-vivi-incident-trace.v1.schema.json`
- RS/ViVi broker analytics schema: `standards/schemas/rs-vivi-qr-analytics.v1.schema.json`

## Canonical Operations

- [ROOT_OPERATIONS](../runbooks/ROOT_OPERATIONS.md)
- `bootstrap` is for clean-room provisioning and shared-platform changes
- `redeploy_component` is the default single-service release path
- `restart_component` is lifecycle control only and does not publish a new release

## Agent Update Rule

When implementation changes component boundaries, runtime paths, deployment behavior, health fields, public/private endpoints, stream/input flow, tunnel behavior, or safety policy, update the relevant architecture doc in the same work. If the change is temporary or measurement-only, link the dated report but keep stable behavior in `docs/architecture/`.
