package lv.jolkins.pixelorchestrator.app.phoneautomation

import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import kotlin.time.Duration.Companion.seconds

object PhonePortraitLock {
  data class EnsureResult(
    val verified: Boolean,
    val outcome: String,
    val durationMillis: Long
  )

  /**
   * The supervisor continuously maintains the portrait lock, so the common
   * Ticket cold-start path proves the live lock and, only when necessary,
   * repairs and proves it again inside one bounded root transaction.
   */
  suspend fun ensureVerified(rootExecutor: RootExecutor): Boolean {
    return ensureVerifiedResult(rootExecutor).verified
  }

  suspend fun ensureVerifiedResult(rootExecutor: RootExecutor): EnsureResult {
    val result = rootExecutor.runScript(ENSURE_VERIFIED_SCRIPT, 5.seconds)
    val outcome = result.stdout.lineSequence()
      .firstOrNull { it.startsWith(OUTCOME_PREFIX) }
      ?.removePrefix(OUTCOME_PREFIX)
      .orEmpty()
    val verified = result.ok && outcome in VERIFIED_OUTCOMES
    return EnsureResult(
      verified = verified,
      outcome = if (verified) outcome else OUTCOME_FAILED,
      durationMillis = result.durationMs.coerceAtLeast(0L)
    )
  }

  private const val OUTCOME_PREFIX = "portrait_lock_outcome="
  private const val OUTCOME_ALREADY_VERIFIED = "already_verified"
  private const val OUTCOME_REPAIRED = "repaired"
  private const val OUTCOME_FAILED = "failed"
  private val VERIFIED_OUTCOMES = setOf(OUTCOME_ALREADY_VERIFIED, OUTCOME_REPAIRED)

  private val ENSURE_VERIFIED_SCRIPT = """
    # phone_portrait_lock_ensure_verified
    portrait_lock_verified() {
      accel="${'$'}(settings get system accelerometer_rotation 2>/dev/null || true)"
      user="${'$'}(settings get system user_rotation 2>/dev/null || true)"
      window="${'$'}(
        dumpsys window displays 2>/dev/null |
          awk '
            /mCurrentRotation=ROTATION_0/ { rotation = 1 }
            /mUserRotationMode=USER_ROTATION_LOCKED/ { mode = 1 }
            /mFixedToUserRotation=true/ { fixed = 1 }
            rotation && mode && fixed { print "ok"; exit }
          ' || true
      )"
      ignore="${'$'}(cmd window get-ignore-orientation-request 2>/dev/null | grep -i 'true' || true)"
      [ "${'$'}accel" = "0" ] &&
        [ "${'$'}user" = "0" ] &&
        [ "${'$'}window" = "ok" ] &&
        [ -n "${'$'}ignore" ]
    }

    if portrait_lock_verified; then
      echo "${OUTCOME_PREFIX}${OUTCOME_ALREADY_VERIFIED}"
      exit 0
    fi

    cmd window set-ignore-orientation-request true >/dev/null 2>&1 || true
    cmd window fixed-to-user-rotation enabled >/dev/null 2>&1 || true
    cmd window user-rotation lock 0 >/dev/null 2>&1 || true
    settings put system accelerometer_rotation 0 >/dev/null 2>&1 || true
    settings put system user_rotation 0 >/dev/null 2>&1 || true

    if portrait_lock_verified; then
      echo "${OUTCOME_PREFIX}${OUTCOME_REPAIRED}"
      exit 0
    fi
    echo "${OUTCOME_PREFIX}${OUTCOME_FAILED}"
    exit 41
  """.trimIndent()
}
