package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TicketTracePrivacyTest {
  @Test
  fun keepsOnlyBoundedNumericAndBooleanFields() {
    val fields = TicketTracePrivacy.allowlistedFields(
      "viewer=account-42 reason=/data/local/private output=secret generation=9 ok=true duration_ms=250 " +
        "database_to_phone_ms=362 popup_open_ms=2997 popup_to_first_input_ms=935"
    )

    assertEquals(
      mapOf(
        "generation" to "9",
        "ok" to "true",
        "duration_ms" to "250",
        "database_to_phone_ms" to "362",
        "popup_open_ms" to "2997",
        "popup_to_first_input_ms" to "935"
      ),
      fields
    )
    assertFalse(fields.containsKey("viewer"))
    assertFalse(fields.containsKey("reason"))
    assertFalse(fields.containsKey("output"))
  }

  @Test
  fun rejectsNonNumericAndNonBooleanValuesForAllowedKeys() {
    val fields = TicketTracePrivacy.allowlistedFields(
      "bytes=12kb success=yes count=4;token stream_active=true"
    )

    assertEquals(mapOf("stream_active" to "true"), fields)
  }

  @Test
  fun durableEnumsRejectHelperOutputAndArbitraryEvents() {
    assertEquals("visible", TicketTracePrivacy.fixedValue("hardwareH264Visibility", "visible"))
    assertEquals(
      "unknown",
      TicketTracePrivacy.fixedValue("hardwareH264Visibility", "/data/local/private token=abc")
    )
    assertEquals("stream_started", TicketTracePrivacy.eventName("stream_started"))
    assertEquals("wake_recovery_action", TicketTracePrivacy.eventName("wake_recovery_action"))
    assertEquals("fast_public_open_root_proof", TicketTracePrivacy.eventName("fast_public_open_root_proof"))
    assertEquals("root_readiness", TicketTracePrivacy.eventName("root_readiness"))
    assertNull(TicketTracePrivacy.eventName("viewer_account_42"))
    assertNull(TicketTracePrivacy.eventName("stream_started token=abc"))
  }

  @Test
  fun durableScalarsAcceptOnlyTypedValues() {
    assertEquals("42", TicketTracePrivacy.numericValue("42"))
    assertEquals("-1", TicketTracePrivacy.numericValue("-1", allowNegative = true))
    assertEquals("", TicketTracePrivacy.numericValue("42ms"))
    assertEquals("true", TicketTracePrivacy.booleanValue("true"))
    assertEquals("", TicketTracePrivacy.booleanValue("yes"))
  }
}
