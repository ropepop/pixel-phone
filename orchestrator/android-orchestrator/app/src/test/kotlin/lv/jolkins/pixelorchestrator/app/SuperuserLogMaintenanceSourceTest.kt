package lv.jolkins.pixelorchestrator.app

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperuserLogMaintenanceSourceTest {
  @Test
  fun foregroundSupervisorRunsFrequentMaintenanceAfterStartupGraceAndHourly() {
    val source = source(
      "src/main/java/lv/jolkins/pixelorchestrator/app/SupervisorService.kt",
      "app/src/main/java/lv/jolkins/pixelorchestrator/app/SupervisorService.kt"
    )

    assertTrue(source.contains("startFrequentMaintenance(AppGraph.facade(this))"))
    assertTrue(source.contains("facade.runFrequentMaintenance()"))
    assertTrue(source.indexOf("facade.runFrequentMaintenance()") < source.indexOf("delay(FREQUENT_MAINTENANCE_INTERVAL_MILLIS)"))
    assertTrue(source.contains("delay(FREQUENT_MAINTENANCE_DEFERRED_RETRY_MILLIS)"))
    assertTrue(source.contains("60L * 60L * 1_000L"))
  }

  @Test
  fun standardDeployDispatchesBeforeFrequentMaintenanceCanOwnMutationLock() {
    val supervisor = source(
      "src/main/java/lv/jolkins/pixelorchestrator/app/SupervisorService.kt",
      "app/src/main/java/lv/jolkins/pixelorchestrator/app/SupervisorService.kt"
    )
    val deploy = source(
      "../scripts/android/deploy_orchestrator_apk.sh",
      "../../scripts/android/deploy_orchestrator_apk.sh",
      "orchestrator/scripts/android/deploy_orchestrator_apk.sh"
    )

    val preDispatchBudget = constantMillis(
      supervisor,
      "STANDARD_DEPLOY_PRE_DISPATCH_BUDGET_MILLIS"
    )
    val startupMargin = constantMillis(
      supervisor,
      "FREQUENT_MAINTENANCE_STARTUP_MARGIN_MILLIS"
    )

    assertEquals(10_000L, preDispatchBudget)
    assertEquals(5_000L, startupMargin)
    assertEquals(15_000L, preDispatchBudget + startupMargin)
    assertTrue(
      supervisor.contains(
        "STANDARD_DEPLOY_PRE_DISPATCH_BUDGET_MILLIS + FREQUENT_MAINTENANCE_STARTUP_MARGIN_MILLIS"
      )
    )

    // This is the real standard deployment sequence that exposed the race: a fresh service can
    // start after force-stop while permission readiness is still doing three bounded one-second
    // attempts, before the explicit foreground-service action is finally dispatched.
    val forceStop = deploy.indexOf("am force-stop \${PKG}")
    val permissionReadiness = deploy.indexOf("repair_phone_automation_permissions || true")
    val dispatch = deploy.indexOf("\ndispatch_orchestrator_action\n")
    assertTrue(forceStop >= 0)
    assertTrue(permissionReadiness > forceStop)
    assertTrue(dispatch > permissionReadiness)
    assertTrue(deploy.contains("for attempt in 1 2 3; do"))
    assertTrue(deploy.contains("sleep 1"))
  }

  private fun source(vararg candidates: String): String {
    val path = candidates.map(Path::of).first(Files::exists)
    return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }

  private fun constantMillis(source: String, name: String): Long {
    val value = Regex("""private const val $name = ([0-9_]+)L""")
      .find(source)
      ?.groupValues
      ?.get(1)
      ?: error("missing constant: $name")
    return value.replace("_", "").toLong()
  }
}
