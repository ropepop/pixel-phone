package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketNotificationLockdownTest {
  @Test
  fun enableScriptDisablesRemoteNotificationSurfaces() {
    val script = TicketNotificationLockdown.enableScript()

    assertTrue(script.contains("cmd statusbar collapse"))
    assertTrue(script.contains("notification-peek"))
    assertTrue(script.contains("notification-icons"))
    assertTrue(script.contains("statusbar-expansion"))
    assertTrue(script.contains("quick-settings"))
    assertFalse(script.contains("home"))
    assertFalse(script.contains("recents"))
  }

  @Test
  fun disableScriptRestoresStatusBarFlags() {
    val script = TicketNotificationLockdown.disableScript()

    assertTrue(script.contains("cmd statusbar send-disable-flag none"))
    assertTrue(script.contains("cmd statusbar collapse"))
  }
}
