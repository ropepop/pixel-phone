package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketActivationCheckpointTest {
  @Test
  fun anAlreadyProvenScreenCanBeCommittedWhenNoOldCheckpointExists() {
    assertEquals(
      TicketActivationRecoveryAction.COMMIT_PROVEN_RESULT,
      ticketActivationRecoveryAction(null, TicketActivationRecoveryScreen.ACTIVATED)
    )
    assertEquals(
      TicketActivationRecoveryAction.NONE,
      ticketActivationRecoveryAction(null, TicketActivationRecoveryScreen.UNUSED)
    )
  }

  @Test
  fun freshProofResumesOnlyOnTheSameUnusedAction() {
    val checkpoint = checkpoint(TicketActivationCheckpointStage.FRESH_TICKET_PROVEN)
    assertEquals(
      TicketActivationRecoveryAction.RESUME_ACTIVATION,
      ticketActivationRecoveryAction(checkpoint, TicketActivationRecoveryScreen.UNUSED)
    )
    assertEquals(
      TicketActivationRecoveryAction.COMMIT_PROVEN_RESULT,
      ticketActivationRecoveryAction(checkpoint, TicketActivationRecoveryScreen.ACTIVATED)
    )
    assertEquals(
      TicketActivationRecoveryAction.NEEDS_ATTENTION,
      ticketActivationRecoveryAction(checkpoint, TicketActivationRecoveryScreen.AMBIGUOUS)
    )
  }

  @Test
  fun dispatchingGestureNeverRetriesOnAnUnusedOrAmbiguousScreen() {
    val checkpoint = checkpoint(TicketActivationCheckpointStage.ACTIVATION_DISPATCHING)
    assertEquals(
      TicketActivationRecoveryAction.COMMIT_PROVEN_RESULT,
      ticketActivationRecoveryAction(checkpoint, TicketActivationRecoveryScreen.ACTIVATED)
    )
    assertEquals(
      TicketActivationRecoveryAction.NEEDS_ATTENTION,
      ticketActivationRecoveryAction(checkpoint, TicketActivationRecoveryScreen.UNUSED)
    )
    assertEquals(
      TicketActivationRecoveryAction.NEEDS_ATTENTION,
      ticketActivationRecoveryAction(checkpoint, TicketActivationRecoveryScreen.AMBIGUOUS)
    )
  }

  @Test
  fun provenResultCommitsWithoutASecondGesture() {
    val checkpoint = checkpoint(
      stage = TicketActivationCheckpointStage.ACTIVATION_PROVEN,
      activationRevision = "activation-revision"
    )
    assertEquals(
      TicketActivationRecoveryAction.COMMIT_PROVEN_RESULT,
      ticketActivationRecoveryAction(checkpoint, TicketActivationRecoveryScreen.ACTIVATED)
    )
    assertEquals(
      TicketActivationRecoveryAction.NEEDS_ATTENTION,
      ticketActivationRecoveryAction(checkpoint, TicketActivationRecoveryScreen.UNUSED)
    )
  }

  @Test
  fun committedResultOnlyAcknowledgesAndNeedsAttentionIsTerminal() {
    assertEquals(
      TicketActivationRecoveryAction.ACKNOWLEDGE_ONLY,
      ticketActivationRecoveryAction(
        checkpoint(TicketActivationCheckpointStage.ACTIVATION_COMMITTED),
        TicketActivationRecoveryScreen.AMBIGUOUS
      )
    )
    assertEquals(
      TicketActivationRecoveryAction.NEEDS_ATTENTION,
      ticketActivationRecoveryAction(
        checkpoint(TicketActivationCheckpointStage.NEEDS_ATTENTION),
        TicketActivationRecoveryScreen.ACTIVATED
      )
    )
  }

  @Test
  fun storeKeepsOnlyOpaqueCheckpointAndClearsMatchingAction() {
    val backend = InMemoryTicketActivationCheckpointBackend()
    val store = TicketActivationCheckpointStore(backend)
    val fresh = requireNotNull(store.recordFreshTicketProven("command", "revision", "attempt"))
    val dispatching = requireNotNull(store.recordActivationDispatching(fresh))
    val proven = requireNotNull(store.recordActivationProven(dispatching, "activation"))
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

    assertNull(store.recordActivationDispatching(fresh))
    assertNull(store.load())
  }

  @Test
  fun successfulCommitWithoutMatchingReadbackIsStillUnproved() {
    val backend = InMemoryTicketActivationCheckpointBackend(persistCommittedValue = false)
    val store = TicketActivationCheckpointStore(backend)
    val fresh = checkpoint(TicketActivationCheckpointStage.FRESH_TICKET_PROVEN)

    assertNull(store.recordActivationDispatching(fresh))
    assertNull(store.load())
  }

  private fun checkpoint(
    stage: TicketActivationCheckpointStage,
    activationRevision: String = ""
  ) = TicketActivationCheckpoint(
    commandId = "command",
    interactionRevision = "revision",
    activationAttemptId = "attempt",
    activationRevision = activationRevision,
    stage = stage
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
  fun narrowBoundsStillProduceAValidSingleTrack() {
    val contract = ticketSliderGestureContract(
      TicketViviGraphicBounds(left = 10, top = 20, right = 30, bottom = 80)
    )
    assertEquals(32, contract.startX)
    assertEquals(32, contract.endX)
    assertTrue(contract.durationMillis > 0L)
  }

  @Test
  fun shortVisualBandKeepsTheMinimumSafeThumbCentreInset() {
    val bounds = TicketViviGraphicBounds(left = 10, top = 20, right = 100, bottom = 40)

    val contract = ticketSliderGestureContract(bounds)

    assertEquals(22, contract.startX)
    assertEquals(88, contract.endX)
  }

  @Test
  fun broadVisualTicketStripUsesItsFullTrackAtTheCompleteSemanticSliderRow() {
    val semantic = TicketViviGraphicBounds(left = 362, top = 1543, right = 718, bottom = 1654)
    val broadVisual = TicketViviGraphicBounds(left = 45, top = 1497, right = 1035, bottom = 1714)

    val result = ticketSliderGestureBoundsAfterVisualProof(
      hierarchyBounds = semantic,
      visualBounds = broadVisual,
      displayWidth = 1080,
      displayHeight = 2424
    )

    assertEquals(TicketViviGraphicBounds(left = 45, top = 1490, right = 1035, bottom = 1707), result)
    val contract = ticketSliderGestureContract(requireNotNull(result))
    assertEquals(126, contract.startX)
    assertEquals(954, contract.endX)
    assertEquals(1598, (result.top + result.bottom) / 2)
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

  @Test
  fun oddHeightUsesDeterministicIntegerInsetAndKeepsSymmetricTravel() {
    val bounds = TicketViviGraphicBounds(left = 20, top = 100, right = 520, bottom = 201)

    val contract = ticketSliderGestureContract(bounds)

    assertEquals(57, contract.startX)
    assertEquals(483, contract.endX)
    assertEquals(contract.startX - bounds.left, bounds.right - contract.endX)
  }

  @Test
  fun semanticRowHeightCannotChangeTheVisuallyProvedHorizontalTravel() {
    val visual = TicketViviGraphicBounds(left = 45, top = 1497, right = 1035, bottom = 1714)
    val tallSemantic = TicketViviGraphicBounds(left = 362, top = 1543, right = 718, bottom = 1654)
    val shortSemantic = TicketViviGraphicBounds(left = 396, top = 1568, right = 684, bottom = 1629)

    val tall = requireNotNull(ticketSliderGestureBoundsAfterVisualProof(tallSemantic, visual, 1080, 2424))
    val short = requireNotNull(ticketSliderGestureBoundsAfterVisualProof(shortSemantic, visual, 1080, 2424))

    assertEquals(ticketSliderGestureContract(tall), ticketSliderGestureContract(short))
    assertEquals(1598, (tall.top + tall.bottom) / 2)
    assertEquals(1598, (short.top + short.bottom) / 2)
  }

  @Test
  fun trivialVisualOverlapCannotAuthorizeAStrokeOutsideTheSemanticSlider() {
    val semantic = TicketViviGraphicBounds(left = 396, top = 1543, right = 684, bottom = 1604)
    val onePixelOverlap = TicketViviGraphicBounds(left = 683, top = 1497, right = 1035, bottom = 1585)

    assertEquals(
      null,
      ticketSliderGestureBoundsAfterVisualProof(
        hierarchyBounds = semantic,
        visualBounds = onePixelOverlap,
        displayWidth = 1080,
        displayHeight = 2424
      )
    )
  }

  @Test
  fun verticallyDistantVisualStripCannotAuthorizeTheSemanticSlider() {
    val semantic = TicketViviGraphicBounds(left = 396, top = 1543, right = 684, bottom = 1604)
    val distantVisual = TicketViviGraphicBounds(left = 45, top = 1800, right = 1035, bottom = 1900)

    assertEquals(
      null,
      ticketSliderGestureBoundsAfterVisualProof(
        hierarchyBounds = semantic,
        visualBounds = distantVisual,
        displayWidth = 1080,
        displayHeight = 2424
      )
    )
  }
}
