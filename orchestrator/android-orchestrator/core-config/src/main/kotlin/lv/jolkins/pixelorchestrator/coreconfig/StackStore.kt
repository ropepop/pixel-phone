package lv.jolkins.pixelorchestrator.coreconfig

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlinx.serialization.encodeToString
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
    if (!Files.exists(configPath)) {
      return StackConfigV1()
    }
    return runCatching {
      val raw = Files.readString(configPath, StandardCharsets.UTF_8)
      val migratedRaw = LegacyConfigCompat.migrateConfigJson(raw, json)
      json.decodeFromString(StackConfigV1.serializer(), migratedRaw)
    }.getOrElse { StackConfigV1() }
  }

  @Synchronized
  open fun saveConfig(config: StackConfigV1) {
    writeJsonAtomically(configPath, json.encodeToString(StackConfigV1.serializer(), config))
  }

  @Synchronized
  open fun loadStateOrDefault(): StackStateV1 {
    if (!Files.exists(statePath)) {
      return StackStateV1()
    }
    return runCatching {
      val raw = Files.readString(statePath, StandardCharsets.UTF_8)
      json.decodeFromString(StackStateV1.serializer(), raw)
    }.getOrElse { StackStateV1() }
  }

  @Synchronized
  open fun saveState(state: StackStateV1) {
    writeJsonAtomically(statePath, json.encodeToString(StackStateV1.serializer(), state))
  }

  private fun writeJsonAtomically(path: Path, body: String) {
    Files.createDirectories(path.parent)
    val temp = Files.createTempFile(path.parent, "${path.fileName}.", ".tmp")
    Files.writeString(temp, body, StandardCharsets.UTF_8)
    try {
      Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
    }
  }
}
