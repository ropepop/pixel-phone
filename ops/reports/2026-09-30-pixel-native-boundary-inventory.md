# Active Pixel owner and platform boundary inventory

This inventory traces active callers. It does not treat a Kotlin/Java file as an
Android adapter merely because it imports the framework. Final APK acceptance
must use a clean source, exact artifact and actual device/browser journeys.

| Owner | Active caller / decision | Native boundary and remaining effect adapter |
| --- | --- | --- |
| Ticket command subscription | WebSocket worker's current inbox, exact scope, expiration, start priority and ACK/outcome routing | Rust ticket_command / ticket_command_state; socket/channel/client and retained result storage remain at original sites |
| Ticket monitoring | Worker/stream/root capture: freshness, classification, epoch changes, rechecks and report cutoff | Rust ticket_monitoring; one coroutine and existing read-only probe/publication effects |
| General telemetry | Service/dashboard: metadata admission, queue limits/expiry, in-flight exclusion, batching and retry | Rust telemetry; one RAM DTO store/lock and actual client send/cancellation |
| Capture demand/cadence | Exact video socket and rooted hardware helper | Rust ticket_capture; synchronized state value, one helper delivery and monotonic framework clock |
| Phone-control context | Stream service and publisher: agreeing evidence, context revision, freshness, exact-context lookup, clock projection and readiness | Rust ticket_control; MutableStateFlow and one transport/renewal coroutine |
| Visual action | Action executor/worker: admission, retained journal outcome, reconciliation, consensus, card/tab selection, current-detail proof binding | Rust ticket_action_policy; journal commit/read-back, actual capture/navigation/Accessibility/input remain in their existing owner |
| Supervisor restart | SupervisorEngine's bounded crash-loop/backoff | Native maintenance backoff; clock and original restart call sites |
| Weekly cleanup schedule | Supervisor/Boot service's next local calendar run and alarm choice | Native maintenance selection; Java ZoneRules/ZonedDateTime retain the device's current timezone/DST database, AlarmManager/PendingIntent remain platform calls |
| Artifact installer | Facade/RuntimeInstaller bootstrap and component release admission/order; sync/release local-source admission, cache validity, checksum and private cache deletion boundary | Rust artifact; Java Path normalization/files copy plus RootExecutor permission fallback and existing root unpack/permissions effects |
| Encoder warm/cold startup | Root helper startup input spacing, first IDR, suppressed siblings, boundary drain, input timestamp/capacity admission, generation rollover, recovery grants and capture visibility | Rust ticket_capture via native media bridge; Java retains platform frame references, object identity and actual MediaCodec/Surface I/O |
| Active brightness verification | AndroidTouchBrightnessDeviceController target/restore/panel/remote fallback and numeric conversion; TouchBrightnessRuntime panel-sleep classification | Rust touch_brightness; exact Float wire bits, Settings/sysfs effects and coupled raw-touch/visible-window arbitration retained |
| Cleanup caller and health | NightlyCleanupSupport receipt/protection/retention/summary/outcome/report decisions and RuntimeCleanupComponentController freshness | Rust cleanup_policy; one root I/O effect owner, original Java time/JSON compatibility and mutation lock |
| Device runtime cleanup | Scheduled/manual/frequent cleanup launch | Rust standalone pixel-runtime-cleanup; native-before-launcher asset installation, root OS tool effects and original scheduler/mutation lock |
| Rooted lifecycle and management readiness | Ticket start/stop/health callers; registry management health and transport `--report` | Rust standalone ticket_lifecycle/management_health; original command modes, POSIX config sourcing and Android/upstream observation effects |
| Component redeploy acceptance | Dashboard/service redeploy, rollback and neighbor checks | Rust redeploy_policy through existing JNI; Kotlin typed DTOs, clock/probe/delay and mutation order |

## Concrete capture bindings

Installed NDK 27.1 headers contain `AMediaCodec_createInputSurface`,
`AMediaCodec_getOutputFormat`, `AMediaCodec_dequeueOutputBuffer` and
`AMediaCodec_setParameters`. Lack of a native encoding API is not a blocker.
The current compatible adapter still invokes `android.window.ScreenCapture`
through reflection, obtains its private result/HardwareBuffer/ColorSpace, wraps
that buffer in a Bitmap, and draws into a Surface using a hardware Canvas and
Paint. These hidden screen-capture/result-release bindings are not public NDK
capture APIs. The existing secure-layer behavior and capability selection remain
at those platform binding sites. Codec assembler/framing, crop/geometry,
recognition/checkpoint, cadence/demand and extracted encoder policy are native.
Encoder resource teardown remains a small platform effect sequence: try every
independent release; pin the source if codec release fails. Java object identity
in the input-reference adapter is intentional and bounded.

The remaining capture checks are explicit, rather than an assertion that every
Java conditional was migrated. `EncodingOwner.encodeSession` drops a borrowed
picture when its capture start is at least three seconds old; `EncodingOwner.run`
waits a fixed 500ms after a failed MediaCodec session, after native generation
rollover. `supportsCbrBitrateMode` queries that actual codec's Android capability
object before setting the optional CBR format field. `SecureScreenCapture`
probes release-dependent reflection methods, waits for its callback and wraps
the returned HardwareBuffer/ColorSpace. These immediate checks remain next to
their exact platform operations. No missing NDK encoding API is asserted as a
reason for retaining them.

The concrete Java reference and release boundary is
`TicketRootHardwareH264CaptureMain` -> `TicketNewestFrameHandoff`,
`TicketSharedFrameLifetime`, `TicketCodecOutputCopy`, `TicketCaptureResult`
and `TicketEncoderTeardown`. They retain the actual opaque Java picture/callback
references: monitor wait/notify and replacement release, last-reader GPU
release, codec-buffer copy before release/reuse, late or unclaimed callback
cleanup, and independent teardown attempts with source pinning after a failed
codec release. These are the retained object-effect methods; they are not a
blanket exception for encoder admission, cadence, framing, visibility or
recovery policy, whose native owners are listed above. A future JNI object
ownership change would need its own reference/release proof; this acceptance
does not claim those methods are already Rust.

## Scope exclusions and remaining independent owners

A fresh private settings whitelist at `pixel-active-automation-scope.json` showed
runtime_state `disabled`, with no enabled key (store defaults false). Speedtest
wake/foreground/dispatch policy is therefore disabled under the user's active-
services scope. It was not changed. Touch brightness is separately enabled.

`TouchBrightnessRuntime.kt` retains active coupled event/effect arbitration:
touch/window generations, blackout/power press, visible-window arbitration,
bounded physical mutations and restoration sequencing. The bounded brightness
verification/conversion/fallback rules and panel-sleep classification are now
native, with frozen complete source parity. Its coroutine/controller are not
blanket exceptions for all pure decisions. Actual emitted-light/raw-physical-
touch/power-target proof is unavailable to this remote session; software/sysfs,
root-injected input and browser screenshots cannot establish that physical layer.
The existing coupled owner remains working until native extraction can receive
that separate actual-target acceptance. This is the explicit remaining boundary.

Its active call chain is `SupervisorService` -> `TouchBrightnessRuntime` ->
`AndroidRootTouchMonitor` / `AndroidTouchPowerButtonPolicyController`, with
`PhoneAutomationServiceBridge` coordinating exact physical-visibility blockers.
Remaining non-Rust decisions include `RootTouchDeviceDiscovery` device scoring,
`RootTouchStateTracker` raw slot/button/tracking-ID interpretation, zero-touch
confirmation and debounce; visible-window generations and source readiness;
cancel-and-join ordering before a restore or immediate zero; side-button
revocation; and original power-setting checkpoint/readback/restoration.
They share the retained physical owner and its existing at-most-one mutation
sequencing. The missing acceptance layer is a human physical touch/power target
and emitted-light observation, not the existence of a native parser or arithmetic
API. The new pure brightness bridge does not silently take ownership of those
remaining decisions.

The focused final rerun passed 85 tests in eight existing suites with no
failure, error or skip: 45 touch-runtime cases, ten raw-touch cases, six
power-checkpoint cases, three readiness cases, ten dark-lease cases, four
picture-lifetime cases, two capture-process cases and five settings-restoration
cases. These verify software sequencing and the retained effect adapters;
they do not replace the missing physical observation. Durable user-readable
counts and output are `pixel-retained-owners-final-test-summary.txt` and
`pixel-retained-owners-final-tests.log` under ignored `output/rust-migration/`.

At 04:52:52Z the installed APK was independently rehashed as
`a66620b109ecadde415225c64cbfc56df125914bbb047028d2e91fb5b88370ae`,
still the accepted b4a66c2 artifact. Current device protocol was
`ticket-stream-2026-09-27-monitor-rechecks-v396`, THF1/all-intra,
994x2046, 8Mbps and 1fps. During legitimate page warmth it had one encoder
and no stale capture, blank failure, dropped frame or restart. The later
inspection-picture and exact old/native bridge reconnect receipts in the final
device report prove the same protocol through its real signed-in caller.

Device cleanup protected paths, age/size retention, allowlisted log budgets and
interruption-safe Magisk rotation/root recheck are now in the native CLI lane.
Weekly alarm policy remains separate. The existing complete disposable contract,
27 actual capped Linux old/native cases and eight actual Android old/native
receipt/filesystem/mode/owner cases passed; no live Magisk database was touched.
Production launcher and installed asset are accepted with the final exact APK,
separately from those disposable proofs.

Local operator `tools/pixel/cleanup_workspace.sh` and `artifact_retention.sh`
are owned by the root's separate host workspace-cleanup lane. They are not APK
callers and cannot be accepted through phone deployment. Root owns their native
CLI packaging, old/native disposable filesystem/interruption/permission proof
and final service inventory; the Android asset does not include that host-only
feature.

## Retained ViVi account business policy

The September 30 closure source audit found an active first-party Kotlin owner
in `app/ticket/TicketViviReauth.kt`; it is not an Android binding. Its request
parser still owns exact version-2/3/4 payload keys, request namespaces, trimmed
request/revision limits and reset/logout/redetection mode selection. It also
owns signed-in and terminal-ready classification, original-ticket restoration
and navigation-transition proof, mutation-phase uncertainty, exact journal
matching, retained terminal outcomes and commit/read-back equality.

These are live callers, not historical references:
`TicketSpacetimeWorker.dispatchViviReauthCommand` parses and reconciles before
claiming database ownership and reading the matching credential revision;
`publishNextPhoneResult` matches the terminal request before settlement.
`TicketStreamService.handleTicketSpacetimeViviReauthCommand` and
`reconcileTicketSpacetimeViviReauthCommand` use that policy before the single
phone-mutation lane. `runViviReauth` / `performViviInAppLogout` and the restoration
flow persist each possibly dispatched phase and prove its successor. The Rust
`pixel-health/src/ticket_command.rs` decoder admits the outer `vivi_reauth`
command only; it does not replace this typed account policy.

The accepted latest-ticket, inspection-code and bridge reconnect journeys did
not exercise account switching, logout/login, full reset, retained linked-device
identity or account restoration after an interrupted dispatch. No designated
disposable linked ViVi account and separately authorized full-reset target were
available for those journeys. Version 2 executes `pm clear --user 0 com.pv.vivi`;
the existing architecture records that clearing local identity can require an
external device-link reset. Existing login/logout software fixtures and
`TicketVisualTerminalOwnershipTest` restoration cases cannot establish that
actual account/device behavior. Acceptance of a native replacement therefore
still requires those bounded disposable-account journeys, including interrupted
mutation reconciliation without replay and fresh restoration/watermark proof.
There is no demonstrated Rust API limitation for extracting the pure rules.
The current working Kotlin owner remains; this report does not claim that all
first-party Kotlin business decisions have moved to Rust. This closure audit
changed documentation only and did not submit an account or phone action.

## Native rooted Ticket lifecycle policy and packaging

The current source candidate routes `pixel-ticket-start.sh`,
`pixel-ticket-stop.sh` and `pixel-ticket-health.sh` through two-line exec
adapters into the existing arm64 `pixel-runtime-cleanup` executable. Their
original basenames remain dispatch arguments, so old lock-owner readers still
recognize a native owner during a coordinated rollback. The original lock
helper path is inert; lock arbitration has one native owner in
`pixel-health/src/ticket_lifecycle.rs`. Registry/module paths, supervisor
controllers, first-setup callers and the fast redeploy health caller retain
their existing public command interfaces.

Rust owns flags, exact configuration equality, readiness and stop waits,
stale/live/reused/unreadable PID arbitration, contested-directory rechecks,
signal release and exact secure-capture restoration. It preserves the older
one-line saved-setting migration, independent setting mutations/readbacks and
retention of the record until both values are restored. POSIX configuration
sourcing is a bounded setup adapter; Android `am`, `settings`, `resetprop`,
`dumpsys` and upstream `curl`/`nc`/`ss` still perform platform effects. Existing
cleanup process/signal handling is reused; no additional binary, dependency or
runtime framework was introduced.

The existing Android build already packages this standalone executable before
JNI is needed. `RuntimeInstaller` now installs it before Ticket entrypoints
in full and `ticket_screen` sync, and `runtime_asset_freshness.sh` checks that
exact executable alongside the launchers and keyboard. The asset effect owner
now copies to a same-directory owned stage, applies permissions/context and
renames it into place; overwriting a running ELF in place can fail with
ETXTBSY. The exact emitted Kotlin installer command passed an owned Linux
running-executable regression: the old copy failed, atomic publication succeeded
while the old process remained alive, and a partial stage copy preserved the
current executable and removed its stage.

Local proof passed 55 frozen b4/native journeys: 37 exact CLI stdout/stderr/exit,
effect-order and durable bytes/modes comparisons, ten actual owned PID/lock
cases, HUP/INT/TERM release plus SIGKILL/stale-owner recovery, and four actual
loopback HTTP200/503 curl/nc journeys. Seven retained JVM-to-installed-launcher
secure restore cases and six focused installer cases passed (15 cases across
the full installer module). A real cancellation regression failed while the
root-copy coroutine was already cancelled, then passed after local extracted-
asset deletion reused the repository's NonCancellable/IO cleanup pattern.
The native health monitor
now detects exact native executable/mode arguments as well as old shell owners;
its new native-owner/privacy case and existing 18-case three-caller collector
comparison passed without changing thresholds or recovery authority.

The reviewed clean source `970fd0056dbf8b61c9a8eb08d21db28b79a9b3da`
is now installed as release `rust-lifecycle-970fd00-20260930`. Its APK and native
assets match their built bytes. Actual installed health/start/restart and direct
native stop passed; the latter independently proved no Ticket listener/service,
restored secure settings and a cleared restoration record. The canonical
read-only monitor reported healthy idle with no stuck lifecycle owner. The
accepted b4 APK, executable and all paired Ticket/management launchers remain
retained for recovery. One fresh signed-in inspection passed exact-picture ACK
and terminal cleanup; paired b4 rollback and 970 restoration both passed actual
saved-session browser reconnect. An existing activation-expiry schedule made
one separate nonactivating latest-ticket refresh during recovery. Final natural
quiet passed independently at 10:45:51Z: zero retained warmth/viewers/video
clients, desired false, inactive capture/inactivity/dark lease, zero encoders/
wrappers/stale capture, cleanup checkpoint false and no stuck lifecycle owner,
repair or failure. No hold or cleanup was forced.
See the combined installed receipt in
`2026-09-30-pixel-final-native-device-acceptance.md`; source/effect receipts
remain in `output/rust-migration/pixel-ticket-lifecycle-*`.

## Native redeploy and management acceptance candidate

`OrchestratorFacade` is an active caller from `DashboardViewModel` and the
SupervisorService redeploy action. Its previous health overrides, disabled
readiness, configuration bounds, route/release/retry/rollback metadata,
watched-neighbor ordering, target reset, persistent-regression/stability windows,
deadline outcomes and result classifications now call `NativeRedeployPolicy`.
Rust owns those decisions in `pixel-health/src/redeploy_policy.rs`; the existing
coroutine supplies time and health snapshots and preserves probe, delay,
installation, stop/start and rollback order. Legacy quiescence command builders
remain exact Android root-command adapters selected by native metadata. The
existing unsupported component error type/message is preserved, including the
management route; this migration does not introduce a management redeploy.

Actual JNI comparison passed against the original b4a66c24 method bodies and
DTOs extracted from Git (complete Facade source SHA
`4b4e9e2878df323d837c08fd1de528db722a8dbfe6760b717e0096ee8ea441da`).
It covered 2,962 admission/configuration/order comparisons, 960 complete frozen
loop journeys with 6,720 outcome/probe/delay comparisons, and 170 route,
unsupported-error, rollback-code and ordered-message comparisons. The nine
retained Facade redeploy integration tests passed, including late target
recovery, transient neighbor failure, disabled VPN, fast local Ticket,
standard full-health Ticket and pre-mutation rejection. Frozen clock/grace/
deadline cases include backward motion, exact boundaries and Long overflow.
Receipts are in `output/rust-migration/pixel-redeploy-parity.log`.

The registry's management health command, general native probe builder and
`tools/pixel/transport.sh --report` still converge on the same command path.
That path is now a three-line exec adapter to `management_health` in the
existing packaged standalone executable. Native code owns command readiness,
VPN/tailnet/wireless/SSH listener identity, network classification, public-IP
selection, password/key consistency comparisons, acceptance precedence and
ordered report projection. POSIX SSH/VPN/DDNS configuration sourcing remains
one private captured pipe; platform/upstream commands perform read-only
observations. No separate reporter, credential writer, new binary or dependency
was introduced. Native general health still interprets this report and applies
its existing configuration-aware authentication gate.

The actual native executable behind the source launcher passed 53 complete
frozen-shell comparisons: all 51 report keys in exact order, raw stdout/stderr
and exit status, ordered root/command/VPN/tailnet/wireless/SSH/auth failures,
disabled-neutral behavior, password/key drift and recovery, IPv6 ownership and
loopback rejection, ordered unique ADB ports, network classes, local/deep
separation, POSIX sourcing/default/output behavior, cache/chroot-adapter
fallback and private-fact protection. The frozen b4 shell SHA is
`c721a6daa577bd105ff4b6ef902e9a03ff68c78bd7511263f8ce2d7bcf852df8`;
native source SHA is
`ba2f150840d2498fd5a6568b8a99453276b80f64374ab198db6f00ab7565b370`;
tested host executable SHA is
`49c7db507d48975058e79e2777006e59df677de0ca97d3af4d59aff86f86ec6f`.
The same executable/library passed Clippy with warnings denied. Controlled
adapter fixtures do not claim actual deep external-network/chroot execution,
installed-phone acceptance or a source-size reduction.

Full/SSH/VPN/management asset sync now installs the executable before this
launcher; SSH/VPN freshness includes that dependency. The complete 16-case
installer module passed, including cancellation-safe extracted-file cleanup
and native-before-launcher ordering for all three management callers. An
explicitly selected Android verification method will invoke this same installer
for management assets only, compare actual before/after authentication rows
and acceptance, and verify APK/installed byte identity and executable modes.
It is gated by `management_asset_sync=true`; ordinary health checks do not
install assets. Installed JNI redeploy fixtures exercise exact stability,
regression and disabled-gate fences without live service mutations.

The clean combined release above passed five actual installed Android checks,
including native redeploy regression/grace fences and explicitly selected
management-only asset sync. All 51 actual old/new management report rows are
byte-identical, both acceptance exits are zero, and current configuration and
authentication observations remain unchanged. The working reauthentication and
physical-touch owners and their explicit missing-target boundaries above remain
retained. These installed checks are distinct from the supporting host fixtures
and the accepted authenticated inspection/paired recovery journeys. Final
natural quiet passed independently as recorded above; these checks do not
exercise the retained reauthentication or physical-touch business owners.
