package lv.jolkins.pixelorchestrator.app.ticket

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketLatestTicketReselectCommandPolicyTest {
  @Test
  fun startsWhenNoReselectOrControlCodeIsActive() {
    assertDecision(
      expectedDisposition = TicketLatestTicketReselectCommandDisposition.START,
      expectedReason = "latest_ticket_reselect_start",
      currentCommandId = "",
      currentStatus = "idle",
      currentPhase = "idle",
      incomingCommandId = "command-a"
    )
  }

  @Test
  fun defersSameCommandWhileItIsPending() {
    assertDecision(
      expectedDisposition = TicketLatestTicketReselectCommandDisposition.DEFER,
      expectedReason = "latest_ticket_reselect_in_progress",
      currentCommandId = "command-a",
      currentStatus = "pending",
      currentPhase = "stream_proof_pending",
      incomingCommandId = "command-a"
    )
  }

  @Test
  fun reportsSameCommandSucceededOnlyAfterReadyProof() {
    assertDecision(
      expectedDisposition = TicketLatestTicketReselectCommandDisposition.DEFER,
      expectedReason = "latest_ticket_reselect_in_progress",
      currentCommandId = "command-a",
      currentStatus = "succeeded",
      currentPhase = "stream_proof_ready",
      incomingCommandId = "command-a"
    )
    assertDecision(
      expectedDisposition = TicketLatestTicketReselectCommandDisposition.SUCCEEDED,
      expectedReason = "latest_ticket_reselect_succeeded",
      currentCommandId = "command-a",
      currentStatus = "succeeded",
      currentPhase = "ready",
      incomingCommandId = "command-a"
    )
  }

  @Test
  fun reportsSameCommandTerminalFailure() {
    assertDecision(
      expectedDisposition = TicketLatestTicketReselectCommandDisposition.FAILED,
      expectedReason = "latest_ticket_reselect_failed",
      currentCommandId = "command-a",
      currentStatus = "failed",
      currentPhase = "stream_proof_failed",
      incomingCommandId = "command-a"
    )
  }

  @Test
  fun secondCommandCannotReplacePendingCommand() {
    assertDecision(
      expectedDisposition = TicketLatestTicketReselectCommandDisposition.DEFER,
      expectedReason = "latest_ticket_reselect_busy",
      currentCommandId = "command-a",
      currentStatus = "pending",
      currentPhase = "ticket_reselect_requested",
      incomingCommandId = "command-b"
    )
  }

  @Test
  fun secondCommandCannotReplaceUnprovenSuccess() {
    assertDecision(
      expectedDisposition = TicketLatestTicketReselectCommandDisposition.DEFER,
      expectedReason = "latest_ticket_reselect_busy",
      currentCommandId = "command-a",
      currentStatus = "succeeded",
      currentPhase = "stream_proof_ready",
      incomingCommandId = "command-b"
    )
  }

  @Test
  fun startsNextCommandAfterPreviousTerminalResult() {
    listOf(
      "succeeded" to "ready",
      "failed" to "stream_proof_failed"
    ).forEach { (status, phase) ->
      assertDecision(
        expectedDisposition = TicketLatestTicketReselectCommandDisposition.START,
        expectedReason = "latest_ticket_reselect_start",
        currentCommandId = "command-a",
        currentStatus = status,
        currentPhase = phase,
        incomingCommandId = "command-b"
      )
    }
  }

  @Test
  fun originalCommandBecomesTerminalBeforeDeferredSecondCommandCanStart() {
    val originalWhilePending = decide(
      currentCommandId = "command-a",
      currentStatus = "pending",
      currentPhase = "stream_proof_pending",
      incomingCommandId = "command-a"
    )
    val secondWhileOriginalPending = decide(
      currentCommandId = "command-a",
      currentStatus = "pending",
      currentPhase = "stream_proof_pending",
      incomingCommandId = "command-b"
    )
    val originalAfterReadyProof = decide(
      currentCommandId = "command-a",
      currentStatus = "succeeded",
      currentPhase = "ready",
      incomingCommandId = "command-a"
    )
    val secondAfterOriginalTerminal = decide(
      currentCommandId = "command-a",
      currentStatus = "succeeded",
      currentPhase = "ready",
      incomingCommandId = "command-b"
    )

    assertEquals(TicketLatestTicketReselectCommandDisposition.DEFER, originalWhilePending.disposition)
    assertEquals(TicketLatestTicketReselectCommandDisposition.DEFER, secondWhileOriginalPending.disposition)
    assertEquals(TicketLatestTicketReselectCommandDisposition.SUCCEEDED, originalAfterReadyProof.disposition)
    assertEquals(TicketLatestTicketReselectCommandDisposition.START, secondAfterOriginalTerminal.disposition)
  }

  @Test
  fun defersNewCommandWhileControlCodeWindowIsActive() {
    assertDecision(
      expectedDisposition = TicketLatestTicketReselectCommandDisposition.DEFER,
      expectedReason = "control_code_active",
      currentCommandId = "",
      currentStatus = "idle",
      currentPhase = "idle",
      incomingCommandId = "command-a",
      controlSensitiveWindowActive = true
    )
  }

  @Test
  fun yieldedCommandWaitsForControlThenResumesWithTheSameId() {
    assertDecision(
      expectedDisposition = TicketLatestTicketReselectCommandDisposition.DEFER,
      expectedReason = "control_code_active",
      currentCommandId = "command-a",
      currentStatus = "yielded",
      currentPhase = "control_code_yielded",
      incomingCommandId = "command-a",
      controlSensitiveWindowActive = true
    )
    assertDecision(
      expectedDisposition = TicketLatestTicketReselectCommandDisposition.START,
      expectedReason = "latest_ticket_reselect_resume_after_control_code",
      currentCommandId = "command-a",
      currentStatus = "yielded",
      currentPhase = "control_code_yielded",
      incomingCommandId = "command-a"
    )
  }

  @Test
  fun differentCommandCannotReplaceYieldedCommand() {
    assertDecision(
      expectedDisposition = TicketLatestTicketReselectCommandDisposition.DEFER,
      expectedReason = "latest_ticket_reselect_busy",
      currentCommandId = "command-a",
      currentStatus = "yielded",
      currentPhase = "control_code_yielded",
      incomingCommandId = "command-b"
    )
  }

  @Test
  fun staleGenerationCannotApplyTerminalMutation() {
    val stateLock = Any()
    var generation = 8L
    var commandId = "command-a"
    var status = "yielded"

    val transitioned = mutateLatestTicketReselectIfCurrent(
      stateLock = stateLock,
      currentGeneration = { generation },
      currentCommandId = { commandId },
      expectedGeneration = 7L,
      expectedCommandId = "command-a"
    ) {
      status = "failed"
    }

    assertFalse(transitioned)
    assertEquals("yielded", status)
  }

  @Test
  fun terminalMutationAndYieldCannotInterleave() {
    val stateLock = Any()
    var generation = 7L
    var commandId = "command-a"
    var status = "pending"
    val mutationEntered = CountDownLatch(1)
    val allowMutationToFinish = CountDownLatch(1)
    val yieldFinished = CountDownLatch(1)

    val terminalThread = thread(start = true, name = "terminal-transition") {
      mutateLatestTicketReselectIfCurrent(
        stateLock = stateLock,
        currentGeneration = { generation },
        currentCommandId = { commandId },
        expectedGeneration = 7L,
        expectedCommandId = "command-a"
      ) {
        mutationEntered.countDown()
        assertTrue(allowMutationToFinish.await(2, TimeUnit.SECONDS))
        status = "failed"
      }
    }
    assertTrue(mutationEntered.await(2, TimeUnit.SECONDS))

    val yieldThread = thread(start = true, name = "yield-transition") {
      synchronized(stateLock) {
        generation += 1L
        status = "yielded"
      }
      yieldFinished.countDown()
    }
    assertFalse(yieldFinished.await(100, TimeUnit.MILLISECONDS))
    allowMutationToFinish.countDown()

    terminalThread.join(2_000)
    yieldThread.join(2_000)
    assertFalse(terminalThread.isAlive)
    assertFalse(yieldThread.isAlive)
    assertEquals(8L, generation)
    assertEquals("yielded", status)
  }

  private fun assertDecision(
    expectedDisposition: TicketLatestTicketReselectCommandDisposition,
    expectedReason: String,
    currentCommandId: String,
    currentStatus: String,
    currentPhase: String,
    incomingCommandId: String,
    controlSensitiveWindowActive: Boolean = false
  ) {
    val decision = TicketLatestTicketReselectCommandPolicy.decide(
      currentCommandId = currentCommandId,
      currentStatus = currentStatus,
      currentPhase = currentPhase,
      incomingCommandId = incomingCommandId,
      controlSensitiveWindowActive = controlSensitiveWindowActive
    )

    assertEquals(expectedDisposition, decision.disposition)
    assertEquals(expectedReason, decision.reason)
  }

  private fun decide(
    currentCommandId: String,
    currentStatus: String,
    currentPhase: String,
    incomingCommandId: String
  ): TicketLatestTicketReselectCommandDecision {
    return TicketLatestTicketReselectCommandPolicy.decide(
      currentCommandId = currentCommandId,
      currentStatus = currentStatus,
      currentPhase = currentPhase,
      incomingCommandId = incomingCommandId,
      controlSensitiveWindowActive = false
    )
  }
}
