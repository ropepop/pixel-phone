package lv.jolkins.pixelorchestrator.app

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ComponentRegistryDocument(
  val schema: Int = 1,
  val components: List<ComponentRegistryEntry> = emptyList()
)

@Serializable
data class ComponentRegistryEntry(
  val id: String,
  val startCommand: String,
  val stopCommand: String,
  val healthCommand: String
)

object ComponentRegistry {
  private const val ASSET_PATH = "runtime/component-registry.json"

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  fun load(context: Context): List<ComponentRegistryEntry> {
    return context.assets.open(ASSET_PATH).use { input ->
      parse(input.bufferedReader().readText())
    }
  }

  internal fun parse(raw: String): List<ComponentRegistryEntry> {
    val parsed = json.decodeFromString<ComponentRegistryDocument>(raw)
    require(parsed.schema == 1) { "Unsupported component registry schema: ${parsed.schema}" }
    require(parsed.components.isNotEmpty()) { "Component registry must not be empty" }
    val ids = mutableSetOf<String>()
    parsed.components.forEach { entry ->
      require(entry.id.matches(Regex("[a-z0-9_]+"))) { "Invalid component id" }
      require(ids.add(entry.id)) { "Duplicate component id: ${entry.id}" }
      require(entry.startCommand.isNotBlank()) { "Missing start command for ${entry.id}" }
      require(entry.stopCommand.isNotBlank()) { "Missing stop command for ${entry.id}" }
      require(entry.healthCommand.isNotBlank()) { "Missing health command for ${entry.id}" }
    }
    return parsed.components
  }
}
