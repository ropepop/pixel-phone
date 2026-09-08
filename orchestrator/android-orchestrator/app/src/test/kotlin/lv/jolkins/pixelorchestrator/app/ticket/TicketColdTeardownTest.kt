package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class TicketColdTeardownTest {
  @Test fun bothIndependentStopsStartAndNeitherCanReleaseTheBarrierAlone() = runBlocking {
    val captureStarted = CompletableDeferred<Unit>()
    val restoreStarted = CompletableDeferred<Unit>()
    val captureReleased = CompletableDeferred<Unit>()
    val restoreReleased = CompletableDeferred<Unit>()
    val result = async {
      completeTicketColdTeardown(
        { captureStarted.complete(Unit); captureReleased.await() },
        { restoreStarted.complete(Unit); restoreReleased.await(); true }
      )
    }
    try {
      withTimeout(1_000) { captureStarted.await(); restoreStarted.await() }
      restoreReleased.complete(Unit)
      assertFalse(result.isCompleted)
      captureReleased.complete(Unit)
      assertTrue(withTimeout(1_000) { result.await() })
    } finally { captureReleased.complete(Unit); restoreReleased.complete(Unit) }
  }

  @Test fun failedRestorationStillWaitsForCaptureToStopAndCannotReportSuccess() = runBlocking {
    var captureStopped = false
    val result = completeTicketColdTeardown(
      { captureStopped = true },
      { false }
    )
    assertTrue(captureStopped)
    assertFalse(result)
  }
}
