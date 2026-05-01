package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class TicketStreamServiceSourceTest {
  @Test
  fun rootCaptureSessionEnablesSecureWindowBypassBeforeLaunchingVivi() {
    val source = ticketStreamServiceSource()
    val start = source.indexOf("if (rootCaptureAvailable) {")
    val end = source.indexOf("return@withLock TicketSessionResponse(ok = true", start)
    val block = source.substring(start, end)

    assertTrue(block.indexOf("enableSecureWindowCaptureBypass()") < block.indexOf("launchVivi()"))
    assertTrue(block.indexOf("enableSecureWindowCaptureBypass()") < block.indexOf("ensureEncoderIfPossible()"))
  }

  @Test
  fun serviceDestroyRestoresSecureWindowBypassBeforeClosingRootWorker() {
    val source = ticketStreamServiceSource()
    val start = source.indexOf("override fun onDestroy()")
    val end = source.indexOf("super.onDestroy()", start)
    val block = source.substring(start, end)

    assertTrue(block.contains("disableSecureWindowCaptureBypass()"))
    assertTrue(block.indexOf("disableSecureWindowCaptureBypass()") < block.indexOf("rootExecutor.close()"))
  }

  @Test
  fun healthReportsSecureWindowBypassState() {
    val source = ticketStreamServiceSource()

    assertTrue(source.contains("secureWindowCaptureBypassActive = secureWindowCaptureBypassActive"))
    assertTrue(source.contains("secureWindowCaptureBypassMessage = secureWindowCaptureBypassMessage"))
  }

  private fun ticketStreamServiceSource(): String {
    val path = listOf(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketStreamService.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketStreamService.kt")
    ).first(Files::exists)
    return String(
      Files.readAllBytes(path)
    )
  }
}
