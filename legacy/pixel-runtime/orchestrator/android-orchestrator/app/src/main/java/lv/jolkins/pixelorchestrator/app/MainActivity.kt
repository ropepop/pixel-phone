package lv.jolkins.pixelorchestrator.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import lv.jolkins.pixelorchestrator.databinding.ActivityMainBinding
import lv.jolkins.pixelorchestrator.health.HealthScope

class MainActivity : ComponentActivity() {
  private lateinit var binding: ActivityMainBinding

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)

    val facade = AppGraph.facade(this)

    binding.bootstrapButton.setOnClickListener {
      lifecycleScope.launch {
        runAction("button:bootstrap") { facade.bootstrapStack() }
      }
    }

    binding.startAllButton.setOnClickListener {
      lifecycleScope.launch {
        runAction("button:start_all") { facade.startAll() }
      }
    }

    binding.stopAllButton.setOnClickListener {
      lifecycleScope.launch {
        runAction("button:stop_all") { facade.stopAll() }
      }
    }

    binding.healthButton.setOnClickListener {
      lifecycleScope.launch {
        runAction("button:health") { facade.runHealthCheck(HealthScope.FULL) }
      }
    }

    binding.ddnsButton.setOnClickListener {
      lifecycleScope.launch {
        runAction("button:sync_ddns") { facade.syncDdnsNow() }
      }
    }

    binding.exportButton.setOnClickListener {
      lifecycleScope.launch {
        runAction("button:export_bundle") { facade.exportSupportBundle(includeSecrets = false) }
      }
    }

    handleIntentActionIfPresent(facade, intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIntentActionIfPresent(AppGraph.facade(this), intent)
  }

  private fun renderResult(result: FacadeOperationResult) {
    val health = result.healthSnapshot
    val text = buildString {
      appendLine(if (result.success) "SUCCESS" else "FAILURE")
      appendLine(result.message)
      if (result.outputPath.isNotBlank()) {
        appendLine("Output: ${result.outputPath}")
      }
      if (health != null) {
        appendLine("root=${health.rootGranted}")
        appendLine("dns=${health.dnsHealthy}")
        appendLine("remote=${health.remoteHealthy}")
        appendLine("ssh=${health.sshHealthy}")
        appendLine("vpn=${health.vpnHealthy}")
        appendLine("management=${health.managementHealthy}")
        appendLine("management_auth=${health.managementAuthHealthy}")
        appendLine("train_bot=${health.trainBotHealthy}")
        appendLine("satiksme_bot=${health.satiksmeBotHealthy}")
        appendLine("site_notifier=${health.siteNotifierHealthy}")
        appendLine("ddns=${health.ddnsHealthy}")
        appendLine("supervisor_loop=${health.supervisorLoopHealthy}")
        appendLine("deploy=${health.deployHealthy}")
        appendLine("supervisor=${health.supervisorHealthy}")
      }
    }

    binding.statusText.text = text
    Log.i(TAG, text.trim())
  }

  private fun handleIntentActionIfPresent(facade: OrchestratorFacade, sourceIntent: Intent?) {
    val action = OrchestratorShellCommand.normalizeAction(sourceIntent?.getStringExtra(EXTRA_ORCHESTRATOR_ACTION))
    val component = sourceIntent?.getStringExtra(EXTRA_ORCHESTRATOR_COMPONENT)?.trim().orEmpty()
    val pixelRunId = sourceIntent?.getStringExtra(EXTRA_PIXEL_RUN_ID)?.trim().orEmpty()
    val dryRun = sourceIntent?.getBooleanExtra(OrchestratorShellCommand.EXTRA_DRY_RUN, false) ?: false
    if (action.isBlank()) {
      return
    }
    Log.i(TAG, "intent_action=$action component=$component")

    lifecycleScope.launch {
      runAction(
        label = "intent:$action:$component",
        facade = facade,
        pixelRunId = pixelRunId,
        action = action,
        component = component
      ) {
        when (action) {
          ACTION_BOOTSTRAP -> facade.bootstrapStack()
          ACTION_START_ALL -> facade.startAll()
          ACTION_STOP_ALL -> facade.stopAll()
          ACTION_HEALTH -> facade.runHealthCheck(HealthScope.FULL)
          ACTION_START_COMPONENT -> facade.startComponent(component)
          ACTION_STOP_COMPONENT -> facade.stopComponent(component)
          ACTION_RESTART_COMPONENT -> facade.restartComponent(component)
          ACTION_REDEPLOY_COMPONENT -> facade.redeployComponent(component)
          ACTION_HEALTH_COMPONENT -> facade.healthComponent(component)
          ACTION_SYNC_DDNS -> facade.syncDdnsNow()
          ACTION_EXPORT_BUNDLE -> facade.exportSupportBundle(includeSecrets = false)
          ACTION_CLEANUP -> facade.runCleanup(dryRun = dryRun)
          else -> FacadeOperationResult(false, "Unknown intent action: $action")
        }
      }
    }
  }

  private suspend fun runAction(
    label: String,
    facade: OrchestratorFacade? = null,
    pixelRunId: String = "",
    action: String = "",
    component: String = "",
    block: suspend () -> FacadeOperationResult
  ) {
    try {
      val result = block()
      renderResult(result)
      if (facade != null && pixelRunId.isNotBlank() && action.isNotBlank()) {
        facade.writeActionResult(pixelRunId, action, component, result)
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Throwable) {
      val failure = FacadeOperationResult(
        success = false,
        message = "Unhandled action exception (${error::class.java.name}): ${error.message ?: "(no message)"}"
      )
      renderResult(failure)
      if (facade != null && pixelRunId.isNotBlank() && action.isNotBlank()) {
        facade.writeActionResult(pixelRunId, action, component, failure)
      }
      Log.e(TAG, "action_failed label=$label", error)
    }
  }

  companion object {
    private const val TAG = "OrchestratorMain"
    const val EXTRA_ORCHESTRATOR_ACTION = OrchestratorShellCommand.EXTRA_ACTION
    const val EXTRA_ORCHESTRATOR_COMPONENT = OrchestratorShellCommand.EXTRA_COMPONENT
    const val EXTRA_PIXEL_RUN_ID = OrchestratorShellCommand.EXTRA_PIXEL_RUN_ID
    const val ACTION_BOOTSTRAP = OrchestratorShellCommand.ACTION_BOOTSTRAP
    const val ACTION_START_ALL = OrchestratorShellCommand.ACTION_START_ALL
    const val ACTION_STOP_ALL = OrchestratorShellCommand.ACTION_STOP_ALL
    const val ACTION_HEALTH = OrchestratorShellCommand.ACTION_HEALTH
    const val ACTION_START_COMPONENT = OrchestratorShellCommand.ACTION_START_COMPONENT
    const val ACTION_STOP_COMPONENT = OrchestratorShellCommand.ACTION_STOP_COMPONENT
    const val ACTION_RESTART_COMPONENT = OrchestratorShellCommand.ACTION_RESTART_COMPONENT
    const val ACTION_REDEPLOY_COMPONENT = OrchestratorShellCommand.ACTION_REDEPLOY_COMPONENT
    const val ACTION_HEALTH_COMPONENT = OrchestratorShellCommand.ACTION_HEALTH_COMPONENT
    const val ACTION_SYNC_DDNS = OrchestratorShellCommand.ACTION_SYNC_DDNS
    const val ACTION_EXPORT_BUNDLE = OrchestratorShellCommand.ACTION_EXPORT_BUNDLE
    const val ACTION_CLEANUP = OrchestratorShellCommand.ACTION_CLEANUP
  }
}
