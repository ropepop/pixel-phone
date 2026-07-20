package lv.jolkins.pixelorchestrator.app.ticket

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketBrowserPageTest {
  @Test
  fun pixelLocalViewerIsRetiredWhilePrivateRuntimeInterfacesRemain() {
    val service = source("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketStreamService.kt")

    assertTrue(service.contains("Pixel-local viewer retired; use the public Ticket service"))
    assertFalse(service.contains("internal fun browserPage()"))
    assertFalse(service.contains("/api/v1/client-log"))
    assertTrue(service.contains("path == \"/api/v1/health\""))
    assertTrue(service.contains("path == \"/api/v1/session/start\""))
    assertTrue(service.contains("path == \"/api/v1/session/recover\""))
    assertTrue(service.contains("path == \"/api/v1/session/stop\""))
    assertTrue(service.contains("path == \"/api/v1/stream\""))
  }

  private fun source(relative: String): String {
    val roots = listOf(Path.of(relative), Path.of("../$relative"), Path.of("../../$relative"))
    val path = roots.firstOrNull(Files::exists) ?: error("Missing source file for $relative")
    return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }
}
