package lv.jolkins.pixelorchestrator.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import lv.jolkins.pixelorchestrator.app.dashboard.DashboardEffect
import lv.jolkins.pixelorchestrator.app.dashboard.DashboardScreen
import lv.jolkins.pixelorchestrator.app.dashboard.DashboardViewModel
import lv.jolkins.pixelorchestrator.app.dashboard.PixelOrchestratorTheme
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationBackgroundReliabilitySupport
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
  private val dashboardViewModel: DashboardViewModel by viewModels()
  private var pendingSupportArchiveUri: Uri? = null
  private val supportShareLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) {
    val archiveUri = pendingSupportArchiveUri ?: return@registerForActivityResult
    pendingSupportArchiveUri = null
    window.decorView.postDelayed(
      { SupportBundleExporter.deleteArchive(this, archiveUri) },
      SUPPORT_SHARE_DELETE_GRACE_MILLIS
    )
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      PixelOrchestratorTheme {
        val state = dashboardViewModel.uiState.collectAsStateWithLifecycle().value
        DashboardScreen(
          state = state,
          onAction = dashboardViewModel::dispatch
        )
      }
    }

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        dashboardViewModel.effects.collect(::handleEffect)
      }
    }
    handleIntentActionIfPresent(intent)
  }

  override fun onResume() {
    super.onResume()
    dashboardViewModel.onForeground()
  }

  override fun onPause() {
    dashboardViewModel.onBackground()
    super.onPause()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIntentActionIfPresent(intent)
  }

  private fun handleEffect(effect: DashboardEffect) {
    when (effect) {
      DashboardEffect.OpenAccessibilitySettings -> {
        openSettingsIntent(
          primary = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
          fallback = PhoneAutomationBackgroundReliabilitySupport.appDetailsIntent(this),
          label = "accessibility"
        )
      }
      DashboardEffect.OpenNotificationAccessSettings -> {
        openSettingsIntent(
          primary = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
          fallback = PhoneAutomationBackgroundReliabilitySupport.appDetailsIntent(this),
          label = "notification_access"
        )
      }
      DashboardEffect.OpenBatterySettings -> {
        openSettingsIntent(
          primary = PhoneAutomationBackgroundReliabilitySupport.requestBatteryUnrestrictedIntent(this),
          fallback = PhoneAutomationBackgroundReliabilitySupport.batteryOptimizationSettingsIntent(this),
          label = "background_battery"
        )
      }
      DashboardEffect.OpenExactAlarmSettings -> {
        openSettingsIntent(
          primary = PhoneAutomationBackgroundReliabilitySupport.requestExactAlarmIntent(this),
          fallback = PhoneAutomationBackgroundReliabilitySupport.appDetailsIntent(this),
          label = "exact_alarm"
        )
      }
      is DashboardEffect.ShareSupportBundle -> {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
          type = "application/zip"
          putExtra(Intent.EXTRA_STREAM, effect.uri)
          clipData = ClipData.newRawUri("Redacted Pixel support bundle", effect.uri)
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
          pendingSupportArchiveUri = effect.uri
          supportShareLauncher.launch(Intent.createChooser(shareIntent, "Share redacted support bundle"))
        } catch (error: ActivityNotFoundException) {
          pendingSupportArchiveUri = null
          SupportBundleExporter.deleteArchive(this, effect.uri)
          Log.e(TAG, "support_bundle_share_unavailable", error)
        }
      }
    }
  }

  private fun handleIntentActionIfPresent(sourceIntent: Intent?) {
    val action = OrchestratorShellCommand.normalizeAction(
      sourceIntent?.getStringExtra(EXTRA_ORCHESTRATOR_ACTION)
    )
    if (action.isBlank()) return
    val component = sourceIntent?.getStringExtra(EXTRA_ORCHESTRATOR_COMPONENT)?.trim().orEmpty()
    val pixelRunId = sourceIntent?.getStringExtra(EXTRA_PIXEL_RUN_ID)?.trim().orEmpty()
    val dryRun = sourceIntent?.getBooleanExtra(OrchestratorShellCommand.EXTRA_DRY_RUN, false) ?: false
    sourceIntent?.removeExtra(EXTRA_ORCHESTRATOR_ACTION)
    Log.i(TAG, "intent_action=$action component=$component")
    dashboardViewModel.runIntentAction(
      action = action,
      component = component,
      pixelRunId = pixelRunId,
      dryRun = dryRun
    )
  }

  private fun openSettingsIntent(primary: Intent, fallback: Intent, label: String) {
    val intents = listOf(primary, fallback)
      .distinctBy { "${it.action}:${it.dataString.orEmpty()}" }
    var lastError: Throwable? = null
    for (candidate in intents) {
      try {
        startActivity(candidate)
        return
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: ActivityNotFoundException) {
        lastError = error
      } catch (error: SecurityException) {
        lastError = error
      }
    }
    Log.e(TAG, "settings_intent_launch_failed label=$label", lastError)
  }

  companion object {
    private const val SUPPORT_SHARE_DELETE_GRACE_MILLIS = 5_000L
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
