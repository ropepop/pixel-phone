package lv.jolkins.pixelorchestrator.supervisor

class DdnsStabilityGate(requiredStableReads: Int) {
  private val required = requiredStableReads.coerceAtLeast(1)
  private var lastValue: String? = null
  private var streak: Int = 0

  fun observe(value: String?): Boolean {
    if (value.isNullOrBlank()) {
      streak = 0
      lastValue = null
      return false
    }

    if (value == lastValue) {
      streak += 1
    } else {
      lastValue = value
      streak = 1
    }

    return streak >= required
  }

  fun reset() {
    lastValue = null
    streak = 0
  }
}
