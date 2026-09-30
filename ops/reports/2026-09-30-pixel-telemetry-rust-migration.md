# Pixel telemetry queue native policy — September 30

The existing `pixel_health` library now owns safe draft/build/correlation
validation, queue eviction admission and stable priority/order decisions,
in-flight exclusion, expiry selection, due-event selection, retry arithmetic
and saturating counter/deadline addition. Metadata crossing JNI contains only
safe enums/scalars and bounded release/correlation tokens. Event payloads stay
in the existing Kotlin DTO store. No operational database, file queue, timer,
credential, service configuration, dependency or parallel sender was introduced.

Kotlin retains the existing synchronized lock, RAM-only payload/recent-event
storage and state application, secure randomness, clock access, transport await,
cancellation release and actual network delivery. Atomic selection and marking
in-flight remain inside the same lock; network delivery remains outside it.
The 4-MiB/24-hour/20-recent bounds and dropped-event summary are unchanged.

Before authoring the comparison, the existing client/runtime test ownership
and SupervisorService/dashboard callers were traced. The new comparison protects
this native ownership boundary; host tests cannot prove installed Android ABI,
so one synthetic on-device queue check is also provided. Neither uses a
production test seam or transmits a synthetic event to production.

The previous actual owner is frozen at `dd41627` in ignored build output.
Existing client/runtime tests pass through the native adapter. The independent
comparison covers draft/build/token boundaries and retry overflow behavior,
plus complete enqueue/drain/delivery/recent/byte/count journeys across four
capacities and three expiry limits. Real transport effects are compared at the
same DTO boundary using synthetic send outcomes; this excludes real HTTP cost.

Device acceptance and exact APK identity remain pending. Completion requires
installed JNI, existing app/supervisor/Ticket behavior, actual safe telemetry
arrival at the single private logging authority and natural final quiet. An
uninstalled native candidate is not reported as a deployed replacement.
