package lv.jolkins.pixelorchestrator.app.ticket

/** A superseded socket cannot start or replace the current phone stream. */
internal class TicketVideoConnectionOwner {
  private var newestGeneration = 0L
  private var activeGeneration: Long? = null

  @Synchronized fun bind(generation: Long): Boolean {
    if (generation <= newestGeneration) return false
    newestGeneration = generation
    activeGeneration = generation
    return true
  }

  @Synchronized fun isCurrent(generation: Long): Boolean = activeGeneration == generation

  @Synchronized fun release(generation: Long) {
    if (activeGeneration == generation) activeGeneration = null
  }

  @Synchronized fun clear() {
    activeGeneration = null
  }
}
