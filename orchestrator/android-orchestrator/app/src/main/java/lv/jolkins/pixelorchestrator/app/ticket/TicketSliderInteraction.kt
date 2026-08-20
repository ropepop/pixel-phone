package lv.jolkins.pixelorchestrator.app.ticket

import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Current-only interaction state read from SpacetimeDB. Pointer history never
 * enters this object; the latest browser sample replaces the previous one.
 */
internal data class TicketSpacetimeInteractionSnapshot(
  val status: String,
  val interactionRevision: String,
  val activationRevision: String,
  val activationAt: String,
  val scheduledResetAt: String,
  val resetRequestId: String,
  val streamEpoch: Long,
  val frameSequence: Long,
  val phoneDisplayWidth: Int,
  val phoneDisplayHeight: Int,
  val sliderLeft: Int,
  val sliderTop: Int,
  val sliderRight: Int,
  val sliderBottom: Int,
  val ownerPublicId: String,
  val controlId: String,
  val leasePhase: String,
  val leaseExpiresAt: String,
  val latestInputSequence: String,
  val latestInputPhase: String,
  val latestProgress: Int,
  val lastAppliedSequence: String,
  val lastAppliedProgress: Int,
  val reason: String,
  val updatedAt: String,
  val expiresAt: String
) {
  val hasSliderBounds: Boolean
    get() = sliderRight > sliderLeft && sliderBottom > sliderTop
}

internal data class TicketSliderApplicationResult(
  val ok: Boolean,
  val reason: String,
  val status: String,
  val lastAppliedSequence: String,
  val lastAppliedProgress: Int,
  val leasePhase: String,
  val leaseExpiresAt: String,
  val ownerPublicId: String,
  val controlId: String,
  val activationRevision: String = "",
  val activationAt: String = "",
  val scheduledResetAt: String = "",
  val activationAttemptId: String = ""
)

internal fun sliderApplied(
  ok: Boolean,
  reason: String,
  status: String,
  sequence: Long,
  progress: Int,
  leasePhase: String,
  leaseExpiresAt: String = "",
  ownerPublicId: String = "",
  controlId: String = "",
  activationRevision: String = "",
  activationAt: String = "",
  scheduledResetAt: String = "",
  activationAttemptId: String = ""
) = TicketSliderApplicationResult(
  ok = ok,
  reason = reason,
  status = status,
  lastAppliedSequence = sequence.toString(),
  lastAppliedProgress = progress,
  leasePhase = leasePhase,
  leaseExpiresAt = leaseExpiresAt,
  ownerPublicId = ownerPublicId,
  controlId = controlId,
  activationRevision = activationRevision,
  activationAt = activationAt,
  scheduledResetAt = scheduledResetAt,
  activationAttemptId = activationAttemptId
)

internal data class TicketRegistrationProof(
  val status: String,
  val reason: String,
  val interactionRevision: String,
  val streamEpoch: Long,
  val frameSequence: Long,
  val phoneDisplayWidth: Int,
  val phoneDisplayHeight: Int,
  val provedAtUptimeMillis: Long = 0L,
  val activationRevision: String = "",
  val activationAt: String = "",
  val activationAttemptId: String = "",
  val sliderLeft: Int = 0,
  val sliderTop: Int = 0,
  val sliderRight: Int = 0,
  val sliderBottom: Int = 0
)

/**
 * The browser binds an unactivated slider to the stream epoch/frame that proved its geometry.
 * A reset can finish before the rooted H.264 stream settles on its final epoch, so a stale row
 * must be revalidated before it is exposed to the browser again.
 */
internal fun ticketRegistrationProofRequiresRefresh(
  status: String,
  interactionRevision: String,
  proofRevision: String,
  proofEpoch: Long,
  proofFrameSequence: Long,
  currentEpoch: Long,
  currentFrameSequence: Long,
  hasSliderBounds: Boolean
): Boolean {
  if (status != "unactivated_ready") return false
  if (interactionRevision.isBlank() || proofRevision != interactionRevision) return true
  if (!hasSliderBounds) return true
  if (proofEpoch <= 0L || proofFrameSequence <= 0L) return true
  if (currentEpoch <= 0L || currentFrameSequence <= 0L) return true
  return proofEpoch != currentEpoch || proofFrameSequence > currentFrameSequence
}

internal val TicketRegistrationProof.hasSliderBounds: Boolean
  get() = sliderRight > sliderLeft && sliderBottom > sliderTop

internal fun TicketRegistrationProof.toGraphicBounds(): TicketViviGraphicBounds {
  return TicketViviGraphicBounds(
    left = sliderLeft,
    top = sliderTop,
    right = sliderRight,
    bottom = sliderBottom
  )
}

internal fun instantSliderActivationRevision(commandId: String, interactionRevision: String): String {
  val identity = "${commandId.trim()}|${interactionRevision.trim()}"
  val uuid = UUID.nameUUIDFromBytes(identity.toByteArray(StandardCharsets.UTF_8))
    .toString()
    .replace("-", "")
  return "activation_button_$uuid"
}
