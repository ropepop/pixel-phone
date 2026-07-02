# Pixel + Ticket Remote Resource Audit - 2026-07-01

Evidence directory: `ops/evidence/pixel-resource-service-audit-20260701T134701Z/`

## Summary

This was a read-only five-minute audit of the Pixel and the Ticket Remote service side. Collection completed cleanly: 60 samples were captured at 5-second intervals, and every phone and host sample returned successfully.

The important context is that the phone was not idle. The local ticket service reported a live stream with one client, fresh visible frames, and hardware H.264 capture active. So these numbers describe an active ticket-stream window, not a quiet baseline.

Overall result: the setup looked stable during the window. The Pixel was warm and under mild thermal pressure, but it did not enter severe thermal states. The Ticket Remote containers were small on memory, Docker health was green for the app and phone bridge, and the host had plenty of memory and disk headroom.

## Pixel Results

| Metric | Result |
| --- | ---: |
| Samples | 60 |
| Counter window | 295 seconds |
| Average CPU busy | 66.0% |
| CPU busy range | 32.8-99.6% |
| Average 1-minute load | 8.70 |
| 1-minute load range | 5.40-13.42 |
| Average available RAM | 801.3 MiB |
| Lowest available RAM | 650.7 MiB |
| Average free swap | 720.8 MiB |
| Lowest free swap | 715.8 MiB |
| Battery temperature | 37.3-38.5 C, avg 38.0 C |
| Thermal status | 1, mild pressure, in all 60 samples |

### Temperatures

| Sensor | Average | Range |
| --- | ---: | ---: |
| battery | 38.0 C | 37.3-38.5 C |
| VIRTUAL-SKIN | 39.4 C | 39.1-40.7 C |
| VIRTUAL-SKIN-DISPLAY | 39.4 C | 39.1-40.7 C |
| VIRTUAL-SKIN-CHARGE | 38.8 C | 38.3-40.1 C |
| soc_therm | 47.7 C | 45.2-53.4 C |
| BIG | 70.2 C | 68.0-84.0 C |
| MID | 70.7 C | 70.0-75.0 C |
| LITTLE | 69.0 C | 68.0-75.0 C |
| G3D | 62.4 C | 62.0-65.0 C |
| TPU | 59.6 C | 59.0-63.0 C |

### Pixel Disk

| Mount | Used | Used space | Available |
| --- | ---: | ---: | ---: |
| / | 100% | 1321.0 MiB | 0.0 MiB |
| /data | 20% | 21397.6 MiB | 90570.5 MiB |
| /storage/emulated | 20% | 21397.6 MiB | 90570.5 MiB |
| /data | 20% | 21397.6 MiB | 90570.5 MiB |

### Pixel Network During Window

| Interface | Received | Sent | Receive rate | Send rate | Drop delta rx/tx |
| --- | ---: | ---: | ---: | ---: | ---: |
| lo | 26.6 MB | 26.6 MB | 92.3 KB/s | 92.3 KB/s | 0/0 |
| wlan0 | 2.8 MB | 32.2 MB | 9.6 KB/s | 111.7 KB/s | 0/4 |
| tailscale0 | 1.0 MB | 28.7 MB | 3.6 KB/s | 99.5 KB/s | 0/0 |

### Pixel Disk I/O During Window

| Device | Read | Written | Read ops | Write ops |
| --- | ---: | ---: | ---: | ---: |
| sda | 24.0 KB | 9.1 MB | 6 | 1170 |
| sda34 | 24.0 KB | 9.1 MB | 6 | 1170 |
| sda33 | 0.0 B | 0.0 B | 0 | 0 |
| dm-62 | 24.0 KB | 9.1 MB | 6 | 1190 |

### Top Pixel Processes

| Process group | Top-row appearances | Average shown CPU | Peak shown CPU |
| --- | ---: | ---: | ---: |
| sh | 113 | 17.2% | 45.1% |
| media codec service | 60 | 103.0% | 130.0% |
| audit top command | 56 | 25.2% | 36.6% |
| Pixel Orchestrator app | 53 | 47.4% | 187.0% |
| ticket hardware capture helper | 28 | 8.7% | 22.5% |
| SurfaceFlinger | 28 | 9.6% | 15.1% |
| android.hardware.usb-service | 19 | 22.5% | 70.9% |
| system_server | 17 | 21.0% | 71.8% |

Notes:

- The old runaway `tr` helper from the May thermal issue did not appear in the sampled top-process rows.
- The largest recurring work was media/capture-related: Android media codec service, the Pixel Orchestrator app, and the ticket hardware capture helper.
- The audit's own `top` command appears in the process list and should not be mistaken for app load.

## Ticket Stream State

Corrected phone-side health after the run showed:

| Field | Value |
| --- | --- |
| Server version | `ticket-stream-2026-05-23-priority-rs-vivi-v235` |
| Session state | `live` |
| Stream active | `true` |
| Stream verdict | `live` |
| Clients | `1` |
| Ticket state | `live` |
| ViVi state | `TICKET_DETAIL` |
| Hardware capture | `active`, active `true` |
| Latest visible frame age | `67` ms |

The raw per-sample health-script calls used a quoting path that made the VPN and management scripts report permission-style failures. A corrected post-run probe returned OK for ticket, VPN, and management health, so the report treats the per-sample script return codes as a collector artifact, not a service failure.

## Host And Containers

| Metric | Result |
| --- | ---: |
| Average host 1-minute load | 0.14 |
| Host load range | 0.03-0.31 |
| Average available host RAM | 2876.1 MiB |
| Lowest available host RAM | 2859.0 MiB |
| Average free host swap | 4048.1 MiB |

### Host Disk

| Mount | Used | Used space | Available |
| --- | ---: | ---: | ---: |
| / | 27% | 25614.9 MiB | 70831.1 MiB |

### Ticket Containers

| Container | Avg CPU | Peak CPU | Avg memory | Peak memory | PIDs |
| --- | ---: | ---: | ---: | ---: | ---: |
| arbuzas-ticket_remote-1 | 2.18% | 4.69% | 19.9 MiB | 20.5 MiB | 9 |
| arbuzas-ticket_phone_bridge-1 | 1.41% | 10.01% | 13.5 MiB | 15.3 MiB | 10 |
| arbuzas-ticket_remote_tunnel-1 | 0.75% | 1.48% | 24.7 MiB | 24.9 MiB | 9 |

Docker health status:

- `ticket_remote`: healthy, failing streak 0.
- `ticket_phone_bridge`: healthy, failing streak 0.
- `ticket_remote_tunnel`: no Docker healthcheck exposed by `docker inspect`.

Docker storage snapshot from the host showed about 13.05 GB of images and 3.05 GB of build cache. This audit did not delete or prune anything.

### Host Network During Window

| Interface | Received | Sent | Receive rate | Send rate | Drop delta rx/tx |
| --- | ---: | ---: | ---: | ---: | ---: |
| eth0 | 37.8 MB | 29.1 MB | 131.4 KB/s | 101.1 KB/s | 0/0 |
| br-f1dbe25e2d0d | 26.9 MB | 31.1 MB | 93.3 KB/s | 107.9 KB/s | 0/0 |
| veth4db3875 | 25.9 MB | 28.1 MB | 90.0 KB/s | 97.6 KB/s | 0/0 |
| vethf1f84bd | 26.2 MB | 23.9 MB | 91.1 KB/s | 82.9 KB/s | 0/0 |
| veth8b5a643 | 24.5 MB | 23.7 MB | 84.9 KB/s | 82.3 KB/s | 0/0 |
| veth2ad7810 | 21.5 MB | 25.3 MB | 74.6 KB/s | 87.7 KB/s | 0/0 |

## Public Endpoint Checks

| Check | Result |
| --- | --- |
| Public root | HTTP 200 |
| Root cache-control | `no-store, no-cache, must-revalidate, max-age=0` |
| Cloudflare cache status | `DYNAMIC` |
| Unauthenticated `/api/v1/health` start | `401 0.076707` |
| Unauthenticated `/api/v1/health` end | `401 0.074419` |

This matches the current access boundary: the public page is reachable, while the health API needs authentication. I did not open an authenticated browser session because this audit scope was Pixel plus services, not browser verification.

## Comparison With Earlier Audits

- Compared with the earlier 2026-06-27 five-minute resource poll, this run observed active ticket streaming rather than a storage/file-audit context. CPU and temperature should therefore not be compared as idle-to-idle values.
- The old May runaway root helper issue did not reappear in the sampled top rows.
- `/data` showed about 20% used in this run's `df` output, with plenty of available space.

## Follow-Ups

- If you want a true idle baseline, repeat the same audit after closing all ticket viewers and confirming `streamActive=false` and `clients=0` first.
- If stream heat matters, run a paired test: five minutes idle, five minutes active stream, same collector, then compare CPU and thermal rise directly.
- No cleanup, restart, deploy, or setting change was performed during this audit.

## Raw Files Checked

- `samples.jsonl`: 60 rows, all phone and host return codes 0.
- `pixel/corrected_health_probe.txt`: corrected ticket/VPN/management health check.
- `pixel/corrected_local_ticket_health_summary.json`: selected ticket stream state.
- `host/docker_health_inspect.txt`: Docker health status.
- `public/root_headers_end.json` and `public/api_health_unauth_end.json`: public endpoint status.
