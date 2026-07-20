package lv.jolkins.pixelorchestrator.app

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import lv.jolkins.pixelorchestrator.coreconfig.HealthSnapshot
import lv.jolkins.pixelorchestrator.coreconfig.ModuleConfig
import lv.jolkins.pixelorchestrator.coreconfig.ModuleHealthState
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
  fun fullHealthActionUsesScopeAwareDeployPolicyInsteadOfRawOptionalBooleans() = runBlocking {
    val scopeHealthy = HealthSnapshot(
      generatedEpochSeconds = 1_234L,
      rootGranted = true,
      dnsHealthy = false,
      remoteHealthy = false,
      managementHealthy = true,
      sshHealthy = true,
      vpnHealthy = true,
      trainBotHealthy = false,
      satiksmeBotHealthy = false,
      siteNotifierHealthy = false,
      subscriptionBotHealthy = false,
      ddnsHealthy = false,
      supervisorLoopHealthy = true,
      managementAuthHealthy = false,
      deployHealthy = true,
      supervisorHealthy = true,
      moduleHealth = mapOf(
        "ticket_screen" to ModuleHealthState(healthy = true, status = "running")
      )
    )
    val harness = buildHarness(
      config = StackConfigV1(),
      healthSnapshots = listOf(scopeHealthy)
    )

    val result = harness.facade.runHealthCheck(HealthScope.FULL)

    assertTrue(result.message, result.success)
    assertEquals("Health check complete: required deployment scope is healthy", result.message)
    assertEquals(scopeHealthy, result.healthSnapshot)
    assertEquals(scopeHealthy, harness.store.lastSavedState.lastHealthSnapshot)
    assertEquals(scopeHealthy, harness.facade.loadLastHealthSnapshot())
    assertEquals(1, harness.supervisor.healthCalls)
  }

  @Test
  fun ticketScreenRuntimeEnvIsWrittenAtomicallyWithoutLineUpsertRaces() {
    val path = listOf(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/OrchestratorFacade.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/OrchestratorFacade.kt")
    ).first { Files.exists(it) }
    val source = String(Files.readAllBytes(path), Charsets.UTF_8)
    val writer = source.substringAfter("private suspend fun writeTicketScreenRuntimeInputs()")
      .substringBefore("private suspend fun runRetriedRootScript")

    assertTrue(writer.contains("cat >"))
    assertTrue(writer.contains("<<'EOF_TICKET_SCREEN_ENV'"))
    assertFalse(writer.contains("<<'EOF'"))
    assertTrue(writer.contains("mv"))
    assertFalse(writer.contains("TICKET_SCREEN_TUNNEL_ENABLED"))
    assertTrue(writer.contains("TICKET_SCREEN_SPACETIME_DIRECT_ENABLED=1"))
    assertTrue(writer.contains("TICKET_SCREEN_SPACETIME_DATABASE=ticket-remote-prod-v3"))
    assertTrue(writer.contains("TICKET_SCREEN_SPACETIME_SERVICE_TOKEN_FILE=/data/local/pixel-stack/conf/apps/ticket-screen-spacetime-token"))
    assertTrue(writer.contains("TICKET_SCREEN_SPACETIME_POLL_MILLIS=250"))
    assertFalse(writer.contains("upsert_env"))
    assertFalse(writer.contains("grep -v \"^${'$'}{key}=\""))
  }

  @Test
  fun redeployWaitsForLateTargetRecoveryAndIgnoresTransientNeighborWobble() = runBlocking {
    val harness = buildHarness(
      config = testConfig(healthWaitSeconds = 4, healthRetrySeconds = 1, neighborGraceSeconds = 1),
      healthSnapshots = listOf(
        health(),
        health(vpn = false),
        health(vpn = false),
        health(),
        health(ssh = false),
        health()
      )
    )

    val result = harness.facade.redeployComponent("vpn")

    assertTrue(result.message, result.success)
    assertEquals("Redeploy complete for vpn", result.message)
    assertEquals(1, harness.runtimeInstaller.installCalls)
    assertEquals(1, harness.runtimeInstaller.pruneCalls)
    assertEquals(0, harness.runtimeInstaller.rollbackCalls)
    assertEquals(listOf("vpn"), harness.supervisor.restartCalls)
    assertTrue(harness.supervisor.stopCalls.isEmpty())
    assertTrue(harness.runtimeInstaller.syncedComponents.contains("vpn"))
  }

  @Test
  fun redeployRefreshesDisabledVpnWithoutWaitingForAListener() = runBlocking {
    val disabledVpn = health(
      vpn = false,
      moduleHealth = mapOf("vpn" to ModuleHealthState(healthy = false, status = "disabled"))
    )
    val harness = buildHarness(
      config = testConfig(healthWaitSeconds = 2, healthRetrySeconds = 1, neighborGraceSeconds = 0).copy(
        vpn = StackConfigV1().vpn.copy(enabled = false),
        modules = mapOf("vpn" to ModuleConfig(enabled = false))
      ),
      healthSnapshots = listOf(disabledVpn, disabledVpn),
      manifestComponent = "vpn"
    )

    val result = harness.facade.redeployComponent("vpn")

    assertTrue(result.message, result.success)
    assertEquals("Redeploy complete for vpn", result.message)
    assertEquals(1, harness.runtimeInstaller.installCalls)
    assertTrue(harness.supervisor.restartCalls.isEmpty())
    assertEquals(listOf("vpn"), harness.supervisor.stopCalls)
    assertEquals(0, harness.supervisor.healthCalls)
    assertTrue(harness.rootExecutor.scripts.none { it.contains("--stage-only") })
    assertTrue(harness.runtimeInstaller.syncedComponents.contains("vpn"))
  }

  @Test
  fun fastTicketRedeployUsesOnlyTheLocalTicketHealthProbe() = runBlocking {
    val harness = buildHarness(
      config = testConfig(healthWaitSeconds = 4, healthRetrySeconds = 1, neighborGraceSeconds = 1),
      healthSnapshots = listOf(health())
    )

    val result = harness.facade.redeployComponent(
      component = "ticket_screen",
      fastTicketScreenRedeploy = true
    )

    assertTrue(result.message, result.success)
    assertEquals("Fast Ticket redeploy complete: local ticket health is ready", result.message)
    assertEquals(0, harness.supervisor.healthCalls)
    assertEquals(listOf("ticket_screen"), harness.supervisor.restartCalls)
    assertTrue(harness.runtimeInstaller.syncedComponents.contains("ticket_screen"))
    assertTrue(harness.rootExecutor.commands.any { it.contains("pixel-ticket-health.sh") })
  }

  @Test
  fun standardTicketRedeployKeepsTheFullCrossComponentHealthPolicy() = runBlocking {
    val harness = buildHarness(
      config = testConfig(healthWaitSeconds = 4, healthRetrySeconds = 1, neighborGraceSeconds = 0),
      healthSnapshots = listOf(health(), health())
    )

    val result = harness.facade.redeployComponent("ticket_screen")

    assertTrue(result.message, result.success)
    assertEquals("Redeploy complete for ticket_screen", result.message)
    assertEquals(2, harness.supervisor.healthCalls)
    assertEquals(listOf("ticket_screen"), harness.supervisor.restartCalls)
    assertTrue(harness.rootExecutor.commands.none { it.contains("pixel-ticket-health.sh") })
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
  fun redeployRejectsRetiredComponentsBeforeRuntimeMutation() = runBlocking {
    val harness = buildHarness(
      config = testConfig(healthWaitSeconds = 2, healthRetrySeconds = 1, neighborGraceSeconds = 1),
      healthSnapshots = listOf(health())
    )

    listOf("train_bot", "dns").forEach { retiredComponent ->
      val result = harness.facade.redeployComponent(retiredComponent)
      assertFalse(result.success)
      assertEquals("Unknown component: $retiredComponent", result.message)
    }
    assertEquals(0, harness.runtimeInstaller.installCalls)
    assertTrue(harness.supervisor.restartCalls.isEmpty())
    assertTrue(harness.supervisor.stopCalls.isEmpty())
    assertEquals(0, harness.supervisor.healthCalls)
  }

  private fun buildHarness(
    config: StackConfigV1,
    healthSnapshots: List<HealthSnapshot>,
    manifestComponent: String = "vpn"
  ): TestHarness {
    val configJson = json.encodeToString(StackConfigV1.serializer(), config)
    val manifestJson = json.encodeToString(ComponentReleaseManifest.serializer(), testManifest(manifestComponent))
    val runtimeInstaller = FakeRuntimeInstaller()
    val supervisor = FakeSupervisor(healthSnapshots)
    val rootExecutor = FakeRootExecutor(configJson = configJson, releaseManifestJson = manifestJson)
    val healthChecker = RuntimeHealthChecker(CommandRunner { _ ->
      CommandResult(ok = true, stdout = "", stderr = "")
    })
    val store = InMemoryStackStore()
    val facade = OrchestratorFacade(
      stackStore = store,
      rootExecutor = rootExecutor,
      runtimeInstaller = runtimeInstaller,
      supervisor = supervisor,
      healthChecker = healthChecker,
      assetProvider = FakeAssetProvider(),
      supportBundleExporter = FakeSupportBundleExporter(),
      json = json
    )
    return TestHarness(facade, store, runtimeInstaller, supervisor, rootExecutor)
  }

  private fun testConfig(
    healthWaitSeconds: Int,
    healthRetrySeconds: Int,
    neighborGraceSeconds: Int
  ): StackConfigV1 {
    return StackConfigV1(
      vpn = StackConfigV1().vpn.copy(enabled = true),
      redeploy = RedeployConfig(
        healthWaitSeconds = healthWaitSeconds,
        healthRetrySeconds = healthRetrySeconds,
        neighborGraceSeconds = neighborGraceSeconds
      )
    )
  }

  private fun testManifest(component: String): ComponentReleaseManifest {
    val artifacts = listOf(
      lv.jolkins.pixelorchestrator.runtimeinstaller.ArtifactEntry(
        id = "tailscale-bundle",
        url = "/tmp/$component-release-123.tar.gz",
        sha256 = "abc123",
        fileName = "$component-release-123.tar.gz",
        sizeBytes = 1,
        required = true
      )
    )
    return ComponentReleaseManifest(
      schema = 1,
      componentId = component,
      releaseId = "$component-release-123",
      signatureSchema = "none",
      artifacts = artifacts
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
    ddns: Boolean = true,
    moduleHealth: Map<String, ModuleHealthState> = emptyMap()
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
      moduleHealth = moduleHealth,
      supervisorLoopHealthy = true,
      managementAuthHealthy = true,
      deployHealthy = true,
      supervisorHealthy = true
    )
  }

  private data class TestHarness(
    val facade: OrchestratorFacade,
    val store: InMemoryStackStore,
    val runtimeInstaller: FakeRuntimeInstaller,
    val supervisor: FakeSupervisor,
    val rootExecutor: FakeRootExecutor
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
    val commands = mutableListOf<String>()
    val scripts = mutableListOf<String>()

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult {
      commands += command
      val stdout = when {
        command.contains("/data/local/pixel-stack/conf/orchestrator-config-v1.json") -> configJson
        command.contains("/data/local/pixel-stack/conf/runtime/components/") -> releaseManifestJson
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
      scripts += script
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
      rootfsArtifactId: String?
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
          currentSymlinkPath = "/data/local/pixel-stack/apps/$component/current",
          previousTargetPath = "/data/local/pixel-stack/apps/$component/releases/$component-previous",
          installedTargetPath = "/data/local/pixel-stack/apps/$component/releases/${manifest.releaseId}"
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
    var healthCalls: Int = 0
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
      healthCalls += 1
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
