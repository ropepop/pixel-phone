package lv.jolkins.pixelorchestrator.app.ticket

import java.nio.charset.StandardCharsets
import java.util.UUID

internal data class TicketRegistrationProof(
  val status: String,
  val reason: String,
  val interactionRevision: String,
  val streamEpoch: Long,
  val frameSequence: Long,
  val phoneDisplayWidth: Int,
  val phoneDisplayHeight: Int,
  val provedAtUptimeMillis: Long = 0L,
  /** Phone-private opaque visual identity; never included in Spacetime publication. */
  val ticketAnchor: String = "",
  /** Phone-private detail-view identity used to reject a stale proof after navigation. */
  val detailAnchor: String = "",
  val activationRevision: String = "",
  val activationAt: String = "",
  val activationAttemptId: String = "",
  val sliderLeft: Int = 0,
  val sliderTop: Int = 0,
  val sliderRight: Int = 0,
  val sliderBottom: Int = 0
)

internal data class TicketSliderGestureContract(
  val startX: Int,
  val endX: Int,
  val durationMillis: Long
)

/** One activation stroke: thumb-down, full usable-track movement, and one release. */
internal fun ticketSliderGestureContract(
  bounds: TicketViviGraphicBounds,
  durationMillis: Long = 800L
): TicketSliderGestureContract {
  // The rooted visual band includes the thumb plus ViVi's vertical padding. Live proof showed
  // that half the full band height inset both endpoints too far; three eighths reaches the
  // thumb centres while retaining the same symmetric, visually bounded stroke.
  val thumbCenterInset = (((bounds.bottom - bounds.top) * 3) / 8).coerceAtLeast(12)
  val startX = bounds.left + thumbCenterInset
  val endX = (bounds.right - thumbCenterInset).coerceAtLeast(startX)
  return TicketSliderGestureContract(
    startX = startX,
    endX = endX,
    durationMillis = durationMillis.coerceAtLeast(1L)
  )
}

/** The current rooted observation owns slider geometry; Android owns input readiness. */
internal fun ticketVisualSliderGestureBounds(
  visualBounds: TicketViviGraphicBounds,
  displayWidth: Int,
  displayHeight: Int
): TicketViviGraphicBounds? = visualBounds.takeIf {
  displayWidth > 0 && displayHeight > 0 &&
    it.left >= 0 && it.top >= 0 &&
    it.right <= displayWidth && it.bottom <= displayHeight &&
    it.width >= 220 && it.height >= 24 && it.width >= it.height * 3
}


internal val TicketRegistrationProof.hasSliderBounds: Boolean
  get() = sliderRight > sliderLeft && sliderBottom > sliderTop

/**
 * Stable activation identity for a command attempt.  It is derived only from identifiers that
 * already belong to the admitted action, so a process restart cannot manufacture a new physical
 * gesture identity from uptime, frame number, or another transient value.
 */
internal fun ticketActivationRevision(
  commandId: String,
  interactionRevision: String,
  activationAttemptId: String
): String {
  val identity = "${commandId.trim()}|${interactionRevision.trim()}|${activationAttemptId.trim()}"
  val uuid = UUID.nameUUIDFromBytes(identity.toByteArray(StandardCharsets.UTF_8))
    .toString()
    .replace("-", "")
  return "activation_ticket_$uuid"
}
