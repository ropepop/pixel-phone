package lv.jolkins.pixelorchestrator.coreconfig

import java.nio.file.Files
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Differential tests load the former owners from Git only in this test run. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class StoreParityTest {
  @get:Rule val temporary = TemporaryFolder()
  private val formats = listOf(
    Json { ignoreUnknownKeys = true },
    Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true },
    Json { isLenient = true; allowTrailingComma = true; ignoreUnknownKeys = true }
  )
  private val textCases = listOf("", " ", "\t\n", "\u00a0\u2007\u202f", "\u001c", "\u0085", "value", " ā\u0000🔒 ", "\uD800")
  private fun outcome(operation: () -> String): Any =
    try { operation() } catch (error: kotlinx.serialization.SerializationException) { error::class.java.name }

  @Test fun aliasesPreserveOriginalFormattingUnknownDataTypesAndPrecedence() {
    val values = listOf<JsonElement?>(null, JsonNull, JsonPrimitive(7), JsonPrimitive(false), JsonObject(emptyMap()), JsonArray(emptyList())) +
      textCases.map(::JsonPrimitive)
    val pairs = listOf("dohPathToken" to "dohSecretToken", "adminUsername" to "adminBasicAuthUser", "adminPasswordFile" to "adminBasicAuthPasswordFile")
    for (format in formats) {
      for ((new, old) in pairs) for (current in values) for (previous in values) {
        val remote = buildJsonObject {
          put("unknownNumber", Json.parseToJsonElement("1234567890123456789012345678901234567890"))
          put("unknownFloat", Json.parseToJsonElement("1e299"))
          if (current != null) put(new, current)
          if (previous != null) put(old, previous)
        }
        val raw = "{ \"unknown\": [1,2], \"remote\": $remote, \"tail\": true }"
        assertEquals(outcome { OriginalConfigCompat.migrateConfigJson(raw, format) }, outcome { LegacyConfigCompat.migrateConfigJson(raw, format) })
      }
      val rawCases = listOf(
        "", "{", "null", "[]", "{\"remote\":7}", "{\"remote\":null}",
        "{remote:{dohSecretToken:\"legacy\",},}",
        "{\"remote\":{\"dohPathToken\":\"first\",\"dohPathToken\":\"\",\"dohSecretToken\":\"last\"}}",
        "{\"remote\":{\"dohSecretToken\":\"x\",\"adminBasicAuthUser\":\"y\",\"adminBasicAuthPasswordFile\":\"z\"}}"
      )
      for (raw in rawCases) assertEquals(OriginalConfigCompat.migrateConfigJson(raw, format), LegacyConfigCompat.migrateConfigJson(raw, format))
    }
  }

  @Test fun redactionMatchesOriginalIncludingBlankUnicodeAndIncludeIdentity() {
    for (token in textCases) {
      val config = StoreProcess.config.copy(remote = StoreProcess.config.remote.copy(dohPathToken = token))
      for (include in listOf(true, false)) {
        assertEquals(OriginalSecretRedactor.redact(config, include), SecretRedactor.redact(config, include))
        if (include) assertSame(config, SecretRedactor.redact(config, include))
      }
    }
  }

  @Test fun savedBytesAndLoadedDefaultsMatchOriginalOwner() {
    val dir = temporary.newFolder().toPath()
    val configs = listOf(StackConfigV1(), StoreProcess.config, StoreProcess.config.copy(schema = 9))
    val states = listOf(StackStateV1(), StoreProcess.state, StoreProcess.state.copy(supervisorLoopHeartbeatEpochSeconds = Long.MAX_VALUE))
    for (format in formats) {
      val oldConfig = dir.resolve("old-config.json")
      val oldState = dir.resolve("old-state.json")
      val newConfig = dir.resolve("new-config.json")
      val newState = dir.resolve("new-state.json")
      val old = LegacyStackStore(oldConfig, oldState, format)
      val new = StackStore(newConfig, newState, format)
      for (config in configs) {
        old.saveConfig(config); new.saveConfig(config)
        assertContentEquals(Files.readAllBytes(oldConfig), Files.readAllBytes(newConfig))
        assertEquals(old.loadConfigOrDefault(), new.loadConfigOrDefault())
      }
      for (state in states) {
        old.saveState(state); new.saveState(state)
        assertContentEquals(Files.readAllBytes(oldState), Files.readAllBytes(newState))
        assertEquals(old.loadStateOrDefault(), new.loadStateOrDefault())
      }
      for (raw in listOf("", "{", "{}", "{\"schema\":\"bad\"}", "{\"remote\":{\"dohSecretToken\":\"legacy\"}}", "{\"remote\":{\"dohSecretToken\":\"legacy\",\"unknown\":1e9999}}")) {
        listOf(oldConfig, oldState, newConfig, newState).forEach { Files.writeString(it, raw) }
        assertEquals(old.loadConfigOrDefault(), new.loadConfigOrDefault())
        assertEquals(old.loadStateOrDefault(), new.loadStateOrDefault())
      }
    }
  }
}

/** Optional measurements exercise both complete save/read journeys on disk. */
object StoreMeasurements {
  @JvmStatic fun main(args: Array<String>) {
    val dir = Files.createTempDirectory("store-measure")
    try {
      val old = LegacyStackStore(dir.resolve("old-config"), dir.resolve("old-state"))
      val new = StackStore(dir.resolve("new-config"), dir.resolve("new-state"))
      val operations = mapOf<String, () -> Unit>(
        "Legacy" to {
          old.saveConfig(StoreProcess.config); old.saveState(StoreProcess.state)
          check(old.loadConfigOrDefault() == StoreProcess.config); check(old.loadStateOrDefault() == StoreProcess.state)
        },
        "Rust" to {
          new.saveConfig(StoreProcess.config); new.saveState(StoreProcess.state)
          check(new.loadConfigOrDefault() == StoreProcess.config); check(new.loadStateOrDefault() == StoreProcess.state)
        }
      )
      val cpu = java.lang.management.ManagementFactory.getThreadMXBean()
      fun sample(operation: () -> Unit): Pair<Double, Double> {
        val cpuStart = cpu.currentThreadCpuTime
        val start = System.nanoTime()
        operation()
        return (System.nanoTime() - start) / 1e6 to (cpu.currentThreadCpuTime - cpuStart) / 1e6
      }
      if (args[0] != "Warm") {
        val value = sample(operations.getValue(args[0]))
        println("${args[0]} first full config/state save/read: wall_ms=${value.first}, cpu_ms=${value.second}")
      } else {
        repeat(30) { operations.values.forEach { it() } }
        val results = operations.keys.associateWith { mutableListOf<Pair<Double, Double>>() }
        repeat(250) { index ->
          val order = if (index % 2 == 0) operations.keys.toList() else operations.keys.reversed()
          order.forEach { results.getValue(it).add(sample(operations.getValue(it))) }
        }
        for ((engine, samples) in results) {
          val wall = samples.map { it.first }.sorted()
          val core = samples.map { it.second }.sorted()
          println("$engine 250 full save/read journeys: success=250 p50_ms=${wall[125]} p95_ms=${wall[237]} cpu_p50_ms=${core[125]}")
        }
      }
    } finally { dir.toFile().deleteRecursively() }
  }
}
