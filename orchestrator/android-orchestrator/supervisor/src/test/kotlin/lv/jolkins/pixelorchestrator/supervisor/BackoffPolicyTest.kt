package lv.jolkins.pixelorchestrator.supervisor

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class BackoffPolicyTest {

  @Test
  fun doublesDelayUntilMaxAndThenCrashLoops() {
    var now = 1_000L
    val policy = BackoffPolicy(
      initialSeconds = 5,
      maxSeconds = 20,
      rapidWindowSeconds = 300,
      maxRapidRestarts = 3,
      nowEpochSeconds = { now }
    )

    val d1 = policy.recordRestart()
    val d2 = policy.recordRestart()
    val d3 = policy.recordRestart()
    val d4 = policy.recordRestart()

    assertFalse(d1.crashLoop)
    assertEquals(5, d1.sleepSeconds)
    assertEquals(10, d2.sleepSeconds)
    assertEquals(20, d3.sleepSeconds)
    assertTrue(d4.crashLoop)
    assertEquals(120, d4.sleepSeconds)
  }

  @Test
  fun resetsWindowAfterRapidWindowExpiry() {
    var now = 1_000L
    val policy = BackoffPolicy(
      initialSeconds = 5,
      maxSeconds = 20,
      rapidWindowSeconds = 60,
      maxRapidRestarts = 2,
      nowEpochSeconds = { now }
    )

    policy.recordRestart()
    policy.recordRestart()

    now += 70
    val next = policy.recordRestart()

    assertFalse(next.crashLoop)
    assertEquals(5, next.sleepSeconds)
  }
}
