# Ticket health monitor Rust candidate — 29 September 2026

The existing local, read-only monitor now has an opt-in Rust executable in the
workload-local `pixel-health` package. The Python implementation and current
recurring invocation remain unchanged pending separate live acceptance. The Rust
command does not wrap Python, alter APK interfaces, deploy or recover anything.

The candidate preserves configuration validation, CLI options and exits 0/1/2/3,
public sign-in protection checks without redirect following, operator identity
verification before SQL, query schema/type checks, absolute frame/report ages
calculated at query receipt, bounded warmth, Pixel health/lifecycle/resource
classification, safe report projection, atomic writes and conservative retention.
Output directories are 0755 and reports 0644. Commands drain stdout/stderr with a
256-KiB bound for each, including invalid UTF-8; timeout and SIGINT terminate the
command process group. Interrupted collection exits 130 and leaves the prior
latest report intact. The native TLS client is macOS `curl`, with `.curlrc`
disabled and no redirect, credential, cookie or retry options. Its output remains
bounded by the same command owner, and JSON body parsing reads at most 64 KiB.

The only added direct dependency is optional `serde`, already present in the lock
file through existing dependencies. No package version or transitive dependency
was added. The opt-in feature keeps the CLI out of normal Android/JNI builds.

## Offline verification

- Release build and warning-free Clippy pass for the opt-in binary.
- The existing 49 Python monitor tests pass. Their actual decision/configuration
  inputs were replayed through the Rust executable: 44 equal verdicts and 22
  matching accepted/rejected configurations, including strict types, missing and
  extra fields, non-finite values, exact 3000/3001-ms boundaries, warm/idle/live
  decisions, unknown evidence, resources and lifecycle failures.
- Both complete CLI implementations were run against a temporary loopback TLS
  server and local SSH/Spacetime/ADB substitutes in 18 scenarios: healthy idle,
  live and warm; stuck lifecycle; incomplete Pixel health; malformed SQL;
  operator mismatch; unreachable commands; resource failures; invalid UTF-8;
  stale/future/invalid/offset timestamps; query delay; expired/unbounded warmth;
  decimal rounding at a binary-float tie.
  Their report projections and exits match after excluding independent clock
  observations, elapsed duration and per-run evidence paths. Separate assertions
  verify stale ages, receipt delay, clock refusal and specific timestamp/warm
  errors instead of hiding these behind the comparison normalization.
  The full SSH/Spacetime/ADB argument sequences also match exactly, including
  the narrow lifecycle process filter and stopping before SQL on identity refusal.
- The decimal fixture first demonstrated a real migration mismatch: a 2.675%
  reading became 2.68 in the candidate versus 2.67 in Python. The shared Rust
  resource formatter now rounds the original float rather than first scaling it,
  matching the established decimal projection.
- Duplicate configuration keys, disabled repair policy, pause/config-check and
  snapshot-only paths leave the existing report unchanged.
- Both timeout paths finish within four seconds under a one-second command
  limit. Rust SIGINT exits 130 within three seconds, kills the sleeping fixture
  child and writes no report or evidence. The report replacement failure check
  preserves the existing target and removes the operation's staging file.
  A real 512-byte process file-size limit also forces a partial staging write;
  both commands fail without changing the complete old report or leaving a
  truncated final summary or staging file.
- Managed evidence stays within its configured limit; operator notes survive.
  Every generated report and directory has the expected readable modes. Reports
  contain no fixture secrets, raw ticket fields or private email strings.
  The HTTPS fixture verifies that login is never followed, even when `.curlrc`
  requests redirect following.

Reproduce from the canonical checkout:

```bash
cargo build --locked --release --manifest-path orchestrator/android-orchestrator/pixel-health/Cargo.toml --features ticket-health-monitor --bin ticket-health-monitor
cargo clippy --locked --manifest-path orchestrator/android-orchestrator/pixel-health/Cargo.toml --features ticket-health-monitor --bin ticket-health-monitor -- -D warnings
python3 -B -m unittest discover -s tools/observability/tests -p 'test_ticket_health_monitor*.py'
```

## Local measurements

Measured the release executable and Python CLI on this Mac. The first invocation
is not a reboot or cleared-cache measurement. Ten subsequent config/snapshot
invocations supply the warm median and maximum; `/usr/bin/time -l` supplies each
process's peak resident memory. Five complete runs per command use the same
loopback TLS and healthy-idle command fixtures, with 5/5 successful runs each.
These fixtures include external subprocess costs but do not model production
latency, availability or physical-device work.

| Local path | Python first / warm median / warm max | Rust first / warm median / warm max | Python / Rust median peak memory |
| --- | --- | --- | --- |
| Configuration check | 67.58 / 66.31 / 67.02 ms | 7.32 / 4.69 / 5.24 ms | 34,144,256 / 2,113,536 bytes |
| Snapshot evaluation | 66.18 / 65.94 / 67.93 ms | 5.06 / 4.46 / 4.86 ms | 34,242,560 / 2,244,608 bytes |
| Full healthy-idle fixture | 1318.27 / 1101.48 / 1124.09 ms | 638.22 / 644.72 / 653.25 ms | Not measured for the subprocess tree |

The release executable is approximately 703 KiB. Compilation is a separate
build step; unattended runs should execute the already-built command.

## Acceptance boundary

No production HTTP, SSH, Spacetime, ADB, browser or phone action was performed in
this lane. No recurring caller was switched, Python removed, APK rebuilt for
this monitor, or service restarted. Production transport behaviour, the actual
canonical invocation, current report evidence and signed-in page/device proof
remain for the root owner. A lost acknowledgement cannot cause a replay here:
the monitor has no consequential external effect, recovery command or retry.
