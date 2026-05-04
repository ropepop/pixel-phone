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
    val mainActivity = source("app/src/main/java/lv/jolkins/pixelorchestrator/app/MainActivity.kt")

    assertTrue(supervisor.contains("ACTION_REFRESH_TICKET_SERVICE"))
    assertTrue(supervisor.contains("syncTicketService(trigger = \"service_create\""))
    assertTrue(supervisor.contains("facade.startComponent(TICKET_SERVICE_COMPONENT)"))
    assertTrue(supervisor.contains("facade.stopComponent(TICKET_SERVICE_COMPONENT)"))
    assertTrue(supervisor.contains("probeTicketTunnelReadiness()"))
    assertTrue(mainActivity.contains("ticketServiceStore.setEnabled(enabled)"))
    assertTrue(mainActivity.contains("SupervisorService.ACTION_REFRESH_TICKET_SERVICE"))
  }

  @Test
  fun ticketStartKeepsUiClosedUnlessExplicitlyRequested() {
    val startScript = source("app/src/main/assets/runtime/entrypoints/pixel-ticket-start.sh")
    val healthScript = source("app/src/main/assets/runtime/entrypoints/pixel-ticket-health.sh")

    assertTrue(startScript.contains("TICKET_SCREEN_OPEN_ORCHESTRATOR_ON_START:-0"))
    assertTrue(startScript.contains("start_tunnel_loop_if_needed"))
    assertTrue(healthScript.contains("ticket-web-tunnel-service-loop.pid"))
    assertTrue(healthScript.contains("ticket-screen-cloudflared.pid"))
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
