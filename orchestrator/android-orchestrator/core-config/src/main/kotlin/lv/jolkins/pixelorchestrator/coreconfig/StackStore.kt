package lv.jolkins.pixelorchestrator.coreconfig

import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

open class StackStore(
  private val configPath: Path = Paths.get(StackPaths.CONFIG_JSON),
  private val statePath: Path = Paths.get(StackPaths.STATE_JSON),
  private val json: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
  }
) {

  @Synchronized
  open fun loadConfigOrDefault(): StackConfigV1 {
    val raw = NativeStore.readUtf8OrNull(configPath.toString()) ?: return StackConfigV1()
    val migratedRaw = try {
      LegacyConfigCompat.migrateConfigJson(raw, json)
    } catch (_: SerializationException) {
      return StackConfigV1()
    }
    return runCatching {
      json.decodeFromString(StackConfigV1.serializer(), migratedRaw)
    }.getOrElse { StackConfigV1() }
  }

  @Synchronized
  open fun saveConfig(config: StackConfigV1) {
    NativeStore.writeAtomic(configPath.toString(), json.encodeToString(StackConfigV1.serializer(), config))
  }

  @Synchronized
  open fun loadStateOrDefault(): StackStateV1 {
    val raw = NativeStore.readUtf8OrNull(statePath.toString()) ?: return StackStateV1()
    return runCatching {
      json.decodeFromString(StackStateV1.serializer(), raw)
    }.getOrElse { StackStateV1() }
  }

  @Synchronized
  open fun saveState(state: StackStateV1) {
    NativeStore.writeAtomic(statePath.toString(), json.encodeToString(StackStateV1.serializer(), state))
  }
}
