package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Test

class TicketVisualTerminalOwnershipTest {
  @Test
  fun oldSettlementGeometryIsPreservedOnlyByItsRetainedEnvelope() {
    val old = TicketVisualActionJournalState(
      commandId = "command", commandRevision = "revision", actionId = "action",
      target = "open_latest_unactivated", phase = "terminal", terminalStatus = "succeeded",
      terminalView = "latest_unactivated", terminalOk = true, completedAt = "2026-09-07T00:00:00Z",
      streamEpoch = 8, frameSequence = 12, sliderLeftBasisPoints = 100,
      sliderTopBasisPoints = 200, sliderRightBasisPoints = 900, sliderBottomBasisPoints = 800
    )
    assertEquals(RetainedTicketSliderGeometry(100, 200, 900, 800),
      ticketActionFinalizationEnvelope(old)?.retainedGeometry)
    assertEquals(null, ticketActionFinalizationEnvelope(old.copy(
      semanticProof = true, streamEpoch = 0, frameSequence = 0
    ))?.retainedGeometry)
    assertEquals(null, ticketActionFinalizationEnvelope(old.copy(
      sliderLeftBasisPoints = 950
    ))?.retainedGeometry)
  }

  @Test
  fun navigationOnlyCannotChangeRegistrationNotDispatchedToUnknown() {
    assertEquals("not_dispatched", ticketActivationFailureTerminalPhase(null, "not_dispatched"))
    val fresh = TicketActivationCheckpoint("test-command", "test-revision", "test-action",
      stage = TicketActivationCheckpointStage.FRESH_TICKET_PROVEN)
    assertEquals("not_dispatched", ticketActivationFailureTerminalPhase(fresh, "not_dispatched"))
  }

  @Test
  fun checkpointDispatchAndExplicitUncertaintyNeverBecomeNoDispatch() {
    val checkpoint = TicketActivationCheckpoint("test-command", "test-revision", "test-action",
      dispatchOrdinal = 1, stage = TicketActivationCheckpointStage.ACTIVATION_DISPATCHING)
    assertEquals("outcome_unknown", ticketActivationFailureTerminalPhase(checkpoint, "not_dispatched"))
    assertEquals("outcome_unknown", ticketActivationFailureTerminalPhase(null, "outcome_unknown"))
    val unchanged = checkpoint.copy(stage = TicketActivationCheckpointStage.NO_TRANSITION_PROVEN)
    assertEquals("retry_not_dispatched", ticketActivationFailureTerminalPhase(unchanged))
    assertEquals("no_transition", ticketActivationFailureTerminalPhase(unchanged.copy(dispatchOrdinal = 2)))
  }

  @Test
  fun exactAccountRestoreRejectsWrongTicketAndWaitsForFreshEvidence() {
    val bounds = TicketVisualProbeBounds(10, 20, 30, 40)
    val original = TicketViviReauthReturnTarget(TicketVisualPhoneState.UNACTIVATED_DETAIL, "d_" + "a".repeat(28))
    val before = TicketVisualActionObservation(10, TicketVisualPhoneState.TICKET_LIST)
    val target = TicketViviNavigationTarget(TicketViviNavigationKind.UNUSED_DETAIL, bounds)
    val wrong = TicketVisualActionObservation(11, TicketVisualPhoneState.UNACTIVATED_DETAIL,
      currentAnchor = "d_" + "b".repeat(28), sliderBounds = bounds, backBounds = bounds)
    assertEquals(TicketViviReauthRestoreTransitionStatus.REJECTED,
      ticketViviNavigationTransition(TicketViviNavigationMode.EXACT, original, target, before, wrong))
    assertEquals(TicketViviReauthRestoreTransitionStatus.WAITING,
      ticketViviNavigationTransition(TicketViviNavigationMode.EXACT, original, target, before,
        wrong.copy(probeId = before.probeId, currentAnchor = original.detailAnchor)))
    assertEquals(TicketViviReauthRestoreTransitionStatus.PROVED,
      ticketViviNavigationTransition(TicketViviNavigationMode.EXACT, original, target, before,
        wrong.copy(currentAnchor = original.detailAnchor)))
  }

  @Test
  fun onlyRedetectionCanCompleteWithBothTicketTabsEmpty() {
    val bounds = TicketVisualProbeBounds(10, 20, 30, 40)
    val original = TicketViviReauthReturnTarget(TicketVisualPhoneState.UNACTIVATED_DETAIL, "d_" + "a".repeat(28))
    val before = TicketVisualActionObservation(10, TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
      timeTicketsTabBounds = bounds)
    val after = TicketVisualActionObservation(11, TicketVisualPhoneState.TICKETS_TIME_EMPTY,
      ticketsTabBounds = bounds)
    val target = TicketViviNavigationTarget(TicketViviNavigationKind.TIME_TAB, bounds)
    assertEquals(TicketViviReauthRestoreTransitionStatus.REJECTED,
      ticketViviNavigationTransition(TicketViviNavigationMode.EXACT, original, target, before, after))
    for (mode in listOf(TicketViviNavigationMode.ORIGINAL, TicketViviNavigationMode.LATEST)) {
      assertEquals(TicketViviReauthRestoreTransitionStatus.PROVED,
        ticketViviNavigationTransition(mode, original, target, before, after))
    }
  }
}
