package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ControlCodeFailureLifecycleTest {
  @Test
  fun failureIsDeliveredBeforeSlowCleanupCompletes() = runTest {
    val allowCleanupToFinish = CompletableDeferred<Unit>()
    val events = mutableListOf<String>()

    val lifecycle = async {
      ControlCodeFailureLifecycle.deliverThenCleanup(
        deliverFailure = { events += "failure_delivered" },
        cleanup = {
          events += "cleanup_started"
          allowCleanupToFinish.await()
          events += "cleanup_finished"
          true
        },
        deliverCleanup = { events += "cleanup_delivered:$it" }
      )
    }
    runCurrent()

    assertFalse(lifecycle.isCompleted)
    assertEquals(listOf("failure_delivered", "cleanup_started"), events)
    allowCleanupToFinish.complete(Unit)
    runCurrent()

    assertTrue(lifecycle.await())
    assertEquals(
      listOf("failure_delivered", "cleanup_started", "cleanup_finished", "cleanup_delivered:true"),
      events
    )
  }

  @Test
  fun cleanupAfterAnAcceptedResultCannotPublishAContradictoryFailure() = runTest {
    val events = mutableListOf<String>()

    val cleanupSucceeded = ControlCodeFailureLifecycle.deliverThenCleanup(
      terminalResultAlreadyDelivered = true,
      deliverFailure = { events += "contradictory_failure" },
      cleanup = {
        events += "cleanup"
        false
      },
      deliverCleanup = { events += "cleanup_delivered:$it" }
    )

    assertFalse(cleanupSucceeded)
    assertEquals(listOf("cleanup", "cleanup_delivered:false"), events)
  }
}
