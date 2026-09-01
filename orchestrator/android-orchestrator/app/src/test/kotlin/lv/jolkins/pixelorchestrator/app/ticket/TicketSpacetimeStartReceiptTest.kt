package lv.jolkins.pixelorchestrator.app.ticket

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketSpacetimeStartReceiptTest {
  @Test
  fun receiptDetailsContainOnlyBoundedTimingAndTheWinningLane() {
    val subscription = Json.parseToJsonElement(
      ticketSpacetimeStartReceiptDetailJson(
        lane = TICKET_DURABLE_COMMAND_LANE_SUBSCRIPTION,
        databaseToPhoneMillis = 347L
      )
    ).jsonObject
    assertEquals(setOf("commandType", "databaseToPhoneMillis", "lane"), subscription.keys)
    assertEquals("start", subscription.getValue("commandType").jsonPrimitive.content)
    assertEquals("347", subscription.getValue("databaseToPhoneMillis").jsonPrimitive.content)
    assertEquals("live_subscription", subscription.getValue("lane").jsonPrimitive.content)

    val rejected = Json.parseToJsonElement(
      ticketSpacetimeStartReceiptDetailJson(
        lane = "viewer/account-42 payload=secret",
        databaseToPhoneMillis = -50L
      )
    ).jsonObject
    assertEquals("unknown", rejected.getValue("lane").jsonPrimitive.content)
    assertEquals("-1", rejected.getValue("databaseToPhoneMillis").jsonPrimitive.content)
  }

  @Test
  fun bothDurableLanesRecordOnlyInsideTheExistingWinningHandoff() {
    val source = workerSource()
    val pollHandoff = source.indexOf("else -> subscribedCommandHandoff.fromPoll(command.id) {")
    val pollReceipt = source.indexOf("noteDurableStartCommandReceipt(", pollHandoff)
    val pollHandler = source.indexOf("service.handleTicketSpacetimeCommand(command)", pollHandoff)
    assertTrue(pollHandoff >= 0)
    assertTrue(pollReceipt > pollHandoff)
    assertTrue(pollHandler > pollReceipt)

    val genericSubscription = source.indexOf("if (command.commandType != \"keyframe\") {")
    val subscriptionHandoff = source.indexOf(
      "subscribedCommandHandoff.fromSubscription(command.id) {",
      genericSubscription
    )
    val subscriptionReceipt = source.indexOf("noteDurableStartCommandReceipt(", subscriptionHandoff)
    val subscriptionHandler = source.indexOf(
      "service.handleTicketSpacetimeCommand(command)",
      subscriptionHandoff
    )
    assertTrue(genericSubscription >= 0)
    assertTrue(subscriptionHandoff > genericSubscription)
    assertTrue(subscriptionReceipt > subscriptionHandoff)
    assertTrue(subscriptionHandler > subscriptionReceipt)

    assertEquals(
      1,
      Regex("service\\.noteStartupStartCommandReceived\\(").findAll(source).count()
    )
    assertEquals(
      1,
      Regex("\"pixel_direct_start_command_received\"").findAll(source).count()
    )
  }

  private fun workerSource(): String {
    val candidates = listOf(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketSpacetimeWorker.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketSpacetimeWorker.kt")
    )
    val path = candidates.firstOrNull(Files::exists)
      ?: error("TicketSpacetimeWorker.kt not found")
    return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }
}
