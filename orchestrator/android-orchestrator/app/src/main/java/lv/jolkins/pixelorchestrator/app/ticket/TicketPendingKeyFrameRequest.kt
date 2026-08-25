package lv.jolkins.pixelorchestrator.app.ticket

/** A last-request-wins keyframe slot that never restores over a newer request. */
internal class TicketPendingKeyFrameRequest {
  data class Taken internal constructor(
    val reason: String,
    internal val generation: Long
  )

  private val lock = Any()
  private var latestGeneration: Long = 0L
  private var pending: Taken? = null

  fun offer(value: String) {
    synchronized(lock) {
      latestGeneration += 1L
      pending = Taken(reason = value, generation = latestGeneration)
    }
  }

  fun take(): Taken? = synchronized(lock) {
    val value = pending
    pending = null
    value
  }

  fun restoreIfEmpty(value: Taken): Boolean = synchronized(lock) {
    if (pending != null || value.generation != latestGeneration) {
      false
    } else {
      pending = value
      true
    }
  }
}
