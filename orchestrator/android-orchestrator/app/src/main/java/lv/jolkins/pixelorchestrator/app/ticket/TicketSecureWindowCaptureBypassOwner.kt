package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.rootexec.RootResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal data class TicketSecureWindowCaptureBypassSnapshot(
  val active: Boolean = false,
  val cleanupRequired: Boolean = false,
  val message: String = "Secure-window capture bypass is inactive",
  val lastFailure: String = ""
)

internal data class TicketSecureWindowCaptureBypassLease(
  val generation: Long
)

internal data class TicketSecureWindowCaptureBypassEnsureResult(
  val lease: TicketSecureWindowCaptureBypassLease? = null,
  val outcome: String,
  val acquiredLease: Boolean = false
)

internal data class TicketSecureWindowCaptureBypassReleaseResult(
  val secureWindowsRestored: Boolean,
  val debuggableRestored: Boolean,
  val stateFileCleared: Boolean,
  val cleanupRequired: Boolean,
  val detail: String = ""
) {
  val ok: Boolean
    get() = secureWindowsRestored && debuggableRestored && stateFileCleared && !cleanupRequired
}

private data class TicketSecureWindowCaptureBypassReadback(
  val ok: Boolean,
  val debuggable: String = "",
  val disableSecureWindows: String = "",
  val savedDebuggable: String = "",
  val savedDisableSecureWindows: String = "",
  val stateFilePresent: Boolean = false,
  val detail: String = ""
) {
  val liveActive: Boolean
    get() = ok && debuggable == "1" && disableSecureWindows == "1"

  val liveInactive: Boolean
    get() = ok && debuggable in VALID_DEBUGGABLE_VALUES && disableSecureWindows in INACTIVE_SECURE_VALUES

  val savedOriginalValid: Boolean
    get() = stateFilePresent &&
      savedDebuggable in VALID_DEBUGGABLE_VALUES &&
      savedDisableSecureWindows in VALID_SECURE_VALUES

  val legacySavedOriginalValid: Boolean
    get() = stateFilePresent &&
      savedDebuggable in VALID_DEBUGGABLE_VALUES &&
      savedDisableSecureWindows.isEmpty()

  companion object {
    val VALID_DEBUGGABLE_VALUES = setOf("0", "1")
    val VALID_SECURE_VALUES = setOf("0", "1", "null")
    val INACTIVE_SECURE_VALUES = setOf("0", "null")
  }
}

/**
 * Owns the temporary Android settings needed to capture protected ViVi pixels. Every acquisition
 * verifies the live settings instead of trusting process memory. Any uncommitted or cancelled
 * acquisition is restored under a non-cancellable mutex, and a saved original is deleted only
 * after both independent restoration steps are read back successfully.
 */
internal class TicketSecureWindowCaptureBypassOwner(
  private val primaryRootExecutor: RootExecutor,
  private val fallbackRootExecutor: RootExecutor,
  private val commandTimeout: Duration = DEFAULT_COMMAND_TIMEOUT,
  private val onEvent: (String, String) -> Unit = { _, _ -> }
) {
  private val mutex = Mutex()
  @Volatile private var state = TicketSecureWindowCaptureBypassSnapshot()
  @Volatile private var acceptingOwnership: Boolean = true
  @Volatile private var ownershipGeneration: Long = 0L
  @Volatile private var activeLease: TicketSecureWindowCaptureBypassLease? = null

  fun snapshot(): TicketSecureWindowCaptureBypassSnapshot = state

  fun currentLease(): TicketSecureWindowCaptureBypassLease? = activeLease

  fun stopAcceptingNewOwnership() {
    acceptingOwnership = false
  }

  suspend fun ensure(reason: String): TicketSecureWindowCaptureBypassLease? =
    ensureWithResult(reason).lease

  /**
   * Performs read, durable original-state save, enable, and final proof in one idempotent root
   * transaction. Session-start callers may preserve an existing lease; they still perform the
   * fresh live proof, but can never replace or release another in-flight owner's lease.
   */
  suspend fun ensureWithResult(
    reason: String,
    preserveExistingLease: Boolean = false
  ): TicketSecureWindowCaptureBypassEnsureResult = mutex.withLock {
    if (!acceptingOwnership) {
      state = state.copy(lastFailure = "ownership_admission_closed")
      onEvent("secure_window_capture_bypass_admission_closed", reason)
      return@withLock TicketSecureWindowCaptureBypassEnsureResult(outcome = "admission_closed")
    }
    val preservedLease = activeLease.takeIf { preserveExistingLease }
    val rollbackRequired = preservedLease == null
    try {
      val acquire = runWithFallback(ACQUIRE_SCRIPT)
      val after = if (acquire.ok) readbackFromResult(acquire) else null
      val acquireOutcome = acquire.stdout.lineSequence()
        .firstOrNull { it.startsWith(ACQUIRE_OUTCOME_PREFIX) }
        ?.removePrefix(ACQUIRE_OUTCOME_PREFIX)
        .orEmpty()
      if (
        acquire.ok &&
        after?.liveActive == true &&
        after.savedOriginalValid &&
        acquireOutcome in SUCCESSFUL_ACQUIRE_OUTCOMES
      ) {
        state = TicketSecureWindowCaptureBypassSnapshot(
          active = true,
          cleanupRequired = true,
          message = "Secure-window capture bypass is active"
        )
        if (preservedLease != null) {
          onEvent("secure_window_capture_bypass_existing_owner_preserved", reason)
          return@withLock TicketSecureWindowCaptureBypassEnsureResult(
            outcome = "ownership_busy",
            acquiredLease = false
          )
        }
        val lease = admitLeaseUnlocked()
        onEvent(
          if (acquireOutcome == "verified") {
            "secure_window_capture_bypass_verified"
          } else {
            "secure_window_capture_bypass_enabled"
          },
          reason
        )
        return@withLock TicketSecureWindowCaptureBypassEnsureResult(
          lease = lease,
          outcome = acquireOutcome,
          acquiredLease = true
        )
      }

      val failure = when {
        !acquire.ok -> "acquire_failed_${failureSummary(acquire)}"
        after?.savedOriginalValid != true -> "saved_original_unproved"
        after?.liveActive != true -> "enable_readback_unproved"
        else -> "acquire_outcome_unproved"
      }
      if (preservedLease != null) {
        return@withLock failEnsurePreservingCurrentOwner(reason, failure)
      }
      failEnsure(reason, failure, rollbackRequired)
      TicketSecureWindowCaptureBypassEnsureResult(outcome = "failed")
    } catch (cancelled: CancellationException) {
      if (rollbackRequired) {
        withContext(NonCancellable) {
          invalidateLeaseUnlocked()
          restoreUnlocked("ensure_cancelled:$reason")
        }
      } else {
        markExistingOwnerUnproved(reason, "ensure_cancelled")
      }
      throw cancelled
    } catch (error: Throwable) {
      val failure = "ensure_exception_${error::class.java.simpleName.take(48)}"
      if (preservedLease != null) {
        failEnsurePreservingCurrentOwner(reason, failure)
      } else {
        failEnsure(reason, failure, rollbackRequired)
        TicketSecureWindowCaptureBypassEnsureResult(outcome = "failed")
      }
    }
  }

  private fun failEnsurePreservingCurrentOwner(
    reason: String,
    failure: String
  ): TicketSecureWindowCaptureBypassEnsureResult {
    markExistingOwnerUnproved(reason, failure)
    return TicketSecureWindowCaptureBypassEnsureResult(outcome = "ownership_unproved")
  }

  private fun markExistingOwnerUnproved(reason: String, failure: String) {
    state = TicketSecureWindowCaptureBypassSnapshot(
      active = false,
      cleanupRequired = true,
      message = "Existing secure-window capture ownership is unproved",
      lastFailure = failure
    )
    onEvent(
      "secure_window_capture_bypass_existing_owner_unproved",
      "reason=$reason failure=$failure existing_owner_preserved=true"
    )
  }

  suspend fun release(reason: String): TicketSecureWindowCaptureBypassReleaseResult =
    withContext(NonCancellable) {
      mutex.withLock {
        invalidateLeaseUnlocked()
        restoreUnlocked(reason)
      }
    }

  suspend fun releaseAcquiredLease(
    lease: TicketSecureWindowCaptureBypassLease,
    reason: String
  ): TicketSecureWindowCaptureBypassReleaseResult? = withContext(NonCancellable) {
    mutex.withLock {
      if (activeLease != lease) {
        onEvent("secure_window_capture_bypass_release_superseded", reason)
        return@withLock null
      }
      invalidateLeaseUnlocked()
      restoreUnlocked(reason)
    }
  }

  suspend fun <T> runRetainingOnSuccess(
    lease: TicketSecureWindowCaptureBypassLease,
    reason: String,
    shouldRetain: (T) -> Boolean,
    retainIfCurrent: () -> Boolean = { false },
    beforeConditionalRelease: suspend () -> Unit = {},
    block: suspend () -> T
  ): T {
    var retained = false
    try {
      val result = block()
      retained = shouldRetain(result)
      return result
    } finally {
      if (!retained) {
        releaseIfCurrent(
          lease,
          "not_retained:$reason",
          retainIfCurrent,
          beforeConditionalRelease
        )
      }
    }
  }

  private suspend fun releaseIfCurrent(
    lease: TicketSecureWindowCaptureBypassLease,
    reason: String,
    retainIfCurrent: () -> Boolean,
    beforeConditionalRelease: suspend () -> Unit
  ) = withContext(NonCancellable) {
    beforeConditionalRelease()
    mutex.withLock {
      if (activeLease != lease) {
        onEvent("secure_window_capture_bypass_release_superseded", reason)
        return@withLock
      }
      if (retainIfCurrent()) {
        onEvent("secure_window_capture_bypass_release_retained_current", reason)
        return@withLock
      }
      invalidateLeaseUnlocked()
      restoreUnlocked(reason)
    }
  }

  private suspend fun failEnsure(
    reason: String,
    failure: String,
    rollbackRequired: Boolean
  ): TicketSecureWindowCaptureBypassLease? {
    invalidateLeaseUnlocked()
    val restore = if (rollbackRequired) {
      withContext(NonCancellable) {
        restoreUnlocked("ensure_failed:$reason")
      }
    } else {
      null
    }
    val cleanupFailed = restore != null && !restore.ok
    state = state.copy(
      active = state.active && cleanupFailed,
      cleanupRequired = cleanupFailed || state.cleanupRequired,
      message = if (cleanupFailed) {
        "Secure-window capture bypass enable and restoration failed"
      } else {
        "Secure-window capture bypass enable failed"
      },
      lastFailure = if (cleanupFailed) "${failure}_cleanup_unproved" else failure
    )
    onEvent(
      "secure_window_capture_bypass_required_failed",
      "reason=$reason failure=${state.lastFailure}"
    )
    return null
  }

  private suspend fun restoreUnlocked(reason: String): TicketSecureWindowCaptureBypassReleaseResult {
    var before = runCatchingReadback()
    if (!before.ok) {
      return failedRestore(reason, before, "restore_readback_unavailable")
    }
    if (before.legacySavedOriginalValid) {
      val migration = runRestoreOperation(ESTABLISH_ACTIVE_OWNERSHIP_SCRIPT)
      val migrated = if (migration.ok) runCatchingReadback() else null
      if (migration.ok && migrated?.savedOriginalValid == true) {
        before = migrated
      } else {
        return failedRestore(reason, migrated ?: before, "legacy_saved_original_migration_unproved")
      }
    }
    if (!before.savedOriginalValid) {
      if (!before.liveInactive) {
        return restoreUnownedToSafeDefaults(reason, before)
      }
      if (before.liveInactive && !before.stateFilePresent) {
        state = TicketSecureWindowCaptureBypassSnapshot(
          active = false,
          cleanupRequired = false,
          message = "Secure-window capture bypass is inactive"
        )
        onEvent("secure_window_capture_bypass_release_unowned", reason)
        return TicketSecureWindowCaptureBypassReleaseResult(
          secureWindowsRestored = true,
          debuggableRestored = true,
          stateFileCleared = true,
          cleanupRequired = false,
          detail = "inactive_without_saved_state"
        )
      }
      return failedRestore(reason, before, "saved_original_unavailable")
    }

    // These two commands are intentionally independent. Failure of the first must not prevent the
    // second restoration attempt.
    val secureAttempt = runRestoreOperation(
      restoreSecureWindowsScript(before.savedDisableSecureWindows)
    )
    val debuggableAttempt = runRestoreOperation(
      restoreDebuggableScript(before.savedDebuggable)
    )
    val after = runCatchingReadback()
    val secureProved = after.ok && after.disableSecureWindows == before.savedDisableSecureWindows
    val debuggableProved = after.ok && after.debuggable == before.savedDebuggable
    val bothProved = secureProved && debuggableProved
    val clearResult = if (bothProved) runRestoreOperation(CLEAR_SAVED_STATE_SCRIPT) else null
    val cleared = clearResult?.ok == true
    val cleanupRequired = !cleared
    val result = TicketSecureWindowCaptureBypassReleaseResult(
      secureWindowsRestored = secureProved,
      debuggableRestored = debuggableProved,
      stateFileCleared = cleared,
      cleanupRequired = cleanupRequired,
      detail = buildString {
        append("secure_attempt=").append(secureAttempt.ok)
        append(" debuggable_attempt=").append(debuggableAttempt.ok)
        append(" secure_proved=").append(secureProved)
        append(" debuggable_proved=").append(debuggableProved)
        append(" state_cleared=").append(cleared)
      }
    )
    state = TicketSecureWindowCaptureBypassSnapshot(
      active = after.liveActive,
      cleanupRequired = cleanupRequired,
      message = if (result.ok) {
        "Secure-window capture bypass is inactive"
      } else {
        "Secure-window capture bypass restoration is unproved"
      },
      lastFailure = if (result.ok) "" else "restore_unproved"
    )
    onEvent(
      if (result.ok) "secure_window_capture_bypass_disabled" else "secure_window_capture_bypass_disable_failed",
      "reason=$reason ${result.detail}"
    )
    return result
  }

  private suspend fun restoreUnownedToSafeDefaults(
    reason: String,
    before: TicketSecureWindowCaptureBypassReadback
  ): TicketSecureWindowCaptureBypassReleaseResult {
    val secureAttempt = runRestoreOperation(restoreSecureWindowsScript(SAFE_SECURE_WINDOWS_VALUE))
    val debuggableAttempt = runRestoreOperation(restoreDebuggableScript(SAFE_DEBUGGABLE_VALUE))
    val after = runCatchingReadback()
    val secureProved = after.ok && after.disableSecureWindows == SAFE_SECURE_WINDOWS_VALUE
    val debuggableProved = after.ok && after.debuggable == SAFE_DEBUGGABLE_VALUE
    val bothProved = secureProved && debuggableProved
    val clearResult = if (bothProved && before.stateFilePresent) {
      runRestoreOperation(CLEAR_SAVED_STATE_SCRIPT)
    } else {
      null
    }
    val stateCleared = if (before.stateFilePresent) clearResult?.ok == true else bothProved
    val cleanupRequired = !stateCleared
    val result = TicketSecureWindowCaptureBypassReleaseResult(
      secureWindowsRestored = secureProved,
      debuggableRestored = debuggableProved,
      stateFileCleared = stateCleared,
      cleanupRequired = cleanupRequired,
      detail = buildString {
        append("unowned_safe_restore=true")
        append(" secure_attempt=").append(secureAttempt.ok)
        append(" debuggable_attempt=").append(debuggableAttempt.ok)
        append(" secure_proved=").append(secureProved)
        append(" debuggable_proved=").append(debuggableProved)
        append(" state_cleared=").append(stateCleared)
      }
    )
    state = TicketSecureWindowCaptureBypassSnapshot(
      active = after.liveActive,
      cleanupRequired = cleanupRequired,
      message = if (result.ok) {
        "Secure-window capture bypass was normalized to the safe inactive state"
      } else {
        "Unowned secure-window capture bypass cleanup is unproved"
      },
      lastFailure = if (result.ok) "" else "unowned_active_restore_unproved"
    )
    onEvent(
      if (result.ok) "secure_window_capture_bypass_unowned_disabled" else "secure_window_capture_bypass_disable_failed",
      "reason=$reason ${result.detail}"
    )
    return result
  }

  private fun admitLeaseUnlocked(): TicketSecureWindowCaptureBypassLease {
    ownershipGeneration += 1L
    return TicketSecureWindowCaptureBypassLease(ownershipGeneration).also { activeLease = it }
  }

  private fun invalidateLeaseUnlocked() {
    ownershipGeneration += 1L
    activeLease = null
  }

  private suspend fun failedRestore(
    reason: String,
    readback: TicketSecureWindowCaptureBypassReadback,
    failure: String
  ): TicketSecureWindowCaptureBypassReleaseResult {
    state = TicketSecureWindowCaptureBypassSnapshot(
      active = readback.liveActive,
      cleanupRequired = readback.stateFilePresent || state.cleanupRequired,
      message = "Secure-window capture bypass restoration is unproved",
      lastFailure = failure
    )
    val result = TicketSecureWindowCaptureBypassReleaseResult(
      secureWindowsRestored = false,
      debuggableRestored = false,
      stateFileCleared = false,
      cleanupRequired = state.cleanupRequired,
      detail = failure
    )
    onEvent("secure_window_capture_bypass_disable_failed", "reason=$reason failure=$failure")
    return result
  }

  private suspend fun runRestoreOperation(script: String): RootResult = try {
    runWithFallback(script)
  } catch (error: Throwable) {
    RootResult(
      exitCode = 125,
      stdout = "",
      stderr = error::class.java.simpleName,
      command = "secure_capture_restore",
      durationMs = 0L
    )
  }

  private suspend fun runCatchingReadback(): TicketSecureWindowCaptureBypassReadback = try {
    readbackUnlocked()
  } catch (error: Throwable) {
    TicketSecureWindowCaptureBypassReadback(
      ok = false,
      detail = error::class.java.simpleName
    )
  }

  private suspend fun readbackUnlocked(): TicketSecureWindowCaptureBypassReadback {
    val result = runWithFallback(READBACK_SCRIPT)
    if (!result.ok) {
      return TicketSecureWindowCaptureBypassReadback(
        ok = false,
        detail = failureSummary(result)
      )
    }
    return readbackFromResult(result)
  }

  private fun readbackFromResult(result: RootResult): TicketSecureWindowCaptureBypassReadback {
    val values = result.stdout.lineSequence().mapNotNull { line ->
      val separator = line.indexOf('=')
      if (separator <= 0) null else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
    }.toMap()
    val debuggable = values["debuggable"].orEmpty()
    val disableSecureWindows = values["disable_secure_windows"].orEmpty()
    val stateFilePresent = values["state_file_present"] == "1"
    return TicketSecureWindowCaptureBypassReadback(
      ok = debuggable in TicketSecureWindowCaptureBypassReadback.VALID_DEBUGGABLE_VALUES &&
        disableSecureWindows in TicketSecureWindowCaptureBypassReadback.VALID_SECURE_VALUES,
      debuggable = debuggable,
      disableSecureWindows = disableSecureWindows,
      savedDebuggable = values["saved_debuggable"].orEmpty(),
      savedDisableSecureWindows = values["saved_disable_secure_windows"].orEmpty(),
      stateFilePresent = stateFilePresent,
      detail = if (stateFilePresent) "saved_state_present" else "saved_state_absent"
    )
  }

  private suspend fun runWithFallback(script: String): RootResult {
    val primary = primaryRootExecutor.runScript(script, commandTimeout)
    if (primary.ok) return primary
    val fallback = fallbackRootExecutor.runScript(script, commandTimeout)
    if (!fallback.ok) {
      onEvent(
        "secure_window_capture_bypass_bounded_root_failed",
        "primary=${failureSummary(primary)} fallback=${failureSummary(fallback)}"
      )
    }
    return fallback
  }

  private fun failureSummary(result: RootResult): String =
    "exit_${result.exitCode}_duration_${result.durationMs.coerceAtLeast(0L)}ms"

  private fun restoreSecureWindowsScript(value: String): String = when (value) {
    "null" -> "$RESTORE_SECURE_WINDOWS_PREFIX\nsettings delete secure disable_secure_windows\n"
    else -> "$RESTORE_SECURE_WINDOWS_PREFIX\nsettings put secure disable_secure_windows $value\n"
  }

  private fun restoreDebuggableScript(value: String): String =
    "$RESET_DEBUGGABLE_FUNCTION\n# ticket_secure_capture_restore_debuggable\nreset_debuggable $value\n"

  private companion object {
    val DEFAULT_COMMAND_TIMEOUT: Duration = 5.seconds
    const val STATE_FILE = "/data/local/pixel-stack/apps/ticket-screen/state/ro-debuggable-before-ticket"
    const val SAFE_DEBUGGABLE_VALUE = "0"
    const val SAFE_SECURE_WINDOWS_VALUE = "0"
    const val ACQUIRE_OUTCOME_PREFIX = "acquire_outcome="
    val SUCCESSFUL_ACQUIRE_OUTCOMES = setOf("verified", "enabled", "ownership_established")

    const val RESET_DEBUGGABLE_FUNCTION = """
reset_debuggable() {
  value="${'$'}1"
  if [ -x /debug_ramdisk/magisk ]; then
    su -M -c "/debug_ramdisk/magisk resetprop ro.debuggable ${'$'}value"
    return "${'$'}?"
  fi
  if command -v resetprop >/dev/null 2>&1; then
    su -M -c "resetprop ro.debuggable ${'$'}value"
    return "${'$'}?"
  fi
  su -M -c "/system_ext/bin/magisk resetprop ro.debuggable ${'$'}value"
}
"""

    const val READBACK_SCRIPT = """
# ticket_secure_capture_readback
state_file="$STATE_FILE"
debuggable="${'$'}(getprop ro.debuggable 2>/dev/null | tr -d '\r')"
disable_secure_windows="${'$'}(settings get secure disable_secure_windows 2>/dev/null | tr -d '\r')"
saved_debuggable=""
saved_disable_secure_windows=""
state_file_present=0
if [ -r "${'$'}state_file" ]; then
  state_file_present=1
  saved_debuggable="${'$'}(sed -n '1p' "${'$'}state_file" 2>/dev/null | tr -d '\r')"
  saved_disable_secure_windows="${'$'}(sed -n '2p' "${'$'}state_file" 2>/dev/null | tr -d '\r')"
fi
printf 'debuggable=%s\n' "${'$'}debuggable"
printf 'disable_secure_windows=%s\n' "${'$'}disable_secure_windows"
printf 'state_file_present=%s\n' "${'$'}state_file_present"
printf 'saved_debuggable=%s\n' "${'$'}saved_debuggable"
printf 'saved_disable_secure_windows=%s\n' "${'$'}saved_disable_secure_windows"
"""

    const val ACQUIRE_SCRIPT = """
# ticket_secure_capture_acquire
$RESET_DEBUGGABLE_FUNCTION
state_dir="/data/local/pixel-stack/apps/ticket-screen/state"
state_file="$STATE_FILE"
mkdir -p "${'$'}state_dir" || exit 20

debuggable="${'$'}(getprop ro.debuggable 2>/dev/null | tr -d '\r')"
disable_secure_windows="${'$'}(settings get secure disable_secure_windows 2>/dev/null | tr -d '\r')"
case "${'$'}debuggable" in 0|1) ;; *) exit 21 ;; esac
case "${'$'}disable_secure_windows" in 0|1|null) ;; *) exit 21 ;; esac

state_file_present=0
saved_debuggable=""
saved_disable_secure_windows=""
if [ -r "${'$'}state_file" ]; then
  state_file_present=1
  saved_debuggable="${'$'}(sed -n '1p' "${'$'}state_file" 2>/dev/null | tr -d '\r')"
  saved_disable_secure_windows="${'$'}(sed -n '2p' "${'$'}state_file" 2>/dev/null | tr -d '\r')"
  case "${'$'}saved_debuggable" in 0|1) ;; *) exit 21 ;; esac
  if [ -z "${'$'}saved_disable_secure_windows" ]; then
    # v311 and earlier stored only ro.debuggable; their established release value was zero.
    printf '%s\n0\n' "${'$'}saved_debuggable" > "${STATE_FILE}.tmp.${'$'}${'$'}" || exit 21
    chmod 600 "${STATE_FILE}.tmp.${'$'}${'$'}" >/dev/null 2>&1 || true
    mv "${STATE_FILE}.tmp.${'$'}${'$'}" "${'$'}state_file" || exit 21
  else
    case "${'$'}saved_disable_secure_windows" in 0|1|null) ;; *) exit 21 ;; esac
  fi
fi

acquire_outcome=verified
if [ "${'$'}debuggable" = "1" ] && [ "${'$'}disable_secure_windows" = "1" ]; then
  if [ "${'$'}state_file_present" != "1" ]; then
    # The already-active values cannot be treated as their own originals. Establish conservative,
    # recoverable ownership exactly as the former active-ownership transaction did.
    saved_debuggable=0
    saved_disable_secure_windows=0
    temporary_state="${STATE_FILE}.tmp.${'$'}${'$'}"
    printf '%s\n%s\n' "${'$'}saved_debuggable" "${'$'}saved_disable_secure_windows" > "${'$'}temporary_state" || exit 26
    chmod 600 "${'$'}temporary_state" >/dev/null 2>&1 || true
    mv "${'$'}temporary_state" "${'$'}state_file" || exit 26
    state_file_present=1
    acquire_outcome=ownership_established
  fi
else
  if [ "${'$'}state_file_present" != "1" ]; then
    # disable_secure_windows=1 without debuggable=1 is an unowned partial override. It has no
    # trustworthy original to save and must fail closed for Kotlin's safe-default restoration.
    [ "${'$'}disable_secure_windows" != "1" ] || exit 27
    saved_debuggable="${'$'}debuggable"
    saved_disable_secure_windows="${'$'}disable_secure_windows"
    temporary_state="${STATE_FILE}.tmp.${'$'}${'$'}"
    printf '%s\n%s\n' "${'$'}saved_debuggable" "${'$'}saved_disable_secure_windows" > "${'$'}temporary_state" || exit 21
    chmod 600 "${'$'}temporary_state" >/dev/null 2>&1 || true
    mv "${'$'}temporary_state" "${'$'}state_file" || exit 21
    state_file_present=1
  fi
  reset_debuggable 1 || exit 22
  settings put secure disable_secure_windows 1 || exit 23
  acquire_outcome=enabled
fi

# Final live proof is part of this same transaction. Kotlin validates these exact values and the
# saved original before admitting an ownership generation.
debuggable="${'$'}(getprop ro.debuggable 2>/dev/null | tr -d '\r')"
disable_secure_windows="${'$'}(settings get secure disable_secure_windows 2>/dev/null | tr -d '\r')"
saved_debuggable="${'$'}(sed -n '1p' "${'$'}state_file" 2>/dev/null | tr -d '\r')"
saved_disable_secure_windows="${'$'}(sed -n '2p' "${'$'}state_file" 2>/dev/null | tr -d '\r')"
case "${'$'}debuggable" in 1) ;; *) exit 28 ;; esac
case "${'$'}disable_secure_windows" in 1) ;; *) exit 28 ;; esac
case "${'$'}saved_debuggable" in 0|1) ;; *) exit 28 ;; esac
case "${'$'}saved_disable_secure_windows" in 0|1|null) ;; *) exit 28 ;; esac
printf 'acquire_outcome=%s\n' "${'$'}acquire_outcome"
printf 'debuggable=%s\n' "${'$'}debuggable"
printf 'disable_secure_windows=%s\n' "${'$'}disable_secure_windows"
printf 'state_file_present=1\n'
printf 'saved_debuggable=%s\n' "${'$'}saved_debuggable"
printf 'saved_disable_secure_windows=%s\n' "${'$'}saved_disable_secure_windows"
"""

    const val ESTABLISH_ACTIVE_OWNERSHIP_SCRIPT = """
# ticket_secure_capture_establish_active_ownership
state_dir="/data/local/pixel-stack/apps/ticket-screen/state"
state_file="$STATE_FILE"
mkdir -p "${'$'}state_dir" || exit 24
saved_debuggable=""
saved_disable_secure_windows=""
if [ -r "${'$'}state_file" ]; then
  saved_debuggable="${'$'}(sed -n '1p' "${'$'}state_file" 2>/dev/null | tr -d '\r')"
  saved_disable_secure_windows="${'$'}(sed -n '2p' "${'$'}state_file" 2>/dev/null | tr -d '\r')"
  case "${'$'}saved_debuggable" in 0|1) ;; *) exit 25 ;; esac
  if [ -z "${'$'}saved_disable_secure_windows" ]; then
    # v311 stored only ro.debuggable and always restored the secure setting to zero.
    saved_disable_secure_windows=0
  else
    case "${'$'}saved_disable_secure_windows" in 0|1|null) ;; *) exit 25 ;; esac
  fi
else
  # A missing file cannot prove the historical values. Establish a conservative, recoverable
  # Ticket ownership contract before accepting the already-active bypass.
  saved_debuggable=0
  saved_disable_secure_windows=0
fi
temporary_state="${STATE_FILE}.tmp.${'$'}${'$'}"
printf '%s\n%s\n' "${'$'}saved_debuggable" "${'$'}saved_disable_secure_windows" > "${'$'}temporary_state" || exit 26
chmod 600 "${'$'}temporary_state" >/dev/null 2>&1 || true
mv "${'$'}temporary_state" "${'$'}state_file" || exit 26
"""

    const val RESTORE_SECURE_WINDOWS_PREFIX = "# ticket_secure_capture_restore_secure_windows"

    const val CLEAR_SAVED_STATE_SCRIPT = """
# ticket_secure_capture_clear_saved_state
state_file="$STATE_FILE"
rm -f "${'$'}state_file" || exit 40
[ ! -e "${'$'}state_file" ] || exit 41
"""
  }
}
