# Active cleanup caller and health policy migration

`NightlyCleanupSupport` now calls `pixel-health::cleanup_policy` for cleanup
receipt parsing, byte/count/category summaries, outcome/report projection,
protected-path trim/first-entry deduplication, current/rollback release retention,
recent Termux cohorts, caller manifest admission and durable report projection.
`RuntimeCleanupComponentController` calls the same owner for report protocol,
freshness and failure/dry-run/stale classification. Java time/JSON compatibility,
root reads/writes, mount namespace and one cleanup invocation stay at their
existing platform sites. The caller keeps protection for retained retired
manifests/releases; migration does not silently prune compatibility state.

Frozen full `9d1aa21` caller comparison passes 10,354 cases, including actual
private source methods, successful and rejected manifests, exact exceptions,
complete protocol/report results, wrapping byte totals, Unicode string ordering,
cohort selection, root-read projection and full current/rollback protection
plans. Health adds 200 comparisons against the frozen controller with only its
clock injected. Existing full Facade cleanup journeys pass.

The native executable asset is generated beside the existing JNI library and
installed before its shell launcher in both full and cleanup-only sync. A
meaningful installer fixture verifies that order and scoped absence of unrelated
runtime files. Actual Android CLI acceptance separately passed all eight
disposable old/native receipt/filesystem/mode/owner journeys: normal, protected,
dry-run, root denial before/after rotation, partial rename rollback, interrupted
missing-database recovery and successful reconciliation. Source SHA-256:
`374a2200b87429bf16347991aad7c3d5270d7e334b7d7c620a74adfc98703815`;
actual tested executable:
`39ff72e9f49a409573448bffc37cf17c7b71661ea764265377db33056c16a4ca`.
All roots/cache/tmp/history paths were remapped, application effects stubbed,
and no live Magisk database touched. Owned fixture/staging absence was verified.

The installed APK fixture will exercise caller JNI and read the existing cleanup
health report. It never triggers cleanup. Production launcher/asset identity and
the normal service lifecycle remain pending the final clean APK acceptance.
