package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.rootexec.RootResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class TicketSecureWindowCaptureBypassOwnerTest {
  @Test
  fun inMemoryActiveHintNeverSkipsLiveReadbackAfterExternalReset() = runTest {
    val device = StatefulRootExecutor()
    val owner = owner(device)

    assertTrue(owner.ensure("first") != null)
    val readsAfterFirstAcquire = device.readbackCalls
    assertTrue(owner.snapshot().active)

    // Matches the live v311 failure: process memory still says active while Android was restored
    // externally and the prior-state file disappeared.
    device.debuggable = "0"
    device.disableSecureWindows = "0"
    device.stateFilePresent = false
    device.savedDebuggable = ""
    device.savedDisableSecureWindows = ""

    assertTrue(owner.ensure("stale_hint") != null)
    assertTrue(device.readbackCalls >= readsAfterFirstAcquire + 2)
    assertEquals(2, device.enableCalls)
    assertEquals("1", device.debuggable)
    assertEquals("1", device.disableSecureWindows)
    assertTrue(device.stateFilePresent)
    assertTrue(owner.snapshot().cleanupRequired)
  }

  @Test
  fun partialEnableFailureRestoresBothSettingsAndClearsOnlyAfterProof() = runTest {
    val device = StatefulRootExecutor(failEnable = true)
    val owner = owner(device)

    assertTrue(owner.ensure("partial_enable") == null)

    assertEquals("0", device.debuggable)
    assertEquals("0", device.disableSecureWindows)
    assertEquals(1, device.restoreSecureCalls)
    assertEquals(1, device.restoreDebuggableCalls)
    assertEquals(1, device.clearStateCalls)
    assertFalse(device.stateFilePresent)
    assertFalse(owner.snapshot().active)
    assertFalse(owner.snapshot().cleanupRequired)
  }

  @Test
  fun cancellationDuringEnableRollsBackNonCancellablyAndReleasesMutex() = runTest {
    val device = StatefulRootExecutor(holdEnableUntilCancelled = true)
    val owner = owner(device)
    val job = launch { owner.ensure("cancel_enable") }

    device.enableStarted.await()
    job.cancelAndJoin()

    assertTrue(job.isCancelled)
    assertEquals("0", device.debuggable)
    assertEquals("0", device.disableSecureWindows)
    assertFalse(device.stateFilePresent)
    assertEquals(1, device.restoreSecureCalls)
    assertEquals(1, device.restoreDebuggableCalls)

    device.holdEnableUntilCancelled = false
    assertTrue(owner.ensure("mutex_reused") != null)
    assertTrue(owner.snapshot().active)
  }

  @Test
  fun cancellationAfterAcquireBeforeRetentionCommitRestoresState() = runTest {
    val device = StatefulRootExecutor()
    val owner = owner(device)
    val lease = requireNotNull(owner.ensure("acquire"))
    val entered = CompletableDeferred<Unit>()
    val job = launch {
      owner.runRetainingOnSuccess(
        lease = lease,
        reason = "pre_admission",
        shouldRetain = { value: Boolean -> value }
      ) {
        entered.complete(Unit)
        awaitCancellation()
      }
    }

    entered.await()
    job.cancelAndJoin()

    assertTrue(job.isCancelled)
    assertEquals("0", device.debuggable)
    assertEquals("0", device.disableSecureWindows)
    assertFalse(device.stateFilePresent)
    assertFalse(owner.snapshot().active)
  }

  @Test
  fun cancellationCannotReleaseAReplacementSessionThatAlreadyRetainedOwnership() = runTest {
    val device = StatefulRootExecutor()
    val owner = owner(device)
    val lease = requireNotNull(owner.ensure("acquire"))
    val entered = CompletableDeferred<Unit>()
    var replacementSessionActive = false
    val job = launch {
      owner.runRetainingOnSuccess(
        lease = lease,
        reason = "old_prepare",
        shouldRetain = { value: Boolean -> value },
        retainIfCurrent = { replacementSessionActive }
      ) {
        entered.complete(Unit)
        awaitCancellation()
      }
    }

    entered.await()
    replacementSessionActive = true
    job.cancelAndJoin()

    assertTrue(job.isCancelled)
    assertEquals("1", device.debuggable)
    assertEquals("1", device.disableSecureWindows)
    assertTrue(device.stateFilePresent)
    assertEquals(0, device.restoreSecureCalls)
    assertTrue(owner.snapshot().active)
  }

  @Test
  fun failedOrInactivePreparationDoesNotRetainCaptureOwnership() = runTest {
    val device = StatefulRootExecutor()
    val owner = owner(device)
    val lease = requireNotNull(owner.ensure("prepare"))

    val result = owner.runRetainingOnSuccess(
      lease = lease,
      reason = "prepare_failed",
      shouldRetain = { prepared: Boolean -> prepared }
    ) { false }

    assertFalse(result)
    assertEquals("0", device.debuggable)
    assertEquals("0", device.disableSecureWindows)
    assertFalse(device.stateFilePresent)
  }

  @Test
  fun restoreAttemptsBothSettingsAndRetainsStateAfterOneUnprovedStep() = runTest {
    val device = StatefulRootExecutor()
    val owner = owner(device)
    assertTrue(owner.ensure("acquire") != null)
    device.failRestoreSecure = true

    val failed = owner.release("partial_restore")

    assertFalse(failed.ok)
    assertEquals(1, device.restoreSecureCalls)
    assertEquals(1, device.restoreDebuggableCalls)
    assertEquals("1", device.disableSecureWindows)
    assertEquals("0", device.debuggable)
    assertTrue(device.stateFilePresent)
    assertEquals(0, device.clearStateCalls)
    assertTrue(owner.snapshot().cleanupRequired)

    device.failRestoreSecure = false
    val recovered = owner.release("retry_restore")
    assertTrue(recovered.ok)
    assertEquals("0", device.disableSecureWindows)
    assertEquals("0", device.debuggable)
    assertFalse(device.stateFilePresent)
  }

  @Test
  fun boundedFallbackUsesExplicitTimeoutAndFailureDoesNotHoldMutex() = runTest {
    val primary = AlwaysFailRootExecutor()
    val fallback = StatefulRootExecutor(failEnable = true, enableFailureExitCode = 124)
    val owner = TicketSecureWindowCaptureBypassOwner(
      primaryRootExecutor = primary,
      fallbackRootExecutor = fallback,
      commandTimeout = 3.seconds
    )

    assertTrue(owner.ensure("bounded_timeout") == null)
    fallback.failEnable = false
    assertTrue(owner.ensure("after_timeout") != null)

    assertTrue(primary.timeouts.isNotEmpty())
    assertTrue(fallback.timeouts.isNotEmpty())
    assertTrue((primary.timeouts + fallback.timeouts).all { it == 3.seconds })
    assertTrue(owner.snapshot().active)
  }

  @Test
  fun liveActiveLegacyOneLineStateIsMigratedBeforeAdmissionAndExactlyReleased() = runTest {
    val device = StatefulRootExecutor().apply {
      debuggable = "1"
      disableSecureWindows = "1"
      stateFilePresent = true
      savedDebuggable = "0"
      savedDisableSecureWindows = ""
    }
    val owner = owner(device)

    assertTrue(owner.ensure("legacy_active") != null)
    assertEquals(1, device.establishOwnershipCalls)
    assertEquals("0", device.savedDebuggable)
    assertEquals("0", device.savedDisableSecureWindows)

    val released = owner.release("legacy_active_release")
    assertTrue(released.ok)
    assertEquals("0", device.debuggable)
    assertEquals("0", device.disableSecureWindows)
    assertFalse(device.stateFilePresent)
  }

  @Test
  fun liveActiveWithoutStateEstablishesRecoverableSafeOwnershipBeforeAdmission() = runTest {
    val device = StatefulRootExecutor().apply {
      debuggable = "1"
      disableSecureWindows = "1"
    }
    val owner = owner(device)

    assertTrue(owner.ensure("orphan_active") != null)
    assertTrue(device.stateFilePresent)
    assertEquals("0", device.savedDebuggable)
    assertEquals("0", device.savedDisableSecureWindows)

    assertTrue(owner.release("orphan_active_release").ok)
    assertEquals("0", device.debuggable)
    assertEquals("0", device.disableSecureWindows)
    assertFalse(device.stateFilePresent)
  }

  @Test
  fun startupReleaseNormalizesLiveActiveWithoutAnySavedState() = runTest {
    val device = StatefulRootExecutor().apply {
      debuggable = "1"
      disableSecureWindows = "1"
    }
    val owner = owner(device)

    val released = owner.release("service_startup_reconcile")

    assertTrue(released.ok)
    assertEquals("0", device.debuggable)
    assertEquals("0", device.disableSecureWindows)
    assertFalse(device.stateFilePresent)
  }

  @Test
  fun startupReleaseAlsoNormalizesPartialSecureOverrideWithoutSavedState() = runTest {
    val device = StatefulRootExecutor().apply {
      debuggable = "0"
      disableSecureWindows = "1"
    }
    val owner = owner(device)

    val released = owner.release("service_startup_reconcile_partial")

    assertTrue(released.ok)
    assertEquals("0", device.debuggable)
    assertEquals("0", device.disableSecureWindows)
    assertFalse(device.stateFilePresent)
  }

  @Test
  fun malformedActiveOwnershipFailsAdmissionAndNormalizesOnlyAfterProof() = runTest {
    val device = StatefulRootExecutor().apply {
      debuggable = "1"
      disableSecureWindows = "1"
      stateFilePresent = true
      savedDebuggable = "invalid"
      savedDisableSecureWindows = "invalid"
    }
    val owner = owner(device)

    assertTrue(owner.ensure("malformed_active") == null)

    assertEquals("0", device.debuggable)
    assertEquals("0", device.disableSecureWindows)
    assertFalse(device.stateFilePresent)
    assertFalse(owner.snapshot().cleanupRequired)
  }

  @Test
  fun newProcessStartupReconciliationRestoresDurableOwnerBeforeReadiness() = runTest {
    val device = StatefulRootExecutor()
    val previousProcess = owner(device)
    assertTrue(previousProcess.ensure("previous_process") != null)
    assertEquals("1", device.disableSecureWindows)
    assertTrue(device.stateFilePresent)

    val replacementProcess = owner(device)
    val reconciled = replacementProcess.release("service_startup_reconcile")

    assertTrue(reconciled.ok)
    assertEquals("0", device.debuggable)
    assertEquals("0", device.disableSecureWindows)
    assertFalse(device.stateFilePresent)
    assertFalse(replacementProcess.snapshot().cleanupRequired)
  }

  @Test
  fun shutdownAdmissionFenceRejectsReenableAfterFinalRelease() = runTest {
    val device = StatefulRootExecutor()
    val owner = owner(device)
    assertTrue(owner.ensure("active") != null)

    owner.stopAcceptingNewOwnership()
    assertTrue(owner.release("shutdown").ok)
    assertTrue(owner.ensure("late_session") == null)

    assertEquals("0", device.debuggable)
    assertEquals("0", device.disableSecureWindows)
    assertFalse(device.stateFilePresent)
    assertEquals(1, device.enableCalls)
  }

  @Test
  fun supersededLeaseCannotRestoreReplacementOwnershipAcrossConditionalReleaseGap() = runTest {
    val device = StatefulRootExecutor()
    val owner = owner(device)
    val oldLease = requireNotNull(owner.ensure("old_session"))
    val conditionalReleaseReached = CompletableDeferred<Unit>()
    val allowConditionalRelease = CompletableDeferred<Unit>()
    val oldJob = launch {
      owner.runRetainingOnSuccess(
        lease = oldLease,
        reason = "old_session",
        shouldRetain = { value: Boolean -> value },
        beforeConditionalRelease = {
          conditionalReleaseReached.complete(Unit)
          allowConditionalRelease.await()
        }
      ) { false }
    }

    conditionalReleaseReached.await()
    val replacementLease = requireNotNull(owner.ensure("replacement_session"))
    allowConditionalRelease.complete(Unit)
    oldJob.join()

    assertTrue(replacementLease.generation > oldLease.generation)
    assertEquals(replacementLease, owner.currentLease())
    assertEquals("1", device.debuggable)
    assertEquals("1", device.disableSecureWindows)
    assertTrue(device.stateFilePresent)
    assertEquals(0, device.restoreSecureCalls)
  }

  private fun owner(device: StatefulRootExecutor): TicketSecureWindowCaptureBypassOwner =
    TicketSecureWindowCaptureBypassOwner(
      primaryRootExecutor = device,
      fallbackRootExecutor = AlwaysFailRootExecutor(),
      commandTimeout = 3.seconds
    )

  private class StatefulRootExecutor(
    var failEnable: Boolean = false,
    var holdEnableUntilCancelled: Boolean = false,
    var enableFailureExitCode: Int = 23
  ) : RootExecutor {
    var debuggable: String = "0"
    var disableSecureWindows: String = "0"
    var stateFilePresent: Boolean = false
    var savedDebuggable: String = ""
    var savedDisableSecureWindows: String = ""
    var failRestoreSecure: Boolean = false
    var failRestoreDebuggable: Boolean = false
    var failClearState: Boolean = false
    var readbackCalls: Int = 0
    var enableCalls: Int = 0
    var restoreSecureCalls: Int = 0
    var restoreDebuggableCalls: Int = 0
    var clearStateCalls: Int = 0
    var establishOwnershipCalls: Int = 0
    val timeouts = mutableListOf<Duration>()
    val enableStarted = CompletableDeferred<Unit>()

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      timeouts += timeout
      return when {
        script.contains("ticket_secure_capture_readback") -> {
          readbackCalls += 1
          success(
            script,
            buildString {
              appendLine("debuggable=$debuggable")
              appendLine("disable_secure_windows=$disableSecureWindows")
              appendLine("state_file_present=${if (stateFilePresent) 1 else 0}")
              appendLine("saved_debuggable=$savedDebuggable")
              appendLine("saved_disable_secure_windows=$savedDisableSecureWindows")
            }
          )
        }
        script.contains("ticket_secure_capture_establish_active_ownership") -> {
          establishOwnershipCalls += 1
          if (stateFilePresent) {
            if (savedDebuggable !in setOf("0", "1")) {
              failure(script, 25)
            } else if (savedDisableSecureWindows.isEmpty()) {
              savedDisableSecureWindows = "0"
              success(script)
            } else if (savedDisableSecureWindows !in setOf("0", "1", "null")) {
              failure(script, 25)
            } else {
              success(script)
            }
          } else {
            stateFilePresent = true
            savedDebuggable = "0"
            savedDisableSecureWindows = "0"
            success(script)
          }
        }
        script.contains("ticket_secure_capture_enable") -> {
          enableCalls += 1
          if (!stateFilePresent) {
            stateFilePresent = true
            savedDebuggable = debuggable
            savedDisableSecureWindows = disableSecureWindows
          }
          debuggable = "1"
          if (holdEnableUntilCancelled) {
            enableStarted.complete(Unit)
            disableSecureWindows = "1"
            awaitCancellation()
          }
          if (failEnable) {
            failure(script, enableFailureExitCode)
          } else {
            disableSecureWindows = "1"
            success(script)
          }
        }
        script.contains("ticket_secure_capture_restore_secure_windows") -> {
          restoreSecureCalls += 1
          if (failRestoreSecure) {
            failure(script, 30)
          } else {
            disableSecureWindows = when {
              script.contains("settings delete secure disable_secure_windows") -> "null"
              script.contains("settings put secure disable_secure_windows 0") -> "0"
              script.contains("settings put secure disable_secure_windows 1") -> "1"
              else -> savedDisableSecureWindows
            }
            success(script)
          }
        }
        script.contains("ticket_secure_capture_restore_debuggable") -> {
          restoreDebuggableCalls += 1
          if (failRestoreDebuggable) {
            failure(script, 31)
          } else {
            debuggable = when {
              script.contains("reset_debuggable 0") -> "0"
              script.contains("reset_debuggable 1") -> "1"
              else -> savedDebuggable
            }
            success(script)
          }
        }
        script.contains("ticket_secure_capture_clear_saved_state") -> {
          clearStateCalls += 1
          if (failClearState) {
            failure(script, 40)
          } else {
            stateFilePresent = false
            savedDebuggable = ""
            savedDisableSecureWindows = ""
            success(script)
          }
        }
        else -> error("unexpected script")
      }
    }

    private fun success(script: String, stdout: String = ""): RootResult = RootResult(
      exitCode = 0,
      stdout = stdout,
      stderr = "",
      command = script,
      durationMs = 1L
    )

    private fun failure(script: String, exitCode: Int): RootResult = RootResult(
      exitCode = exitCode,
      stdout = "",
      stderr = "failed",
      command = script,
      durationMs = 1L
    )
  }

  private class AlwaysFailRootExecutor : RootExecutor {
    val timeouts = mutableListOf<Duration>()

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      timeouts += timeout
      return RootResult(
        exitCode = 126,
        stdout = "",
        stderr = "unavailable",
        command = script,
        durationMs = 1L
      )
    }
  }
}
