package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketRemoteKeyPolicyTest {
  @Test
  fun mapsSupportedKeysToAndroidInputCommands() {
    assertEquals("input text A", TicketRemoteKeyPolicy.commandFor("A"))
    assertEquals("input text 7", TicketRemoteKeyPolicy.commandFor("7"))
    assertEquals("input keyevent KEYCODE_DEL", TicketRemoteKeyPolicy.commandFor("Backspace"))
    assertEquals("input keyevent KEYCODE_FORWARD_DEL", TicketRemoteKeyPolicy.commandFor("Delete"))
    assertEquals("input keyevent KEYCODE_ENTER", TicketRemoteKeyPolicy.commandFor("Enter"))
  }

  @Test
  fun rejectsUnsupportedKeysAndTreatsEscapeAsCloseRequest() {
    assertTrue(TicketRemoteKeyPolicy.isCloseRequest("Escape"))
    assertNull(TicketRemoteKeyPolicy.commandFor("Escape"))
    assertNull(TicketRemoteKeyPolicy.commandFor(" "))
    assertNull(TicketRemoteKeyPolicy.commandFor("ArrowLeft"))
    assertNull(TicketRemoteKeyPolicy.commandFor(";"))
  }
}
