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

/**
 * A rooted visual probe confirms that the current frame still contains the registration
 * control and its full horizontal travel. Its dark-thumb check makes the visual registration
 * band's height a proven symmetric horizontal-inset proxy; the accessibility hierarchy supplies
 * the precise slider-row center. Preserve that visual height while translating the rectangle
 * onto the semantic center, so split Flutter text nodes cannot move the gesture above the real
 * control or shrink its horizontal travel.
 */
internal fun ticketSliderGestureBoundsAfterVisualProof(
  hierarchyBounds: TicketViviGraphicBounds,
  visualBounds: TicketViviGraphicBounds,
  displayWidth: Int,
  displayHeight: Int
): TicketViviGraphicBounds? {
  if (
    displayWidth <= 0 || displayHeight <= 0 ||
    hierarchyBounds.left < 0 || hierarchyBounds.top < 0 ||
    hierarchyBounds.right > displayWidth || hierarchyBounds.bottom > displayHeight ||
    hierarchyBounds.width < 220 || hierarchyBounds.height < 24 ||
    hierarchyBounds.width < hierarchyBounds.height * 2 ||
    visualBounds.left < 0 || visualBounds.top < 0 ||
    visualBounds.right > displayWidth || visualBounds.bottom > displayHeight ||
    visualBounds.width < 220 || visualBounds.height < 24 ||
    visualBounds.width < visualBounds.height * 3
  ) {
    return null
  }
  val horizontalOverlap = minOf(hierarchyBounds.right, visualBounds.right) -
    maxOf(hierarchyBounds.left, visualBounds.left)
  val requiredHorizontalOverlap = (minOf(hierarchyBounds.width, visualBounds.width) + 1) / 2
  if (horizontalOverlap < requiredHorizontalOverlap) return null
  val hierarchyCenterX = (hierarchyBounds.left + hierarchyBounds.right) / 2
  if (hierarchyCenterX !in visualBounds.left..visualBounds.right) return null
  val hierarchyCenterY = (hierarchyBounds.top + hierarchyBounds.bottom) / 2
  val verticalOverlap = minOf(hierarchyBounds.bottom, visualBounds.bottom) -
    maxOf(hierarchyBounds.top, visualBounds.top)
  val requiredVerticalOverlap = (minOf(hierarchyBounds.height, visualBounds.height) + 1) / 2
  if (hierarchyCenterY !in visualBounds.top..visualBounds.bottom && verticalOverlap < requiredVerticalOverlap) {
    return null
  }
  val translatedTop = hierarchyCenterY - visualBounds.height / 2
  val translatedBottom = translatedTop + visualBounds.height
  if (translatedTop < 0 || translatedBottom > displayHeight) return null
  return TicketViviGraphicBounds(
    left = visualBounds.left,
    top = translatedTop,
    right = visualBounds.right,
    bottom = translatedBottom
  )
}

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
