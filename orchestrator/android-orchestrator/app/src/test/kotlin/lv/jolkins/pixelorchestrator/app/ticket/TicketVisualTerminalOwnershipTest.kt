package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Test

class TicketVisualTerminalOwnershipTest {
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

}
