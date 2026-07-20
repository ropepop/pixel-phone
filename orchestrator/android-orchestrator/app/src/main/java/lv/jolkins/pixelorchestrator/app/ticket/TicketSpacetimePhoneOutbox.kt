package lv.jolkins.pixelorchestrator.app.ticket

internal class TicketSpacetimePhoneOutbox(
  private val maxLossyMessages: Int,
  private val criticalTtlMillis: Long,
  private val criticalKey: (String) -> String?,
  private val nowMillis: () -> Long
) {
  private val lock = Any()
  private val critical = linkedMapOf<String, Pair<String, Long>>()
  private val lossy = ArrayDeque<String>()

  fun enqueue(message: String) {
    if (message.isBlank()) return
    synchronized(lock) {
      val now = nowMillis()
      pruneExpiredLocked(now)
      val key = criticalKey(message)
      if (key != null) {
        critical[key] = message to now
      } else {
        lossy.addLast(message)
        while (lossy.size > maxLossyMessages) {
          lossy.removeFirst()
        }
      }
    }
  }

  fun peek(maxMessages: Int = Int.MAX_VALUE): List<String> {
    synchronized(lock) {
      pruneExpiredLocked(nowMillis())
      val count = maxMessages.coerceAtLeast(1).coerceAtMost(critical.size + lossy.size)
      if (count == 0) return emptyList()
      return buildList(count) {
        critical.values.forEach { (message, _) ->
          if (size < count) add(message)
        }
        lossy.forEach { message ->
          if (size < count) add(message)
        }
      }
    }
  }

  fun acknowledge(message: String) {
    synchronized(lock) {
      val key = criticalKey(message)
      if (key != null) {
        if (critical[key]?.first == message) {
          critical.remove(key)
        }
      } else {
        lossy.remove(message)
      }
    }
  }

  private fun pruneExpiredLocked(now: Long) {
    critical.entries.removeAll { (_, pending) -> now - pending.second > criticalTtlMillis }
  }
}
