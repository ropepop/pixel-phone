package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Test

class InstantSliderPublicationDecisionTest {
  @Test
  fun interactionArrivingBeforeCommandIsPublishedBeforeAck() {
    assertEquals(
      InstantSliderPublicationDecision.PUBLISH,
      decision(status = "control_active", revision = "revision", controlId = "control")
    )
  }

  @Test
  fun commandObservedBeforeInteractionWaitsWithoutAcknowledging() {
    assertEquals(
      InstantSliderPublicationDecision.WAIT_FOR_INTERACTION,
      decision(status = null, revision = null, controlId = null)
    )
  }

  @Test
  fun repeatedPendingReadAfterPublicationIsIdempotent() {
    assertEquals(
      InstantSliderPublicationDecision.ALREADY_PUBLISHED,
      decision(
        status = "activated",
        revision = "revision",
        controlId = "control",
        currentActivationRevision = "activation"
      )
    )
  }

  @Test
  fun restartAfterPublishBeforeAckRecognizesDeterministicActivationWithEmptyMemory() {
    val firstProcessIdentity = instantSliderActivationRevision("pending_command", "revision")
    val restartedProcessIdentity = instantSliderActivationRevision("pending_command", "revision")
    assertEquals(firstProcessIdentity, restartedProcessIdentity)
    assertEquals(
      InstantSliderPublicationDecision.ALREADY_PUBLISHED,
      instantSliderPublicationDecision(
        currentStatus = "activated",
        currentRevision = "revision",
        currentControlId = "",
        currentActivationRevision = firstProcessIdentity,
        expectedRevision = "revision",
        expectedControlId = "control",
        activationRevision = restartedProcessIdentity
      )
    )
  }

  @Test
  fun activatedRowFromAnotherInteractionCannotAcknowledgePendingCommand() {
    assertEquals(
      InstantSliderPublicationDecision.WAIT_FOR_INTERACTION,
      instantSliderPublicationDecision(
        currentStatus = "activated",
        currentRevision = "older_revision",
        currentControlId = "",
        currentActivationRevision = instantSliderActivationRevision("pending_command", "revision"),
        expectedRevision = "revision",
        expectedControlId = "control",
        activationRevision = instantSliderActivationRevision("pending_command", "revision")
      )
    )
  }

  private fun decision(
    status: String?,
    revision: String?,
    controlId: String?,
    currentActivationRevision: String? = null
  ): InstantSliderPublicationDecision = instantSliderPublicationDecision(
    currentStatus = status,
    currentRevision = revision,
    currentControlId = controlId,
    currentActivationRevision = currentActivationRevision,
    expectedRevision = "revision",
    expectedControlId = "control",
    activationRevision = "activation"
  )
}
