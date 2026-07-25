package lv.jolkins.pixelorchestrator.app.ticket

internal object TicketControlCodeRootInput {
  const val HELPER_ASSET = "ticket-root-keyboard"
  const val HELPER_PATH = "/data/local/pixel-stack/bin/pixel-ticket-root-keyboard"
  const val KEYBOARD_LEASE_MILLIS = 6_000L
  private val digitsPattern = Regex("^[0-9]{2,8}${'$'}")

  fun remainingInitialKeyboardLeaseMillis(
    initialTypeCompletedAtMillis: Long,
    nowMillis: Long,
    safetyMarginMillis: Long
  ): Long {
    if (initialTypeCompletedAtMillis <= 0L || safetyMarginMillis < 0L) {
      return 0L
    }
    return (
      initialTypeCompletedAtMillis +
        KEYBOARD_LEASE_MILLIS +
        safetyMarginMillis -
        nowMillis
      ).coerceAtLeast(0L)
  }

  fun buildTypeScript(
    digits: String,
    inputX: Int,
    inputY: Int,
    openX: Int? = null,
    openY: Int? = null
  ): String {
    require(digitsPattern.matches(digits)) { "control code must contain 2 to 8 digits" }
    require(inputX >= 0 && inputY >= 0) { "control-code coordinates must be non-negative" }
    require((openX == null) == (openY == null)) { "control-code open coordinates must be provided together" }
    require(openX == null || openX >= 0 && openY!! >= 0) { "control-code open coordinates must be non-negative" }
    val open = if (openX == null) "" else "--open-x $openX --open-y $openY "
    return """
      [ -x $HELPER_PATH ] || exit 52
      settings put secure show_ime_with_hard_keyboard 0 >/dev/null 2>&1 || exit 43
      printf '%s\n' '$digits' | $HELPER_PATH ${open}--input-x $inputX --input-y $inputY >/dev/null 2>&1
      exit ${'$'}?
    """.trimIndent()
  }
}
