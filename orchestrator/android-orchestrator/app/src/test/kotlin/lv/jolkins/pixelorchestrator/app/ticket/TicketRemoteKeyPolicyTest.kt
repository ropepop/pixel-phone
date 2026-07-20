package lv.jolkins.pixelorchestrator.app.ticket

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Test

class TicketRemoteKeyPolicyTest {
  @Test
  fun arbitraryBrowserKeysAndTapsAreNotAccepted() {
    val service = source("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketStreamService.kt")

    assertFalse(service.contains("\"tap\" ->"))
    assertFalse(service.contains("\"key\" ->"))
    assertFalse(service.contains("private suspend fun handleRemoteKey"))
    assertFalse(service.contains("private suspend fun tap(inputId"))
  }

  private fun source(relative: String): String {
    val roots = listOf(Path.of(relative), Path.of("../$relative"), Path.of("../../$relative"))
    val path = roots.firstOrNull(Files::exists) ?: error("Missing source file for $relative")
    return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }
}
