package lv.jolkins.pixelorchestrator.app.phoneautomation

import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import kotlin.time.Duration.Companion.seconds

object PhonePortraitLock {
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
      rotation="${'$'}(dumpsys window displays 2>/dev/null | grep -m1 'mCurrentRotation=ROTATION_0' || true)"
      mode="${'$'}(dumpsys window 2>/dev/null | grep -m1 'mUserRotationMode=USER_ROTATION_LOCKED' || true)"
      fixed="${'$'}(dumpsys window 2>/dev/null | grep -m1 'mFixedToUserRotation=true' || true)"
      ignore="${'$'}(cmd window get-ignore-orientation-request 2>/dev/null | grep -i 'true' || true)"
      if [ "${'$'}accel" = "0" ] &&
        [ "${'$'}user" = "0" ] &&
        [ -n "${'$'}rotation" ] &&
        [ -n "${'$'}mode" ] &&
        [ -n "${'$'}fixed" ] &&
        [ -n "${'$'}ignore" ]; then
        echo ok
      fi
    """.trimIndent()
    val result = rootExecutor.runScript(command, 5.seconds)
    return result.ok && result.stdout.contains("ok")
  }
}
