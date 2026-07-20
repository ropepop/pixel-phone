package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets

class TicketServiceSourceTest {
  @Test
  fun ticketScreenAutoStartIsControlledByDurableToggle() {
    val appGraph = source("app/src/main/java/lv/jolkins/pixelorchestrator/app/AppGraph.kt")
    val supervisorEngine = source("supervisor/src/main/kotlin/lv/jolkins/pixelorchestrator/supervisor/SupervisorEngine.kt")
    val controller = source("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketServiceComponentController.kt")

    assertTrue(appGraph.contains("TicketServicePreferencesStore(appContext)"))
    assertTrue(appGraph.contains("TicketServiceComponentController(controller, ticketServiceStore)"))
    assertTrue(controller.contains("override suspend fun shouldAutoStart(): Boolean = settingsStore.load().enabled"))
    assertTrue(supervisorEngine.contains("controller is AutoStartAwareComponentController && !controller.shouldAutoStart()"))
    assertTrue(supervisorEngine.contains("auto-start disabled"))
  }

  @Test
  fun supervisorRefreshStartsAndStopsTicketServiceFromToggle() {
    val supervisor = source("app/src/main/java/lv/jolkins/pixelorchestrator/app/SupervisorService.kt")
    val dashboard = source("app/src/main/java/lv/jolkins/pixelorchestrator/app/dashboard/DashboardViewModel.kt")

    assertTrue(supervisor.contains("ACTION_REFRESH_TICKET_SERVICE"))
    assertTrue(supervisor.contains("syncTicketService(trigger = \"service_create\""))
    assertTrue(supervisor.contains("facade.startComponent(TICKET_SERVICE_COMPONENT)"))
    assertTrue(supervisor.contains("facade.stopComponent(TICKET_SERVICE_COMPONENT)"))
    assertTrue(supervisor.contains("public ingress is owned by kitty-gration"))
    assertTrue(dashboard.contains("ticketServiceStore.setEnabled(action.enabled)"))
    assertTrue(dashboard.contains("SupervisorService.ACTION_REFRESH_TICKET_SERVICE"))
  }

  @Test
  fun ticketStartKeepsUiClosedUnlessExplicitlyRequested() {
    val startScript = source("app/src/main/assets/runtime/entrypoints/pixel-ticket-start.sh")
    val healthScript = source("app/src/main/assets/runtime/entrypoints/pixel-ticket-health.sh")

    assertTrue(startScript.contains("TICKET_SCREEN_OPEN_ORCHESTRATOR_ON_START:-0"))
    assertTrue(startScript.contains("pixel-ticket-health.sh"))
    assertTrue(!startScript.contains("cloudflared"))
    assertTrue(!healthScript.contains("cloudflared"))
  }

  private fun source(relative: String): String {
    val roots = listOf(
      Path.of(relative),
      Path.of("../$relative"),
      Path.of("../../$relative")
    )
    val path = roots.firstOrNull { Files.exists(it) }
      ?: error("Missing source file for $relative")
    return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }
}
