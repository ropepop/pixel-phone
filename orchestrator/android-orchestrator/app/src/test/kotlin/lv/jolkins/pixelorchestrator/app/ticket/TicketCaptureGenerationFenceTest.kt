package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketCaptureGenerationFenceTest {
  @Test
  fun cancelBeforeProcessPublicationRejectsOldHelperAndProtectsQueuedReplacement() {
    val fence = TicketCaptureGenerationFence()
    val oldGeneration = 41L
    val replacementGeneration = 42L
    val oldHelper = FakeHelper()
    val replacementHelper = FakeHelper()

    assertTrue(fence.begin(oldGeneration))
    assertTrue(fence.beginCancellation(oldGeneration))

    // The old app_process finishes spawning after cancellation has already fenced its generation.
    if (!fence.mayPublish(oldGeneration)) oldHelper.destroyAndWait()
    assertFalse(oldHelper.alive)

    // A reconnect can request a replacement, but it cannot publish until old cleanup is complete.
    assertFalse(fence.begin(replacementGeneration))
    assertFalse(fence.mayPublish(replacementGeneration))
    assertEquals(replacementGeneration, fence.completeCancellation(oldGeneration))
    assertTrue(fence.mayPublish(replacementGeneration))

    // A stale old finally block is no longer authorized to sweep the replacement generation.
    fence.completeNormally(oldGeneration)
    assertTrue(fence.mayPublish(replacementGeneration))
    if (fence.mayReap(oldGeneration)) replacementHelper.destroyAndWait()
    assertTrue(replacementHelper.alive)
  }

  private class FakeHelper {
    var alive = true
      private set

    fun destroyAndWait() {
      alive = false
    }
  }
}
