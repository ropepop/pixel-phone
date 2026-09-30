package lv.jolkins.pixelorchestrator.coreconfig

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

object LegacyConfigCompat {
  fun migrateConfigJson(raw: String, json: Json): String {
    val parsed = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return raw
    val remote = parsed["remote"] as? JsonObject ?: return raw
    val strings = JsonObject(remote.filterValues { it is JsonPrimitive && it.isString })
    val aliases = Json.parseToJsonElement(NativeStore.legacyRemoteAliases(strings.toString())) as JsonArray
    if (aliases.isEmpty()) return raw
    val migratedRemote = remote.toMutableMap()
    aliases.forEach { pair ->
      pair as JsonArray
      migratedRemote[pair[0].jsonPrimitive.content] = remote.getValue(pair[1].jsonPrimitive.content)
    }
    return json.encodeToString(JsonElement.serializer(), JsonObject(parsed + ("remote" to JsonObject(migratedRemote))))
  }
}
