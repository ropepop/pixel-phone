package lv.jolkins.pixelorchestrator.app

import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import lv.jolkins.pixelorchestrator.coreconfig.HealthSnapshot
import lv.jolkins.pixelorchestrator.coreconfig.RedeployConfig
import lv.jolkins.pixelorchestrator.coreconfig.StackConfigV1
import lv.jolkins.pixelorchestrator.coreconfig.StackStateV1
import lv.jolkins.pixelorchestrator.coreconfig.StackStore
import lv.jolkins.pixelorchestrator.health.CommandRunner
import lv.jolkins.pixelorchestrator.health.CommandResult
import lv.jolkins.pixelorchestrator.health.HealthScope
import lv.jolkins.pixelorchestrator.health.RuntimeHealthChecker
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.rootexec.RootResult
import lv.jolkins.pixelorchestrator.runtimeinstaller.ArtifactManifest
import lv.jolkins.pixelorchestrator.runtimeinstaller.AssetProvider
import lv.jolkins.pixelorchestrator.runtimeinstaller.BootstrapResult
import lv.jolkins.pixelorchestrator.runtimeinstaller.ComponentReleaseManifest
import lv.jolkins.pixelorchestrator.runtimeinstaller.ReleaseRollbackMetadata
import lv.jolkins.pixelorchestrator.runtimeinstaller.RuntimeInstallerControl
import lv.jolkins.pixelorchestrator.runtimeinstaller.SyncResult
import lv.jolkins.pixelorchestrator.supervisor.SupervisorControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration

class OrchestratorFacadeRedeployPolicyTest {

  private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

  @Test
  fun redeployWaitsForLateTargetRecoveryAndIgnoresTransientNeighborWobble() = runBlocking {
    val harness = buildHarness(
      config = testConfig(healthWaitSeconds = 4, healthRetrySeconds = 1, neighborGraceSeconds = 1),
      healthSnapshots = listOf(
        health(),
        health(trainBot = false),
        health(trainBot = false),
        health(),
        health(vpn = false),
        health()
      )
    )

    val result = harness.facade.redeployComponent("train_bot")

    assertTrue(result.message, result.success)
    assertEquals("Redeploy complete for train_bot", result.message)
    assertEquals(1, harness.runtimeInstaller.installCalls)
    assertEquals(1, harness.runtimeInstaller.pruneCalls)
    assertEquals(0, harness.runtimeInstaller.rollbackCalls)
    assertEquals(listOf("train_bot"), harness.supervisor.restartCalls)
    assertEquals(listOf("train_bot"), harness.supervisor.stopCalls)
    assertTrue(harness.runtimeInstaller.syncedComponents.contains("train_bot"))
  }

  @Test
  fun resumeSupervisionRejectsTailnetRecoveryProfileWhenVpnIsDisabled() = runBlocking {
    val harness = buildHarness(
      config = StackConfigV1(
        vpn = StackConfigV1().vpn.copy(enabled = false, nativeWirelessDebugEnabled = true),
        supervision = StackConfigV1().supervision.copy(managementRequireWirelessDebug = true)
      ),
      healthSnapshots = listOf(health())
    )

    val result = harness.facade.resumeSupervision()

    assertFalse(result.success)
  }

  @Test
  fun resumeSupervisionRejectsTailnetRecoveryProfileWhenSshPortCollidesWithHttps() = runBlocking {
    val harness = buildHarness(
      config = StackConfigV1(
        vpn = StackConfigV1().vpn.copy(enabled = true, nativeWirelessDebugEnabled = true),
        ssh = StackConfigV1().ssh.copy(port = 443),
        supervision = StackConfigV1().supervision.copy(managementRequireWirelessDebug = true)
      ),
      healthSnapshots = listOf(health())
    )

    val result = harness.facade.resumeSupervision()

    assertFalse(result.success)
  }

  @Test
  fun redeployRollsBackWhenTargetNeverRecovers() = runBlocking {
    val harness = buildHarness(
      config = testConfig(healthWaitSeconds = 2, healthRetrySeconds = 1, neighborGraceSeconds = 1),
      healthSnapshots = listOf(
        health(),
        health(trainBot = false),
        health(trainBot = false),
        health(trainBot = false),
        health()
      )
    )

    val result = harness.facade.redeployComponent("train_bot")

    assertFalse(result.success)
    assertTrue(result.message.contains("previous release restored"))
    assertTrue(result.message.contains("health gate failed"))
    assertEquals(1, harness.runtimeInstaller.installCalls)
    assertEquals(1, harness.runtimeInstaller.rollbackCalls)
    assertEquals(0, harness.runtimeInstaller.pruneCalls)
    assertEquals(listOf("train_bot"), harness.supervisor.startCalls)
  }

  @Test
  fun redeployRollsBackWhenNeighborRegressionPersistsPastGraceWindow() = runBlocking {
    val harness = buildHarness(
      config = testConfig(healthWaitSeconds = 3, healthRetrySeconds = 1, neighborGraceSeconds = 1),
      healthSnapshots = listOf(
        health(),
        health(vpn = false),
        health(vpn = false),
        health()
      )
    )

    val result = harness.facade.redeployComponent("train_bot")

    assertFalse(result.success)
    assertTrue(result.message.contains("previous release restored"))
    assertTrue(result.message.contains("healthy neighbors regressed: vpn"))
    assertEquals(1, harness.runtimeInstaller.installCalls)
    assertEquals(1, harness.runtimeInstaller.rollbackCalls)
    assertEquals(0, harness.runtimeInstaller.pruneCalls)
  }

  private fun buildHarness(
    config: StackConfigV1,
    healthSnapshots: List<HealthSnapshot>
  ): TestHarness {
    val configJson = json.encodeToString(StackConfigV1.serializer(), config)
    val manifestJson = json.encodeToString(ComponentReleaseManifest.serializer(), testManifest())
    val runtimeInstaller = FakeRuntimeInstaller()
    val supervisor = FakeSupervisor(healthSnapshots)
    val rootExecutor = FakeRootExecutor(configJson = configJson, releaseManifestJson = manifestJson)
    val healthChecker = RuntimeHealthChecker(CommandRunner { _ ->
      CommandResult(ok = true, stdout = "", stderr = "")
    })
    val facade = OrchestratorFacade(
      stackStore = InMemoryStackStore(),
      rootExecutor = rootExecutor,
      runtimeInstaller = runtimeInstaller,
      supervisor = supervisor,
      healthChecker = healthChecker,
      assetProvider = FakeAssetProvider(),
      supportBundleExporter = FakeSupportBundleExporter(),
      json = json
    )
    return TestHarness(facade, runtimeInstaller, supervisor)
  }

  private fun testConfig(
    healthWaitSeconds: Int,
    healthRetrySeconds: Int,
    neighborGraceSeconds: Int
  ): StackConfigV1 {
    return StackConfigV1(
      redeploy = RedeployConfig(
        healthWaitSeconds = healthWaitSeconds,
        healthRetrySeconds = healthRetrySeconds,
        neighborGraceSeconds = neighborGraceSeconds
      )
    )
  }

  private fun testManifest(): ComponentReleaseManifest {
    return ComponentReleaseManifest(
      schema = 1,
      componentId = "train_bot",
      releaseId = "train-bot-release-123",
      signatureSchema = "none",
      artifacts = listOf(
        lv.jolkins.pixelorchestrator.runtimeinstaller.ArtifactEntry(
          id = "train-bot-bundle",
          url = "/tmp/train-bot-release-123.tar.gz",
          sha256 = "abc123",
          fileName = "train-bot-release-123.tar.gz",
          sizeBytes = 1,
          required = true
        )
      )
    )
  }

  private fun health(
    trainBot: Boolean = true,
    vpn: Boolean = true,
    dns: Boolean = true,
    remote: Boolean = true,
    ssh: Boolean = true,
    satiksmeBot: Boolean = true,
    siteNotifier: Boolean = true,
    ddns: Boolean = true
  ): HealthSnapshot {
    return HealthSnapshot(
      rootGranted = true,
      dnsHealthy = dns,
      remoteHealthy = remote,
      managementHealthy = true,
      sshHealthy = ssh,
      vpnHealthy = vpn,
      trainBotHealthy = trainBot,
      satiksmeBotHealthy = satiksmeBot,
      siteNotifierHealthy = siteNotifier,
      ddnsHealthy = ddns,
      supervisorLoopHealthy = true,
      managementAuthHealthy = true,
      deployHealthy = true,
      supervisorHealthy = true
    )
  }

  private data class TestHarness(
    val facade: OrchestratorFacade,
    val runtimeInstaller: FakeRuntimeInstaller,
    val supervisor: FakeSupervisor
  )

  private class InMemoryStackStore : StackStore() {
    var lastSavedConfig: StackConfigV1 = StackConfigV1()
    var lastSavedState: StackStateV1 = StackStateV1()

    override fun saveConfig(config: StackConfigV1) {
      lastSavedConfig = config
    }

    override fun loadConfigOrDefault(): StackConfigV1 {
      return lastSavedConfig
    }

    override fun saveState(state: StackStateV1) {
      lastSavedState = state
    }

    override fun loadStateOrDefault(): StackStateV1 {
      return lastSavedState
    }
  }

  private class FakeRootExecutor(
    private val configJson: String,
    private val releaseManifestJson: String
  ) : RootExecutor {
    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult {
      val stdout = when {
        command.contains("/data/local/pixel-stack/conf/orchestrator-config-v1.json") -> configJson
        command.contains("/data/local/pixel-stack/conf/runtime/components/train_bot/release-manifest.json") -> releaseManifestJson
        else -> ""
      }
      return RootResult(
        exitCode = 0,
        stdout = stdout,
        stderr = "",
        command = command,
        durationMs = 0
      )
    }

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      return RootResult(
        exitCode = 0,
        stdout = "QUIESCENT\n",
        stderr = "",
        command = script,
        durationMs = 0
      )
    }
  }

  private class FakeRuntimeInstaller : RuntimeInstallerControl {
    var installCalls: Int = 0
    var rollbackCalls: Int = 0
    var pruneCalls: Int = 0
    val syncedComponents = mutableListOf<String?>()

    override suspend fun bootstrap(
      config: StackConfigV1,
      assets: AssetProvider,
      manifest: ArtifactManifest,
      rootfsArtifactId: String
    ): BootstrapResult {
      throw UnsupportedOperationException("bootstrap not used in redeploy tests")
    }

    override suspend fun syncBundledRuntimeAssets(assets: AssetProvider, component: String?): SyncResult {
      syncedComponents += component
      return SyncResult(success = true, message = "synced")
    }

    override suspend fun installComponentRelease(
      config: StackConfigV1,
      component: String,
      manifest: ComponentReleaseManifest
    ): SyncResult {
      installCalls += 1
      return SyncResult(
        success = true,
        message = "installed",
        rollbackMetadata = ReleaseRollbackMetadata(
          component = component,
          releaseId = manifest.releaseId,
          currentSymlinkPath = "/data/local/pixel-stack/apps/train-bot/bin/train-bot.current",
          previousTargetPath = "/data/local/pixel-stack/apps/train-bot/releases/train-bot-previous",
          installedTargetPath = "/data/local/pixel-stack/apps/train-bot/releases/${manifest.releaseId}"
        )
      )
    }

    override suspend fun rollbackComponentRelease(
      config: StackConfigV1,
      component: String,
      rollbackMetadata: ReleaseRollbackMetadata
    ): SyncResult {
      rollbackCalls += 1
      return SyncResult(success = true, message = "rolled back")
    }

    override suspend fun pruneComponentReleases(config: StackConfigV1, component: String, keepReleases: Int): SyncResult {
      pruneCalls += 1
      return SyncResult(success = true, message = "pruned")
    }
  }

  private class FakeSupervisor(
    private val healthSnapshots: List<HealthSnapshot>
  ) : SupervisorControl {
    private var healthIndex: Int = 0
    val restartCalls = mutableListOf<String>()
    val stopCalls = mutableListOf<String>()
    val startCalls = mutableListOf<String>()

    override suspend fun startAll() = Unit
    override suspend fun resumeSupervision() = Unit

    override suspend fun stopAll() = Unit

    override suspend fun startComponent(component: String) {
      startCalls += component
    }

    override suspend fun stopComponent(component: String) {
      stopCalls += component
    }

    override suspend fun restart(component: String) {
      restartCalls += component
    }

    override suspend fun runHealthCheck(scope: HealthScope): HealthSnapshot {
      val snapshot = healthSnapshots.getOrElse(healthIndex) { healthSnapshots.last() }
      if (healthIndex < healthSnapshots.lastIndex) {
        healthIndex += 1
      }
      return snapshot
    }

    override suspend fun syncDdnsNow() = Unit
  }

  private class FakeAssetProvider : AssetProvider {
    override fun open(path: String) = ByteArrayInputStream(ByteArray(0))

    override fun list(path: String): List<String> = emptyList()
  }

  private class FakeSupportBundleExporter : SupportBundleExporting {
    override suspend fun export(
      config: StackConfigV1,
      state: StackStateV1,
      includeSecrets: Boolean
    ): File {
      return File.createTempFile("support-bundle", ".zip")
    }
  }
}
