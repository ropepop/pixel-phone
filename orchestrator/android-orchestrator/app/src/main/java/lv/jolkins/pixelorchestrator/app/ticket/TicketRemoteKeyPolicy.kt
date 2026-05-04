package lv.jolkins.pixelorchestrator.app.ticket

internal object TicketRemoteKeyPolicy {
  fun isCloseRequest(key: String): Boolean {
    return key == "Escape"
  }

  fun commandFor(key: String): String? {
    return when (key) {
      "Backspace" -> "input keyevent KEYCODE_DEL"
      "Delete" -> "input keyevent KEYCODE_FORWARD_DEL"
      "Enter" -> "input keyevent KEYCODE_ENTER"
      else -> printableTextCommand(key)
    }
  }

  private fun printableTextCommand(key: String): String? {
    if (key.length != 1) {
      return null
    }
    val char = key.single()
    if (!char.isLetterOrDigit()) {
      return null
    }
    return "input text $char"
  }
}
