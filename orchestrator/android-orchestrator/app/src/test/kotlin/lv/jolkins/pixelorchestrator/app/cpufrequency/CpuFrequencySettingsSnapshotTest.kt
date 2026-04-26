package lv.jolkins.pixelorchestrator.app.cpufrequency

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuFrequencySettingsSnapshotTest {
  private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

  @Test
  fun legacyCpuOnlySnapshotsLoadWithGpuDefaults() {
    val legacyJson = """
      {
        "enabled": true,
        "desiredPolicyMaxKHz": {
          "policy0": 1950000
        },
        "runtimeState": "ENFORCING",
        "runtimeDetail": "Applying CPU caps"
      }
    """.trimIndent()

    val snapshot = json.decodeFromString<CpuFrequencySettingsSnapshot>(legacyJson)

    assertTrue(snapshot.enabled)
    assertEquals(1950000L, snapshot.desiredCap(CpuFrequencyCluster.LITTLE))
    assertEquals(null, snapshot.desiredGpuCap())
    assertFalse(snapshot.liveSnapshot.gpu.available)
  }

  @Test
  fun profileSummaryIncludesGpuWhenDesiredCapIsSet() {
    val snapshot = CpuFrequencySettingsSnapshot(
      enabled = true,
      desiredPolicyMaxKHz = mapOf(CpuFrequencyCluster.LITTLE.policyId to 1_950_000L),
      desiredGpuMaxKHz = 649_000L,
      runtimeDetail = "Applying CPU and GPU caps"
    )

    val summary = snapshot.notificationSummary()

    assertTrue(summary.contains("little 1.95 GHz"))
    assertTrue(summary.contains("gpu 649 MHz"))
    assertTrue(summary.contains("Applying CPU and GPU caps"))
  }

  @Test
  fun profileSummaryOmitsGpuWhenNoGpuCapOrLiveGpuIsPresent() {
    val snapshot = CpuFrequencySettingsSnapshot(
      enabled = true,
      desiredPolicyMaxKHz = mapOf(CpuFrequencyCluster.BIG.policyId to 3_105_000L),
      runtimeDetail = "Applying CPU and GPU caps"
    )

    val summary = snapshot.profileSummary()

    assertTrue(summary.contains("big 3.11 GHz"))
    assertFalse(summary.contains("gpu"))
  }
}
