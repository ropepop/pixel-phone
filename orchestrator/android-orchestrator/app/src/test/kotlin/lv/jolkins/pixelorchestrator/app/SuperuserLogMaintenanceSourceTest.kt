package lv.jolkins.pixelorchestrator.app

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperuserLogMaintenanceSourceTest {
  @Test
  fun foregroundSupervisorRunsFrequentMaintenanceImmediatelyAndHourly() {
    val source = String(
      Files.readAllBytes(Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/SupervisorService.kt")),
      StandardCharsets.UTF_8
    )

    assertTrue(source.contains("startFrequentMaintenance(AppGraph.facade(this))"))
    assertTrue(source.contains("facade.runFrequentMaintenance()"))
    assertTrue(source.indexOf("facade.runFrequentMaintenance()") < source.indexOf("delay(FREQUENT_MAINTENANCE_INTERVAL_MILLIS)"))
    assertTrue(source.contains("delay(FREQUENT_MAINTENANCE_DEFERRED_RETRY_MILLIS)"))
    assertTrue(source.contains("60L * 60L * 1_000L"))
  }
}
