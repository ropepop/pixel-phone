package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class TicketSpacetimeOptionalStringTest {
  @Test fun decodesProductionSqlColdRestartOptions() {
    assertEquals("stopping", ticketSpacetimeOptionalString(Json.parseToJsonElement("[0,\"stopping\"]")))
    assertEquals("", ticketSpacetimeOptionalString(Json.parseToJsonElement("[1,[]]")))
    assertEquals("", ticketSpacetimeOptionalString(null))
    assertEquals("confirmed", ticketSpacetimeOptionalString(Json.parseToJsonElement("{\"some\":\"confirmed\"}")))
    assertEquals("failed", ticketSpacetimeOptionalString(Json.parseToJsonElement("\"failed\"")))
  }
}
