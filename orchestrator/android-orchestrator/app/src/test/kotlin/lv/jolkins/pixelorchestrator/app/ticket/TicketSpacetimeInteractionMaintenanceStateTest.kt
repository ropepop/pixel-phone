package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketSpacetimeInteractionMaintenanceStateTest {
  @Test
  fun quietWorkerWithNoPositiveEpochSkipsTheInteractionRow() {
    val state = TicketSpacetimeInteractionMaintenanceState()
    state.onClientConnected()

    val plan = state.plan(
      streamEpoch = 0L,
      streamFrameSequence = 0L,
      sliderActive = false,
      sliderApplicationPending = false
    )

    assertFalse(plan.refreshRegistrationProof)
    assertFalse(plan.publishSliderApplication)
    assertFalse(plan.interactionQueryRequired)
  }

  @Test
  fun newPositiveEpochWaitsForItsFirstFrameBeforeCheckingProof() {
    val state = TicketSpacetimeInteractionMaintenanceState()
    state.onClientConnected()

    val starting = state.plan(
      streamEpoch = 31L,
      streamFrameSequence = 0L,
      sliderActive = false,
      sliderApplicationPending = false
    )
    assertFalse(starting.refreshRegistrationProof)
    assertFalse(starting.interactionQueryRequired)

    val framed = state.plan(
      streamEpoch = 31L,
      streamFrameSequence = 1L,
      sliderActive = false,
      sliderApplicationPending = false
    )
    assertTrue(framed.refreshRegistrationProof)
    assertTrue(framed.interactionQueryRequired)
  }

  @Test
  fun failedNewEpochReadRemainsDueUntilOneSnapshotIsAvailable() {
    val state = TicketSpacetimeInteractionMaintenanceState()
    state.onClientConnected()

    val first = state.plan(41L, 1L, sliderActive = false, sliderApplicationPending = false)
    assertTrue(first.refreshRegistrationProof)
    assertTrue(first.interactionQueryRequired)
    state.recordMaintenanceResult(
      first,
      interactionSnapshotAvailable = false,
      registrationProofCompleted = false
    )

    val retry = state.plan(41L, 1L, sliderActive = false, sliderApplicationPending = false)
    assertTrue(retry.refreshRegistrationProof)
    assertTrue(retry.interactionQueryRequired)
    state.recordMaintenanceResult(
      retry,
      interactionSnapshotAvailable = true,
      registrationProofCompleted = true
    )

    val settled = state.plan(41L, 1L, sliderActive = false, sliderApplicationPending = false)
    assertFalse(settled.refreshRegistrationProof)
    assertFalse(settled.interactionQueryRequired)
  }

  @Test
  fun newEpochAndClientReconnectEachScheduleOneFreshProofCheck() {
    val state = TicketSpacetimeInteractionMaintenanceState()
    state.onClientConnected()

    val firstEpoch = state.plan(51L, 1L, sliderActive = false, sliderApplicationPending = false)
    state.recordMaintenanceResult(
      firstEpoch,
      interactionSnapshotAvailable = true,
      registrationProofCompleted = true
    )
    assertFalse(
      state.plan(51L, 1L, sliderActive = false, sliderApplicationPending = false)
        .interactionQueryRequired
    )

    val nextEpoch = state.plan(52L, 1L, sliderActive = false, sliderApplicationPending = false)
    assertTrue(nextEpoch.refreshRegistrationProof)
    state.recordMaintenanceResult(
      nextEpoch,
      interactionSnapshotAvailable = true,
      registrationProofCompleted = true
    )

    state.onClientConnected()
    assertTrue(
      state.plan(52L, 1L, sliderActive = false, sliderApplicationPending = false)
        .refreshRegistrationProof
    )
  }

  @Test
  fun activeAndRetainedSliderWorkEachRequestPublicationWithoutAnotherProofCheck() {
    val state = TicketSpacetimeInteractionMaintenanceState()
    state.onClientConnected()
    val initial = state.plan(61L, 1L, sliderActive = false, sliderApplicationPending = false)
    state.recordMaintenanceResult(
      initial,
      interactionSnapshotAvailable = true,
      registrationProofCompleted = true
    )

    val active = state.plan(61L, 1L, sliderActive = true, sliderApplicationPending = false)
    assertFalse(active.refreshRegistrationProof)
    assertTrue(active.inspectRegistrationProof)
    assertTrue(active.publishSliderApplication)
    assertTrue(active.interactionQueryRequired)

    val retained = state.plan(61L, 1L, sliderActive = false, sliderApplicationPending = true)
    assertFalse(retained.refreshRegistrationProof)
    assertTrue(retained.inspectRegistrationProof)
    assertTrue(retained.publishSliderApplication)
    assertTrue(retained.interactionQueryRequired)
  }

  @Test
  fun interactionMutationInvalidatesACompletedCheckWithinTheSameEpoch() {
    val state = TicketSpacetimeInteractionMaintenanceState()
    state.onClientConnected()
    val completed = state.plan(71L, 1L, sliderActive = false, sliderApplicationPending = false)
    state.recordMaintenanceResult(
      completed,
      interactionSnapshotAvailable = true,
      registrationProofCompleted = true
    )
    assertFalse(
      state.plan(71L, 1L, sliderActive = false, sliderApplicationPending = false)
        .interactionQueryRequired
    )

    state.invalidateRegistrationProofCheck()

    val invalidated = state.plan(71L, 1L, sliderActive = false, sliderApplicationPending = false)
    assertTrue(invalidated.refreshRegistrationProof)
    assertTrue(invalidated.interactionQueryRequired)
  }

  @Test
  fun onlyNonMediaCommandsInvalidateInteractionMaintenance() {
    listOf("start", "recover_stream", "keyframe", "activity", "stream_cadence").forEach { commandType ->
      assertFalse(ticketSpacetimeCommandInvalidatesInteractionMaintenance(commandType))
    }
    listOf(
      "reset_ticket_registration",
      "slider_control_start",
      "force_ticket_reselect",
      "generate_control_code",
      "unknown_future_command"
    ).forEach { commandType ->
      assertTrue(ticketSpacetimeCommandInvalidatesInteractionMaintenance(commandType))
    }
  }

  @Test
  fun nonNullSnapshotWithFailedProofPublicationKeepsTheSameEpochDue() {
    val state = TicketSpacetimeInteractionMaintenanceState()
    state.onClientConnected()
    val failed = state.plan(81L, 1L, sliderActive = false, sliderApplicationPending = false)

    state.recordMaintenanceResult(
      failed,
      interactionSnapshotAvailable = true,
      registrationProofCompleted = false
    )

    val retry = state.plan(81L, 2L, sliderActive = false, sliderApplicationPending = false)
    assertTrue(retry.refreshRegistrationProof)
    assertTrue(retry.interactionQueryRequired)
  }

  @Test
  fun terminalSliderPublicationForcesFreshSameEpochProofSnapshot() {
    val state = TicketSpacetimeInteractionMaintenanceState()
    state.onClientConnected()
    val initial = state.plan(91L, 1L, sliderActive = false, sliderApplicationPending = false)
    state.recordMaintenanceResult(
      initial,
      interactionSnapshotAvailable = true,
      registrationProofCompleted = true
    )

    val terminalSlider = state.plan(91L, 2L, sliderActive = true, sliderApplicationPending = true)
    assertFalse(terminalSlider.refreshRegistrationProof)
    assertTrue(terminalSlider.publishSliderApplication)
    assertTrue(terminalSlider.inspectRegistrationProof)
    state.invalidateRegistrationProofCheck()

    val freshProof = state.plan(91L, 3L, sliderActive = false, sliderApplicationPending = false)
    assertTrue(freshProof.refreshRegistrationProof)
    assertTrue(freshProof.interactionQueryRequired)
  }
}
