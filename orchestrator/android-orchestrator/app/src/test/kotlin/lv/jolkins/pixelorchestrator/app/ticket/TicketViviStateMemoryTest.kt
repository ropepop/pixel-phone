package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertNull
import org.junit.Test

class TicketViviStateMemoryTest {
  @Test
  fun rootedTicketDetailHierarchyIsNeverRetained() {
    val memory = TicketViviStateMemory { 1_000L }
    val hierarchy = "<hierarchy package=\"lv.jolkins.vivi\" />"

    memory.record(
      state = TicketViviRecoveryState.TICKET_DETAIL,
      ticketId = "ticket-1",
      source = "root",
      reason = "cleanup_verified",
      hierarchy = hierarchy
    )

    assertNull(memory.current().hierarchy)
    assertNull(memory.recentTicketDetailHierarchyWithin(5_000L))
  }

  @Test
  fun laterVisualDetailObservationCannotReviveHierarchyProof() {
    val memory = TicketViviStateMemory { 1_000L }
    val hierarchy = "<hierarchy package=\"lv.jolkins.vivi\" />"

    memory.record(
      state = TicketViviRecoveryState.TICKET_DETAIL,
      ticketId = "ticket-1",
      source = "root",
      reason = "cleanup_verified",
      hierarchy = hierarchy
    )
    memory.record(
      state = TicketViviRecoveryState.TICKET_DETAIL,
      ticketId = "ticket-1",
      source = "root_h264_visual",
      reason = "raw_ticket_visual"
    )

    assertNull(memory.current().hierarchy)
    assertNull(memory.recentTicketDetailHierarchyWithin(5_000L))
  }

  @Test
  fun nonRootHierarchyCannotBecomeTheFastHandoffProof() {
    val memory = TicketViviStateMemory { 1_000L }
    memory.record(
      state = TicketViviRecoveryState.TICKET_DETAIL,
      ticketId = "ticket-1",
      source = "accessibility",
      reason = "accessibility_detail",
      hierarchy = "<hierarchy />"
    )

    assertNull(memory.recentTicketDetailHierarchyWithin(5_000L))
  }

  @Test
  fun clearingStateAlsoClearsTheRootedProof() {
    val memory = TicketViviStateMemory { 1_000L }
    memory.record(
      state = TicketViviRecoveryState.TICKET_DETAIL,
      ticketId = "ticket-1",
      source = "root",
      reason = "cleanup_verified",
      hierarchy = "<hierarchy />"
    )

    memory.clear("root", "force_reselect")

    assertNull(memory.recentTicketDetailHierarchyWithin(5_000L))
  }
}
