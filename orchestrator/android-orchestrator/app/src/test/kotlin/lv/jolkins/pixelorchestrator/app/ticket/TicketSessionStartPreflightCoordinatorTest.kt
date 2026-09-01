package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketSessionStartPreflightCoordinatorTest {
  @Test
  fun portraitCompletionCanArmWhileSecureCaptureIsStillRunning() = runTest {
    val portraitComplete = CompletableDeferred<Unit>()
    val secureStarted = CompletableDeferred<Unit>()
    val releaseSecure = CompletableDeferred<Unit>()
    val armed = CompletableDeferred<Unit>()
    val result = async {
      runTicketSessionStartPreflight(
        portrait = {
          portraitComplete.complete(Unit)
          secureStarted.await()
          "portrait_ok"
        },
        secureCapture = {
          secureStarted.complete(Unit)
          releaseSecure.await()
          "secure_ok"
        },
        onPortraitComplete = { portrait ->
          assertEquals("portrait_ok", portrait.getOrThrow())
          assertTrue(portraitComplete.isCompleted)
          assertTrue(secureStarted.isCompleted)
          assertFalse(releaseSecure.isCompleted)
          armed.complete(Unit)
        }
      )
    }

    armed.await()
    assertFalse(result.isCompleted)
    releaseSecure.complete(Unit)

    val completed = result.await()
    assertEquals("portrait_ok", completed.portrait.getOrThrow())
    assertEquals("secure_ok", completed.secureCapture.getOrThrow())
  }

  @Test
  fun independentChecksStartTogetherAndBothAreAwaited() = runTest {
    val portraitStarted = CompletableDeferred<Unit>()
    val secureStarted = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val result = async {
      runTicketSessionStartPreflight(
        portrait = {
          portraitStarted.complete(Unit)
          release.await()
          "portrait_ok"
        },
        secureCapture = {
          secureStarted.complete(Unit)
          release.await()
          "secure_ok"
        }
      )
    }

    portraitStarted.await()
    secureStarted.await()
    assertFalse(result.isCompleted)
    release.complete(Unit)

    val completed = result.await()
    assertEquals("portrait_ok", completed.portrait.getOrThrow())
    assertEquals("secure_ok", completed.secureCapture.getOrThrow())
  }

  @Test
  fun ordinaryFailureDoesNotCancelTheOtherCheck() = runTest {
    val secureStarted = CompletableDeferred<Unit>()
    val allowSecureCompletion = CompletableDeferred<Unit>()
    val result = async {
      runTicketSessionStartPreflight(
        portrait = { error("portrait failed") },
        secureCapture = {
          secureStarted.complete(Unit)
          allowSecureCompletion.await()
          "secure_completed"
        }
      )
    }

    secureStarted.await()
    yield()
    assertFalse(result.isCompleted)
    allowSecureCompletion.complete(Unit)

    val completed = result.await()
    assertTrue(completed.portrait.isFailure)
    assertEquals("secure_completed", completed.secureCapture.getOrThrow())
  }

  @Test
  fun parentCancellationCancelsAndJoinsBothChecks() = runTest {
    val portraitStarted = CompletableDeferred<Unit>()
    val secureStarted = CompletableDeferred<Unit>()
    val portraitFinished = CompletableDeferred<Unit>()
    val secureFinished = CompletableDeferred<Unit>()
    val cancellationCleanup = CompletableDeferred<Unit>()
    val job = async {
      runTicketSessionStartPreflight(
        portrait = {
          portraitStarted.complete(Unit)
          try {
            awaitCancellation()
          } finally {
            portraitFinished.complete(Unit)
          }
        },
        secureCapture = {
          secureStarted.complete(Unit)
          try {
            awaitCancellation()
          } finally {
            secureFinished.complete(Unit)
          }
        },
        onCancelled = { cancellationCleanup.complete(Unit) }
      )
    }

    portraitStarted.await()
    secureStarted.await()
    job.cancelAndJoin()

    assertTrue(job.isCancelled)
    assertTrue(portraitFinished.isCompleted)
    assertTrue(secureFinished.isCompleted)
    assertTrue(cancellationCleanup.isCompleted)
  }

  @Test
  fun cancellationAfterSecureSuccessRunsCleanupAfterPortraitIsJoined() = runTest {
    val portraitStarted = CompletableDeferred<Unit>()
    val portraitFinished = CompletableDeferred<Unit>()
    val secureFinished = CompletableDeferred<Unit>()
    var acquiredLease: String? = null
    var cleanedLease: String? = null
    val job = async {
      runTicketSessionStartPreflight(
        portrait = {
          portraitStarted.complete(Unit)
          try {
            awaitCancellation()
          } finally {
            portraitFinished.complete(Unit)
          }
        },
        secureCapture = {
          acquiredLease = "lease-7"
          secureFinished.complete(Unit)
          "secure_ok"
        },
        onCancelled = {
          assertTrue(portraitFinished.isCompleted)
          cleanedLease = acquiredLease
        }
      )
    }

    portraitStarted.await()
    secureFinished.await()
    job.cancelAndJoin()

    assertEquals("lease-7", cleanedLease)
  }

  @Test
  fun onlyNewLeaseIsReleasedWhenPortraitFails() {
    val acquired = TicketSecureWindowCaptureBypassEnsureResult(
      lease = TicketSecureWindowCaptureBypassLease(4L),
      outcome = "enabled",
      acquiredLease = true
    )
    val preserved = TicketSecureWindowCaptureBypassEnsureResult(
      outcome = "ownership_busy",
      acquiredLease = false
    )

    assertTrue(shouldReleaseSessionStartSecureLease(false, acquired))
    assertFalse(shouldReleaseSessionStartSecureLease(true, acquired))
    assertFalse(shouldReleaseSessionStartSecureLease(false, preserved))
  }
}
