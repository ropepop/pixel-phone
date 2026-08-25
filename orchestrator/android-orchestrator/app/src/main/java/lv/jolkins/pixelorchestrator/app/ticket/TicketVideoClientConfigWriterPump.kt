package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded, generation-guarded configuration writer for one private video client. */
internal class TicketVideoClientConfigWriterPump(
  private val scope: CoroutineScope,
  private val slowCloseMillis: Long,
  private val nowMillis: () -> Long,
  private val isCurrent: () -> Boolean,
  private val sendConfig: suspend (canSend: () -> Boolean) -> Boolean,
  private val markReady: () -> Boolean,
  private val onConfigured: () -> Unit,
  private val onExpired: (blockedMillis: Long) -> Unit,
  private val onRejectedCurrent: () -> Unit
) {
  fun start(): Job {
    return scope.launch {
      if (!isCurrent()) return@launch
      val startedAtMillis = nowMillis()
      val writeFinished = AtomicBoolean(false)
      val timeoutJob = launch {
        delay(slowCloseMillis)
        if (writeFinished.compareAndSet(false, true)) {
          onExpired((nowMillis() - startedAtMillis).coerceAtLeast(0L))
        }
      }
      var completedBeforeDeadline = false
      val sent = try {
        sendConfig(isCurrent)
      } finally {
        completedBeforeDeadline = writeFinished.compareAndSet(false, true)
        timeoutJob.cancel()
      }
      if (!completedBeforeDeadline) return@launch
      val durationMillis = (nowMillis() - startedAtMillis).coerceAtLeast(0L)
      if (durationMillis >= slowCloseMillis) {
        onExpired(durationMillis)
        return@launch
      }
      if (sent && isCurrent() && markReady()) {
        onConfigured()
      } else if (isCurrent()) {
        onRejectedCurrent()
      }
    }
  }
}
