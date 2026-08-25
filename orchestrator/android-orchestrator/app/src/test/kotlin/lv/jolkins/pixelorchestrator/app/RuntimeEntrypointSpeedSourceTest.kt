package lv.jolkins.pixelorchestrator.app

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeEntrypointSpeedSourceTest {
  @Test
  fun ticketStartOwnsOnlyThePrivateLocalRuntime() {
    val source = source("app/src/main/assets/runtime/entrypoints/pixel-ticket-start.sh")
    assertTrue(source.contains("pixel-ticket-health.sh"))
    assertTrue(source.contains("lv.jolkins.pixelorchestrator.action.TICKET_START_SERVER"))
    assertTrue(source.contains("wait_ready"))
    assertTrue(source.contains("pixel-ticket-lifecycle-lock.sh"))
    assertTrue(source.contains("ticket_lock_acquire \"${'$'}LOCK\""))
    assertFalse(source.contains("rm -rf \"${'$'}LOCK\""))
    assertFalse(source.contains("cloudflared"))
    assertFalse(source.contains("ticket-web-tunnel"))
  }

  @Test
  fun ticketStopRecoversOnlyOwnerCheckedStaleLocks() {
    val source = source("app/src/main/assets/runtime/entrypoints/pixel-ticket-stop.sh")
    val lock = source("app/src/main/assets/runtime/entrypoints/pixel-ticket-lifecycle-lock.sh")
    assertTrue(source.contains("ticket_lock_acquire \"${'$'}LOCK\""))
    assertTrue(source.trimEnd().endsWith("exit 0"))
    assertTrue(lock.contains("TICKET_LOCK_OWNER=\"${'$'}{TICKET_LOCK_DIR}/owner.pid\""))
    assertTrue(lock.contains("ticket_lock_owner_active"))
    assertTrue(lock.contains("ticket_lock_run_bounded"))
    assertTrue(lock.contains("timeout 1 \"${'$'}@\""))
    assertTrue(lock.contains("kill -0 \"${'$'}owner\""))
    assertTrue(lock.contains("rmdir \"${'$'}TICKET_LOCK_DIR\""))
    assertFalse(source.contains("rm -rf \"${'$'}LOCK\""))
    assertFalse(lock.contains("rm -rf"))
  }

  @Test
  fun ticketStopRestoresTheTwoValueSecureCaptureRecordBeforeClearingIt() {
    val source = source("app/src/main/assets/runtime/entrypoints/pixel-ticket-stop.sh")
    val restore = source.substringAfter("restore_secure_capture_state() {")
      .substringBefore("ticket_lock_acquire")

    assertTrue(restore.contains("saved_debuggable=${'$'}(sed -n '1p'"))
    assertTrue(restore.contains("saved_disable_secure_windows=${'$'}(sed -n '2p'"))
    assertTrue(restore.contains("settings delete secure disable_secure_windows"))
    assertTrue(restore.contains("settings put secure disable_secure_windows \"${'$'}saved_disable_secure_windows\""))
    assertTrue(restore.contains("resetprop ro.debuggable \"${'$'}saved_debuggable\""))
    assertTrue(restore.contains("current_debuggable=${'$'}(getprop ro.debuggable"))
    assertTrue(restore.contains("current_disable_secure_windows=${'$'}(settings get secure disable_secure_windows"))
    assertTrue(restore.contains("if [ ! -e \"${'$'}state_file\" ]; then"))
    assertTrue(restore.contains("settings put secure disable_secure_windows 0"))
    assertTrue(restore.contains("resetprop ro.debuggable 0"))
    assertTrue(restore.indexOf("[ \"${'$'}current_debuggable\" = \"${'$'}saved_debuggable\" ]") <
      restore.indexOf("rm -f \"${'$'}state_file\""))
    assertTrue(restore.indexOf("[ \"${'$'}current_disable_secure_windows\" = \"${'$'}saved_disable_secure_windows\" ]") <
      restore.indexOf("rm -f \"${'$'}state_file\""))
    val stopLifecycle = source.substringAfter("ticket_lock_acquire \"${'$'}LOCK\"")
    assertFalse(stopLifecycle.contains("settings put secure disable_secure_windows 0 >/dev/null"))
    assertTrue(source.contains("SECURE_CAPTURE_RESTORE_OK=0"))
    assertTrue(source.indexOf("restore_secure_capture_state || SECURE_CAPTURE_RESTORE_OK=0") >
      source.indexOf("if listening; then"))
    assertTrue(source.indexOf("restore_secure_capture_state || SECURE_CAPTURE_RESTORE_OK=0") >
      source.indexOf("if ticket_service_active; then"))
    assertTrue(source.contains("dumpsys activity services \"${'$'}{APP}/.app.ticket.TicketStreamService\""))
    assertFalse(source.contains("dumpsys activity services \"${'$'}{APP}/.app.SupervisorService\""))
  }

  private fun source(relative: String): String {
    val roots = listOf(Path.of(relative), Path.of("../$relative"), Path.of("../../$relative"))
    val path = roots.firstOrNull(Files::exists) ?: error("Missing source file for $relative")
    return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }
}
