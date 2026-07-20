package lv.jolkins.pixelorchestrator.app.ticket

/** Keeps failure delivery ahead of any slow phone cleanup or recovery work. */
internal object ControlCodeFailureLifecycle {
  suspend fun deliverThenCleanup(
    terminalResultAlreadyDelivered: Boolean = false,
    deliverFailure: suspend () -> Unit,
    cleanup: suspend () -> Boolean,
    deliverCleanup: suspend (Boolean) -> Unit
  ): Boolean {
    if (!terminalResultAlreadyDelivered) {
      deliverFailure()
    }
    val cleanupSucceeded = cleanup()
    deliverCleanup(cleanupSucceeded)
    return cleanupSucceeded
  }
}
