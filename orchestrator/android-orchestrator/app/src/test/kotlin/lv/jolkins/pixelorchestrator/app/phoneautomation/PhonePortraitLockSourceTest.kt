package lv.jolkins.pixelorchestrator.app.phoneautomation

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePortraitLockSourceTest {
  @Test
  fun portraitVerificationRequiresLiveWindowManagerLockState() {
    val source = readFirstExisting(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhonePortraitLock.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhonePortraitLock.kt")
    )
    val force = source.substringBetween("suspend fun force", "suspend fun verify")
    val verify = source.substringBetween("suspend fun verify", "\n}")

    assertTrue(force.contains("cmd window user-rotation lock 0"))
    assertTrue(force.contains("cmd window fixed-to-user-rotation enabled"))
    assertTrue(force.contains("settings put system accelerometer_rotation 0"))
    assertTrue(force.contains("settings put system user_rotation 0"))
    assertTrue(verify.contains("mCurrentRotation=ROTATION_0"))
    assertTrue(verify.contains("mUserRotationMode=USER_ROTATION_LOCKED"))
    assertTrue(verify.contains("mFixedToUserRotation=true"))
    assertTrue(verify.contains("get-ignore-orientation-request"))
    assertFalse(verify.contains("USER_ROTATION_FREE"))
    assertFalse(source.contains("USER_ROTATION_FREE"))
    assertFalse(source.contains("accelerometer_rotation 1"))
    assertFalse(source.contains("set-ignore-orientation-request false"))
  }

  private fun String.substringBetween(startNeedle: String, endNeedle: String): String {
    val start = indexOf(startNeedle)
    assertTrue("missing start needle: $startNeedle", start >= 0)
    val end = indexOf(endNeedle, start + startNeedle.length)
    assertTrue("missing end needle: $endNeedle", end >= 0)
    return substring(start, end)
  }

  private fun readFirstExisting(vararg paths: Path): String {
    for (path in paths) {
      if (Files.exists(path)) {
        return String(Files.readAllBytes(path))
      }
    }
    error("none of the source paths exist: ${paths.joinToString()}")
  }
}
