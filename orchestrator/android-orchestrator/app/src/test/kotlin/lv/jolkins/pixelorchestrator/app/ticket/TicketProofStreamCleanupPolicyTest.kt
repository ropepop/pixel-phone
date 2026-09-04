package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Test

class TicketProofStreamCleanupPolicyTest {
  @Test
  fun exactCommandStartedNoViewerSessionCanStopOnlyWithoutOwners() {
    assertEquals(
      TicketProofStreamCleanupDecision.STOP,
      decide(expectedSessionGeneration = 7L)
    )
  }

  @Test
  fun genericBrowserDisconnectCleanupCanStillStopTheCurrentNoViewerSession() {
    assertEquals(
      TicketProofStreamCleanupDecision.STOP,
      decide(expectedSessionGeneration = null)
    )
  }

  @Test
  fun viewerOrReplacementSessionPermanentlyDropsCommandCleanup() {
    assertEquals(
      TicketProofStreamCleanupDecision.DROP,
      decide(expectedSessionGeneration = 7L, clientCount = 1)
    )
    assertEquals(
      TicketProofStreamCleanupDecision.DROP,
      decide(expectedSessionGeneration = 7L, currentSessionGeneration = 8L)
    )
    assertEquals(
      TicketProofStreamCleanupDecision.DROP,
      decide(expectedSessionGeneration = 0L)
    )
    assertEquals(
      TicketProofStreamCleanupDecision.DROP,
      decide(expectedSessionGeneration = 7L, streamActive = false)
    )
  }

  @Test
  fun everyAutomationOwnerDefersCleanupForAnotherGracePeriod() {
    listOf(
      decide(expectedSessionGeneration = 7L, startOwnershipActive = true),
      decide(expectedSessionGeneration = 7L, controlOwnershipActive = true),
      decide(expectedSessionGeneration = 7L, actionOwnershipActive = true),
      decide(expectedSessionGeneration = 7L, reauthOwnershipActive = true)
    ).forEach { decision ->
      assertEquals(TicketProofStreamCleanupDecision.RETRY, decision)
    }
  }

  @Test
  fun viewerAndReplacementFencesWinOverTemporaryOwnership() {
    assertEquals(
      TicketProofStreamCleanupDecision.DROP,
      decide(
        expectedSessionGeneration = 7L,
        clientCount = 1,
        reauthOwnershipActive = true
      )
    )
    assertEquals(
      TicketProofStreamCleanupDecision.DROP,
      decide(
        expectedSessionGeneration = 7L,
        currentSessionGeneration = 8L,
        actionOwnershipActive = true
      )
    )
  }

  private fun decide(
    expectedSessionGeneration: Long?,
    currentSessionGeneration: Long = 7L,
    streamActive: Boolean = true,
    clientCount: Int = 0,
    startOwnershipActive: Boolean = false,
    controlOwnershipActive: Boolean = false,
    actionOwnershipActive: Boolean = false,
    reauthOwnershipActive: Boolean = false
  ): TicketProofStreamCleanupDecision = ticketProofStreamCleanupDecision(
    expectedSessionGeneration = expectedSessionGeneration,
    currentSessionGeneration = currentSessionGeneration,
    streamActive = streamActive,
    clientCount = clientCount,
    startOwnershipActive = startOwnershipActive,
    controlOwnershipActive = controlOwnershipActive,
    actionOwnershipActive = actionOwnershipActive,
    reauthOwnershipActive = reauthOwnershipActive
  )
}
