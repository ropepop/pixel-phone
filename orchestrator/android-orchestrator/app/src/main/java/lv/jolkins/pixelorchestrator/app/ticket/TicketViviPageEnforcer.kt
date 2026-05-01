package lv.jolkins.pixelorchestrator.app.ticket

internal data class TicketViviPageAction(
  val x: Int,
  val y: Int,
  val reason: String
)

internal object TicketViviPageEnforcer {
  fun actionForHierarchy(xml: String): TicketViviPageAction? {
    if (xml.isBlank() || !xml.contains("""package="com.pv.vivi"""")) {
      return null
    }
    if (isTicketDetail(xml)) {
      return null
    }
    ticketCardBounds(xml)?.let { bounds ->
      return bounds.action("open_ticket_card")
    }
    ticketsTabBounds(xml)?.let { bounds ->
      return bounds.action("open_tickets_tab")
    }
    return null
  }

  private fun isTicketDetail(xml: String): Boolean {
    return xml.contains("KONTROLES KODS") &&
      xml.contains("ZONAS") &&
      Regex("""PV-[A-Z0-9-]+""").containsMatchIn(xml)
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

  private fun contentDescription(node: String): String? {
    return contentDescriptionRegex.find(node)?.groupValues?.getOrNull(1)
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

  private data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
  )

  private val nodeRegex = Regex("""<node\b[^>]*>""")
  private val contentDescriptionRegex = Regex("""content-desc="([^"]*)"""")
  private val boundsRegex = Regex("""bounds="\[(\d+),(\d+)]\[(\d+),(\d+)]"""")
  private val TICKET_CARD_TOP_RANGE = 420..1900
  private const val BOTTOM_NAVIGATION_TOP = 2100
}
