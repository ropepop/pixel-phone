package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

// Only for the explicit cold barrier: admission and frame publication are
// already disabled. These independent resources must both settle before that
// barrier may be acknowledged or any replacement owner admitted.
internal suspend fun <T> completeTicketColdTeardown(
  stopCapture: suspend () -> Unit,
  restoreSettings: suspend () -> T
): T = coroutineScope {
  val stopped = async(Dispatchers.IO) { stopCapture() }
  val restored = async(Dispatchers.IO) { restoreSettings() }
  stopped.await()
  restored.await()
}
