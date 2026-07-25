package lv.jolkins.pixelorchestrator.app.ticket

/**
 * Resolves a fresh, actionable ViVi hierarchy before control-code cleanup gives up.
 *
 * A rooted hierarchy dump can transiently return no content while the popup is visibly stable.
 * Keeping the bounded retry policy separate makes that failure mode executable in JVM tests.
 */
internal object TicketControlCodeCleanupHierarchyResolver {
  suspend fun resolve(
    initialHierarchy: String,
    maxFreshReads: Int,
    isUsable: (String) -> Boolean,
    readFresh: suspend (attempt: Int) -> String?,
    waitBeforeRetry: suspend () -> Unit
  ): String {
    if (initialHierarchy.isNotBlank() && isUsable(initialHierarchy)) {
      return initialHierarchy
    }
    val readLimit = maxFreshReads.coerceAtLeast(0)
    repeat(readLimit) { index ->
      val hierarchy = readFresh(index + 1).orEmpty()
      if (hierarchy.isNotBlank() && isUsable(hierarchy)) {
        return hierarchy
      }
      if (index + 1 < readLimit) {
        waitBeforeRetry()
      }
    }
    return ""
  }
}
