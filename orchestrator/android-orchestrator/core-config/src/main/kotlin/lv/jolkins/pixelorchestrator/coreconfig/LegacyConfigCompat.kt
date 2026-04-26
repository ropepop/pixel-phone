package lv.jolkins.pixelorchestrator.coreconfig

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object LegacyConfigCompat {
  fun migrateConfigJson(raw: String, json: Json): String {
    val parsed = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return raw
    val remote = parsed["remote"] as? JsonObject ?: return raw

    val migratedRemote = buildJsonObject {
      remote.forEach { (key, value) -> put(key, value) }
      copyLegacyRemoteKeyIfMissing(
        target = this,
        current = remote,
        newKey = "dohPathToken",
        oldKey = "dohSecretToken"
      )
      copyLegacyRemoteKeyIfMissing(
        target = this,
        current = remote,
        newKey = "adminUsername",
        oldKey = "adminBasicAuthUser"
      )
      copyLegacyRemoteKeyIfMissing(
        target = this,
        current = remote,
        newKey = "adminPasswordFile",
        oldKey = "adminBasicAuthPasswordFile"
      )
    }

    if (migratedRemote == remote) {
      return raw
    }

    val migratedRoot = buildJsonObject {
      parsed.forEach { (key, value) ->
        if (key == "remote") {
          put("remote", migratedRemote)
        } else {
          put(key, value)
        }
      }
    }
    return json.encodeToString(JsonElement.serializer(), migratedRoot)
  }

  private fun copyLegacyRemoteKeyIfMissing(
    target: JsonObjectBuilder,
    current: JsonObject,
    newKey: String,
    oldKey: String
  ) {
    if (current.hasNonBlankString(newKey)) {
      return
    }
    val legacy = current[oldKey] as? JsonPrimitive ?: return
    if (!legacy.isString || legacy.content.isBlank()) {
      return
    }
    target.put(newKey, legacy)
  }

  private fun JsonObject.hasNonBlankString(key: String): Boolean {
    val value = this[key] as? JsonPrimitive ?: return false
    return value.isString && value.content.isNotBlank()
  }
}
