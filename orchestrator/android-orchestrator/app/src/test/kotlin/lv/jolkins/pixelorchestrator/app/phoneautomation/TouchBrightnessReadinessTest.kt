package lv.jolkins.pixelorchestrator.app.phoneautomation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchBrightnessReadinessTest {
  private val pixelTouchDevice = RootTouchDevice(
    path = "/dev/input/event2",
    name = "synaptics_tcm_touch",
    score = 109
  )

  @Test
  fun readyWhenRootBatteryPhysicalTouchAndPanelAreAvailable() {
    val result = TouchBrightnessReadiness.evaluate(
      TouchBrightnessReadinessSnapshot(
        rootAvailable = true,
        batteryUnrestricted = true,
        touchDevices = listOf(pixelTouchDevice),
        panelAvailable = true
      )
    )

    assertTrue(result.ready)
    assertEquals("Ready for physical touch brightness via synaptics_tcm_touch", result.detail)
  }

  @Test
  fun readyWhenRootWhitelistIsUsedAsBatteryUnrestrictedSignal() {
    val result = TouchBrightnessReadiness.evaluate(
      TouchBrightnessReadinessSnapshot(
        rootAvailable = true,
        batteryUnrestricted = true,
        touchDevices = listOf(pixelTouchDevice),
        panelAvailable = true
      )
    )

    assertTrue(result.ready)
    assertFalse(result.detail.contains("Unrestricted battery access is not enabled"))
  }

  @Test
  fun reportsEveryMissingPhysicalTouchRequirement() {
    val result = TouchBrightnessReadiness.evaluate(
      TouchBrightnessReadinessSnapshot(
        rootAvailable = false,
        batteryUnrestricted = false,
        touchDevices = emptyList(),
        panelAvailable = false
      )
    )

    assertFalse(result.ready)
    assertTrue(result.detail.contains("Root access is unavailable"))
    assertTrue(result.detail.contains("Unrestricted battery access is not enabled"))
    assertTrue(result.detail.contains("No touchscreen input device could be identified"))
    assertTrue(result.detail.contains("Panel brightness path is unavailable"))
  }
}
