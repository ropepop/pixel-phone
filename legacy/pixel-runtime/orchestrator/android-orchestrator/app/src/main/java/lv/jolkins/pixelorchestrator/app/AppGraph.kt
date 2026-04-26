package lv.jolkins.pixelorchestrator.app

import android.content.Context
import kotlinx.serialization.json.Json
import lv.jolkins.pixelorchestrator.coreconfig.StackConfigV1
import lv.jolkins.pixelorchestrator.coreconfig.StackStore
import lv.jolkins.pixelorchestrator.health.RuntimeHealthChecker
import lv.jolkins.pixelorchestrator.rootexec.SuRootExecutor
import lv.jolkins.pixelorchestrator.runtimeinstaller.ArtifactSyncer
import lv.jolkins.pixelorchestrator.runtimeinstaller.RuntimeInstaller
import lv.jolkins.pixelorchestrator.supervisor.RootScriptController
import lv.jolkins.pixelorchestrator.supervisor.SupervisorEngine

object AppGraph {
  @Volatile
  private var facade: OrchestratorFacade? = null

  fun facade(context: Context): OrchestratorFacade {
    val existing = facade
    if (existing != null) {
      return existing
    }

    synchronized(this) {
      val recheck = facade
      if (recheck != null) {
        return recheck
      }

      val appContext = context.applicationContext
      val storeRoot = appContext.filesDir.toPath().resolve("stack-store")
      val store = StackStore(
        configPath = storeRoot.resolve("orchestrator-config-v1.json"),
        statePath = storeRoot.resolve("orchestrator-state-v1.json")
      )
      val rootExecutor = SuRootExecutor()
      val healthChecker = RuntimeHealthChecker(RootCommandRunnerAdapter(rootExecutor))

      val components = ComponentRegistry.load(appContext).associate { entry ->
        entry.id to RootScriptController(
          name = entry.id,
          rootExecutor = rootExecutor,
          startCommand = entry.startCommand,
          stopCommand = entry.stopCommand,
          healthCommand = entry.healthCommand
        )
      }

      val supervisor = SupervisorEngine(
        configProvider = { runCatching { store.loadConfigOrDefault() }.getOrElse { StackConfigV1() } },
        stateStore = store,
        healthChecker = healthChecker,
        components = components
      )

      val artifactSyncer = ArtifactSyncer(
        cacheDir = appContext.cacheDir.toPath().resolve("runtime-artifacts"),
        rootExecutor = rootExecutor
      )
      val installer = RuntimeInstaller(rootExecutor, artifactSyncer)
      val assetProvider = AndroidAssetProvider(appContext.assets)
      val bundleExporter = SupportBundleExporter(
        context = appContext,
        rootExecutor = rootExecutor
      )

      val created = OrchestratorFacade(
        stackStore = store,
        rootExecutor = rootExecutor,
        runtimeInstaller = installer,
        supervisor = supervisor,
        healthChecker = healthChecker,
        assetProvider = assetProvider,
        supportBundleExporter = bundleExporter,
        json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
      )

      facade = created
      return created
    }
  }
}
