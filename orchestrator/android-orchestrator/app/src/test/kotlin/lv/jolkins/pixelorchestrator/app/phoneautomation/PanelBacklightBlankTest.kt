package lv.jolkins.pixelorchestrator.app.phoneautomation

import android.content.ContextWrapper
import lv.jolkins.pixelorchestrator.app.ticket.TicketActionPanelDarkLease
import java.nio.file.Files
import java.io.File
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.rootexec.RootResult

class PanelBacklightBlankTest {
  @Test fun blankAndUnblankPreserveBrightnessRequests() {
    withPanel { root, panel ->
      assertEquals(0, execute(root, ScreenBrightnessControl.buildPanelBlankScript(true)))
      assertEquals("4", File(panel, "bl_power").readText().trim())
      assertEquals("500", File(panel, "brightness").readText().trim())
      File(panel, "brightness").writeText("1000")
      assertEquals("4", File(panel, "bl_power").readText().trim())
      assertEquals(0, execute(root, ScreenBrightnessControl.buildPanelBlankScript(false)))
      assertEquals("0", File(panel, "bl_power").readText().trim())
      assertEquals("1000", File(panel, "brightness").readText().trim())
    }
  }

  @Test fun controllerBlanksAndWritesRequestedZeroInOneRootCommand() = runTest {
    withPanel { root, panel ->
      val executor = LocalPanelRoot(root)
      val controller = AndroidTouchBrightnessDeviceController(ContextWrapper(null), executor)
      assertTrue(controller.setBrightnessPercent(0).success)
      assertEquals(1, executor.scripts.size)
      assertEquals("4", File(panel, "bl_power").readText().trim())
      assertEquals("0", File(panel, "brightness").readText().trim())
      assertFalse(executor.scripts.single().contains("settings "))
      assertFalse(executor.scripts.single().contains("sleep "))
    }
  }

  @Test fun missingBlankControlFailsWithoutChangingBrightness() = runTest {
    withPanel { root, panel ->
      File(panel, "bl_power").delete()
      val controller = AndroidTouchBrightnessDeviceController(ContextWrapper(null), LocalPanelRoot(root))
      assertFalse(controller.clampPanelSleepImmediately().success)
      assertEquals("500", File(panel, "brightness").readText().trim())
    }
  }

  @Test fun failedZeroWriteKeepsPanelBlankAndDoesNotClaimSuccess() = runTest {
    withPanel { root, panel ->
      File(panel, "brightness").delete()
      File(panel, "brightness").mkdir()
      val controller = AndroidTouchBrightnessDeviceController(ContextWrapper(null), LocalPanelRoot(root))
      assertFalse(controller.clampPanelSleepForWake().success)
      assertEquals("4", File(panel, "bl_power").readText().trim())
    }
  }

  @Test fun actionProofAcceptsBlankGateDespitePositiveBrightnessRequests() {
    withPanel { root, panel ->
      File(panel, "bl_power").writeText("4\n")
      File(panel, "actual_brightness").writeText("1000\n")
      assertEquals(0, execute(root, TicketActionPanelDarkLease.panelDarkReadbackScript()))
      File(panel, "bl_power").writeText("0\n")
      assertNotEquals(0, execute(root, TicketActionPanelDarkLease.panelDarkReadbackScript()))
      File(panel, "bl_power").writeText("invalid\n")
      assertNotEquals(0, execute(root, TicketActionPanelDarkLease.panelDarkReadbackScript()))
    }
  }

  @Test fun actionProofWithoutBlankStillRequiresBothRawReadbacksToBeZero() {
    withPanel { root, panel ->
      File(panel, "brightness").writeText("0\n")
      File(panel, "actual_brightness").writeText("1\n")
      assertNotEquals(0, execute(root, TicketActionPanelDarkLease.panelDarkReadbackScript()))
      File(panel, "actual_brightness").writeText("0\n")
      assertEquals(0, execute(root, TicketActionPanelDarkLease.panelDarkReadbackScript()))
    }
  }

  @Test fun readbackDistinguishesRequestedBrightnessFromBlankState() {
    val state = ScreenBrightnessControl.parseState("panel_brightness=1000\npanel_actual_brightness=1000\npanel_backlight_power=4")!!
    assertEquals(1000, state.panelActualBrightness)
    assertEquals(4, state.panelBacklightPower)
  }

  private inline fun withPanel(block: (File, File) -> Unit) {
    val root = Files.createTempDirectory("panel-blank-test-").toFile()
    try {
      val panel = File(root, "panel0-backlight").apply { mkdir() }
      File(panel, "brightness").writeText("500")
      File(panel, "max_brightness").writeText("4095")
      File(panel, "bl_power").writeText("0")
      block(root, panel)
    } finally { root.deleteRecursively() }
  }

  private class LocalPanelRoot(private val root: File) : RootExecutor {
    val scripts = mutableListOf<String>()
    override suspend fun isRootAvailable() = true
    override suspend fun run(command: String, timeout: Duration): RootResult = error("unexpected")
    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      scripts += script
      return RootResult(execute(root, script), "", "", "panel test", 0L)
    }
  }

  companion object {
    private fun execute(root: File, script: String): Int {
      val process = ProcessBuilder("sh", "-c", script.replace("/sys/class/backlight", root.absolutePath))
        .redirectErrorStream(true).start()
      process.inputStream.readBytes()
      return process.waitFor()
    }
  }
}
