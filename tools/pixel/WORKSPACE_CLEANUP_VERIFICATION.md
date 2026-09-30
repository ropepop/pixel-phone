# Native local cleanup — 2026-09-30

The existing cleanup and retention shell entrypoints now call the feature-gated
`pixel-workspace-cleanup` binary in the existing `pixel-health` package. The
embedded Python owners were removed after acceptance. Cargo's ordinary locked
incremental build supplies source freshness; no new cache, dependency or
alternate release path was introduced. The existing packaging/deploy hooks and
best-effort retention behavior remain.

`python3 tools/pixel/tests/test_workspace_cleanup_rust.py` compares the actual
old executables frozen from `62d1023` with the native CLI over 24 disposable
Git/filesystem journeys: dry-run/check/prune, flags and precedence, tracked-file
refusal, stale/recent/mixed directories, Unicode names, child and dangling links,
outside targets, invalid/negative/fractional/underscored windows. Protected
browser state and evidence stay unchanged.

Additional actual effect checks reproduce three unsafe old cases and prove the
native refusals: NaN retention could delete recent files, and a top-level linked
root or workload link could delete outside its approved tree. Non-finite
windows are rejected; root and workload links are skipped. File permission failure reports remaining output;
repair/retry succeeds. A closed reporting pipe fails before deletion. Real
HUP/INT/TERM during partial deletion followed by restart finishes safely,
preserving recent and protected files. Missing Git or metadata causes no effects.

The existing `test_deployment_artifact_retention_contract.sh` passes through the
actual full component packaging command with the real native executable. Only
the fixture compiler is replaced; its exact manifest/target/feature/bin/locked
arguments are checked. Full packaging prunes stale artifacts, preserves recent
ones and returns the original manifest identity; fast packaging does not prune.
All targets are disposable and removed on exit.

The actual canonical workspace dry-run matched the frozen old caller exactly:
zero tracked violations and zero stale candidates. No canonical destructive
cleanup was used for acceptance. First source rebuild cost 9.042 seconds and
501 MB maximum RSS; warm checked caller cost 236.8 ms/38.6 MB versus old Python
184.7 ms/17.1 MB. Investigation separated the ordinary build freshness check
from runtime: the native program itself took 110 ms/4.6 MB. The small warm Cargo
overhead is retained to prevent running stale code; first build is a preparation
cost. These are single observations, not tail-latency claims.

Locked release build, Clippy with warnings denied, shell syntax and caller/fault
checks passed. Evidence under `output/rust-migration/workspace-cleanup-*` is
regular-user accessible. Device cleanup is a separate native owner with separate
Android proof; this command has no device authority.
