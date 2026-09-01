package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

internal data class TicketSessionStartPreflightResults<P, S>(
  val portrait: Result<P>,
  val secureCapture: Result<S>
)

/**
 * Runs the two independent, fresh phone safety checks as children of the session-start request.
 * One ordinary failure never cancels the sibling; request timeout or service cancellation still
 * cancels and joins both children through structured concurrency.
 */
internal suspend fun <P, S> runTicketSessionStartPreflight(
  portrait: suspend () -> P,
  secureCapture: suspend () -> S,
  onPortraitComplete: suspend (Result<P>) -> Unit = {},
  onCancelled: suspend () -> Unit = {}
): TicketSessionStartPreflightResults<P, S> = try {
  supervisorScope {
    val portraitResult = async {
      capturePreflightResult(portrait).also { onPortraitComplete(it) }
    }
    val secureCaptureResult = async { capturePreflightResult(secureCapture) }
    TicketSessionStartPreflightResults(
      portrait = portraitResult.await(),
      secureCapture = secureCaptureResult.await()
    )
  }
} catch (cancelled: CancellationException) {
  withContext(NonCancellable) { onCancelled() }
  throw cancelled
}

internal fun shouldReleaseSessionStartSecureLease(
  portraitVerified: Boolean,
  secureCapture: TicketSecureWindowCaptureBypassEnsureResult
): Boolean = !portraitVerified && secureCapture.acquiredLease && secureCapture.lease != null

private suspend fun <T> capturePreflightResult(block: suspend () -> T): Result<T> = try {
  Result.success(block())
} catch (cancelled: CancellationException) {
  throw cancelled
} catch (error: Throwable) {
  Result.failure(error)
}
