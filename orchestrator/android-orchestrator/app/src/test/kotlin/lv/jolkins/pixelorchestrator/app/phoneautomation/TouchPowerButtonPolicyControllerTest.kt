package lv.jolkins.pixelorchestrator.app.phoneautomation

import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.rootexec.RootResult
import org.junit.Assert.*
import org.junit.Test

class TouchPowerButtonPolicyControllerTest {
  @Test fun absentOriginalIsPersistedBeforeOverrideAndRestoredByDeletion() = runTest {
    val store = Store()
    val root = Root("", "", "", "")
    root.onRun = { script ->
      if (script.startsWith("settings put")) assertEquals(TouchPowerButtonOriginalSetting(null), store.value)
    }
    val controller = AndroidTouchPowerButtonPolicyController(root, store)
    assertTrue(controller.acquire())
    assertTrue(controller.restore())
    assertTrue(root.scripts[2].startsWith("settings delete global power_button_short_press"))
    assertNull(store.value)
  }

  @Test fun serviceRestartRetainsOriginalValueInsteadOfCapturingOverride() = runTest {
    val store = Store()
    val root = Root("power_button_short_press=7\n", "", "", "", "power_button_short_press=7\n")
    assertTrue(AndroidTouchPowerButtonPolicyController(root, store).acquire())
    val restarted = AndroidTouchPowerButtonPolicyController(root, store)
    assertTrue(restarted.acquire())
    assertEquals(TouchPowerButtonOriginalSetting("7"), store.value)
    assertTrue(restarted.restore())
    assertEquals("settings put global power_button_short_press '7'", root.scripts[3])
    assertNull(store.value)
  }

  @Test fun failedDurableSaveDoesNotChangeAndroidPolicy() = runTest {
    val store = Store().apply { saveOk = false }
    val root = Root("")
    assertFalse(AndroidTouchPowerButtonPolicyController(root, store).acquire())
    assertEquals(1, root.scripts.size)
  }

  @Test fun failedOverrideRetainsOriginalForRecovery() = runTest {
    val store = Store()
    val root = Root("power_button_short_press=7\n", "").apply { failAt = 2 }
    assertFalse(AndroidTouchPowerButtonPolicyController(root, store).acquire())
    assertEquals(TouchPowerButtonOriginalSetting("7"), store.value)
  }

  @Test fun failedRestoreReadbackRetainsCheckpoint() = runTest {
    val store = Store().apply { value = TouchPowerButtonOriginalSetting(null) }
    val root = Root("", "power_button_short_press=0\n")
    assertFalse(AndroidTouchPowerButtonPolicyController(root, store).restore())
    assertNotNull(store.value)
  }

  @Test fun disableWithoutCheckpointDoesNotChangeUnownedSetting() = runTest {
    val root = Root()
    assertTrue(AndroidTouchPowerButtonPolicyController(root, Store()).restore())
    assertTrue(root.scripts.isEmpty())
  }

  private class Store : TouchPowerButtonPolicyCheckpoint {
    var value: TouchPowerButtonOriginalSetting? = null
    var saveOk = true
    override fun load() = value
    override fun save(setting: TouchPowerButtonOriginalSetting): Boolean {
      if (saveOk) value = setting
      return saveOk
    }
    override fun clear(): Boolean { value = null; return true }
  }

  private class Root(vararg initialOutputs: String) : RootExecutor {
    val scripts = mutableListOf<String>()
    val outputs = ArrayDeque(initialOutputs.toList())
    var failAt = -1
    var onRun: (String) -> Unit = {}
    override suspend fun isRootAvailable() = true
    override suspend fun run(command: String, timeout: Duration) = error("Unexpected command")
    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      scripts += script
      onRun(script)
      return RootResult(if (scripts.size == failAt) 1 else 0, outputs.removeFirst(), "", script, 1)
    }
  }
}
