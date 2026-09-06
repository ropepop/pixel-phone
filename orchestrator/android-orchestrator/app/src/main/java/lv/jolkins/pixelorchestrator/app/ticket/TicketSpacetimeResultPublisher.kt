package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

/** One notification-driven owner for already-recorded outcomes; never executes phone input. */
internal class TicketSpacetimeResultPublisher(
  private val notifications: Channel<Unit>,
  private val publishNext: suspend () -> Boolean,
  private val onFailure: (Throwable) -> Unit = {},
  private val retryMillis: Long = 250L
) {
  suspend fun run() {
    // A reconnect must replay retained delivery, even if the original notification was consumed.
    notifications.trySend(Unit)
    for (ignored in notifications) {
      while (true) {
        val more = try {
          publishNext()
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Throwable) {
          onFailure(error)
          // Only database delivery is retried. The immutable phone result stays retained.
          delay(retryMillis)
          true
        }
        if (!more) break
        yield()
      }
    }
  }
}
