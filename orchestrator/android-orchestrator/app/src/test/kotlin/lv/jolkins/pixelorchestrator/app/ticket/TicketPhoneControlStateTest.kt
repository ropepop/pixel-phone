package lv.jolkins.pixelorchestrator.app.ticket

import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TicketPhoneControlStateTest {
  private fun observation(capture: Long, anchor: String = "private-a") = TicketVisualActionObservation(
    probeId = 1, state = TicketVisualPhoneState.UNACTIVATED_DETAIL, currentAnchor = anchor,
    sliderBounds = TicketVisualProbeBounds(20, 168, 180, 196), captureStartUs = capture * 1_000L,
    atMillis = capture + 20
  )

  @Test fun controlRegionUsesClassifierOutputDimensionsRatherThanItsLargerInputProbe() {
    val normalized = ticketPhoneControlBounds(TicketVisualProbeBounds(0, 168, 184, 196))!!
    assertEquals(0, normalized.leftBasisPoints)
    assertEquals(5833, normalized.topBasisPoints)
    assertEquals(9583, normalized.rightBasisPoints)
    assertEquals(6806, normalized.bottomBasisPoints)
  }

  @Test fun repeatedCaptureCannotRenewAndContextSurvivesNewPictures() {
    val state = TicketPhoneControlState("pc-test")
    state.observe(observation(100), false)
    val first = state.updates.value
    state.observe(observation(100), false)
    assertEquals(first, state.updates.value)
    state.observe(observation(200), false)
    assertEquals(first.contextRevision, state.updates.value.contextRevision)
    assertTrue(state.updates.value.sequence > first.sequence)
    assertNotNull(state.exactContext(first.contextRevision, 300))
    assertNull(state.exactContext(first.contextRevision, 3_200))
    state.observe(observation(300, "private-b"), false)
    assertNull(state.exactContext(first.contextRevision, 301))
  }

  @Test fun geometryUnknownAndInterferenceInvalidateOldGestures() {
    val state = TicketPhoneControlState("pc-test")
    state.observe(observation(100), false)
    val first = state.updates.value.contextRevision
    state.observe(observation(200).copy(sliderBounds = TicketVisualProbeBounds(30, 280, 180, 310)), false)
    assertNotEquals(first, state.updates.value.contextRevision)
    val second = state.updates.value.contextRevision
    state.invalidate("physical_touch", busy = true)
    assertNull(state.exactContext(second, 250))
    state.observe(observation(300), false)
    assertNotEquals(first, state.updates.value.contextRevision)
    state.observe(observation(400).copy(state = TicketVisualPhoneState.UNKNOWN, currentAnchor = ""), false)
    assertNull(state.exactContext(state.updates.value.contextRevision, 401))
  }

  @Test fun timestampIncludesNetworkDelayAndNeverUsesPhoneWallClock() {
    val server = Instant.parse("2026-09-06T12:00:00Z").toEpochMilli()
    val clock = TicketPhoneControlClock(server, 500)
    assertEquals(Instant.ofEpochMilli(server - 101).toString(), clock.observedAt(400_000, 600))
    assertNull(clock.observedAt(700_000, 600))
    assertNull(clock.observedAt(400_000, 30_500))
  }

  @Test fun missingInputServiceKeepsVisualHealthButCannotGrantOrRetainActionReadiness() = runTest {
    val state = TicketPhoneControlState("pc-test")
    val transport = Transport()
    var now = 200L
    backgroundScope.launch { TicketPhoneControlPublisher(state, { now }).run(transport) }
    runCurrent()
    state.observe(liveObservation(150), false, evidenceFence, inputAvailable = false)
    runCurrent()
    val unavailable = state.updates.value
    assertEquals("ticket_action_accessibility_unavailable", unavailable.reason)
    assertFalse(transport.published.last().third)
    assertNull(state.exactContext(unavailable.contextRevision, now))
    assertNull(state.registrationEvidence(unavailable.contextRevision, evidenceFence, now))
    assertEquals("ready", ticketMonitoringObservation(unavailable.observation!!.state,
      unavailable.busy, unavailable.observation.captureStartUs / 1_000L).status)

    state.invalidate("input_service_connected", capturedThroughUs = 250_000)
    state.observe(liveObservation(240), false, evidenceFence, inputAvailable = true)
    runCurrent()
    assertFalse(transport.published.last().third)
    now = 350
    state.observe(liveObservation(300), false, evidenceFence, inputAvailable = true)
    runCurrent()
    val restored = state.updates.value.contextRevision
    assertTrue(transport.published.last().third)
    assertNotEquals(unavailable.contextRevision, restored)
    assertNotNull(state.exactContext(restored, now))
    state.invalidate("ticket_action_accessibility_unavailable", capturedThroughUs = 360_000)
    runCurrent()
    assertFalse(transport.published.last().third)
    assertNull(state.exactContext(restored, 370))
    now = 450
    state.observe(liveObservation(400), false, evidenceFence, inputAvailable = false)
    runCurrent()
    assertFalse(transport.published.last().third)
  }

  @Test fun receivedIdentityDoesNotNeedAnEncodedFrameAndRejectsChangedDetail() {
    val state = TicketPhoneControlState("pc-test")
    state.observe(observation(100), false)
    val revision = state.updates.value.contextRevision
    val identity = ticketPhoneControlRegistrationIdentity(revision, state.exactContext(revision, 200))!!
    val request = TicketVisualActionRequest(
      actionId = "register-test", target = TicketVisualActionTarget.REGISTER_CURRENT,
      expectedInteractionRevision = revision, attemptId = "register-test",
      source = "browser_slider", reason = "", scheduleId = "", policyRevision = "", switchExpiresAt = ""
    )
    assertEquals(0L, identity.frameSequence)
    assertNotNull(ticketRegistrationProofForCurrentVisualAction(identity, request, observation(300)).proof)
    assertNull(ticketRegistrationProofForCurrentVisualAction(identity, request, observation(300, "private-b")).proof)
    state.observe(observation(300), true)
    assertNull(state.exactContext(revision, 400))
    // Owning the action cannot erase the immutable identity already received.
    assertNotNull(ticketRegistrationProofForCurrentVisualAction(identity, request, observation(400)).proof)
    assertNull(ticketPhoneControlRegistrationIdentity(revision, null))
  }

  private val evidenceFence = TicketRegistrationEvidenceFence(1, 7, 3, 17, 0, 0)
  private fun liveObservation(capture: Long) = observation(capture).copy(probeId = 0, captureGeneration = 7)

  @Test fun registrationReusesTwoDistinctLivePicturesWithoutTurningThemIntoRequestedProbes() {
    val state = TicketPhoneControlState("pc-test")
    state.observe(liveObservation(1_000), false, evidenceFence)
    val revision = state.updates.value.contextRevision
    assertTrue(state.registrationCandidateIsCurrent(revision, evidenceFence, 1_100))
    assertNull(state.registrationEvidence(revision, evidenceFence, 1_100))
    state.observe(liveObservation(1_000), false, evidenceFence)
    state.observe(liveObservation(900), false, evidenceFence)
    assertNull(state.registrationEvidence(revision, evidenceFence, 1_100))
    state.observe(liveObservation(2_000), true, evidenceFence)
    val evidence = state.registrationEvidence(revision, evidenceFence, 2_100)!!
    assertEquals(1_000_000L, evidence.first.captureStartUs)
    assertEquals(2_000_000L, evidence.second.captureStartUs)
    assertNull(state.exactContext(revision, 2_100)) // Public readiness stays busy.
    assertFalse(ticketVisualObservationIsFreshForDispatch(evidence.second, 2_100, 3_000))
    assertTrue(state.registrationEvidenceIsCurrent(evidence, evidenceFence, 3_999))
    assertFalse(state.registrationEvidenceIsCurrent(evidence, evidenceFence, 4_000))
    state.observe(liveObservation(3_000), true, evidenceFence)
    val newer = state.registrationEvidence(revision, evidenceFence, 4_000)!!
    assertEquals(2_000_000L, newer.first.captureStartUs)
    assertEquals(3_000_000L, newer.second.captureStartUs)
    assertNull(state.registrationEvidence(revision, evidenceFence, 2_999)) // Future capture.
  }

  @Test fun preparedRegistrationCannotCrossFocusTouchOrCaptureBoundaries() {
    val changedFences = listOf(
      evidenceFence.copy(streamEpoch = 2),
      evidenceFence.copy(captureGeneration = 8),
      evidenceFence.copy(inputGeneration = 4),
      evidenceFence.copy(windowId = 18),
      evidenceFence.copy(touchGeneration = 1),
      evidenceFence.copy(validAfterMillis = 1_500)
    )
    for (changed in changedFences) {
      val state = TicketPhoneControlState("pc-test")
      state.observe(liveObservation(1_000), false, evidenceFence)
      state.observe(liveObservation(2_000), false, evidenceFence)
      val revision = state.updates.value.contextRevision
      val prepared = state.registrationEvidence(revision, evidenceFence, 2_100)!!
      assertFalse(state.registrationEvidenceIsCurrent(prepared, changed, 2_100))
      state.observe(liveObservation(2_200), true, changed)
      assertNull(state.registrationEvidence(revision, changed, 2_300))
      assertFalse(state.registrationEvidenceIsCurrent(prepared, evidenceFence, 2_300))
    }
  }

  @Test fun unknownInterferenceAndDispatchClearThePairAndLatePicturesCannotRestoreIt() {
    for (interruption in listOf("unknown", "different_ticket", "touch", "dispatch")) {
      val state = TicketPhoneControlState("pc-test")
      state.observe(liveObservation(1_000), false, evidenceFence)
      state.observe(liveObservation(2_000), false, evidenceFence)
      val revision = state.updates.value.contextRevision
      val prepared = state.registrationEvidence(revision, evidenceFence, 2_100)!!
      when (interruption) {
        "unknown" -> state.observe(liveObservation(2_100).copy(state = TicketVisualPhoneState.UNKNOWN), true, evidenceFence)
        "different_ticket" -> state.observe(liveObservation(2_100).copy(currentAnchor = "private-b"), true, evidenceFence)
        "touch" -> state.invalidate("physical_touch", true, 2_100_000)
        "dispatch" -> state.clearRegistrationEvidence()
      }
      state.observe(liveObservation(1_500), false, evidenceFence)
      assertFalse(state.registrationEvidenceIsCurrent(prepared, evidenceFence, 2_200))
      state.observe(liveObservation(2_200), true, evidenceFence)
      assertNull(state.registrationEvidence(state.updates.value.contextRevision, evidenceFence, 2_300))
      state.observe(liveObservation(2_400), true, evidenceFence)
      assertNotNull(state.registrationEvidence(state.updates.value.contextRevision, evidenceFence, 2_500))
    }
  }

  @Test fun picturesCapturedBeforeAnInputChangeCannotFormPreparedEvidence() {
    val state = TicketPhoneControlState("pc-test")
    val fence = evidenceFence.copy(validAfterMillis = 1_500)
    state.observe(liveObservation(1_000), false, fence)
    state.observe(liveObservation(2_000), false, fence)
    val revision = state.updates.value.contextRevision
    assertNull(state.registrationEvidence(revision, fence, 2_100))
    state.observe(liveObservation(3_000), false, fence)
    assertNotNull(state.registrationEvidence(revision, fence, 3_100))
  }

  private class Transport : TicketPhoneControlTransport {
    var session: TicketPhoneControlSession? = null
    val published = mutableListOf<Triple<TicketPhoneControlObservation, String, Boolean>>()
    override suspend fun readControlSession() = session
    override suspend fun beginControlSession(sessionId: String, expectedPrevious: String) {
      check(session == null || session!!.sessionId == sessionId || session!!.sessionId == expectedPrevious)
      session = TicketPhoneControlSession(sessionId, "2026-09-06T12:00:00Z")
    }
    override suspend fun publishControlObservation(sessionId: String, state: TicketPhoneControlObservation, observedAt: String, ready: Boolean) {
      check(session?.sessionId == sessionId)
      published += Triple(state, observedAt, ready)
    }
  }

  @Test fun statePublishesWithoutMediaAndReplacedSessionCannotReclaim() = runTest {
    val state = TicketPhoneControlState("pc-test")
    val transport = Transport()
    var now = 200L
    val publisher = TicketPhoneControlPublisher(state, { now })
    val job = backgroundScope.launch { publisher.run(transport) }
    runCurrent()
    state.observe(observation(150), false)
    runCurrent()
    assertTrue(transport.published.single().third)
    job.cancel()
    runCurrent()
    transport.session = TicketPhoneControlSession("pc-replacement", "2026-09-06T12:00:01Z")
    backgroundScope.launch { publisher.run(transport) }
    runCurrent()
    assertEquals("pc-replacement", transport.session?.sessionId)
    assertEquals("phone_session_replaced", state.updates.value.reason)
    now = 400L
    state.observe(observation(350), false)
    runCurrent()
    assertEquals(1, transport.published.size)
  }

  @Test fun slowPublicationIsNotStarvedByNewCaptureAndPendingStateIsConflated() = runTest {
    val state = TicketPhoneControlState("pc-test")
    val backing = Transport()
    val transport = object : TicketPhoneControlTransport by backing {
      override suspend fun publishControlObservation(sessionId: String, state: TicketPhoneControlObservation, observedAt: String, ready: Boolean) {
        delay(1_400)
        backing.publishControlObservation(sessionId, state, observedAt, ready)
      }
    }
    val publisher = TicketPhoneControlPublisher(state, { testScheduler.currentTime })
    backgroundScope.launch { publisher.run(transport) }
    runCurrent()
    advanceTimeBy(100)
    state.observe(observation(100), false)
    runCurrent()
    repeat(8) {
      advanceTimeBy(200)
      state.observe(observation(testScheduler.currentTime), false)
      runCurrent()
    }
    assertEquals(1, backing.published.size)
    assertEquals(1L, backing.published.single().first.sequence)
    advanceTimeBy(1_400)
    runCurrent()
    assertTrue(backing.published.last().first.sequence > 2L)
  }
  @Test fun slowClockRenewalDoesNotInterruptFreshObservationPublication() = runTest {
    val state = TicketPhoneControlState("pc-test")
    val backing = Transport()
    var renewals = 0
    val transport = object : TicketPhoneControlTransport by backing {
      override suspend fun beginControlSession(sessionId: String, expectedPrevious: String) {
        if (++renewals > 1) delay(6_000)
        backing.beginControlSession(sessionId, expectedPrevious)
      }
    }
    val publisher = TicketPhoneControlPublisher(state, { testScheduler.currentTime })
    backgroundScope.launch { publisher.run(transport) }
    runCurrent()
    repeat(23) {
      advanceTimeBy(1_000)
      state.observe(observation(testScheduler.currentTime), false)
      runCurrent()
    }
    assertEquals(23, backing.published.size)
    assertTrue(backing.published.all { it.third })
    assertTrue(renewals >= 2)
  }

}
