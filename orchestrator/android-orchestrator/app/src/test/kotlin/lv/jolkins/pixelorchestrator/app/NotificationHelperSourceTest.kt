package lv.jolkins.pixelorchestrator.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class NotificationHelperSourceTest {
  @Test
  fun foregroundNotificationUsesQuietChannelAndRetiresLegacyChannel() {
    val source = notificationHelperSource()

    assertTrue(source.contains("private const val LEGACY_CHANNEL_ID = \"stack_supervision\""))
    assertTrue(source.contains("private const val CHANNEL_ID = \"stack_supervision_quiet\""))
    assertTrue(source.contains("NotificationManager.IMPORTANCE_MIN"))
    assertTrue(source.contains("Notification.VISIBILITY_SECRET"))
    assertTrue(source.contains("setShowBadge(false)"))
    assertTrue(source.contains("setSound(null, null)"))
    assertTrue(source.contains("enableLights(false)"))
    assertTrue(source.contains("enableVibration(false)"))
    assertTrue(source.contains("deleteNotificationChannel(LEGACY_CHANNEL_ID)"))
    assertTrue(source.contains("setLocalOnly(true)"))
    assertTrue(source.contains("setSilent(true)"))
    assertTrue(source.contains("setOnlyAlertOnce(true)"))
    assertTrue(source.contains("setPriority(NotificationCompat.PRIORITY_MIN)"))
    assertTrue(source.contains("setVisibility(NotificationCompat.VISIBILITY_SECRET)"))
    assertTrue(source.contains("setCategory(NotificationCompat.CATEGORY_SERVICE)"))
    assertFalse(source.contains("NotificationManager.IMPORTANCE_LOW"))
  }

  @Test
  fun productionSourceDoesNotCreateToastOrSnackbarPopups() {
    val sourceRoot = firstExistingPath(
      Path.of("app/src/main/java"),
      Path.of("src/main/java")
    )
    val forbidden = Regex("""\bToast\b|makeText\s*\(|\bSnackbar\b""")
    val offenders = mutableListOf<String>()
    val paths = Files.walk(sourceRoot)
    try {
      paths
        .filter { Files.isRegularFile(it) }
        .filter { it.toString().endsWith(".kt") || it.toString().endsWith(".java") }
        .forEach { path ->
          val content = String(Files.readAllBytes(path), Charsets.UTF_8)
          if (forbidden.containsMatchIn(content)) offenders += path.toString()
        }
    } finally {
      paths.close()
    }

    assertTrue("production popup call sites found: ${offenders.joinToString()}", offenders.isEmpty())
  }

  private fun notificationHelperSource(): String {
    val path = firstExistingPath(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/NotificationHelper.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/NotificationHelper.kt")
    )
    return String(Files.readAllBytes(path), Charsets.UTF_8)
  }

  private fun firstExistingPath(vararg paths: Path): Path {
    return paths.firstOrNull { Files.exists(it) } ?: error("missing source path: ${paths.joinToString()}")
  }
}
