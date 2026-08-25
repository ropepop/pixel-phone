package lv.jolkins.pixelorchestrator.app.ticket

/** Keeps optional command correlation from replacing the active video socket's startup owner. */
internal class TicketStartupTraceCorrelation {
  private val lock = Any()
  @Volatile private var correlationId: String = ""
  private var activeVideoGeneration: Long? = null
  private var activeVideoCorrelationId: String = ""
  private var newestVideoGeneration: Long = 0L

  fun current(): String = correlationId

  fun bindVideoSocket(value: String, generation: Long): Boolean = synchronized(lock) {
    if (generation <= newestVideoGeneration) {
      false
    } else {
      newestVideoGeneration = generation
      activeVideoGeneration = generation
      activeVideoCorrelationId = value
      if (value.isNotBlank()) {
        correlationId = value
      }
      true
    }
  }

  fun bindCommand(value: String): Boolean {
    if (value.isBlank()) return false
    return synchronized(lock) {
      if (
        activeVideoGeneration != null &&
        activeVideoCorrelationId.isNotBlank() &&
        activeVideoCorrelationId != value
      ) {
        false
      } else {
        correlationId = value
        true
      }
    }
  }

  fun isCurrentVideoSocket(value: String, generation: Long): Boolean = synchronized(lock) {
    activeVideoGeneration == generation && activeVideoCorrelationId == value
  }

  /** The caller must hold the lifecycle lock that also serializes video-socket binding. */
  fun <T> resolveVideoSocketStart(
    value: String,
    generation: Long,
    superseded: () -> T,
    current: () -> T
  ): T {
    return if (isCurrentVideoSocket(value, generation)) current() else superseded()
  }

  fun releaseVideoSocket(generation: Long) {
    synchronized(lock) {
      if (activeVideoGeneration == generation) {
        activeVideoGeneration = null
        activeVideoCorrelationId = ""
      }
    }
  }

  fun clear() {
    synchronized(lock) {
      correlationId = ""
      activeVideoGeneration = null
      activeVideoCorrelationId = ""
    }
  }
}
