# Pixel command authority and monitoring native migration — September 30

## Boundary and finishing criteria

The existing `pixel_health` JNI library owns the authoritative command inbox's
initial/update/delete/disconnect transitions, scoped containment and expiry,
start revalidation, stable stream-start priority, and the decision whether a
terminal outcome belongs to generic ACK or its existing atomic result publisher.
It also owns monitoring classification, freshness, epoch reset, ordinary cadence,
bounded rechecks, evidence cutoff and report suppression/state transitions.

Android retains the socket/identity protocol, current coroutine and monitor
notification loops, monotonic/UTC clocks, synchronized inbox call boundary,
concurrent retained-result DTO storage, capture, input, private journal persistence,
HTTP delivery and the actual ACK/finalizer calls. No new crate, dependency feature,
worker, timer, persisted state, fallback or physical attempt was added. No command,
settings, wire schema, signing or Android service configuration changed.

Completion requires the actual old-owner host comparison, existing full tests,
installed Android JNI, enabled live monitoring, a fresh nonactivating browser
command through durable settlement, reconnect and natural final quiet. Working
platform adapters remain. Tests never substitute for the browser/device journey.

## Local proof

- Monitoring: 18,400 actual host-JNI comparisons against the former owner at
  `dd41627`, covering every typed phone state, busy capture, clock overflow and
  backward changes, freshness cutoffs, epochs and repeated checked/reported
  transitions. The existing monitoring contracts also passed.
- Commands: 976 host-JNI comparisons against extracted actual `dd41627` owners.
  They cover malformed/expired/nanosecond/expanded-year/midnight/leap-second
  deadlines, start priority, terminal ACK routing, whitespace/default reasons,
  identity/revision containment, insertion/deletion order, capacity error state,
  authoritative replacement and disconnect. Existing real WebSockets preserve
  recorded outcomes across reconnect and retire them only after deletion.
- Both app variants and supporting modules: 1,156 records across 162 suites,
  zero failures/errors, in 27 seconds. Clippy with warnings denied, formatting
  and whitespace checks passed. New Android instrumentation is pending install.

The differential runners freeze prior production owners into ignored build output;
they do not install legacy owners or expose a production test seam. New test
coverage protects the native ABI and its new ownership boundary; it is not an
additional phone effect or an implementation-shape assertion.

## Exact preceding decoder/lifecycle acceptance

The earlier source `7c7b4ac` saved APK was installed, rather than rebuilding it:
`3f869f57599125f6a6fe0f01503499a7f98264016c3e29bc34474561f573522c`.
Its test APK is `967e30589a87281a109f760072f0f9df1191410c3036d2146c18e8157a2f3267`.
The actual previous installed checkpoint APK was pulled for rollback and verified
as `8f5e316f9dbbc7171ec1e97788ebe7d60e0d6a38694981ae5bbffe3297c7ea8f`.

- Before installation, canonical monitor and filtered local health proved natural
  idle: zero viewers/clients/warm sessions/pending stream commands, no encoder,
  capture, lifecycle wrappers, secure bypass or active dark lease. Release and
  private state ownership were recorded before any mutation.
- Canonical standard scoped `ticket_screen` installation passed in 57.966 seconds.
  Independent installed APK hash matched, config hash remained unchanged and the
  private saved state remained 0600/UID10205.
- Actual process-loss acceptance: force-stop at 23:36:21 UTC without STOP_ALL or
  configuration change left the app absent until 23:38:33. The saved RUNNING state
  and heartbeat remained retained past the reported 80-second freshness limit.
  Only canonical standard skip-build HEALTH was invoked; it passed in 31.358
  seconds, created a new PID and resumed supervision. Generated health proved
  root, SSH, VPN, management, supervisor and deploy true with heartbeat age22s
  within80s. Action journal, activation and code-cleanup fence hashes were identical.
- Actual private native decoder-ready events followed installation at
  23:34:40/23:34:51 UTC and health-only resume at23:38:44. This proves installed
  IDENTITY plus matching initial request-zero admission, separately from row handling.
- A fresh signed-in Brave nonactivating latest-unused command
  `ticket_a2824633-cbdb-4131-8de9-da8d981b7023` reached
  succeeded/complete/latest_unactivated/ticket_action_target_visible at23:46:02 UTC.
  The matching phone command was observed; pending command table was empty afterward.
  Canonical monitor reported healthy_live; root separately verified the real picture.
  The durable action row contains literal0/0 epoch/sequence, so no positive durable
  watermark is inferred from that row. Browser/relay frame proof is separate.
- One new synthetic-code request (no registration) reached succeeded/generated,
  exact phone-result browser-capture ACK and automatic phone cleanup. Phone health
  reported total17085ms, inactive terminal dark lease and cleanup_pending=false.
  Safe events proved first input23:50:20, browser ACK23:50:28 and final cleanup23:50:35.
  Code values and raw result pixels were not persisted in this proof.

Root closed its task viewer after the journey and restored saved user language,
HDR and quality preferences. Natural final quiet for this decoder release is
pending the ordinary 30-minute warm hold; no forced stop is quiet-state proof.
The newer command/monitoring source above has not been installed yet.

Local proof, logs and rollback APKs are under ignored `output/rust-migration/`,
owned by the regular workspace user. The live app config/state remains private.

## Predecessor decoder natural final quiet

At 2026-09-30T00:34:07Z, the exact installed decoder APK completed ordinary
warm-hold release without a force stop. Canonical monitor returned `healthy_idle`
with no failures or actions: durable desired false/viewers zero/pending commands
zero; relay no clients, phone connection or retained page-open sessions. Pixel
stream inactive/clients zero/capture idle, secure bypass false, root capture and
start/stop wrapper counts zero. Dark lease inactive/no failure, release
`ticket_action_terminal`, visible window zero; control-code checkpoint
`cleanup_pending=false`; ADB forwards empty. Evidence is the filtered
`output/rust-migration/pixel-decoder-settled-quiet-*` bundle. A transient 00:25
stale-state mismatch settled naturally by 00:26:10; no speculative repair ran.
The previously retained `ticket_d551...` command completed at 23:55:34, so its
last-command identity was not evidence of a new 00:20 physical action.
