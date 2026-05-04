package lv.jolkins.pixelorchestrator.app.ticket

internal object TicketNotificationLockdown {
  fun enableScript(): String {
    return """
      cmd statusbar collapse >/dev/null 2>&1 || true
      cmd statusbar send-disable-flag notification-peek notification-icons statusbar-expansion quick-settings >/dev/null 2>&1
      cmd statusbar collapse >/dev/null 2>&1 || true
    """.trimIndent()
  }

  fun disableScript(): String {
    return """
      cmd statusbar send-disable-flag none >/dev/null 2>&1
      cmd statusbar collapse >/dev/null 2>&1 || true
    """.trimIndent()
  }

  fun collapseScript(): String {
    return """cmd statusbar collapse >/dev/null 2>&1 || true"""
  }
}
