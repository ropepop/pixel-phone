package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketVideoConnectionOwnerTest {
  @Test fun lateSocketAndCloseCannotReplaceNewConnection() {
    val owner = TicketVideoConnectionOwner()
    assertTrue(owner.bind(2))
    assertFalse(owner.bind(1))
    owner.release(1)
    assertTrue(owner.isCurrent(2))
    assertTrue(owner.bind(3))
    owner.release(2)
    assertFalse(owner.isCurrent(2))
    assertTrue(owner.isCurrent(3))
    owner.clear()
    assertFalse(owner.isCurrent(3))
    assertFalse(owner.bind(3))
    assertTrue(owner.bind(4))
  }
}
