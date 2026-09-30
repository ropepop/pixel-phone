package lv.jolkins.pixelorchestrator.supervisor

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class BackoffPolicy(
  private val initialSeconds: Int,
  private val maxSeconds: Int,
  private val rapidWindowSeconds: Int,
  private val maxRapidRestarts: Int,
  private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) {
  private var state = transition("", false)

  fun recordRestart(): BackoffDecision {
    state = transition(state, true)
    return Json.decodeFromString<BackoffDecision>(Json.parseToJsonElement(state).jsonObject.getValue("decision").toString())
  }

  fun reset() {
    state = transition("", false)
  }

  private fun transition(previous: String, restart: Boolean): String =
    NativeSupervisor.backoff(previous, restart, nowEpochSeconds(), initialSeconds, maxSeconds, rapidWindowSeconds, maxRapidRestarts)
}

@Serializable
data class BackoffDecision(
  val crashLoop: Boolean,
  val sleepSeconds: Int,
  val rapidCount: Int
)
