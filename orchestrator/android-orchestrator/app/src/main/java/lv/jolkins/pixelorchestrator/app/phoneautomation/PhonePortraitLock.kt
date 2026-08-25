package lv.jolkins.pixelorchestrator.app.phoneautomation

import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import kotlin.time.Duration.Companion.seconds

object PhonePortraitLock {
  /**
   * The supervisor continuously maintains the portrait lock, so the common
   * Ticket cold-start path only needs to prove that the live lock is already
   * correct. If that proof fails, repair every required setting and require a
   * second live proof before allowing capture to start.
   */
  suspend fun ensureVerified(rootExecutor: RootExecutor): Boolean {
    if (verify(rootExecutor)) {
      return true
    }
    force(rootExecutor)
    return verify(rootExecutor)
  }

  suspend fun force(rootExecutor: RootExecutor): Boolean {
    val command = """
      cmd window set-ignore-orientation-request true >/dev/null 2>&1 || true
      cmd window fixed-to-user-rotation enabled >/dev/null 2>&1 || true
      cmd window user-rotation lock 0 >/dev/null 2>&1 || true
      settings put system accelerometer_rotation 0 >/dev/null 2>&1 || true
      settings put system user_rotation 0 >/dev/null 2>&1 || true
    """.trimIndent()
    return rootExecutor.runScript(command, 5.seconds).ok
  }

  suspend fun verify(rootExecutor: RootExecutor): Boolean {
    val command = """
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
      if [ "${'$'}accel" = "0" ] &&
        [ "${'$'}user" = "0" ] &&
        [ "${'$'}window" = "ok" ] &&
        [ -n "${'$'}ignore" ]; then
        echo ok
      fi
    """.trimIndent()
    val result = rootExecutor.runScript(command, 5.seconds)
    return result.ok && result.stdout.contains("ok")
  }
}
