# Canonical native Ticket health monitor — 2026-09-29

## Change and acceptance boundary

The existing `python3 tools/observability/ticket_health_monitor.py` command now
executes the built native monitor directly. Python retains only the existing
caller filename and an actionable missing-binary error. Collection, schemas,
classification and atomic reporting have one Rust owner in the existing
`pixel-health` package. There is no automatic Cargo build, runtime Python
fallback, second timer, new authentication location or phone recovery action.

The launcher resolves the binary from its own repository path and calls
`os.execv` with the original arguments. Working directory, environment, standard
streams, process ID and signal delivery are inherited. The native owner retains
full-run exits 0/1/2/3 and interruption exit 130. An unavailable native command
prints the build command to standard error and exits 2 before any probe/report.

Actual caller trace found the repository runbook and its regression tests plus
the ops source-footprint rule. No additional monitor schedule was created.
Existing Python callers keep their filename/arguments. Former imported APIs
remain only in a test-owned temporary copy loaded from frozen Git baseline
`076d0ff`; the production launcher never imports, loads or falls back to it.
Tests read the current canonical configuration independently of that baseline.

The protected-navigation probe remains `/admin`; public welcome `/` is HTTP 200.
The legacy report field `root_http` continues reporting the configured protected
page's response. No threshold, enum, authentication or classifier was relaxed.

## Verified local behavior

`python3 -B -m unittest discover -s tools/observability/tests -p 'test_ticket_health_monitor*.py'`
passed **57 tests in 54.250 seconds** with the final native binary below.
The original 49 imported contracts remain, yielding **44 verdict** and
**22 configuration** comparisons through both direct native execution and the
canonical Python caller path. All **18 complete collection scenarios** match
the frozen former Python owner, direct binary and launcher.

Existing loopback TLS and command fixtures verify collection order, malformed
and unavailable surfaces, delayed/stale/future timestamps, live/warm/idle
decisions, resource faults, identity rejection and safe bounded output. They
also verify untouched reports on paused/config-check/error paths, privacy,
retention, atomic replacement and forced partial-write failure. Reports remain
0644 and generated directories 0755.

Additional canonical checks prove relative configuration paths use the caller's
working directory, standard streams/exit status match the native command, and
fixture environment settings reach the real collectors. `ps` observes the
original caller PID running the native binary after exec. SIGINT reaches that
same process, exits 130, removes its bounded command child and preserves the
prior report without writing interrupted evidence. A copied launcher in an
isolated temporary checkout proves a missing binary returns exactly 2, prints
the build command and leaves the prior report untouched without collecting.

`git diff --check` passes. The launcher remains executable/user-owned 0755;
tests, runbook, architecture and this report are user-owned 0644. Temporary test
baselines are owned/readable by the regular workspace user and cleaned at exit.
No live collection, browser/profile access, production/device mutation or APK
build occurred in this lane.

## Final command provenance and separate live proof

The final release binary SHA-256 is
`3c34c94bab8a0a3f955b49d2eaafe25615073967068dfd5c90958d6fab8acbb0`.
It matches the original candidate built from monitor source at `076d0ff`; the
final Cargo manifest retains the original dependency feature set. The protected
target configuration was corrected at `c66dc93`. The decoder candidate at
`f4b72a3` adds no native-monitor implementation change.

Root's supplied actual reports at ops
`output/rust-migration/monitor-{native,python}-r2-live.json` were read only through
their safe summary/public fields. Both report timestamp `2026-09-29T19:28:08Z`,
`healthy_live`, active viewer, zero failures, no actions and no repair attempt;
public results match: protected page 302, livez 200 and unauthenticated detailed
health 401. Those reports used the development native binary
`c27568ee8c77fd2414a6f1eeabc8a6f0f62e5adcab7a1cf737828100990cf59a`
with a temporary decoder-development dependency feature. Its exact binary also
passed all 55 earlier local monitor tests before that unnecessary feature was
removed; final original-feature binary passed all 55 checks again and the
expanded 57 canonical checks above.

**Actual final-binary/canonical full invocation and natural quiet-state proof
remain root-owned acceptance work.** The prior active-viewer evidence and local
fixture passes do not substitute for those journeys. Standalone monitor health
still does not prove authenticated browser presentation, durable/physical action
outcome, HDR brightness or a second physical failover device.

Build once, then retain the existing caller:

```bash
cargo build --locked --release --manifest-path orchestrator/android-orchestrator/pixel-health/Cargo.toml --features ticket-health-monitor --bin ticket-health-monitor
python3 tools/observability/ticket_health_monitor.py --config tools/observability/ticket_health_monitor.config.json --check-config
```

Only root's authorized actual full run should omit `--check-config` during this
acceptance window.

## Source-footprint accounting

Ops checkpoint `4baeda1c` updates only source-selection rules and their checks.
The immutable baselines, line/byte limits and protected-file rule are unchanged.
Counting now includes actual Rust HTTP source/build/embedded error page, dedicated
Pixel Rust media/visual/checkpoint/command owners, the native monitor and Rust
keyboard. Mixed shared orchestrator/health/lib/JNI files retain the original
reproducible-scope exclusion.

All four source-contract tests pass, including immutable baseline verification.
The accurate counter itself remains **not at target**, returning 1: ops 49,217
lines/1,909,604 bytes; Pixel 27,512 lines/1,073,688 bytes; combined 76,729 lines
against the unchanged 37,622 cap, 2,983,292 bytes. The ten protected Pixel files
also differed from their historical baseline before this lane. This checkpoint
does not claim the broader reduction contract is complete or hide retained old
owners until their separately-authorized retirement.
