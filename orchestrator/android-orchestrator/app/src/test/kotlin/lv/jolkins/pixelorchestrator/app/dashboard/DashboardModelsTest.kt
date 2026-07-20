package lv.jolkins.pixelorchestrator.app.dashboard

import lv.jolkins.pixelorchestrator.coreconfig.HealthSnapshot
import lv.jolkins.pixelorchestrator.coreconfig.ModuleHealthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardModelsTest {
  @Test
  fun healthySnapshotProducesHealthySummary() {
    val now = 1_000_000L
    assertEquals(
      DashboardHealthLevel.HEALTHY,
      dashboardHealthLevel(healthySnapshot(now / 1_000L), nowMillis = now)
    )
  }

  @Test
  fun disabledModuleRemainsVisibleButDoesNotDegradeSummary() {
    val now = 1_000_000L
    val snapshot = healthySnapshot(now / 1_000L).copy(
      trainBotHealthy = false,
      moduleHealth = healthySnapshot(now / 1_000L).moduleHealth +
        ("train_bot" to ModuleHealthState(healthy = false, status = "disabled"))
    )

    val modules = dashboardModules(snapshot)
    val train = modules.single { it.id == "train_bot" }
    assertEquals("Disabled", train.status)
    assertFalse(train.healthy == true)
    assertEquals(DashboardHealthLevel.HEALTHY, dashboardHealthLevel(snapshot, nowMillis = now))
  }

  @Test
  fun explicitFailureProducesFailedSummary() {
    val now = 1_000_000L
    val healthy = healthySnapshot(now / 1_000L)
    val snapshot = healthy.copy(
      moduleHealth = healthy.moduleHealth +
        ("ticket_screen" to ModuleHealthState(healthy = false, status = "error"))
    )

    assertEquals(DashboardHealthLevel.FAILED, dashboardHealthLevel(snapshot, nowMillis = now))
  }

  @Test
  fun oldSnapshotIsStaleBeforeItsValuesAreEvaluated() {
    val snapshot = healthySnapshot(generatedEpochSeconds = 1L)
    assertEquals(
      DashboardHealthLevel.STALE,
      dashboardHealthLevel(snapshot, nowMillis = 500_000L, staleAfterMillis = 120_000L)
    )
  }

  @Test
  fun frequencySelectionUsesExactOrNearestLowerValue() {
    val values = listOf(500L, 1_000L, 1_500L)
    assertEquals(2, nearestFrequencyIndex(values, 1_500L))
    assertEquals(1, nearestFrequencyIndex(values, 1_200L))
    assertEquals(2, nearestFrequencyIndex(values, 200L))
  }

  @Test
  fun recentActivityCombinesTelemetryNewestFirstAndCapsAtTwenty() {
    val local = (1L..12L).map { index ->
      DashboardActivityItem(index, "local $index", "detail", successful = true)
    }
    val telemetry = (13L..28L).map { index ->
      DashboardActivityItem(index, "telemetry $index", "queued", successful = null)
    }

    val visible = DashboardUiState(
      buildIdentity = "test",
      recentActivity = local,
      telemetryActivity = telemetry
    ).visibleActivity

    assertEquals(20, visible.size)
    assertEquals(28L, visible.first().recordedAtMillis)
    assertEquals(9L, visible.last().recordedAtMillis)
  }

  private fun healthySnapshot(generatedEpochSeconds: Long): HealthSnapshot {
    val healthyModule = ModuleHealthState(healthy = true, status = "healthy")
    return HealthSnapshot(
      generatedEpochSeconds = generatedEpochSeconds,
      rootGranted = true,
      dnsHealthy = true,
      remoteHealthy = true,
      managementHealthy = true,
      sshHealthy = true,
      vpnHealthy = true,
      trainBotHealthy = true,
      satiksmeBotHealthy = true,
      siteNotifierHealthy = true,
      subscriptionBotHealthy = true,
      ddnsHealthy = true,
      supervisorLoopHealthy = true,
      managementAuthHealthy = true,
      deployHealthy = true,
      supervisorHealthy = true,
      moduleHealth = mapOf(
        "cpu_frequency" to healthyModule,
        "ticket_screen" to healthyModule,
        "runtime_cleanup" to healthyModule
      )
    )
  }
}
