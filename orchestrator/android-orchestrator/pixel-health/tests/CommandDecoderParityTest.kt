package lv.jolkins.pixelorchestrator.app.ticket

import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CommandDecoderParityTest {
  private var comparisons = 0
  private val mismatches = mutableListOf<String>()
  private val now = Instant.parse("2026-09-29T12:00:00.123456789Z")
  private val fields = listOf("id", "ticketId", "backendId", "commandType", "status", "revision", "reason", "payloadJson", "createdAt", "updatedAt", "expiresAt")
  private fun row(changes: Map<String, String> = emptyMap(), array: Boolean = false): String {
    val values = listOf("command", "ticket", "pixel", "start", "pending", "rev", "owner", "{}", "2026-09-29T00:00:00Z", "2026-09-29T00:00:00Z", "2099-01-01T00:00:00Z")
    val mapped = fields.zip(values).toMap() + changes
    return if (array) JsonArray(fields.map { JsonPrimitive(mapped.getValue(it)) }).toString()
      else JsonObject(mapped.mapValues { JsonPrimitive(it.value) }).toString()
  }
  private fun message(rows: List<String>, table: String = "ticketremote_service_stream_command", deletes: List<String> = emptyList(), initial: Boolean = false, request: Int = 0): String {
    val update = buildJsonObject { put("tables", buildJsonArray { add(buildJsonObject {
      put("table_name", table); put("updates", buildJsonArray { add(buildJsonObject { put("Uncompressed", buildJsonObject {
        put("inserts", JsonArray(rows.map(::JsonPrimitive))); put("deletes", JsonArray(deletes.map(::JsonPrimitive)))
      }) }) })
    }) }) }
    return buildJsonObject { if (initial) put("InitialSubscription", buildJsonObject { put("request_id", request); put("database_update", update) })
      else put("TransactionUpdateLight", buildJsonObject { put("update", update) }) }.toString()
  }
  private fun compare(raw: String) {
    comparisons++
    val old = runCatching { legacyParseCommandMessage(raw, "ticket", "pixel", now = now) }
    val current = runCatching { parseTicketSpacetimeCommandSubscriptionMessage(raw, "ticket", "pixel", now = now) }
    if (old.isSuccess != current.isSuccess || (old.isSuccess && old.getOrThrow() != current.getOrNull())) {
      mismatches += "length=${raw.length} old=${old.getOrNull()} new=${current.getOrNull()} input=${raw.take(1200)}"
    }
    current.exceptionOrNull()?.let { assertTrue(it.toString().let { text -> !text.contains("synthetic-secret") && !text.contains("2099-") }) }
  }
  @Test fun frozenDecoderContract() {
    listOf("", "null", "[]", "{", "{}", "{\"IdentityToken\":\"synthetic-secret\"}", "{\"SubscriptionError\":{}}", "{\"TransactionUpdate\":{\"status\":{\"Failed\":{}}}}", "{\"TransactionUpdateLight\":{}}", "{\"InitialSubscription\":{\"request_id\":0,\"database_update\":{}}}").forEach(::compare)
    val identity = parseTicketSpacetimeCommandSubscriptionMessage("{\"IdentityToken\":\"synthetic-secret\"}", "ticket", "pixel", now = now)
    assertEquals(TicketSpacetimeCommandSubscriptionMessageKind.IDENTITY, identity.kind)
    assertFalse(identity.toString().contains("synthetic-secret"))
    compare(message(emptyList(), initial = true)); compare(message(listOf(row()), initial = true, request = 1))
    for (array in listOf(false, true)) {
      compare(message(listOf(row(array = array))))
      for ((field, values) in mapOf("id" to listOf("", " ", "x".repeat(512), "x".repeat(513), "😀".repeat(256), "😀".repeat(257)), "ticketId" to listOf("foreign"), "backendId" to listOf("foreign"), "status" to listOf("done"), "commandType" to listOf("start", "cold_stop", "ticket_action_v3", "generate_control_code", "control_code_browser_capture", "vivi_reauth", "keyframe"), "payloadJson" to listOf("", " ", "x".repeat(8192), "x".repeat(8193), "€".repeat(2731)), "expiresAt" to listOf("", "broken", now.toString(), now.plusNanos(1).toString(), now.minusNanos(1).toString(), "2026-09-29T14:00:00.123456790+02:00"))) {
        for (value in values) compare(message(listOf(row(mapOf(field to value), array))))
      }
    }
    for (expiry in listOf("2026-09-29t12:00:00.123456790z", "2026-09-29T24:00:00Z", "2026-09-29T23:59:60Z", "2026-09-29T12:00:00.123456790+00:00", "+10000-01-01T00:00:00Z", "2026-09-29T12:00:00.123456790Z ", "2026-09-29T24:00:00.000000001Z", "2026-09-29T12:30:60Z", "+999999999-12-31T23:59:59Z", "-999999999-01-01T00:00:00Z", "2099-01-01T00:00:00+18:00", "2099-01-01T00:00:00+18:00:01", "2099-01-01T00:00:00+23:00", "2099-01-01T00:00:00-23:00")) compare(message(listOf(row(mapOf("expiresAt" to expiry)))))
    for (value in listOf("\u2003", "\u00a0", "\u0000", "😀", "é", "e\u0301")) compare(message(listOf(row(mapOf("id" to value, "reason" to value)))))
    for (request in listOf("0", "-0", "0.0", "0e0", "\"0\"", "2147483648", "null", "false")) compare(message(emptyList(), initial = true).replace("\"request_id\":0", "\"request_id\":$request"))
    compare(message(listOf("broken", "null", "[]", "{}", row().replace("\"start\"", "3"), row())))
    compare(message(emptyList(), deletes = listOf(row(), "[\"command\"]")))
    for (invalid in listOf("broken", "null", "[]", "{}", "x".repeat(16385))) compare(message(emptyList(), deletes = listOf(invalid)))
    compare(message(List(128) { row(mapOf("id" to "command-$it")) }))
    compare(message(List(129) { row(mapOf("id" to "command-$it")) }))
    compare(message(listOf(row(mapOf("reason" to "x".repeat(16384))))))
    compare("{\"padding\":\"${"x".repeat(768 * 1024)}\"}")
    compare("{\"padding\":\"${"€".repeat(350000)}\"}")
    for (phase in listOf("live", "stopping", "invalid")) {
      val desired = """["ticket:pixel","ticket","pixel",false,0,"owner","rev","owner","date",[0,"restart"],[0,"$phase"],[1,[]],[1,[]]]"""
      compare(message(listOf(desired), "ticketremote_service_stream_desired_state", listOf(desired)))
      compare(message(listOf(desired.replace("ticket:pixel", "foreign:pixel")), "ticketremote_service_stream_desired_state"))
    }
    for (option in listOf("null", "\"live\"", "[0,\"live\"]", "[1,[]]", "[]", "[2,\"live\"]")) {
      compare(message(listOf("""{"id":"ticket:pixel","ticketId":"ticket","backendId":"pixel","desiredActive":true,"viewerCount":2,"reason":"owner","revision":"rev","updatedAt":"date","coldRestartId":$option,"coldRestartPhase":$option}"""), "ticketremote_service_stream_desired_state"))
    }
    for (viewers in listOf("0", "-0", "2", "2e0", "2e1", "2.0", "2.5", "\"2\"", "\"2e0\"", "2147483647", "2147483648", "-2147483648", "-2147483649")) {
      compare(message(listOf("""{"id":"ticket:pixel","ticketId":"ticket","backendId":"pixel","desiredActive":true,"viewerCount":$viewers,"reason":"owner","revision":"rev","updatedAt":"date"}"""), "ticketremote_service_stream_desired_state"))
    }
    for (number in listOf("NaN", "Infinity", "-Infinity", "1e99999")) compare("{\"ignored\":$number}")
    compare(message(listOf("""{"id":"ticket:pixel","ticketId":"ticket","backendId":"pixel","enabled":true,"epoch":"epoch"}"""), "ticketremote_monitoring_config"))
    compare(message(listOf("[]"), "ticketremote_monitoring_config"))
    compare(message(listOf("[]"), "ticketremote_service_stream_desired_state"))
    for (token in listOf("opaque", "NaN", "Infinity", "undefined", "start", "pending")) {
      compare("{\"ignored\":$token}")
      for (field in listOf("commandType", "status")) {
        val value = if (field == "commandType") "start" else "pending"
        compare(message(listOf(row().replace("\"$field\":\"$value\"", "\"$field\":$token"))))
      }
    }
    compare(message(emptyList(), deletes = listOf("[null]", "{\"id\":null}")))
    for (duplicate in listOf("\"status\":\"done\",", "\"status\":\"pending\",", "\"ticketId\":\"foreign\",")) {
      compare(message(listOf(row().replaceFirst("{", "{$duplicate"))))
      compare(message(listOf(row().dropLast(1) + "," + duplicate.dropLast(1) + "}")))
    }
    compare("{\"IdentityToken\":null,\"IdentityToken\":\"synthetic-secret\"}")
    for (tag in listOf("S:", "L:", "S:start", "L:start", "S:pending", "L:pending", "S:S:start", "L:0")) {
      compare(message(listOf(row(mapOf("id" to tag, "reason" to tag)))))
      for (field in listOf("commandType", "status")) compare(message(listOf(row(mapOf(field to tag)))))
      compare(message(emptyList(), deletes = listOf(JsonObject(mapOf("id" to JsonPrimitive(tag))).toString())))
      compare(JsonObject(mapOf(tag to JsonPrimitive(tag))).toString())
    }
    compare(message(listOf(row().replace("\"commandType\"", "\"L:commandType\""))))
    compare(message(listOf(row().replace("\"status\"", "\"S:status\""))))
    compare(message(listOf(row()), "foreign_table"))
    for (enabled in listOf("true", "false", "3", "\"true\"")) for (epoch in listOf("epoch", "", "x".repeat(129))) {
      val monitoring = """["ticket:pixel","ticket","pixel",$enabled,"$epoch"]"""
      compare(message(listOf(monitoring), "ticketremote_monitoring_config", listOf(monitoring)))
    }
    println("command_decoder_parity_cases=$comparisons")
    assertTrue(mismatches.joinToString("\n"), mismatches.isEmpty())
  }
}
