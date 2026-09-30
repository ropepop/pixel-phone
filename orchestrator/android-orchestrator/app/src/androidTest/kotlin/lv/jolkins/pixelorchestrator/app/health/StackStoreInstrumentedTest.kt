package lv.jolkins.pixelorchestrator.app.health

import android.os.Bundle
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import lv.jolkins.pixelorchestrator.coreconfig.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Two instrumentation invocations prove Android process-restart persistence. */
@RunWith(AndroidJUnit4::class)
class StackStoreInstrumentedTest {
  @Test fun installedNativeStorePreservesDisposableStateAcrossProcesses() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val liveConfig = File(context.filesDir, "stack-store/orchestrator-config-v1.json")
    val liveBefore = liveConfig.readBytes()
    val dir = File(context.filesDir, "native-store-acceptance")
    val phase = InstrumentationRegistry.getArguments().getString("store_phase") ?: "roundtrip"
    val config = StackConfigV1(remote = RemoteConfig(dohPathToken = "synthetic-test-only", adminUsername = "acceptance-ā"))
    val state = StackStateV1(
      bootPath = BootPath.PROVIDER_HOOK, supervisorLoopHeartbeatEpochSeconds = 1790000001,
      services = mapOf("vpn" to ServiceRuntimeState(ServiceStatus.DEGRADED, 7, "synthetic")),
      lastHealthSnapshot = HealthSnapshot(rootGranted = true, evidence = mapOf("synthetic" to "value")),
      operationLog = listOf(OperationEvent(1790000001, "vpn", "check", false, "synthetic"))
    )
    val configPath = File(dir, "config.json").toPath()
    val statePath = File(dir, "state.json").toPath()
    val store = StackStore(configPath, statePath)
    val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    try {
      require(phase in setOf("write", "read", "roundtrip", "cleanup")) { "Unknown store test phase" }
      if (phase == "cleanup") {
        assertTrue(!dir.exists() || dir.deleteRecursively())
        return
      }
      if (phase != "read") {
        assertFalse("Acceptance fixture must not already exist", dir.exists())
        store.saveConfig(config)
        store.saveState(state)
        File(dir, "state.json.interrupted.tmp").writeText("{\"schema\":")
        val damaged = File(dir, "damaged.json").toPath()
        Files.write(damaged, byteArrayOf(0xc3.toByte(), 0x28))
        val damagedStore = StackStore(damaged, damaged)
        assertEquals(StackConfigV1(), damagedStore.loadConfigOrDefault())
        assertEquals(StackStateV1(), damagedStore.loadStateOrDefault())
        assertArrayEquals(byteArrayOf(0xc3.toByte(), 0x28), Files.readAllBytes(damaged))
      }
      assertEquals(config, store.loadConfigOrDefault())
      assertEquals(state, store.loadStateOrDefault())
      assertEquals(json.encodeToString(StackConfigV1.serializer(), config), configPath.toFile().readText())
      assertEquals(json.encodeToString(StackStateV1.serializer(), state), statePath.toFile().readText())
      assertEquals(0x180, Os.stat(configPath.toString()).st_mode and 0x1ff)
      assertEquals(0x180, Os.stat(statePath.toString()).st_mode and 0x1ff)
      assertEquals("{\"schema\":", File(dir, "state.json.interrupted.tmp").readText())
      assertEquals("***redacted***", SecretRedactor.redact(config, false).remote.dohPathToken)
      val legacy = """{"remote":{"dohSecretToken":"synthetic-legacy"}}"""
      File(dir, "legacy.json").writeText(legacy)
      assertEquals("synthetic-legacy", StackStore(File(dir, "legacy.json").toPath(), statePath).loadConfigOrDefault().remote.dohPathToken)
      instrumentation.sendStatus(0, Bundle().apply {
        putString("store_phase", phase)
        putString("store_process_id", android.os.Process.myPid().toString())
        putString("store_config_bytes", Files.size(configPath).toString())
        putString("store_state_bytes", Files.size(statePath).toString())
      })
    } finally {
      assertArrayEquals("Live config must not change", liveBefore, liveConfig.readBytes())
      if (phase != "write") assertTrue(!dir.exists() || dir.deleteRecursively())
    }
  }
}
