package lv.jolkins.pixelorchestrator.app.ticket

internal data class TicketViviPageAction(
  val x: Int,
  val y: Int,
  val reason: String,
  val bounds: String? = null
)

internal data class TicketViviGraphicBounds(
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int
) {
  val width: Int get() = (right - left).coerceAtLeast(0)
  val height: Int get() = (bottom - top).coerceAtLeast(0)
}

internal enum class TicketViviRecoveryState {
  BLANK,
  OUTSIDE_VIVI,
  TICKET_DETAIL,
  CONTROL_CODE_POPUP,
  CONTROL_CODE_RESULT,
  DISMISSIBLE_BLOCKER,
  CART_OR_CHECKOUT,
  LOGIN_REQUIRED,
  AUTH_ATTENTION_REQUIRED,
  TICKET_LIST_WITH_CARD,
  TICKET_LIST_EMPTY,
  OTHER_VIVI_TAB,
  SETTINGS_OR_PROFILE,
  UNKNOWN_VIVI
}
