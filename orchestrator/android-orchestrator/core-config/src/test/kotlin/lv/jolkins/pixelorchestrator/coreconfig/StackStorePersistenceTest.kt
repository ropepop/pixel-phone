package lv.jolkins.pixelorchestrator.coreconfig

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StackStorePersistenceTest {
  @get:Rule val temporary = TemporaryFolder()
  private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
  private fun root() = temporary.newFolder().toPath()
  private fun store(dir: Path) = StackStore(dir.resolve("config.json"), dir.resolve("state.json"))

  private fun child(dir: Path, mode: String, limited: Boolean = false, library: String = System.getProperty("java.library.path")): Pair<Int, String> {
    val command = mutableListOf<String>()
    if (limited) command.addAll(listOf("/bin/sh", "-c", "ulimit -f 128; exec \"\$@\"", "store-limit"))
    command.addAll(listOf(
      "${System.getProperty("java.home")}/bin/java", "-XX:-CreateCoredumpOnCrash", "-XX:ErrorFile=/dev/null",
      "-Djava.library.path=$library", "-cp", System.getProperty("pixel.store.testClasspath"),
      StoreProcess::class.java.name, mode, dir.toString()
    ))
    val process = ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start()
    try { assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Store child must terminate") }
    finally { if (process.isAlive) process.destroyForcibly().waitFor() }
    return process.exitValue() to process.inputStream.bufferedReader().readText()
  }

  @Test fun storesExactBytesPrivatePermissionsAndReloadsInFreshProcess() {
    val dir = root().resolve("nested space ā")
    val store = store(dir)
    store.saveConfig(StoreProcess.config)
    store.saveState(StoreProcess.state)
    assertEquals(json.encodeToString(StackConfigV1.serializer(), StoreProcess.config), Files.readString(dir.resolve("config.json")))
    assertEquals(json.encodeToString(StackStateV1.serializer(), StoreProcess.state), Files.readString(dir.resolve("state.json")))
    assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(dir.resolve("config.json")))
    assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(dir.resolve("state.json")))
    val result = child(dir, "verify")
    assertEquals(0, result.first, result.second)
    assertTrue(result.second.contains("verified"))
    assertEquals(setOf("config.json", "state.json"), Files.list(dir).use { paths -> paths.map { it.fileName.toString() }.toList().toSet() })
  }

  @Test fun damagedMissingUnreadableAndWrongTypeFilesDefaultWithoutRepair() {
    val dir = root()
    val store = store(dir)
    assertEquals(StackConfigV1(), store.loadConfigOrDefault())
    assertEquals(StackStateV1(), store.loadStateOrDefault())
    val damaged = listOf(
      byteArrayOf(), "{".toByteArray(), "null".toByteArray(), "[]".toByteArray(),
      "{\"schema\":\"bad\"}".toByteArray(), byteArrayOf(0xc3.toByte(), 0x28)
    )
    for (bytes in damaged) {
      Files.write(dir.resolve("config.json"), bytes)
      Files.write(dir.resolve("state.json"), bytes)
      assertEquals(StackConfigV1(), store.loadConfigOrDefault())
      assertEquals(StackStateV1(), store.loadStateOrDefault())
      assertContentEquals(bytes, Files.readAllBytes(dir.resolve("config.json")))
      assertContentEquals(bytes, Files.readAllBytes(dir.resolve("state.json")))
    }
    store.saveConfig(StoreProcess.config)
    Files.setPosixFilePermissions(dir.resolve("config.json"), emptySet())
    try { assertEquals(StackConfigV1(), store.loadConfigOrDefault()) }
    finally { Files.setPosixFilePermissions(dir.resolve("config.json"), PosixFilePermissions.fromString("rw-------")) }
    assertEquals(StoreProcess.config, store.loadConfigOrDefault())
  }

  @Test fun failedWritesPreserveTargetAndSymlinkReplacementPreservesReferent() {
    val dir = root()
    val store = store(dir)
    store.saveState(StoreProcess.state)
    val previous = Files.readAllBytes(dir.resolve("state.json"))
    assertFailsWith<IOException> { store.saveState(StoreProcess.state.copy(lastNetworkFingerprint = "\uD800")) }
    assertContentEquals(previous, Files.readAllBytes(dir.resolve("state.json")))
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"))
    try { assertFailsWith<IOException> { store.saveState(StackStateV1()) } }
    finally { Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------")) }
    assertContentEquals(previous, Files.readAllBytes(dir.resolve("state.json")))
    val target = dir.resolve("config.json")
    Files.createDirectory(target)
    assertFailsWith<IOException> { store.saveConfig(StoreProcess.config) }
    assertTrue(Files.isDirectory(target))
    assertEquals(setOf("config.json", "state.json"), Files.list(dir).use { paths -> paths.map { it.fileName.toString() }.toList().toSet() })
    Files.delete(target)
    val referent = dir.resolve("referent.json")
    Files.writeString(referent, "{}")
    Files.createSymbolicLink(target, referent)
    store.saveConfig(StoreProcess.config)
    assertFalse(Files.isSymbolicLink(target))
    assertEquals("{}", Files.readString(referent))
    assertEquals(StoreProcess.config, store.loadConfigOrDefault())
  }

  @Test fun concurrentReadersOnlyObserveCompleteVersions() {
    val dir = root()
    val store = store(dir)
    val first = StoreProcess.state
    val second = first.copy(lastNetworkFingerprint = "next-" + "ā".repeat(8192))
    val expected = setOf(json.encodeToString(StackStateV1.serializer(), first), json.encodeToString(StackStateV1.serializer(), second))
    store.saveState(first)
    val running = AtomicBoolean(true)
    val error = AtomicReference<Throwable?>()
    val reader = thread {
      try { while (running.get()) assertTrue(Files.readString(dir.resolve("state.json")) in expected) }
      catch (failure: Throwable) { error.set(failure) }
    }
    try { repeat(100) { store.saveState(if (it % 2 == 0) second else first) } }
    finally { running.set(false); reader.join(5000) }
    assertFalse(reader.isAlive)
    error.get()?.let { throw it }
  }

  @Test fun operatingSystemInterruptedWriteKeepsPreviousStateAndRestartIgnoresPartialStaging() {
    val dir = root()
    store(dir).saveConfig(StoreProcess.config)
    store(dir).saveState(StoreProcess.state)
    val prior = Files.readAllBytes(dir.resolve("state.json"))
    // The real OS file-size limit interrupts a >1-MiB native write after at most
    // 128 KiB. No production hook supplies an artificial failure or receipt.
    val failed = child(dir, "oversize", limited = true)
    assertNotEquals(0, failed.first, failed.second)
    assertTrue(failed.second.contains("java.io.IOException: store write failed: file too large"), failed.second)
    assertContentEquals(prior, Files.readAllBytes(dir.resolve("state.json")))
    val orphan = dir.resolve("state.json.interrupted.tmp")
    Files.writeString(orphan, "{\"schema\":")
    val verified = child(dir, "verify")
    assertEquals(0, verified.first, verified.second)
    assertEquals("{\"schema\":", Files.readString(orphan))
    store(dir).saveState(StoreProcess.state)
    assertEquals("{\"schema\":", Files.readString(orphan))
  }

  @Test fun missingNativeLibraryCannotSilentlyResetConfigurationToDefaults() {
    val dir = root()
    val result = child(dir, "unavailable", library = dir.toString())
    assertEquals(0, result.first, result.second)
    assertTrue(result.second.contains("native failure visible"))
    assertEquals(0L, Files.list(dir).use { it.count() })
  }
}

/** Fresh JVMs verify actual persistence and interruption, not shared object state. */
object StoreProcess {
  val config = StackConfigV1(remote = RemoteConfig(dohPathToken = "synthetic-ā-🔒", adminUsername = "disposable"))
  val state = StackStateV1(
    bootPath = BootPath.PROVIDER_HOOK, lastSuccessfulBootEpochSeconds = 1790000000,
    supervisorLoopHeartbeatEpochSeconds = 1790000001, lastNetworkFingerprint = "interface-ā",
    services = mapOf("vpn" to ServiceRuntimeState(ServiceStatus.DEGRADED, 7, "synthetic")),
    moduleState = mapOf("vpn" to ModuleRuntimeState("degraded", false, 1790000001, mapOf("reason" to "synthetic"))),
    lastHealthSnapshot = HealthSnapshot(rootGranted = true, evidence = mapOf("synthetic" to "value")),
    operationLog = listOf(OperationEvent(1790000001, "vpn", "check", false, "synthetic"))
  )
  @JvmStatic fun main(args: Array<String>) {
    val dir = Path.of(args[1])
    val store = StackStore(dir.resolve("config.json"), dir.resolve("state.json"))
    when (args[0]) {
      "verify" -> {
        check(store.loadConfigOrDefault() == config)
        check(store.loadStateOrDefault() == state)
        println("verified")
      }
      "oversize" -> {
        store.saveState(state.copy(lastNetworkFingerprint = "x".repeat(1024 * 1024)))
        println("oversize write unexpectedly succeeded")
      }
      "unavailable" -> {
        try { store.loadConfigOrDefault(); error("missing native library returned defaults") }
        catch (_: UnsatisfiedLinkError) { println("native failure visible") }
      }
    }
  }
}
