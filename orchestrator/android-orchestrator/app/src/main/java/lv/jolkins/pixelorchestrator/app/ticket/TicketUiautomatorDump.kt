package lv.jolkins.pixelorchestrator.app.ticket

internal object TicketUiautomatorDump {
  private const val LOCK_PATH = "/data/local/tmp/pixel-ticket-uiautomator.lock"
  private const val OUTER_TIMEOUT_CUSHION_MILLIS = 1_000L
  private const val MIN_SHELL_TIMEOUT_MILLIS = 250L
  private const val POST_FAILURE_LOCK_SETTLE_MILLIS = 500L
  private const val MIN_SAFE_OUTER_TIMEOUT_MILLIS = 1_000L

  fun command(path: String, timeoutMillis: Long, emitContent: Boolean = true): String {
    val mode = if (emitContent) "cat" else "nocat"
    return """
      ${functionDefinition(timeoutMillis)}
      ticket_safe_uiautomator_dump ${shellWord(path)} $mode
    """.trimIndent()
  }

  fun functionDefinition(timeoutMillis: Long): String {
    if (timeoutMillis < MIN_SAFE_OUTER_TIMEOUT_MILLIS) {
      return """
        ticket_safe_uiautomator_dump() {
          exit 124
        }
      """.trimIndent()
    }
    val timeoutSeconds = shellTimeoutSeconds(timeoutMillis)
    return """
      LOCK_PATH=${shellWord(LOCK_PATH)}
      ticket_safe_uiautomator_cleanup() {
        ticket_safe_pids="${'$'}(/system/bin/pgrep -f '[c]om.android.commands.uiautomator' 2>/dev/null || true) ${'$'}(/system/bin/pidof uiautomator 2>/dev/null || true)"
        for ticket_safe_pid in ${'$'}ticket_safe_pids; do
          [ "${'$'}ticket_safe_pid" = "${'$'}$" ] && continue
          /system/bin/kill -9 "${'$'}ticket_safe_pid" >/dev/null 2>&1 || true
        done
      }
      ticket_safe_uiautomator_dump() {
        ticket_safe_path="${'$'}1"
        ticket_safe_emit="${'$'}{2:-cat}"
        /system/bin/touch "${'$'}LOCK_PATH" >/dev/null 2>&1 || true
        /system/bin/chmod 0666 "${'$'}LOCK_PATH" >/dev/null 2>&1 || true
        (
          /system/bin/flock -x -n 9 || exit 125
          ticket_safe_uiautomator_cleanup
          /system/bin/rm -f "${'$'}ticket_safe_path" >/dev/null 2>&1 || true
          if /system/bin/timeout -k 0.100s ${timeoutSeconds}s /system/bin/uiautomator dump "${'$'}ticket_safe_path" >/dev/null 2>&1; then
            ticket_safe_rc=0
          else
            ticket_safe_rc="${'$'}?"
          fi
          ticket_safe_uiautomator_cleanup
          ticket_safe_has_hierarchy=0
          if [ -s "${'$'}ticket_safe_path" ] && /system/bin/grep -q '<hierarchy' "${'$'}ticket_safe_path" 2>/dev/null; then
            ticket_safe_has_hierarchy=1
          fi
          if [ "${'$'}ticket_safe_rc" -ne 0 ] && [ "${'$'}ticket_safe_has_hierarchy" -ne 1 ]; then
            /system/bin/sleep 0.500
          fi
          if [ "${'$'}ticket_safe_has_hierarchy" -eq 1 ]; then
            if [ "${'$'}ticket_safe_emit" = "cat" ]; then
              /system/bin/cat "${'$'}ticket_safe_path" 2>/dev/null || true
            fi
            exit 0
          fi
          exit "${'$'}ticket_safe_rc"
        ) 9<>${'$'}LOCK_PATH
      }
    """.trimIndent()
  }

  private fun shellTimeoutSeconds(timeoutMillis: Long): String {
    val adjustedMillis = (timeoutMillis - OUTER_TIMEOUT_CUSHION_MILLIS - POST_FAILURE_LOCK_SETTLE_MILLIS)
      .coerceAtLeast(MIN_SHELL_TIMEOUT_MILLIS)
    val seconds = adjustedMillis / 1_000L
    val millis = (adjustedMillis % 1_000L).toString().padStart(3, '0')
    return "$seconds.$millis"
  }

  private fun shellWord(value: String): String {
    return if (value.matches(Regex("[A-Za-z0-9_./:-]+"))) {
      value
    } else {
      "'" + value.replace("'", "'\"'\"'") + "'"
    }
  }
}
