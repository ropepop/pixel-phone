package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.serialization.json.*
import java.time.Instant

/** Stateless decoding; the subscribed inbox and Android executor retain authority. */
internal object NativeTicketCommand {
  init { System.loadLibrary("pixel_health") }
  @JvmStatic private external fun decode(raw: String, ticket: String, backend: String, seconds: Long, nanos: Int): String
  @JvmStatic private external fun policy(operation: String, payload: String): String
  val json = Json { encodeDefaults = true }

  fun call(operation: String, args: JsonObject, inbox: JsonObject? = null, now: Instant = Instant.now()): JsonElement =
    json.parseToJsonElement(policy(operation, buildJsonObject {
      put("args", args)
      put("inbox", inbox ?: JsonNull)
      put("seconds", now.epochSecond)
      put("nanos", now.nano)
    }.toString()))

  fun parse(raw: String, ticket: String, backend: String, json: Json, now: Instant): TicketSpacetimeCommandSubscriptionMessage =
    json.decodeFromString(decode(raw, ticket, backend, now.epochSecond, now.nano))
}
