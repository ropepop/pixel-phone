# Deployment Resource Cut Analysis - 2026-07-01

Evidence directory: `ops/evidence/deployment-resource-cut-audit-20260701T140429Z/`

## Summary

This was a read-only resource-cut audit of the Pixel/Ticket Remote path, plus a lighter scan of the full kitty-gration host. I did not change app code, services, configs, Docker state, browser state, Pixel settings, cookies, or sessions.

The short version: the host is not under pressure. Ticket Remote services are small, Docker health is green, root disk is only 27% used, and the biggest no-user-impact win is cleanup of Docker images/build cache, not stopping live services.

The active cost is the Pixel stream. During this audit the Pixel had one video client, no control client, and active root hardware H.264 capture for the whole run. That means phone CPU, warmth, and outbound network are expected. The main possible cut without user-quality loss is not lowering stream quality; it is proving whether that viewer is real or stale, then making stale/no-visible viewers stop capture promptly.

## Current Resource Picture

| Metric | Current focused sample | Earlier five-minute active-stream audit |
| --- | --- | --- |
| Samples / window | 24 over 115 s | 60 over 295 s |
| Average CPU busy | 46.6% | 66.0% |
| CPU range | 30.3%-74.9% | 32.8%-99.6% |
| Average 1-min load | 4.57 | 8.70 |
| Available RAM avg / low | 1273.3 / 1005.3 MiB | 801.3 / 650.7 MiB |
| Battery temperature | 37.8-37.9 C, avg 37.8 C | 37.3-38.5 C, avg 38.0 C |
| Thermal status | 1: 24 | 1 in all 60 samples |
| Stream state | live, 1 video client, 0 control clients, H.264 active | live, 1 client, H.264 active |

### Pixel Temperatures

| Sensor | Average | Range |
| --- | --- | --- |
| BIG | 59.4 C | 52.0-82.0 C |
| G3D | 53.1 C | 50.0-57.0 C |
| LITTLE | 59.2 C | 53.0-65.0 C |
| MID | 61.3 C | 53.0-73.0 C |
| TPU | 52.1 C | 49.0-56.0 C |
| VIRTUAL-SKIN | 38.9 C | 38.7-39.2 C |
| VIRTUAL-SKIN-DISPLAY | 38.9 C | 38.7-39.2 C |
| battery | 37.8 C | 37.8-37.9 C |
| soc_therm | 46.6 C | 44.8-48.5 C |

### Pixel Top Processes

| Process group | Appearances | Avg shown CPU | Peak shown CPU |
| --- | --- | --- | --- |
| sh | 54 | 11.9% | 76.6% |
| audit top command | 39 | 18.0% | 38.7% |
| media codec service | 24 | 101.3% | 130.0% |
| Pixel Orchestrator app | 24 | 42.6% | 233.0% |
| ticket hardware capture helper | 23 | 8.3% | 25.8% |
| SurfaceFlinger | 23 | 6.1% | 17.2% |
| sleep | 23 | 0.0% | 0.0% |
| system_server | 21 | 19.5% | 90.3% |

Notes:

- The recurring heavy work is still media/capture-related: Android media codec, the Pixel Orchestrator app, and the ticket H.264 capture helper.
- The audit `top` command and shell rows are measurement noise and should not be treated as app load.
- The old runaway root-helper pattern was not present: H.264 showed one encoder process and zero stale capture processes in the health samples.

### Pixel Network During Sample

| Interface | Received | Sent | Receive rate | Send rate | Drops rx/tx |
| --- | --- | --- | --- | --- | --- |
| lo | 9.2 MiB | 9.2 MiB | 82.2 KiB/s | 82.2 KiB/s | 0/0 |
| wlan0 | 0.9 MiB | 11.1 MiB | 7.9 KiB/s | 98.4 KiB/s | 0/2 |
| tailscale0 | 0.3 MiB | 9.9 MiB | 2.8 KiB/s | 87.8 KiB/s | 0/0 |

## Service And Host Picture

| Metric | Result |
| --- | --- |
| Average host CPU busy | 6.8% |
| Average 1-min load | 0.25 |
| Available RAM avg / low | 2848.2 / 2828.1 MiB |
| Free swap | 4048.1 MiB |
| Root disk | 26G used, 70G available, 27% |
| Docker images | 13.05GB, 12.58GB (96%) reclaimable |
| Docker build cache | 3.047GB, 3.047GB reclaimable |
| Release bundle count | 10 |
| Retired DNS directory | 166 MB |

### Ticket Path Containers

| Container | Avg CPU | Peak CPU | Avg RAM | Network delta rx/tx |
| --- | --- | --- | --- | --- |
| phone_broker | 0.2% | 0.4% | 8.9 MiB | 9.0 / 8.0 MiB |
| ticket_phone_bridge | 0.7% | 2.7% | 13.4 MiB | 10.0 / 9.0 MiB |
| ticket_remote | 2.1% | 2.5% | 19.6 MiB | 9.0 / 9.0 MiB |
| ticket_remote_spacetime_sidecar | 0.5% | 0.5% | 11.7 MiB | 0.8 / 0.6 MiB |
| ticket_remote_tunnel | 0.8% | 2.0% | 24.7 MiB | 8.0 / 8.0 MiB |

### Host-Wide Container Snapshot

| Container | Avg CPU | Peak CPU | Avg RAM | Network delta rx/tx |
| --- | --- | --- | --- | --- |
| ticket_remote | 2.1% | 2.5% | 19.6 MiB | 9.0 / 9.0 MiB |
| ticket_remote_tunnel | 0.8% | 2.0% | 24.7 MiB | 8.0 / 8.0 MiB |
| ticket_phone_bridge | 0.7% | 2.7% | 13.4 MiB | 10.0 / 9.0 MiB |
| satiksme_bot | 0.5% | 3.2% | 53.1 MiB | 0.5 / 0.1 MiB |
| ticket_remote_spacetime_sidecar | 0.5% | 0.5% | 11.7 MiB | 0.8 / 0.6 MiB |
| subscription_tunnel | 0.3% | 1.8% | 24.0 MiB | 0.0 / 0.0 MiB |
| train_tunnel | 0.3% | 0.4% | 27.4 MiB | 0.0 / 0.1 MiB |
| satiksme_tunnel | 0.3% | 0.3% | 26.7 MiB | 0.1 / 0.1 MiB |
| phone_broker | 0.2% | 0.4% | 8.9 MiB | 9.0 / 8.0 MiB |
| chatgpt_broker | 0.0% | 0.3% | 15.9 MiB | 0.0 / 0.0 MiB |

Notes:

- Ticket Remote itself averaged about 2.1% CPU and 19.6 MiB RAM.
- The ticket tunnel, phone bridge, phone broker, and Spacetime sidecar are all small compared with the Pixel capture cost.
- Cloudflare tunnels are visible overhead, but each is small; merging them would save modest RAM/CPU at the cost of wider blast radius.
- Release bundles are not the storage problem right now: there are 10 release directories, each around 9.6-9.9 MiB.

## Spacetime And Polling

| Table | Count | Meaning |
| --- | --- | --- |
| ticketremote_stream_command | 0 | Old command-backlog spike is not present |
| ticketremote_safe_operational_log | 14664 | Still nontrivial, but far below old remembered spike and should be retained/sampled, not purged blindly |
| ticketremote_phone_status_history | 27 | Small bounded history |
| ticketremote_viewer_presence | 1 | Matches one current viewer |
| ticketremote_control_code_request | 1 | Small |
| ticketremote_audit_event | 0 | No retained audit backlog |

The old Spacetime spike pattern does not appear to be active in this count-only check: `ticketremote_stream_command` is `0`, not tens of thousands. `ticketremote_safe_operational_log` is still the largest ticket table at 14,664 rows, but current code keeps recent operational history bounded and the report should not recommend wholesale deletion.

Static code evidence also shows the obvious hot loops are already partially throttled:

- Browser health polling uses fast/normal/healthy-live delays instead of a constant fast loop.
- Browser heartbeats run every 15 seconds.
- Client log aggregates flush every 5 seconds, with server-side quieting.
- Presence writes are throttled to 30 seconds per session.
- Phone health writes skip unchanged compact summaries and keep a 30-second keepalive.
- Cached state broadcasts are suppressed except at a bounded 5-second interval.

This means further database/browser tuning may help at the margins, but it is not the biggest live resource driver today.

## Ranked Cuts

### Safe / No End-User Impact

| Rank | Candidate | Class | Evidence | Expected win |
| --- | --- | --- | --- | --- |
| 1 | Docker image and build cache cleanup | Safe/no user impact | Images show 12.58 GB reclaimable and build cache shows 3.047 GB reclaimable. This is storage waste, not live service work. Keep current image and rollback policy before pruning. | ~15.6 GB disk |
| 2 | Retired DNS directory | Safe after rollback check | `/srv/arbuzas/dns.retired-20260621T195108Z` is 166 MB and DNS is marked retired. Small win; remove only if the retired rollback copy is no longer needed. | 166 MB disk |
| 3 | Old dangling/duplicate Docker tags | Safe with normal retention | 428 images exist, only 11 active. This is the same cleanup bucket as Docker image pruning; do it with retention rather than deleting by hand. | Part of image reclaim |

### Likely Safe With Validation

| Rank | Candidate | Class | Evidence | Expected win |
| --- | --- | --- | --- | --- |
| 1 | Stale ticket viewer prevention | Likely safe with browser/user validation | The Pixel stayed live with 1 video client, 0 control clients, and active H.264 throughout the sample. If that client is a stale tab, stopping the stream on stale/no-visible viewers removes the largest active cost without reducing real user quality. | High: Pixel CPU/heat/network |
| 2 | Adaptive static-stream quieting | Likely safe only with visual proof | The ticket screen is often static, but the phone still captures steady H.264 frames. A future change could keep current resolution/readability and burst on changes/control while reducing work during unchanged/static periods. Must prove the browser ticket stays readable and reconnects stay fast. | Medium/high, phone-side |
| 3 | Browser telemetry/presence tuning | Low-impact likely safe | Current code already throttles presence writes, phone-health writes, cached state broadcasts, and client logs. Further reductions may save minor Spacetime/browser work, but this is not the main cost now. | Low |
| 4 | Ops-only services during tight memory periods | End-user safe, ops tradeoff | Portainer and non-ticket services consume RAM but little CPU. Stopping Portainer would not affect end users but would reduce operations visibility. Do only if the host becomes memory-constrained. | ~70 MB RAM for Portainer |

### Risky / Not Recommended

| Rank | Candidate | Class | Reason | Risk |
| --- | --- | --- | --- | --- |
| 1 | Lower active capture quality globally | Not recommended | Pixel quality is already constrained to 720 px width, 1.2 Mbps, 4 FPS steady/8 FPS burst. Cutting this directly risks ticket readability, control-code capture, and reconnect quality. | Could hurt users |
| 2 | Remove Ticket tunnel/bridge/sidecar | Not recommended | These pieces are small and required for the public path. Their combined CPU/RAM is not the bottleneck. | Low savings, high break risk |
| 3 | Merge Cloudflare tunnels just to save memory | Not first choice | Each tunnel is small. Consolidation could save some RAM/CPU but increases blast radius across services. | Modest savings, broader failure impact |
| 4 | Delete Spacetime safe logs wholesale | Not recommended | The old incident showed safe logs are useful for diagnosis. Current command backlog is gone; safe-log count is much lower than the old spike pattern. Use retention/sampling, not bulk deletion. | Could remove diagnostics |
| 5 | Disable heartbeats/keyframes/health checks broadly | Not recommended | These are part of freshness, reconnect, and control behavior. Tune only with targeted evidence. | Could cause stale/spinning pages |

## Interpretation

The deployment is not oversized on the service host. The host has plenty of memory, swap is effectively unused, and disk pressure is low. Docker cleanup can recover meaningful disk, but it will not reduce Pixel heat or active stream CPU.

The phone is the only meaningful active resource consumer. That is not automatically waste: the phone was serving a live video client. Cutting phone work safely requires distinguishing real viewing from stale or hidden tabs. If the one viewer is real, the current CPU and network are the cost of preserving stream quality. If the viewer is stale, ending that session promptly is the best cut available.

Compared with the earlier five-minute audit, this shorter run was calmer: Pixel CPU averaged 46.6% instead of 66.0%, battery stayed around 37.8 C instead of peaking at 38.5 C, and thermal status remained mild. The shape is the same, just less intense.

## Verification

- Sample collection completed with 24 rows over 115 seconds.
- All host and Pixel sample commands returned successfully; `collector_errors.jsonl` is empty.
- Final sanitized Pixel health confirmed the stream was still live with 1 client, 0 control clients, active H.264, one encoder process, no stale capture process, and no slow/closed video clients.
- Public root returned HTTP 200 with no-store/dynamic cache behavior; unauthenticated `/api/v1/health` returned 401 as expected.
- Spacetime checks used count-only SQL through existing local CLI auth; no row contents were saved.
- Private `/api/v1/health` from a plain container curl returned 401, so relay-private diagnostic counters from that endpoint were not available without an authenticated route.

## Raw Files Checked

- `analysis/samples.jsonl`: 24 sample rows.
- `analysis/summary.json`: computed metrics and cross-checks.
- `analysis/spacetime_table_counts.json`: count-only Spacetime evidence.
- `host/snapshot.txt`: host, Docker, disk, image/cache, and directory-size snapshot.
- `pixel/snapshot.txt`: Pixel battery, thermal, memory, disk, process, listener, and network snapshot.
- `pixel/final_health_sanitized.json`: final sanitized Pixel stream state.
- `public/preflight_public.txt`: public root and unauthenticated health checks.
- `code/`: focused snippets for polling, stream throttles, Spacetime cleanup, Compose shape, and Pixel H.264 settings.
