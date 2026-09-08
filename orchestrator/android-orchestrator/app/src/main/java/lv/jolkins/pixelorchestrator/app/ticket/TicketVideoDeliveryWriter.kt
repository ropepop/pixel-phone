package lv.jolkins.pixelorchestrator.app.ticket

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class TicketVideoDeliveryFrame(
  val bytes: ByteArray,
  val keyFrame: Boolean,
  val epoch: Long,
  val sequence: Long
)

/** One serial writer owns configuration and the newest waiting independent picture. */
internal class TicketVideoDeliveryWriter(
  private val scope: CoroutineScope,
  private val maxFrameBytes: Int,
  private val timeoutMillis: Long,
  private val nowMillis: () -> Long,
  private val sendConfig: suspend (String, () -> Boolean) -> Boolean,
  private val sendFrame: suspend (ByteArray, () -> Boolean) -> Boolean,
  private val onConfigured: () -> Unit,
  private val onFailure: (String) -> Unit,
  private val requestRefresh: () -> Unit
) {
  private data class Write(val generation: Long, val config: String? = null, val frame: ByteArray? = null)
  private val lock = Any()
  private val changed = Channel<Unit>(Channel.CONFLATED)
  private var closed = false
  private var epoch = 0L
  private var generation = 0L
  private var config: String? = null
  private var configured = false
  private var latestSequence = 0L
  private var pending: ByteArray? = null
  private val job = scope.launch(start = CoroutineStart.LAZY) {
    try {
      for (signal in changed) {
        while (true) {
          val write = synchronized(lock) {
            if (closed) null
            else if (config != null) Write(generation, config = config).also { config = null }
            else if (configured && pending != null) Write(generation, frame = pending).also { pending = null }
            else null
          } ?: break
          if (!current(write)) continue
          val completed = AtomicBoolean(false)
          val started = nowMillis()
          // Socket writes block: a separate job must close the socket to interrupt them.
          val deadline = scope.launch {
            delay(timeoutMillis)
            if (completed.compareAndSet(false, true)) fail("write_timeout")
          }
          val sent = try {
            if (write.config != null) sendConfig(write.config) { current(write) }
            else sendFrame(requireNotNull(write.frame)) { current(write) }
          } finally {
            deadline.cancel()
          }
          if (!completed.compareAndSet(false, true)) return@launch
          if (nowMillis() - started >= timeoutMillis) {
            fail("write_timeout")
            return@launch
          }
          if (!current(write)) continue
          if (!sent) {
            fail("write_failed")
            return@launch
          }
          if (write.config != null) {
            val accepted = synchronized(lock) {
              if (!current(write)) false else { configured = true; true }
            }
            if (accepted) onConfigured()
          }
        }
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Throwable) {
      fail("write_failed")
    }
  }

  init { job.start() }

  fun configure(expectedEpoch: Long, message: String? = null) {
    synchronized(lock) {
      if (closed || expectedEpoch < epoch) return
      epoch = expectedEpoch
      generation += 1L
      configured = false
      config = message
      pending = null
      latestSequence = 0L
    }
    changed.trySend(Unit)
  }

  fun offer(frame: TicketVideoDeliveryFrame) {
    val refresh = synchronized(lock) {
      if (closed || epoch <= 0L || frame.epoch != epoch || frame.sequence <= latestSequence) return
      if (!frame.keyFrame || frame.bytes.isEmpty() || frame.bytes.size > maxFrameBytes) true
      else {
        latestSequence = frame.sequence
        pending = frame.bytes
        changed.trySend(Unit)
        false
      }
    }
    if (refresh) requestRefresh()
  }

  private fun current(write: Write): Boolean = synchronized(lock) {
    !closed && write.generation == generation
  }

  private fun fail(reason: String) {
    if (close()) onFailure(reason)
  }

  fun close(): Boolean {
    synchronized(lock) {
      if (closed) return false
      closed = true
      pending = null
      config = null
    }
    changed.close()
    job.cancel()
    return true
  }
}
