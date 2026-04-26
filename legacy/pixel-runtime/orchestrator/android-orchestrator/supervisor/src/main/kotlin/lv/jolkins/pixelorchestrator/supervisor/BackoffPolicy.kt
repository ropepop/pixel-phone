package lv.jolkins.pixelorchestrator.supervisor

class BackoffPolicy(
  private val initialSeconds: Int,
  private val maxSeconds: Int,
  private val rapidWindowSeconds: Int,
  private val maxRapidRestarts: Int,
  private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) {
  private var windowStart = nowEpochSeconds()
  private var rapidCount = 0
  private var currentBackoff = initialSeconds

  fun recordRestart(): BackoffDecision {
    val now = nowEpochSeconds()
    if (now - windowStart > rapidWindowSeconds) {
      resetWindow(now)
    }

    rapidCount += 1
    if (rapidCount > maxRapidRestarts) {
      return BackoffDecision(
        crashLoop = true,
        sleepSeconds = 120,
        rapidCount = rapidCount
      )
    }

    val delay = currentBackoff
    currentBackoff = (currentBackoff * 2).coerceAtMost(maxSeconds)

    return BackoffDecision(
      crashLoop = false,
      sleepSeconds = delay,
      rapidCount = rapidCount
    )
  }

  fun reset() {
    resetWindow(nowEpochSeconds())
  }

  private fun resetWindow(now: Long) {
    windowStart = now
    rapidCount = 0
    currentBackoff = initialSeconds
  }
}

data class BackoffDecision(
  val crashLoop: Boolean,
  val sleepSeconds: Int,
  val rapidCount: Int
)
