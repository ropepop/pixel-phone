# Native runtime cleanup — 2026-09-30

Rust now owns the existing allowlisted cleanup, protected path admission,
Termux generation retention, log rotation/total limits and root-history
DB/WAL/SHM rotation, rollback and restart reconciliation. The existing
`pixel-runtime-cleanup.sh` path is a three-line sibling executable launcher.
Scheduling, active/rollback protected-generation construction, the mutation
lock, root execution/timeouts and report storage remain their existing Android
owners. There is no additional database, maintenance schedule or root authority.

The standalone Android executable is built in the existing `pixel-health`
package and NDK script, with the existing ARM64/API29/16 KiB linker settings.
Gradle packages its generated asset; the runtime installer installs the native
executable before the launcher during full and `runtime_cleanup` asset sync.
The host-only workspace cleanup binary is excluded from the Android build.
Small existing platform commands still perform filesystem/process effects;
the cleanup policy and rollback state are owned by Rust. Subprocesses preserve
the existing root executor's process group and complete-tree cancellation.

Verification actually performed:

- The complete retained executable runtime cleanup contract passed against
  the native CLI. It covers active/rollback artifacts, protected paths,
  30-day retention, 24-hour receipts, log per-file/total bounds, root history
  above 2 GiB, pre/post root checks and partial-move rollback.
- `pixel-health/tests/runtime_cleanup_e2e.py` compares the actual frozen shell
  owner at `9d1aa2142b456996f498e4bf74256e2d8aa0a71e` with the real native
  process. All 27 Linux journeys passed; 26 macOS journeys passed, excluding
  invalid-byte filenames which APFS refuses. Comparisons include complete TSV
  receipts, exact resulting file bytes/modes, preserved protected files,
  partial/failed rollback, backup deletion failure, interrupted reconciliation,
  dry-run, zero limits, malformed Unicode retention names, real DNS process
  admission, HUP/INT/TERM and whole-group SIGKILL followed by recovery. The
  signal fixture verifies no active root-check child remains. Every history
  file and process belongs to a disposable fixture.
- Actual Linux compilation used Rust1.94 in an owned container with the shared
  1 CPU/1 GiB/no-swap cap. Local release build, warning-denying Clippy and the
  existing NDK library+executable build passed.
- The sole phone operator executed `runtime_cleanup_android.sh` on the actual
  Pixel. Eight old/native journeys matched complete receipts and exact
  filesystem bytes/modes/owners: normal, protected, dry-run, root denial before
  and after rotation, real partial rename/rollback, missing-DB interrupted
  restoration and successful reconciliation. All roots/history were remapped
  below an owned `/data/local/tmp/pixel-cleanup-*` subtree. An `am` adapter
  prevented application effects. No live Magisk DB was read or changed.
  Independent absence verification confirmed fixture cleanup. Output is in
  ignored `output/rust-migration/pixel-runtime-cleanup-android-fixture.log`.

The frozen former owner SHA256 is
`044272114774d47814c88fa193bfdba84f478fdc7e2ee894ffa8e8085a8cbfe8`.
Native policy source SHA256 is
`374a2200b87429bf16347991aad7c3d5270d7e334b7d7c620a74adfc98703815`.
The exact ARM64 executable used for actual Pixel fixture proof is
`39ff72e9f49a409573448bffc37cf17c7b71661ea764265377db33056c16a4ca`;
the measured-needed SHA2 assembly feature belongs to the shared package.

Migration regressions were caught before source activation: creating separate
subprocess groups could leave a nested root check after owner death; preserving
the existing outer group fixed the actual SIGKILL/restart comparison. UTF-8
decoding could reject raw-byte protected names or confuse targets; byte-exact
protection, find output, argv and receipts now preserve the former boundary.
Both controls failed for their intended reason before repair and passed after.

All generated source and build outputs are accessible to the normal workspace
user; owned root fixtures were removed. This proves compiled Android CLI
behavior and packaging source. Final clean APK installation, installed asset
identity and ordinary production maintenance acceptance remain the sole phone
operator's next gate; no live root-history rotation is claimed here.
