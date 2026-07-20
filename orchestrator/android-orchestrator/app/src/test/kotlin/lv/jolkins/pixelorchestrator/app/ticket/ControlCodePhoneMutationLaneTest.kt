package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ControlCodePhoneMutationLaneTest {
  @Test
  fun scheduledControlExitWaitsUntilTheActiveRequestReleasesThePhone() = runTest {
    val lane = ControlCodePhoneMutationLane()
    val releaseRequest = CompletableDeferred<Unit>()
    val events = mutableListOf<String>()

    val request = launch {
      lane.withOwnership {
        events += "request_started"
        releaseRequest.await()
        events += "request_finished"
      }
    }
    runCurrent()
    val controlExit = launch {
      lane.withOwnership {
        events += "control_exit_started"
      }
    }
    runCurrent()

    assertFalse(controlExit.isCompleted)
    assertEquals(listOf("request_started"), events)
    releaseRequest.complete(Unit)
    runCurrent()

    assertEquals(listOf("request_started", "request_finished", "control_exit_started"), events)
    request.join()
    controlExit.join()
  }
}
