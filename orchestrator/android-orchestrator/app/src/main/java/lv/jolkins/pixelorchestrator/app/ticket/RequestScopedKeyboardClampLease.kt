package lv.jolkins.pixelorchestrator.app.ticket

import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationServiceBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns request-lifetime soft-keyboard suppression without disabling an IME or registering a
 * hardware keyboard. The critical field tap is gated on the Accessibility controller's verified
 * hidden mode, so control-code entry cannot trigger an Android display-configuration change.
 */
internal class RequestScopedKeyboardClampLease(
  private val requestId: String,
  private val expectedPackageName: String,
  private val commandTimeoutMillis: Long,
  private val onEvent: (String, String) -> Unit
) {
  private val releaseMutex = Mutex()
  @Volatile private var suppressionMayBeOwned: Boolean = false
  @Volatile private var acquired: Boolean = false
  @Volatile private var released: Boolean = false

  suspend fun acquire(): Boolean = releaseMutex.withLock {
    if (released) {
      return false
    }
    if (acquired) {
      return PhoneAutomationServiceBridge.isViviControlCodeKeyboardModeSuppressed(
        expectedPackageName
      )
    }
    val applied = runCatching {
      val connected = PhoneAutomationServiceBridge.awaitAccessibilityConnection(
        commandTimeoutMillis
      )
      if (!connected) {
        false
      } else {
        // From this call boundary onward, a failed Boolean may still mean HIDDEN was applied but
        // its inline rollback could not be proved. Finalization must therefore verify restore.
        suppressionMayBeOwned = true
        PhoneAutomationServiceBridge.suppressViviControlCodeKeyboardMode(expectedPackageName)
      }
    }.getOrElse { error ->
      onEvent(
        "keyboard_clamp_apply_failed",
        "request=$requestId error=${error::class.java.simpleName}"
      )
      false
    }
    if (!applied) {
      onEvent(
        "keyboard_clamp_apply_failed",
        "request=$requestId authority=accessibility_soft_keyboard unavailable=true"
      )
      return false
    }
    acquired = true
    true
  }

  /** Revalidates the exact owned mode at the last safe point before the field can be touched. */
  suspend fun awaitApplied(): Boolean {
    val ready = !released && acquired && runCatching {
      PhoneAutomationServiceBridge.isViviControlCodeKeyboardModeSuppressed(expectedPackageName)
    }.getOrDefault(false)
    if (!ready) {
      onEvent(
        "keyboard_clamp_gate_failed",
        "request=$requestId owned_hidden_mode_not_confirmed"
      )
    }
    return ready
  }

  private suspend fun restoreMode(reason: String): Boolean {
    var restored = false
    var lastError = ""
    for (attempt in 0 until 2) {
      restored = runCatching {
        PhoneAutomationServiceBridge.restoreViviControlCodeKeyboardMode(expectedPackageName)
      }.getOrElse { error ->
        lastError = error::class.java.simpleName
        false
      }
      if (restored) {
        break
      }
      if (attempt == 0) {
        delay(40L)
      }
    }
    if (!restored) {
      onEvent(
        "keyboard_clamp_restore_failed",
        "request=$requestId reason=$reason authority=accessibility_soft_keyboard " +
          "error=${lastError.ifBlank { "unavailable" }}"
      )
    }
    return restored
  }

  suspend fun release(reason: String): Boolean = releaseMutex.withLock {
    if (released) {
      return true
    }
    val restored = if (acquired || suppressionMayBeOwned) {
      restoreMode(reason)
    } else {
      true
    }
    if (restored) {
      suppressionMayBeOwned = false
      acquired = false
      released = true
    }
    restored
  }
}
