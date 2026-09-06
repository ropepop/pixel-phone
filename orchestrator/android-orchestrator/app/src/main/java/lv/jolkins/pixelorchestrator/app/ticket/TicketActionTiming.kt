package lv.jolkins.pixelorchestrator.app.ticket

/** One content-free, monotonic sample per admitted action. Missing phases are never zeroes. */
internal class TicketActionTiming(
  private val nowMillis: () -> Long,
  val startedAtMillis: Long = nowMillis()
) {
  enum class Phase(val field: String) {
    ADMITTED("admitted_ms"),
    LANE_READY("lane_ready_ms"),
    PANEL_READY("panel_ready_ms"),
    CAPTURE_READY("capture_ready_ms"),
    INITIAL_PROOF("initial_proof_ms"),
    INPUT_REQUESTED("input_requested_ms"),
    INPUT_RETURNED("input_returned_ms"),
    CLEANUP_STARTED("cleanup_started_ms"),
    CLEANUP_FINISHED("cleanup_finished_ms"),
    TERMINAL("terminal_ms")
  }

  enum class Work(val field: String) {
    HELPER_LAUNCH("helper_launch_work_ms"),
    PANEL_VERIFICATION("panel_verification_work_ms"),
    CAPTURE("capture_work_ms"),
    CLASSIFY("classify_work_ms")
  }

  private val phases = linkedMapOf<Phase, Long>()
  private val work = linkedMapOf<Work, Long>()

  @Synchronized fun mark(phase: Phase) {
    phases.putIfAbsent(phase, (nowMillis() - startedAtMillis).coerceAtLeast(0L))
  }

  @Synchronized fun recordWork(kind: Work, durationMillis: Long?) {
    if (durationMillis != null) work.putIfAbsent(kind, durationMillis.coerceAtLeast(0L))
  }

  @Synchronized fun addWork(kind: Work, durationMillis: Long) {
    work[kind] = (work[kind] ?: 0L) + durationMillis.coerceAtLeast(0L)
  }

  @Synchronized fun detail(): String = (
    phases.map { (phase, elapsed) -> "${phase.field}=$elapsed" } +
      work.map { (kind, duration) -> "${kind.field}=$duration" }
    ).joinToString(" ")
}
