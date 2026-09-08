package lv.jolkins.pixelorchestrator.app.ticket

import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationRootPhysicalTouchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class TicketActivationCheckpointTest {






  @Test
  fun storeKeepsOnlyOpaqueCheckpointAndClearsMatchingAction() {
    val backend = InMemoryTicketActivationCheckpointBackend()
    val store = TicketActivationCheckpointStore(backend)
    val fresh = requireNotNull(store.recordFreshTicketProven("command", "revision", "attempt"))
    val dispatching = requireNotNull(store.recordActivationDispatching(fresh, 1))
    assertEquals(1, dispatching.dispatchOrdinal)
    val noTransition = requireNotNull(store.recordNoTransitionProven(dispatching))
    assertEquals(TicketActivationCheckpointStage.NO_TRANSITION_PROVEN, noTransition.stage)
    val retryDispatching = requireNotNull(store.recordActivationDispatching(noTransition, 2))
    assertEquals(2, retryDispatching.dispatchOrdinal)
    val proven = requireNotNull(store.recordActivationProven(retryDispatching, "activation"))
    assertEquals(proven, store.loadFor("command", "revision", "attempt"))
    assertNull(store.loadFor("other-command", "revision", "attempt"))
    assertFalse(store.clearIfMatches("command", "other-attempt"))
    assertTrue(store.clearIfMatches("command", "attempt"))
    assertNull(store.load())
  }

  @Test
  fun failedDurableCommitNeverAdmitsDispatchingCheckpoint() {
    val backend = InMemoryTicketActivationCheckpointBackend(commitSucceeds = false)
    val store = TicketActivationCheckpointStore(backend)
    val fresh = checkpoint(TicketActivationCheckpointStage.FRESH_TICKET_PROVEN)

    assertNull(store.recordActivationDispatching(fresh, 1))
    assertNull(store.load())
  }

  @Test
  fun unresolvedCheckpointCannotBeOverwrittenByAnotherActivation() {
    val store = TicketActivationCheckpointStore(InMemoryTicketActivationCheckpointBackend())
    val first = store.recordFreshTicketProven("first-command", "first-revision", "first-attempt")
    assertNotNull(first)

    assertNull(store.recordFreshTicketProven("second-command", "second-revision", "second-attempt"))
    assertEquals(first, store.load())
  }

  @Test
  fun successfulCommitWithoutMatchingReadbackIsStillUnproved() {
    val backend = InMemoryTicketActivationCheckpointBackend(persistCommittedValue = false)
    val store = TicketActivationCheckpointStore(backend)
    val fresh = checkpoint(TicketActivationCheckpointStage.FRESH_TICKET_PROVEN)

    assertNull(store.recordActivationDispatching(fresh, 1))
    assertNull(store.load())
  }

  @Test
  fun retryRequiresConclusiveNoTransitionAndNeitherOrdinalCanReplay() {
    val store = TicketActivationCheckpointStore(InMemoryTicketActivationCheckpointBackend())
    val fresh = requireNotNull(store.recordFreshTicketProven("command", "revision", "attempt"))
    val first = requireNotNull(store.recordActivationDispatching(fresh, 1))
    assertThrows(IllegalArgumentException::class.java) {
      store.recordActivationDispatching(first, 1)
    }
    assertThrows(IllegalArgumentException::class.java) {
      store.recordActivationDispatching(first, 2)
    }
    val noTransition = requireNotNull(store.recordNoTransitionProven(first))
    val second = requireNotNull(store.recordActivationDispatching(noTransition, 2))
    assertThrows(IllegalArgumentException::class.java) {
      store.recordActivationDispatching(second, 2)
    }
  }



  @Test
  fun terminalFinalizationClearsOnlyAConclusiveMatchingCheckpointStage() {
    val fresh = checkpoint(TicketActivationCheckpointStage.FRESH_TICKET_PROVEN)
    val notDispatched = activationTerminal(
      status = "needs_attention",
      phase = "not_dispatched",
      reason = "ticket_action_exact_input_fence_changed",
      view = TicketVisualActionView.UNKNOWN,
      ok = false
    )
    assertTrue(ticketActivationCheckpointSafeToClearAfterTerminalFinalization(
      fresh,
      "command",
      notDispatched
    ))
    assertFalse(ticketActivationCheckpointSafeToClearAfterTerminalFinalization(
      fresh,
      "command",
      notDispatched.copy(phase = "outcome_unknown")
    ))
    assertFalse(ticketActivationCheckpointSafeToClearAfterTerminalFinalization(
      fresh.copy(dispatchOrdinal = 1),
      "command",
      notDispatched
    ))

    val afterFirst = checkpoint(
      TicketActivationCheckpointStage.NO_TRANSITION_PROVEN,
      dispatchOrdinal = 1
    )
    val retryNotDispatched = activationTerminal(
      status = "needs_attention",
      phase = "retry_not_dispatched",
      reason = "ticket_action_retry_not_dispatched",
      view = TicketVisualActionView.LATEST_UNACTIVATED,
      ok = false
    )
    assertTrue(ticketActivationCheckpointSafeToClearAfterTerminalFinalization(
      afterFirst,
      "command",
      retryNotDispatched
    ))
    assertFalse(ticketActivationCheckpointSafeToClearAfterTerminalFinalization(
      afterFirst,
      "command",
      retryNotDispatched.copy(reason = "ticket_action_gesture_completed_no_transition")
    ))
    assertFalse(ticketActivationCheckpointSafeToClearAfterTerminalFinalization(
      afterFirst.copy(dispatchOrdinal = 0),
      "command",
      retryNotDispatched
    ))

    val afterSecond = checkpoint(
      TicketActivationCheckpointStage.NO_TRANSITION_PROVEN,
      dispatchOrdinal = 2
    )
    val noTransition = retryNotDispatched.copy(
      phase = "no_transition",
      reason = "ticket_action_gesture_completed_no_transition"
    )
    assertTrue(ticketActivationCheckpointSafeToClearAfterTerminalFinalization(
      afterSecond,
      "command",
      noTransition
    ))
    assertFalse(ticketActivationCheckpointSafeToClearAfterTerminalFinalization(
      afterSecond,
      "command",
      noTransition.copy(currentView = TicketVisualActionView.UNKNOWN)
    ))

    val proven = checkpoint(
      TicketActivationCheckpointStage.ACTIVATION_PROVEN,
      activationRevision = "activation-revision",
      dispatchOrdinal = 1
    )
    val activationProven = activationTerminal(
      status = "succeeded",
      phase = "activation_proven",
      reason = "ticket_action_registered",
      view = TicketVisualActionView.ACTIVATED_CURRENT,
      ok = true,
      interactionRevision = "revision",
      activationRevision = "activation-revision"
    )
    assertTrue(ticketActivationCheckpointSafeToClearAfterTerminalFinalization(
      proven,
      "command",
      activationProven
    ))
    assertFalse(ticketActivationCheckpointSafeToClearAfterTerminalFinalization(
      proven,
      "command",
      activationProven.copy(phase = "outcome_unknown", ok = false, activationRevision = "")
    ))
    assertFalse(ticketActivationCheckpointSafeToClearAfterTerminalFinalization(
      proven,
      "command",
      activationProven.copy(activationRevision = "other")
    ))
  }

  @Test
  fun uncertainCheckpointStagesAlwaysSurviveTerminalFinalization() {
    val terminalCandidates = listOf(
      activationTerminal(
        status = "needs_attention",
        phase = "outcome_unknown",
        reason = "ticket_action_activation_outcome_unknown",
        view = TicketVisualActionView.UNKNOWN,
        ok = false
      ),
      activationTerminal(
        status = "succeeded",
        phase = "activation_proven",
        reason = "ticket_action_registered",
        view = TicketVisualActionView.ACTIVATED_CURRENT,
        ok = true,
        interactionRevision = "revision",
        activationRevision = "activation-revision"
      )
    )
    listOf(
      TicketActivationCheckpointStage.ACTIVATION_DISPATCHING,
      TicketActivationCheckpointStage.NEEDS_ATTENTION
    ).forEach { stage ->
      terminalCandidates.forEach { action ->
        assertFalse(ticketActivationCheckpointSafeToClearAfterTerminalFinalization(
          checkpoint(
            stage,
            activationRevision = "activation-revision",
            dispatchOrdinal = 1
          ),
          "command",
          action
        ))
      }
    }
  }

  @Test
  fun terminalFinalizationCannotClearAIdentityMismatchedCheckpoint() {
    val fresh = checkpoint(TicketActivationCheckpointStage.FRESH_TICKET_PROVEN)
    val terminal = activationTerminal(
      status = "needs_attention",
      phase = "not_dispatched",
      reason = "ticket_action_visual_unproved",
      view = TicketVisualActionView.UNKNOWN,
      ok = false
    )
    assertFalse(ticketActivationCheckpointSafeToClearAfterTerminalFinalization(
      fresh,
      "other-command",
      terminal
    ))
    assertFalse(ticketActivationCheckpointSafeToClearAfterTerminalFinalization(
      fresh,
      "command",
      terminal.copy(actionId = "other", activationAttemptId = "other")
    ))
  }

  @Test
  fun quickPhysicalDownUpInvalidatesTheActivationProofFence() {
    val fence = requireNotNull(ticketActivationPhysicalTouchFence(
      PhoneAutomationRootPhysicalTouchState(
        available = true,
        active = false,
        observedAtUptimeMillis = 10L
      )
    ))

    assertTrue(ticketActivationPhysicalTouchFenceIsCurrent(
      fence,
      PhoneAutomationRootPhysicalTouchState(true, false, 10L)
    ))
    assertFalse(ticketActivationPhysicalTouchFenceIsCurrent(
      fence,
      PhoneAutomationRootPhysicalTouchState(true, true, 11L)
    ))
    assertFalse(ticketActivationPhysicalTouchFenceIsCurrent(
      fence,
      PhoneAutomationRootPhysicalTouchState(true, false, 12L)
    ))
  }

  private fun checkpoint(
    stage: TicketActivationCheckpointStage,
    activationRevision: String = "",
    dispatchOrdinal: Int = 0
  ) = TicketActivationCheckpoint(
    commandId = "command",
    interactionRevision = "revision",
    activationAttemptId = "attempt",
    activationRevision = activationRevision,
    dispatchOrdinal = dispatchOrdinal,
    stage = stage
  )

  private fun activationTerminal(
    status: String,
    phase: String,
    reason: String,
    view: TicketVisualActionView,
    ok: Boolean,
    interactionRevision: String = "",
    activationRevision: String = ""
  ) = TicketVisualActionSnapshot(
    actionId = "attempt",
    target = TicketVisualActionTarget.REGISTER_CURRENT.wireName,
    status = status,
    phase = phase,
    currentView = view,
    reason = reason,
    completedAt = "2026-09-03T00:00:00Z",
    interactionRevision = interactionRevision,
    activationRevision = activationRevision,
    activationAttemptId = "attempt",
    terminal = true,
    ok = ok
  )
}

private class InMemoryTicketActivationCheckpointBackend(
  private val commitSucceeds: Boolean = true,
  private val persistCommittedValue: Boolean = true
) : TicketActivationCheckpointBackend {
  private var value: TicketActivationCheckpoint? = null

  override fun load(): TicketActivationCheckpoint? = value

  override fun save(checkpoint: TicketActivationCheckpoint): Boolean {
    if (commitSucceeds && persistCommittedValue) value = checkpoint
    return commitSucceeds
  }

  override fun clear(): Boolean {
    if (commitSucceeds) value = null
    return commitSucceeds
  }
}

class TicketSliderGestureContractTest {
  @Test
  fun activationStartsAtThumbAndReleasesAtTheMatchingUsableEnd() {
    val contract = ticketSliderGestureContract(
      TicketViviGraphicBounds(left = 100, top = 400, right = 500, bottom = 500),
      durationMillis = 800L
    )
    assertEquals(137, contract.startX)
    assertEquals(463, contract.endX)
    assertEquals(800L, contract.durationMillis)
    assertTrue(contract.endX > contract.startX)
  }




  @Test
  fun liveProvenSliderBandReachesBothThumbCentresSymmetrically() {
    val bounds = TicketViviGraphicBounds(left = 4, top = 1496, right = 1032, bottom = 1712)

    val contract = ticketSliderGestureContract(bounds)

    assertEquals(85, contract.startX)
    assertEquals(951, contract.endX)
    assertEquals(800L, contract.durationMillis)
    assertEquals(contract.startX - bounds.left, bounds.right - contract.endX)
  }




}
