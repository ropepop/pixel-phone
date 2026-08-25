package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Serial per-client writer used by TicketStreamService and exercised directly in JVM tests. */
internal class TicketVideoClientWriterPump(
  private val scope: CoroutineScope,
  private val state: TicketVideoClientDeliveryState,
  private val slowWriteMillis: Long,
  private val slowCloseMillis: Long,
  private val nowMillis: () -> Long,
  private val isCurrent: () -> Boolean,
  private val sendBinary: suspend (TicketVideoDeliveryFrame, () -> Boolean) -> Boolean,
  private val onDecision: (TicketVideoDeliveryDecision) -> Unit,
  private val onSlowWrite: (TicketVideoDeliveryFrame, Long) -> Unit,
  private val onWriteExpired: (TicketVideoDeliveryFrame, Long) -> Unit,
  private val onRejectedCurrent: () -> Unit
) {
  fun start(
    firstFrame: TicketVideoDeliveryFrame,
    firstWriteToken: Long
  ): Job {
    return scope.launch {
      var nextFrame: TicketVideoDeliveryFrame? = firstFrame
      var nextWriteToken = firstWriteToken
      while (nextFrame != null) {
        val currentFrame = nextFrame
        val currentWriteToken = nextWriteToken
        if (!isCurrent()) {
          state.close()
          return@launch
        }
        val startMillis = nowMillis()
        val writeFinished = AtomicBoolean(false)
        val timeoutJob = launch {
          delay(slowCloseMillis)
          if (writeFinished.compareAndSet(false, true)) {
            val timeoutAtMillis = nowMillis()
            onDecision(
              state.timeoutWrite(
                writeToken = currentWriteToken,
                nowMillis = timeoutAtMillis
              )
            )
            onWriteExpired(
              currentFrame,
              (timeoutAtMillis - startMillis).coerceAtLeast(0L)
            )
          }
        }
        var completedBeforeDeadline = false
        val sent = try {
          sendBinary(currentFrame) {
            state.canWrite(currentWriteToken)
          }
        } finally {
          completedBeforeDeadline = writeFinished.compareAndSet(false, true)
          timeoutJob.cancel()
        }
        if (!completedBeforeDeadline) return@launch
        val durationMillis = (nowMillis() - startMillis).coerceAtLeast(0L)
        if (durationMillis > slowWriteMillis) {
          onSlowWrite(currentFrame, durationMillis)
        }
        val completion = state.completeWrite(
          writeToken = currentWriteToken,
          nowMillis = nowMillis(),
          succeeded = sent
        )
        onDecision(completion)
        if (completion.closeSlowClient || durationMillis >= slowCloseMillis) {
          onWriteExpired(currentFrame, durationMillis)
          return@launch
        }
        if (!sent) {
          if (isCurrent()) onRejectedCurrent()
          return@launch
        }
        nextFrame = completion.frameToWrite
        nextWriteToken = completion.writeToken
      }
    }
  }
}
