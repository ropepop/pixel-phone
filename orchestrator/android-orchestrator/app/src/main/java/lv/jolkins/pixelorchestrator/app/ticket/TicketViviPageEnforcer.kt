package lv.jolkins.pixelorchestrator.app.ticket

import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationVisibleNode

internal data class TicketViviPageAction(
  val x: Int,
  val y: Int,
  val reason: String
)

internal enum class TicketViviRecoveryState {
  BLANK,
  OUTSIDE_VIVI,
  TICKET_DETAIL,
  CONTROL_CODE_POPUP,
  CONTROL_CODE_RESULT,
  DISMISSIBLE_BLOCKER,
  CART_OR_CHECKOUT,
  TICKET_LIST_WITH_CARD,
  TICKET_LIST_EMPTY,
  OTHER_VIVI_TAB,
  SETTINGS_OR_PROFILE,
  UNKNOWN_VIVI
}

internal object TicketViviPageEnforcer {
  fun isTicketDetail(xml: String): Boolean {
    return hasViviPackage(xml) &&
      hasTicketDetailMarkers(xml) &&
      !isControlCodePopup(xml) &&
      !isControlCodeResult(xml) &&
      !hasDismissibleBlockerMarkers(xml)
  }

  fun ticketIdForHierarchy(xml: String): String? {
    return Regex("""PV-[A-Z0-9-]+""").find(xml)?.value
  }

  fun hierarchyForVisibleNodes(nodes: List<PhoneAutomationVisibleNode>): String {
    if (nodes.isEmpty()) {
      return ""
    }
    return nodes.joinToString(
      separator = "\n",
      prefix = "<hierarchy>\n",
      postfix = "\n</hierarchy>"
    ) { node ->
      """<node package="${TicketScreenConfig.VIVI_PACKAGE}" text="${node.text.xmlAttr()}" resource-id="${node.resourceId.xmlAttr()}" class="${node.className.xmlAttr()}" content-desc="${node.contentDescription.xmlAttr()}" clickable="${node.clickable}" enabled="${node.enabled}" focused="${node.focused}" bounds="${node.bounds.xmlAttr()}" />"""
    }
  }

  fun isControlCodePopup(xml: String): Boolean {
    return hasViviPackage(xml) &&
      hasControlCodeText(xml) &&
      controlCodeInputBounds(xml) != null
  }

  fun isControlCodeInputFocused(xml: String): Boolean {
    return controlCodeInputNode(xml)?.contains("""focused="true"""") == true
  }

  fun controlCodeInputActionForHierarchy(xml: String): TicketViviPageAction? {
    if (!isControlCodePopup(xml)) {
      return null
    }
    return controlCodeInputBounds(xml)?.action("focus_control_code_input")
  }

  fun controlCodeCloseActionForHierarchy(xml: String): TicketViviPageAction? {
    if (!isControlCodePopup(xml)) {
      return null
    }
    return controlCodeCloseBounds(xml)?.action("close_control_code_popup")
  }

  fun controlCodeExitCloseActionForHierarchy(xml: String): TicketViviPageAction? {
    if (isControlCodePopup(xml)) {
      return controlCodeCloseActionForHierarchy(xml)
    }
    if (!isControlCodeResult(xml)) {
      return null
    }
    return closeBounds(xml)?.action("close_control_code_result")
  }

  fun isControlCodeCloseTap(xml: String, x: Int, y: Int): Boolean {
    if (!isControlCodePopup(xml)) {
      return false
    }
    val bounds = controlCodeCloseBounds(xml) ?: return false
    return bounds.contains(x, y, CLOSE_TAP_PADDING)
  }

  fun isControlCodeExitCloseTap(xml: String, x: Int, y: Int): Boolean {
    if (!isControlCodePopup(xml) && !isControlCodeResult(xml)) {
      return false
    }
    val bounds = closeBounds(xml) ?: return false
    return bounds.contains(x, y, CLOSE_TAP_PADDING)
  }

  fun isControlCodeButtonTap(xml: String, x: Int, y: Int): Boolean {
    return controlCodeButtonBounds(xml).any { bounds ->
      bounds.contains(x, y, CONTROL_CODE_BUTTON_TAP_PADDING)
    }
  }

  fun dismissibleBlockerActionForHierarchy(xml: String): TicketViviPageAction? {
    if (xml.isBlank() || !hasViviPackage(xml) || isControlCodePopup(xml)) {
      return null
    }
    if (!hasDismissibleBlockerMarkers(xml)) {
      return null
    }
    val close = closeBounds(xml) ?: return null
    return close.action("dismiss_blocking_popup")
  }

  fun isForbiddenViviTap(xml: String, x: Int, y: Int): Boolean {
    if (!hasViviPackage(xml)) {
      return false
    }
    if (isControlCodeButtonTap(xml, x, y)) {
      return false
    }
    return nodeRegex.findAll(xml)
      .map { it.value }
      .any { node ->
        packageName(node) == TicketScreenConfig.VIVI_PACKAGE &&
          node.contains("""clickable="true"""") &&
          bounds(node)?.contains(x, y, DANGEROUS_TAP_PADDING) == true &&
          isForbiddenViviNode(xml, node)
      }
  }

  fun resetActionForHierarchy(xml: String): TicketViviPageAction? {
    if (xml.isBlank() || !hasViviPackage(xml) || isTicketDetail(xml)) {
      return null
    }
    if (isControlCodePopup(xml)) {
      return controlCodeCloseActionForHierarchy(xml)
    }
    if (isControlCodeResult(xml)) {
      return controlCodeExitCloseActionForHierarchy(xml)
    }
    dismissibleBlockerActionForHierarchy(xml)?.let { action ->
      return action
    }
    ticketCardBounds(xml)?.let { bounds ->
      return bounds.action("open_ticket_card")
    }
    ticketsTabBounds(xml)?.let { bounds ->
      return bounds.action("open_tickets_tab")
    }
    return null
  }

  fun classifyForRecovery(xml: String): TicketViviRecoveryState {
    if (xml.isBlank()) {
      return TicketViviRecoveryState.BLANK
    }
    if (!hasViviPackage(xml)) {
      return TicketViviRecoveryState.OUTSIDE_VIVI
    }
    if (isTicketDetail(xml)) {
      return TicketViviRecoveryState.TICKET_DETAIL
    }
    if (isControlCodePopup(xml)) {
      return TicketViviRecoveryState.CONTROL_CODE_POPUP
    }
    if (isControlCodeResult(xml)) {
      return TicketViviRecoveryState.CONTROL_CODE_RESULT
    }
    if (dismissibleBlockerActionForHierarchy(xml) != null) {
      return TicketViviRecoveryState.DISMISSIBLE_BLOCKER
    }
    if (looksLikeCartOrCheckoutPage(xml)) {
      return TicketViviRecoveryState.CART_OR_CHECKOUT
    }
    if (ticketCardBounds(xml) != null) {
      return TicketViviRecoveryState.TICKET_LIST_WITH_CARD
    }
    if (emptyTicketListAlternateTabBounds(xml) != null) {
      return TicketViviRecoveryState.TICKET_LIST_EMPTY
    }
    if (looksLikeSettingsOrProfilePage(xml)) {
      return TicketViviRecoveryState.SETTINGS_OR_PROFILE
    }
    if (ticketsTabBounds(xml) != null) {
      return TicketViviRecoveryState.OTHER_VIVI_TAB
    }
    return TicketViviRecoveryState.UNKNOWN_VIVI
  }

  fun recoveryActionForHierarchy(xml: String): TicketViviPageAction? {
    return when (classifyForRecovery(xml)) {
      TicketViviRecoveryState.CONTROL_CODE_POPUP -> controlCodeCloseActionForHierarchy(xml)
      TicketViviRecoveryState.CONTROL_CODE_RESULT -> controlCodeExitCloseActionForHierarchy(xml)
      TicketViviRecoveryState.DISMISSIBLE_BLOCKER -> dismissibleBlockerActionForHierarchy(xml)
      TicketViviRecoveryState.CART_OR_CHECKOUT -> backButtonBounds(xml)?.action("leave_cart_or_checkout")
        ?: backAction("leave_cart_or_checkout")
      TicketViviRecoveryState.TICKET_LIST_WITH_CARD -> ticketCardBounds(xml)?.action("open_ticket_card")
      TicketViviRecoveryState.TICKET_LIST_EMPTY -> emptyTicketListAlternateTabBounds(xml)?.action("open_time_ticket_tab")
      TicketViviRecoveryState.OTHER_VIVI_TAB -> ticketsTabBounds(xml)?.action("open_tickets_tab")
      else -> null
    }
  }

  fun actionForHierarchy(xml: String): TicketViviPageAction? {
    if (xml.isBlank() || !hasViviPackage(xml)) {
      return null
    }
    if (isTicketDetail(xml) || isControlCodePopup(xml)) {
      return null
    }
    if (isControlCodeResult(xml)) {
      return controlCodeExitCloseActionForHierarchy(xml)
    }
    dismissibleBlockerActionForHierarchy(xml)?.let { action ->
      return action
    }
    ticketCardBounds(xml)?.let { bounds ->
      return bounds.action("open_ticket_card")
    }
    emptyTicketListAlternateTabBounds(xml)?.let { bounds ->
      return bounds.action("open_time_ticket_tab")
    }
    ticketsTabBounds(xml)?.let { bounds ->
      return bounds.action("open_tickets_tab")
    }
    return null
  }

  private fun hasViviPackage(xml: String): Boolean {
    return xml.contains("""package="com.pv.vivi"""")
  }

  private fun hasTicketDetailMarkers(xml: String): Boolean {
    val normalized = visibleText(xml).lowercase()
    return normalized.contains("kontroles kods") &&
      normalized.contains("zonas") &&
      Regex("""PV-[A-Z0-9-]+""").containsMatchIn(xml)
  }

  private fun hasControlCodeText(xml: String): Boolean {
    return visibleText(xml).lowercase().contains("kontroles kods")
  }

  private fun isControlCodeResult(xml: String): Boolean {
    if (!hasViviPackage(xml) || isControlCodePopup(xml) || hasTicketDetailMarkers(xml) || looksLikeSettingsOrProfilePage(xml)) {
      return false
    }
    return hasCloseLabel(xml) && hasStandaloneControlCodeValue(xml)
  }

  private fun hasStandaloneControlCodeValue(xml: String): Boolean {
    return nodeRegex.findAll(xml)
      .map { nodeVisibleText(it.value).trim() }
      .any { label -> Regex("""^\d{4,8}$""").matches(label) }
  }

  private fun hasDismissibleBlockerMarkers(xml: String): Boolean {
    val normalized = visibleText(xml).lowercase()
    return normalized.contains("pasažieri") ||
      normalized.contains("pasazieri") ||
      normalized.contains("apliecību") ||
      normalized.contains("apliecibu") ||
      normalized.contains("atlaidi") ||
      normalized.contains("bez maksas")
  }

  private fun controlCodeInputNode(xml: String): String? {
    return nodeRegex.findAll(xml)
      .map { it.value }
      .firstOrNull { node ->
        val bounds = bounds(node)
        packageName(node) == TicketScreenConfig.VIVI_PACKAGE &&
          bounds != null &&
          looksLikeControlCodeInput(node)
      }
  }

  private fun controlCodeInputBounds(xml: String): Bounds? {
    return controlCodeInputNode(xml)?.let(::bounds)
  }

  private fun looksLikeControlCodeInput(node: String): Boolean {
    val className = attr(node, "class").lowercase()
    val resourceId = attr(node, "resource-id").lowercase()
    return node.contains("""editable="true"""") ||
      className.contains("edittext") ||
      resourceId.contains("code") ||
      resourceId.contains("kod")
  }

  private fun controlCodeCloseBounds(xml: String): Bounds? {
    return closeBounds(xml)
  }

  private fun closeBounds(xml: String): Bounds? {
    val nodes = nodeRegex.findAll(xml).map { it.value }.toList()
    val viviBounds = nodes
      .filter { packageName(it) == TicketScreenConfig.VIVI_PACKAGE }
      .mapNotNull(::bounds)
    val maxRight = viviBounds.maxOfOrNull { it.right } ?: return null
    val maxBottom = viviBounds.maxOfOrNull { it.bottom } ?: return null
    val closeByLabel = nodes.firstNotNullOfOrNull { node ->
      if (packageName(node) != TicketScreenConfig.VIVI_PACKAGE || !node.contains("""clickable="true"""")) {
        return@firstNotNullOfOrNull null
      }
      val label = nodeVisibleText(node).lowercase()
      val bounds = bounds(node) ?: return@firstNotNullOfOrNull null
      if (isCloseLabel(label)) {
        bounds
      } else {
        null
      }
    }
    if (closeByLabel != null) {
      return closeByLabel
    }
    return nodes.firstNotNullOfOrNull { node ->
      if (packageName(node) != TicketScreenConfig.VIVI_PACKAGE || !node.contains("""clickable="true"""")) {
        return@firstNotNullOfOrNull null
      }
      val bounds = bounds(node) ?: return@firstNotNullOfOrNull null
      val width = bounds.right - bounds.left
      val height = bounds.bottom - bounds.top
      if (
        bounds.right >= (maxRight * TOP_RIGHT_CLOSE_MIN_X_FRACTION).toInt() &&
        bounds.top <= (maxBottom * TOP_RIGHT_CLOSE_MAX_Y_FRACTION).toInt() &&
        width <= TOP_RIGHT_CLOSE_MAX_SIZE &&
        height <= TOP_RIGHT_CLOSE_MAX_SIZE
      ) {
        bounds
      } else {
        null
      }
    }
  }

  private fun hasCloseLabel(xml: String): Boolean {
    return nodeRegex.findAll(xml)
      .map { it.value }
      .any { node ->
        packageName(node) == TicketScreenConfig.VIVI_PACKAGE &&
          node.contains("""clickable="true"""") &&
          isCloseLabel(nodeVisibleText(node).lowercase())
      }
  }

  private fun isForbiddenViviNode(xml: String, node: String): Boolean {
    val label = nodeVisibleText(node).lowercase()
    val nodeBounds = bounds(node) ?: return false
    return FORBIDDEN_VIVI_LABEL_TOKENS.any { token -> label.contains(token) } ||
      (looksLikeUnlabeledSettingsButton(nodeBounds) && !overlapsControlCodeButton(xml, nodeBounds)) ||
      looksLikeUnlabeledProfileTab(nodeBounds)
  }

  private fun controlCodeButtonBounds(xml: String): List<Bounds> {
    if (!hasViviPackage(xml) || isControlCodePopup(xml)) {
      return emptyList()
    }
    return nodeRegex.findAll(xml)
      .map { it.value }
      .mapNotNull { node ->
        val bounds = bounds(node) ?: return@mapNotNull null
        val label = nodeVisibleText(node).lowercase()
        if (
          packageName(node) == TicketScreenConfig.VIVI_PACKAGE &&
          node.contains("""clickable="true"""") &&
          label.contains("kontroles kods")
        ) {
          bounds
        } else {
          null
        }
      }
      .toList()
  }

  private fun overlapsControlCodeButton(xml: String, bounds: Bounds): Boolean {
    return controlCodeButtonBounds(xml).any { it.intersects(bounds) }
  }

  private fun looksLikeSettingsOrProfilePage(xml: String): Boolean {
    val normalized = visibleText(xml).lowercase()
    return SETTINGS_OR_PROFILE_PAGE_TOKENS.any { token -> normalized.contains(token) }
  }

  private fun looksLikeCartOrCheckoutPage(xml: String): Boolean {
    val normalized = visibleText(xml).lowercase()
    return CART_OR_CHECKOUT_TOKENS.any { token -> normalized.contains(token) }
  }

  private fun looksLikeUnlabeledSettingsButton(bounds: Bounds): Boolean {
    val width = bounds.right - bounds.left
    val height = bounds.bottom - bounds.top
    return bounds.left <= SETTINGS_FALLBACK_MAX_LEFT &&
      bounds.top <= SETTINGS_FALLBACK_MAX_TOP &&
      width <= SETTINGS_FALLBACK_MAX_SIZE &&
      height <= SETTINGS_FALLBACK_MAX_SIZE
  }

  private fun looksLikeUnlabeledProfileTab(bounds: Bounds): Boolean {
    return bounds.top >= BOTTOM_NAVIGATION_TOP &&
      bounds.left >= PROFILE_TAB_FALLBACK_MIN_LEFT &&
      bounds.right <= PROFILE_TAB_FALLBACK_MAX_RIGHT
  }

  private fun isCloseLabel(label: String): Boolean {
    val normalized = label.trim().lowercase()
    return CLOSE_LABEL_TOKENS.any { token -> normalized.contains(token) } ||
      normalized == "x"
  }

  private fun ticketCardBounds(xml: String): Bounds? {
    return nodeRegex.findAll(xml)
      .map { it.value }
      .firstNotNullOfOrNull { node ->
        val desc = contentDescription(node) ?: return@firstNotNullOfOrNull null
        val bounds = bounds(node) ?: return@firstNotNullOfOrNull null
        if (
          node.contains("""clickable="true"""") &&
          bounds.top in TICKET_CARD_TOP_RANGE &&
          bounds.bottom < BOTTOM_NAVIGATION_TOP &&
          desc.contains("biļete", ignoreCase = true) &&
          (desc.contains("DERĪGA", ignoreCase = true) || desc.contains("Cena", ignoreCase = true))
        ) {
          bounds
        } else {
          null
        }
      }
  }

  private fun emptyTicketListAlternateTabBounds(xml: String): Bounds? {
    val normalized = visibleText(xml).lowercase()
    if (!normalized.contains("manas biļetes") || !normalized.contains("biļetes nav atrastas")) {
      return null
    }
    return nodeRegex.findAll(xml)
      .map { it.value }
      .firstNotNullOfOrNull { node ->
        val bounds = bounds(node) ?: return@firstNotNullOfOrNull null
        val label = nodeVisibleText(node).lowercase()
        if (
          packageName(node) == TicketScreenConfig.VIVI_PACKAGE &&
          node.contains("""clickable="true"""") &&
          label.contains("laika biļetes")
        ) {
          bounds
        } else {
          null
        }
      }
  }

  private fun ticketsTabBounds(xml: String): Bounds? {
    return nodeRegex.findAll(xml)
      .map { it.value }
      .firstNotNullOfOrNull { node ->
        val desc = contentDescription(node) ?: return@firstNotNullOfOrNull null
        val bounds = bounds(node) ?: return@firstNotNullOfOrNull null
        if (
          node.contains("""clickable="true"""") &&
          bounds.top >= BOTTOM_NAVIGATION_TOP &&
          desc.contains("Tickets", ignoreCase = true)
        ) {
          bounds
        } else {
          null
        }
      }
  }

  private fun backButtonBounds(xml: String): Bounds? {
    return nodeRegex.findAll(xml)
      .map { it.value }
      .firstNotNullOfOrNull { node ->
        val bounds = bounds(node) ?: return@firstNotNullOfOrNull null
        val label = nodeVisibleText(node).lowercase()
        if (
          packageName(node) == TicketScreenConfig.VIVI_PACKAGE &&
          node.contains("""clickable="true"""") &&
          bounds.top <= TOP_BAR_MAX_BOTTOM &&
          BACK_LABEL_TOKENS.any { token -> label.contains(token) }
        ) {
          bounds
        } else {
          null
        }
      }
  }

  private fun packageName(node: String): String {
    return attr(node, "package")
  }

  private fun contentDescription(node: String): String? {
    return attr(node, "content-desc").ifBlank { null }
  }

  private fun nodeVisibleText(node: String): String {
    return listOf(
      attr(node, "text"),
      attr(node, "content-desc"),
      attr(node, "hint")
    ).joinToString(" ").decodeXmlText()
  }

  private fun visibleText(xml: String): String {
    return nodeRegex.findAll(xml)
      .map { nodeVisibleText(it.value) }
      .joinToString(" ")
  }

  private fun attr(node: String, name: String): String {
    val match = Regex("""\b${Regex.escape(name)}="([^"]*)"""").find(node)
    return match?.groupValues?.getOrNull(1).orEmpty().decodeXmlText()
  }

  private fun String.decodeXmlText(): String {
    return replace("&#10;", "\n")
      .replace("&quot;", "\"")
      .replace("&apos;", "'")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&amp;", "&")
  }

  private fun String.xmlAttr(): String {
    return replace("&", "&amp;")
      .replace("\"", "&quot;")
      .replace("'", "&apos;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\n", "&#10;")
  }

  private fun bounds(node: String): Bounds? {
    val match = boundsRegex.find(node) ?: return null
    return Bounds(
      left = match.groupValues[1].toInt(),
      top = match.groupValues[2].toInt(),
      right = match.groupValues[3].toInt(),
      bottom = match.groupValues[4].toInt()
    )
  }

  private fun Bounds.action(reason: String): TicketViviPageAction {
    return TicketViviPageAction(
      x = (left + right) / 2,
      y = (top + bottom) / 2,
      reason = reason
    )
  }

  private fun backAction(reason: String): TicketViviPageAction {
    return TicketViviPageAction(
      x = -1,
      y = -1,
      reason = reason
    )
  }

  private data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
  ) {
    fun contains(x: Int, y: Int, padding: Int = 0): Boolean {
      return x in (left - padding)..(right + padding) &&
        y in (top - padding)..(bottom + padding)
    }

    fun intersects(other: Bounds): Boolean {
      return left < other.right &&
        right > other.left &&
        top < other.bottom &&
        bottom > other.top
    }
  }

  private val nodeRegex = Regex("""<node\b[^>]*>""")
  private val boundsRegex = Regex("""bounds="\[(\d+),(\d+)]\[(\d+),(\d+)]"""")
  private val TICKET_CARD_TOP_RANGE = 420..1900
  private const val BOTTOM_NAVIGATION_TOP = 2100
  private const val CLOSE_TAP_PADDING = 24
  private const val CONTROL_CODE_BUTTON_TAP_PADDING = 8
  private const val DANGEROUS_TAP_PADDING = 8
  private const val TOP_RIGHT_CLOSE_MIN_X_FRACTION = 0.72f
  private const val TOP_RIGHT_CLOSE_MAX_Y_FRACTION = 0.35f
  private const val TOP_RIGHT_CLOSE_MAX_SIZE = 180
  private const val SETTINGS_FALLBACK_MAX_LEFT = 220
  private const val SETTINGS_FALLBACK_MAX_TOP = 560
  private const val SETTINGS_FALLBACK_MAX_SIZE = 220
  private const val TOP_BAR_MAX_BOTTOM = 360
  private const val PROFILE_TAB_FALLBACK_MIN_LEFT = 500
  private const val PROFILE_TAB_FALLBACK_MAX_RIGHT = 850
  private val CLOSE_LABEL_TOKENS = listOf(
    "aizvērt",
    "aizvert",
    "close",
    "dismiss",
    "cancel",
    "atcelt",
    "×",
    "✕"
  )
  private val FORBIDDEN_VIVI_LABEL_TOKENS = listOf(
    "settings",
    "iestat",
    "profile",
    "profils",
    "lietot",
    "account",
    "konts",
    "user"
  )
  private val SETTINGS_OR_PROFILE_PAGE_TOKENS = listOf(
    "iestatījumi",
    "iestatijumi",
    "settings",
    "profils",
    "profile",
    "lietotājs",
    "lietotajs",
    "account",
    "konts"
  )
  private val CART_OR_CHECKOUT_TOKENS = listOf(
    "grozs",
    "cart",
    "checkout",
    "maksājums",
    "maksajums",
    "apmaksa"
  )
  private val BACK_LABEL_TOKENS = listOf(
    "atpakaļ",
    "atpakal",
    "back"
  )
}
