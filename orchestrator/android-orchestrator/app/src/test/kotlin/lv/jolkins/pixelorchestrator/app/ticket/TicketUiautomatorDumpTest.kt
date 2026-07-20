package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketUiautomatorDumpTest {
  @Test
  fun dumpCommandRemovesTheFileOnEveryExitPath() {
    val command = TicketUiautomatorDump.command("/data/local/tmp/rs-direct-window.xml", 5_000)

    assertTrue(command.contains("trap '/system/bin/rm -f"))
    assertTrue(command.contains("EXIT HUP INT TERM"))
    assertTrue(command.contains("/system/bin/cat"))
  }

  @Test
  fun startupSweepContainsOnlyKnownDumpFiles() {
    val command = TicketUiautomatorDump.startupSweepCommand()

    assertTrue(command.contains("/sdcard/pixel-ticket-window.xml"))
    assertTrue(command.contains("/data/local/tmp/pixel-vivi-fast-return-window.xml"))
    assertTrue(command.contains("/data/local/tmp/rs-direct-window.xml"))
    assertFalse(command.contains("*.xml"))
    assertFalse(command.contains("rm -rf"))
  }
}
