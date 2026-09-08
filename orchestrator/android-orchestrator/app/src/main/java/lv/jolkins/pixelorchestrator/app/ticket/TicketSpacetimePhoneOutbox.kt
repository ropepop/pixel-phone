package lv.jolkins.pixelorchestrator.app.ticket

internal enum class TicketCodePublicationKind { GENERATED, FAILURE, CLEANUP, READY, PROGRESS }

internal data class TicketCodePublication(
  val requestId: String,
  val kind: TicketCodePublicationKind,
  val status: String = "",
  val reason: String = "",
  val cleanupPending: Boolean = false,
  val epoch: Long = 0L,
  val sequence: Long = 0L,
  val proof: String = "",
  val proofAt: String = "",
  val cleanupRevision: String = ""
) {
  val key get() = requestId to kind

  fun reducerArguments(ticketId: String, backendId: String, now: String): List<Any> {
    if (kind == TicketCodePublicationKind.READY) {
      require(cleanupRevision.startsWith("pc-")) { "control_code_cleanup_semantic_proof_required" }
      return listOf(ticketId, backendId, requestId, cleanupRevision, "", "", now)
    }
    val generated = kind == TicketCodePublicationKind.GENERATED
    if (generated) require(epoch > 0 && sequence > 0 && proof.isNotBlank() && proofAt.isNotBlank()) {
      "control_code_exact_result_required"
    }
    val frameEpoch = if (generated) epoch.toString() else ""
    val frameSequence = if (generated) sequence.toString() else ""
    val message = if (generated || status.isEmpty()) "" else reason
    return listOf(ticketId, requestId, status, reason, message,
      frameEpoch, frameSequence, frameSequence, frameEpoch, frameSequence,
      proof, proofAt, cleanupPending, now)
  }
}

internal class TicketSpacetimePhoneOutbox(
  private val criticalTtlMillis: Long,
  private val nowMillis: () -> Long
) {
  private val lock = Any()
  private val pending = linkedMapOf<Pair<String, TicketCodePublicationKind>, Pair<TicketCodePublication, Long>>()

  fun enqueue(publication: TicketCodePublication) = synchronized(lock) {
    if (publication.requestId.isBlank()) return@synchronized
    pruneExpiredLocked()
    pending[publication.key] = publication to nowMillis()
  }

  fun peek(): TicketCodePublication? = synchronized(lock) {
    pruneExpiredLocked()
    pending.values.minByOrNull {
      if (it.first.kind == TicketCodePublicationKind.PROGRESS) 1 else 0
    }?.first
  }

  fun acknowledge(publication: TicketCodePublication) = synchronized(lock) {
    // A completed send cannot erase a newer marker published while it was in flight.
    if (pending[publication.key]?.first == publication) pending.remove(publication.key)
  }

  private fun pruneExpiredLocked() {
    val now = nowMillis()
    pending.entries.removeAll { now - it.value.second > criticalTtlMillis }
  }
}
