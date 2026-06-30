package lv.jolkins.pixelorchestrator.app

import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import lv.jolkins.pixelorchestrator.coreconfig.StackConfigV1
import lv.jolkins.pixelorchestrator.coreconfig.StackStateV1
import lv.jolkins.pixelorchestrator.coreconfig.StackStore
import lv.jolkins.pixelorchestrator.health.CommandRunner
import lv.jolkins.pixelorchestrator.health.CommandResult
import lv.jolkins.pixelorchestrator.health.RuntimeHealthChecker
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.rootexec.RootResult
import lv.jolkins.pixelorchestrator.runtimeinstaller.ArtifactEntry
import lv.jolkins.pixelorchestrator.runtimeinstaller.ArtifactManifest
import lv.jolkins.pixelorchestrator.runtimeinstaller.AssetProvider
import lv.jolkins.pixelorchestrator.runtimeinstaller.BootstrapResult
import lv.jolkins.pixelorchestrator.runtimeinstaller.ComponentReleaseManifest
import lv.jolkins.pixelorchestrator.runtimeinstaller.ReleaseRollbackMetadata
import lv.jolkins.pixelorchestrator.runtimeinstaller.RuntimeInstallerControl
import lv.jolkins.pixelorchestrator.runtimeinstaller.SyncResult
import lv.jolkins.pixelorchestrator.supervisor.SupervisorControl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration

class OrchestratorFacadeCleanupTest {
  private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }

  @Test
  fun cleanupDryRunProtectsCurrentRollbackAndManifestArtifacts() = runBlocking {
    val runtimeManifestJson =
      json.encodeToString(
        ArtifactManifest.serializer(),
        ArtifactManifest(
          schema = 1,
          manifestVersion = "pixel-redeploy-20260325T015455Z-22808",
          signatureSchema = "none",
          artifacts = listOf(
            ArtifactEntry(
              id = "adguardhome-rootfs",
              url = "/data/local/pixel-stack/conf/runtime/artifacts/adguardhome-rootfs-arm64.tar",
              sha256 = "abc",
              fileName = "adguardhome-rootfs-arm64.tar",
              sizeBytes = 10,
              required = true
            )
          )
        )
      )
    val siteNotifierManifestJson =
      json.encodeToString(
        ComponentReleaseManifest.serializer(),
        ComponentReleaseManifest(
          schema = 1,
          componentId = "site_notifier",
          releaseId = "site-notifier-20260319T163538Z",
          signatureSchema = "none",
          artifacts = listOf(
            ArtifactEntry(
              id = "site-notifier-bundle",
              url = "/data/local/pixel-stack/conf/runtime/components/site_notifier/artifacts/site-notifier-bundle.tar",
              sha256 = "def",
              fileName = "site-notifier-bundle.tar",
              sizeBytes = 20,
              required = true
            )
          )
        )
      )

    val rootExecutor = FakeCleanupRootExecutor(
      runtimeManifestJson = runtimeManifestJson,
      componentManifests = mapOf("site_notifier" to siteNotifierManifestJson),
      cleanupStdout = """
        SKIP	release_dir	4096	/data/local/pixel-stack/apps/train-bot/releases/train-current	protected
        CANDIDATE	release_dir	2048	/data/local/pixel-stack/apps/train-bot/releases/train-old	non_current_release
        CANDIDATE	app_cache	1024	/data/user/0/lv.jolkins.pixelorchestrator/cache/runtime-artifacts/site-notifier-bundle-old.tar	runtime_artifact_cache
      """.trimIndent()
    )
    val facade = buildFacade(rootExecutor)

    val result = facade.runCleanup(trigger = CleanupTrigger.MANUAL, dryRun = true)

    assertTrue(result.success)
    assertTrue(result.message.contains("Cleanup dry-run complete"))
    val report = rootExecutor.decodeCleanupReport(result.outputPath)
    assertEquals(CleanupReportStatus.DRY_RUN.wireValue(), report.status)
    assertTrue(report.protectedPaths.any { it.path == "/data/local/pixel-stack/apps/train-bot/releases/train-current" })
    assertTrue(report.protectedPaths.any { it.path == "/data/local/pixel-stack/apps/train-bot/releases/train-previous" })
    assertTrue(
      report.protectedPaths.any {
        it.path == "/data/local/pixel-stack/conf/runtime/components/site_notifier/artifacts/site-notifier-bundle.tar"
      }
    )
    assertTrue(report.candidates.any { it.path == "/data/local/pixel-stack/apps/train-bot/releases/train-old" })
    assertTrue(report.skippedPaths.any { it.path == "/data/local/pixel-stack/apps/train-bot/releases/train-current" })
  }

  @Test
  fun cleanupSkipStillWritesReportWhenMutationLockIsHeld() = runBlocking {
    val rootExecutor = FakeCleanupRootExecutor(
      runtimeManifestJson = "",
      componentManifests = emptyMap(),
      cleanupStdout = "",
      lockAvailable = false
    )
    val facade = buildFacade(rootExecutor)

    val result = facade.runCleanup(trigger = CleanupTrigger.MANUAL, dryRun = false)

    assertTrue(result.success)
    assertTrue(result.message.contains("Cleanup skipped"))
    val report = rootExecutor.decodeCleanupReport(result.outputPath)
    assertEquals(CleanupReportStatus.SKIPPED.wireValue(), report.status)
    assertTrue(report.skippedPaths.any { it.category == "mutation_lock" })
  }

  @Test
  fun cleanupRecoversStaleMutationLockWhenNoLocalMutationIsActive() = runBlocking {
    val rootExecutor = FakeCleanupRootExecutor(
      runtimeManifestJson = "",
      componentManifests = emptyMap(),
      cleanupStdout = """
        CANDIDATE	app_cache	1024	/data/user/0/lv.jolkins.pixelorchestrator/cache/runtime-artifacts/stale.tar	runtime_artifact_cache
      """.trimIndent(),
      lockAcquireFailuresBeforeSuccess = 1,
      staleLockOwner = "redeploy:train_bot"
    )
    val facade = buildFacade(rootExecutor)

    val result = facade.runCleanup(trigger = CleanupTrigger.MANUAL, dryRun = true)

    assertTrue(result.message.contains("Cleanup"))
    assertTrue(rootExecutor.commands.any { it.contains("/data/local/pixel-stack/run/orchestrator-mutation.lock/owner") })
    assertTrue(rootExecutor.commands.any { it.contains("rm -rf '/data/local/pixel-stack/run/orchestrator-mutation.lock'") })
  }

  @Test
  fun runtimeCleanupComponentDoesNotAutoStart() = runBlocking {
    val rootExecutor = FakeCleanupRootExecutor(
      runtimeManifestJson = basicRuntimeManifestJson(),
      componentManifests = emptyMap(),
      cleanupStdout = ""
    )
    val controller = RuntimeCleanupComponentController(rootExecutor, json)

    assertFalse(controller.shouldAutoStart())
    assertTrue(controller.start())
    assertFalse(rootExecutor.commands.any { it.contains("/data/local/pixel-stack/bin/pixel-runtime-cleanup.sh") })
  }

  @Test
  fun runtimeCleanupComponentStartRunsCleanupAction() = runBlocking {
    val rootExecutor = FakeCleanupRootExecutor(
      runtimeManifestJson = basicRuntimeManifestJson(),
      componentManifests = emptyMap(),
      cleanupStdout = """
        DELETE	tmp_artifact	4096	/data/local/tmp/pixel-orchestrator-runtime-old	old_runtime_tmp
      """.trimIndent()
    )
    val facade = buildFacade(rootExecutor)

    val result = facade.startComponent(RuntimeCleanupComponentController.COMPONENT_NAME)

    assertTrue(result.success)
    assertTrue(result.message.contains("Cleanup complete"))
    assertTrue(rootExecutor.commands.any { it.contains("/data/local/pixel-stack/bin/pixel-runtime-cleanup.sh") })
    val report = rootExecutor.decodeCleanupReport(result.outputPath)
    assertEquals(CleanupReportStatus.COMPLETED.wireValue(), report.status)
    assertTrue(report.deletedPaths.any { it.category == "tmp_artifact" })
  }

  private fun basicRuntimeManifestJson(): String =
    json.encodeToString(
      ArtifactManifest.serializer(),
      ArtifactManifest(
        schema = 1,
        manifestVersion = "pixel-cleanup-test",
        signatureSchema = "none",
        artifacts = listOf(
          ArtifactEntry(
            id = "adguardhome-rootfs",
            url = "/data/local/pixel-stack/conf/runtime/artifacts/adguardhome-rootfs-arm64.tar",
            sha256 = "abc",
            fileName = "adguardhome-rootfs-arm64.tar",
            sizeBytes = 10,
            required = true
          )
        )
      )
    )

  private fun buildFacade(rootExecutor: FakeCleanupRootExecutor): OrchestratorFacade {
    val healthChecker = RuntimeHealthChecker(CommandRunner { CommandResult(ok = true, stdout = "", stderr = "") })
    return OrchestratorFacade(
      stackStore = InMemoryStackStore(),
      rootExecutor = rootExecutor,
      runtimeInstaller = FakeRuntimeInstaller(),
      supervisor = FakeSupervisor(),
      healthChecker = healthChecker,
      assetProvider = FakeAssetProvider(),
      supportBundleExporter = FakeSupportBundleExporter(),
      json = json
    )
  }

  private class InMemoryStackStore : StackStore() {
    private var config = StackConfigV1()
    private var state = StackStateV1()

    override fun saveConfig(config: StackConfigV1) {
      this.config = config
    }

    override fun loadConfigOrDefault(): StackConfigV1 = config

    override fun saveState(state: StackStateV1) {
      this.state = state
    }

    override fun loadStateOrDefault(): StackStateV1 = state
  }

  private class FakeCleanupRootExecutor(
    private val runtimeManifestJson: String,
    private val componentManifests: Map<String, String>,
    private val cleanupStdout: String,
    private val lockAvailable: Boolean = true,
    private val lockAcquireFailuresBeforeSuccess: Int = 0,
    private val staleLockOwner: String = ""
  ) : RootExecutor {
    private val writes = linkedMapOf<String, String>()
    val commands = mutableListOf<String>()
    private var remainingLockAcquireFailures = lockAcquireFailuresBeforeSuccess

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult {
      commands += command
      return when {
        command.contains("orchestrator-mutation.lock") && command.contains("mkdir") -> {
          if (remainingLockAcquireFailures > 0) {
            remainingLockAcquireFailures -= 1
            RootResult(1, "", "locked", command, 0)
          } else if (lockAvailable) {
            ok(command)
          } else {
            RootResult(1, "", "locked", command, 0)
          }
        }
        command.contains("/data/local/pixel-stack/run/orchestrator-mutation.lock/owner") -> ok(command, staleLockOwner)
        command.contains("/data/local/pixel-stack/conf/runtime/runtime-manifest.json") -> ok(command, runtimeManifestJson)
        command.contains("/data/local/pixel-stack/conf/runtime/components/") -> {
          val component = componentManifests.keys.firstOrNull { command.contains("/$it/release-manifest.json") }
          ok(command, component?.let { componentManifests[it] }.orEmpty())
        }
        else -> ok(command)
      }
    }

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      commands += script
      return when {
        script.contains("runtime_root='/data/local/pixel-stack/apps/train-bot'") ->
          ok(
            script,
            """
            CURRENT	/data/local/pixel-stack/apps/train-bot/releases/train-current
            RELEASE	/data/local/pixel-stack/apps/train-bot/releases/train-current
            RELEASE	/data/local/pixel-stack/apps/train-bot/releases/train-previous
            RELEASE	/data/local/pixel-stack/apps/train-bot/releases/train-old
            """.trimIndent()
          )
        script.contains("runtime_root='/data/local/pixel-stack/apps/satiksme-bot'") ->
          ok(
            script,
            """
            CURRENT	/data/local/pixel-stack/apps/satiksme-bot/releases/satiksme-current
            RELEASE	/data/local/pixel-stack/apps/satiksme-bot/releases/satiksme-current
            RELEASE	/data/local/pixel-stack/apps/satiksme-bot/releases/satiksme-previous
            """.trimIndent()
          )
        script.contains("runtime_root='/data/local/pixel-stack/apps/site-notifications'") ->
          ok(
            script,
            """
            CURRENT	/data/local/pixel-stack/apps/site-notifications/releases/site-current
            RELEASE	/data/local/pixel-stack/apps/site-notifications/releases/site-current
            RELEASE	/data/local/pixel-stack/apps/site-notifications/releases/site-previous
            """.trimIndent()
          )
        script.contains("runtime_root='/data/local/pixel-stack/apps/subscription-bot'") ->
          ok(
            script,
            """
            CURRENT	/data/local/pixel-stack/apps/subscription-bot/releases/subscription-current
            RELEASE	/data/local/pixel-stack/apps/subscription-bot/releases/subscription-current
            RELEASE	/data/local/pixel-stack/apps/subscription-bot/releases/subscription-previous
            """.trimIndent()
          )
        script.contains("ls -1dt /data/user/0/com.termux/files/home/telegram-train-app/workloads/site-notifications/.artifacts/site-notifier/*") ->
          ok(
            script,
            """
            /data/user/0/com.termux/files/home/telegram-train-app/workloads/site-notifications/.artifacts/site-notifier/site-notifier-bundle-site-notifier-20260308T124938Z.tar
            /data/user/0/com.termux/files/home/telegram-train-app/workloads/site-notifications/.artifacts/site-notifier/site-notifier-bundle-site-notifier-20260308T124748Z.tar
            """.trimIndent()
          )
        script.contains("ls -1dt /data/user/0/com.termux/files/home/telegram-train-app/workloads/site-notifications/.artifacts/component-releases/*") ->
          ok(
            script,
            """
            /data/user/0/com.termux/files/home/telegram-train-app/workloads/site-notifications/.artifacts/component-releases/site_notifier-site-notifier-20260308T124938Z
            /data/user/0/com.termux/files/home/telegram-train-app/workloads/site-notifications/.artifacts/component-releases/site_notifier-site-notifier-20260308T124748Z
            """.trimIndent()
          )
        script.contains("ls -1dt /data/user/0/com.termux/files/home/telegram-train-app/orchestrator/.artifacts/runtime-local/*") ->
          ok(
            script,
            """
            /data/user/0/com.termux/files/home/telegram-train-app/orchestrator/.artifacts/runtime-local/local-20260308Tdns-hardening2
            /data/user/0/com.termux/files/home/telegram-train-app/orchestrator/.artifacts/runtime-local/local-20260308Tdns-hardening
            """.trimIndent()
          )
        script.contains("ls -1dt /data/user/0/com.termux/files/home/site-notifications-build*") ->
          ok(
            script,
            """
            /data/user/0/com.termux/files/home/site-notifications-build-site-notifier-20260309T135009Z
            /data/user/0/com.termux/files/home/site-notifications-build
            """.trimIndent()
          )
        script.contains("/data/local/pixel-stack/bin/pixel-runtime-cleanup.sh") -> ok(script, cleanupStdout)
        script.contains("cp \"${'$'}source_file\" \"${'$'}tmp\"") -> captureWrite(script)
        else -> ok(script)
      }
    }

    fun decodeCleanupReport(path: String): CleanupReport {
      val body = writes[path] ?: error("Missing written report at $path")
      return Json { ignoreUnknownKeys = true }.decodeFromString(body)
    }

    private fun captureWrite(script: String): RootResult {
      val target = Regex("""target='([^']+)'""").find(script)?.groupValues?.get(1)
        ?: error("Missing target in script: $script")
      val sourceFile = Regex("""source_file='([^']+)'""").find(script)?.groupValues?.get(1)
        ?: error("Missing source file in script: $script")
      writes[target] = File(sourceFile).readText()
      return ok(script)
    }

    private fun ok(command: String, stdout: String = ""): RootResult {
      return RootResult(0, stdout, "", command, 0)
    }
  }

  private class FakeRuntimeInstaller : RuntimeInstallerControl {
    override suspend fun bootstrap(
      config: StackConfigV1,
      assets: AssetProvider,
      manifest: ArtifactManifest,
      rootfsArtifactId: String
    ): BootstrapResult = BootstrapResult(true, true, 0, "bootstrap", emptyList())

    override suspend fun syncBundledRuntimeAssets(assets: AssetProvider, component: String?): SyncResult {
      return SyncResult(success = true, message = "synced")
    }

    override suspend fun installComponentRelease(
      config: StackConfigV1,
      component: String,
      manifest: ComponentReleaseManifest
    ): SyncResult = SyncResult(success = true, message = "installed")

    override suspend fun rollbackComponentRelease(
      config: StackConfigV1,
      component: String,
      rollbackMetadata: ReleaseRollbackMetadata
    ): SyncResult = SyncResult(success = true, message = "rolled back")

    override suspend fun pruneComponentReleases(config: StackConfigV1, component: String, keepReleases: Int): SyncResult {
      return SyncResult(success = true, message = "pruned")
    }
  }

  private class FakeSupervisor : SupervisorControl {
    override suspend fun startAll() {}
    override suspend fun resumeSupervision() {}
    override suspend fun stopAll() {}
    override suspend fun startComponent(component: String) {}
    override suspend fun stopComponent(component: String) {}
    override suspend fun restart(component: String) {}
    override suspend fun runHealthCheck(scope: lv.jolkins.pixelorchestrator.health.HealthScope) =
      lv.jolkins.pixelorchestrator.coreconfig.HealthSnapshot(
        rootGranted = true,
        supervisorLoopHealthy = true,
        managementAuthHealthy = true,
        deployHealthy = true,
        supervisorHealthy = true
      )
    override suspend fun syncDdnsNow() {}
  }

  private class FakeAssetProvider : AssetProvider {
    override fun open(path: String) = ByteArrayInputStream("#!/system/bin/sh\n".toByteArray())
    override fun list(path: String): List<String> = emptyList()
  }

  private class FakeSupportBundleExporter : SupportBundleExporting {
    override suspend fun export(
      config: StackConfigV1,
      state: StackStateV1,
      includeSecrets: Boolean
    ) = java.io.File.createTempFile("cleanup-test", ".zip")
  }
}
