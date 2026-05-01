package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TicketViviPageEnforcerTest {
  @Test
  fun leavesTicketDetailAlone() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="KONTROLES KODS" clickable="true" bounds="[53,264][450,390]" />
        <node package="com.pv.vivi" content-desc="ZONAS" bounds="[68,434][190,486]" />
        <node package="com.pv.vivi" content-desc="PV-ELB-20260423-0RJB2M" bounds="[119,1329][961,1423]" />
      </hierarchy>
    """.trimIndent()

    assertNull(TicketViviPageEnforcer.actionForHierarchy(xml))
  }

  @Test
  fun opensVisibleTicketCardFromTicketsList() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="Manas biļetes" bounds="[288,158][792,270]" />
        <node package="com.pv.vivi" content-desc="Olaine - Rīga&#10;30 dienu biļete&#10;DERĪGA POSMĀ&#10;Cena - Rīga&#10;DERĪGA&#10;23.04.2026 - 22.05.2026&#10;B&#10;A" clickable="true" bounds="[0,536][1080,1011]" />
        <node package="com.pv.vivi" content-desc="Tickets&#10;2. cilne no 4" clickable="true" selected="true" bounds="[270,2209][540,2361]" />
      </hierarchy>
    """.trimIndent()

    val action = TicketViviPageEnforcer.actionForHierarchy(xml)

    assertEquals("open_ticket_card", action?.reason)
    assertEquals(540, action?.x)
    assertEquals(773, action?.y)
  }

  @Test
  fun opensTicketsTabWhenOnAnotherViviTab() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="Maršruta plānošana" bounds="[200,300][900,390]" />
        <node package="com.pv.vivi" content-desc="Tickets&#10;2. cilne no 4" clickable="true" bounds="[270,2209][540,2361]" />
      </hierarchy>
    """.trimIndent()

    val action = TicketViviPageEnforcer.actionForHierarchy(xml)

    assertEquals("open_tickets_tab", action?.reason)
    assertEquals(405, action?.x)
    assertEquals(2285, action?.y)
  }
}
