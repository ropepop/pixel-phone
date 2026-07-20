package lv.jolkins.pixelorchestrator.app.ticket

import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationVisibleNode

/** The active, side-effect-free state classifier shared by the direct RS driver and its tests. */
internal object RigasSatiksmeSemanticClassifier {
  fun classify(
    nodes: List<PhoneAutomationVisibleNode>,
    cleanDigits: String
  ): RigasSatiksmeSemanticState {
    val text = nodes.joinToString("\n") { node ->
      listOf(node.text, node.contentDescription, node.hint, node.resourceId)
        .filter { it.isNotBlank() }
        .joinToString(" ")
    }
    fun has(value: String): Boolean = text.contains(value, ignoreCase = true)
    fun hasAny(vararg values: String): Boolean = values.any { has(it) }
    val hasEditable = nodes.any { it.editable || it.className.contains("EditText", ignoreCase = true) }
    val hasMonthlyMarker = hasAny(
      "1 month",
      "1 mēnes",
      "monthly",
      "month ticket",
      "30 dienu biļete",
      "30 dienu bilete",
      "30 day ticket",
      "30-day ticket"
    )
    val hasQr = hasAny("qr code", "qr", "KONTROLES KODS", "kontroles kods")
    val hasControl = hasAny(
      "TICKET FOR CONTROL",
      "Ticket for control",
      "Present a ticket for control",
      "KONTROLES KODS",
      "Kontroles kods",
      "Kontrolei"
    )
    val hasConfirm = hasAny("CONFIRM", "OK", "Labi")
    val hasCancel = hasAny("Cancel", "Atcelt")

    return when {
      hasControl && hasQr && has(cleanDigits) && hasMonthlyMarker -> RigasSatiksmeSemanticState.TICKET_CONTROL_MATCHING
      hasControl && hasQr && hasMonthlyMarker -> RigasSatiksmeSemanticState.TICKET_CONTROL_STALE
      hasAny("wrong code", "incorrect code", "invalid code", "nepareizs kods", "nederīgs kods", "nederigs kods") -> {
        RigasSatiksmeSemanticState.WRONG_CODE
      }
      hasAny("no active tickets", "no tickets", "empty ticket list", "nav aktīvu biļešu", "nav aktivu bilesu", "nav biļešu") -> {
        RigasSatiksmeSemanticState.NO_MONTHLY_TICKET
      }
      hasAny("sign in", "log in", "authentication", "session expired", "pieslēgties", "pierakstīties", "autentifik", "sesija beigusies") -> {
        RigasSatiksmeSemanticState.AUTH_BLOCKED
      }
      has("trip is registered") || (has("registered") && hasConfirm) || (has("brauciens reģistrēts") && hasConfirm) -> {
        RigasSatiksmeSemanticState.TRIP_REGISTERED
      }
      hasEditable || hasAny("Control code", "Ievadi kontroles kodu") || (hasAny("Kods", "kontroles kods") && (hasConfirm || hasCancel)) -> {
        RigasSatiksmeSemanticState.MANUAL_CODE_ENTRY
      }
      hasAny(
        "ENTER THE CODE MANUALLY",
        "ENTER CODE MANUALLY",
        "Enter code manually",
        "Ievadīt kodu manuāli",
        "Ievadit kodu manuali",
        "Ievadīt kodu"
      ) -> {
        RigasSatiksmeSemanticState.MANUAL_CODE_BUTTON_READY
      }
      hasAny("REGISTER A TRIP", "Register a trip", "Reģistrēt braucienu", "Registret braucienu") -> {
        RigasSatiksmeSemanticState.REGISTER_TRIP_READY
      }
      hasAny("Public transport", "Sabiedriskais transports") && hasMonthlyMarker -> RigasSatiksmeSemanticState.HOME_READY
      hasAny("Tickets", "Biļetes", "Biletes", "Manas biļetes") && hasMonthlyMarker -> RigasSatiksmeSemanticState.TICKET_LIST_READY
      else -> RigasSatiksmeSemanticState.UNKNOWN
    }
  }
}

internal enum class RigasSatiksmeSemanticState {
  HOME_READY,
  TICKET_LIST_READY,
  REGISTER_TRIP_READY,
  MANUAL_CODE_BUTTON_READY,
  MANUAL_CODE_ENTRY,
  TRIP_REGISTERED,
  TICKET_CONTROL_MATCHING,
  TICKET_CONTROL_STALE,
  WRONG_CODE,
  NO_MONTHLY_TICKET,
  AUTH_BLOCKED,
  UNKNOWN
}

internal fun List<PhoneAutomationVisibleNode>.toSemanticHierarchy(): String {
  return joinToString(separator = "\n") { node ->
    buildString {
      append("<node")
      if (node.text.isNotBlank()) append(" text=\"").append(node.text.escapeHierarchyAttribute()).append("\"")
      if (node.contentDescription.isNotBlank()) {
        append(" content-desc=\"").append(node.contentDescription.escapeHierarchyAttribute()).append("\"")
      }
      if (node.resourceId.isNotBlank()) append(" resource-id=\"").append(node.resourceId.escapeHierarchyAttribute()).append("\"")
      if (node.hint.isNotBlank()) append(" hint=\"").append(node.hint.escapeHierarchyAttribute()).append("\"")
      if (node.editable) append(" editable=\"true\"")
      append(" />")
    }
  }
}

private fun String.escapeHierarchyAttribute(): String {
  return replace("&", "&amp;")
    .replace("\"", "&quot;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
}
