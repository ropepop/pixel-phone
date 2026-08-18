package lv.jolkins.pixelorchestrator.app.ticket

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
  val scheduledResetAt: String = ""
)

internal data class TicketRegistrationProof(
  val status: String,
  val reason: String,
  val interactionRevision: String,
  val streamEpoch: Long,
  val frameSequence: Long,
  val phoneDisplayWidth: Int,
  val phoneDisplayHeight: Int,
  val sliderLeft: Int = 0,
  val sliderTop: Int = 0,
  val sliderRight: Int = 0,
  val sliderBottom: Int = 0
)

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
