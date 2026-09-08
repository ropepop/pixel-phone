package lv.jolkins.pixelorchestrator.app.ticket

/** One content-free, monotonic sample per admitted action. Missing phases are never zeroes. */
internal class TicketActionTiming(
  private val nowMillis: () -> Long,
  val startedAtMillis: Long = nowMillis()
) {
  enum class Phase(val field: String) {
    ADMITTED("admitted_ms"),
    INPUT_REQUESTED("input_requested_ms"),
    INPUT_RETURNED("input_returned_ms"),
    TERMINAL("terminal_ms")
  }

  private val phases = linkedMapOf<Phase, Long>()

  @Synchronized fun mark(phase: Phase) {
    phases.putIfAbsent(phase, (nowMillis() - startedAtMillis).coerceAtLeast(0L))
  }

  @Synchronized fun detail(): String = phases.entries.joinToString(" ") { (phase, elapsed) ->
    "${phase.field}=$elapsed"
  }
}
