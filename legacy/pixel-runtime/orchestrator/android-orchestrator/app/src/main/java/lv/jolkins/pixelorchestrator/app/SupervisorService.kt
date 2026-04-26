package lv.jolkins.pixelorchestrator.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import lv.jolkins.pixelorchestrator.health.HealthScope

class SupervisorService : Service() {
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  override fun onCreate() {
    super.onCreate()
    NotificationHelper.ensureChannel(this)
    startForeground(4001, NotificationHelper.buildForegroundNotification(this))
    NightlyCleanupScheduler(this).scheduleNext(reason = "service_create")
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val action = intent?.action
    val commandAction = OrchestratorShellCommand.normalizeAction(intent?.getStringExtra(OrchestratorShellCommand.EXTRA_ACTION))
    val component = intent?.getStringExtra(EXTRA_COMPONENT).orEmpty()
    val bootEventToken = intent?.getStringExtra(EXTRA_BOOT_EVENT_TOKEN).orEmpty()
    val pixelRunId = intent?.getStringExtra(EXTRA_PIXEL_RUN_ID).orEmpty()
    val dryRun = intent?.getBooleanExtra(EXTRA_CLEANUP_DRY_RUN, false) ?: false
    val cleanupTrigger = intent?.getStringExtra(EXTRA_CLEANUP_TRIGGER).orEmpty()
    val facade = AppGraph.facade(this)
    val resultAction = commandAction.ifBlank { OrchestratorShellCommand.fromSupervisorAction(action).orEmpty() }

    serviceScope.launch {
      val result = when (action) {
        null -> facade.resumeSupervision()

        ACTION_BOOT_START -> {
          FacadeOperationResult(true, "Ignoring legacy boot start request")
        }

        ACTION_BOOT_RECOVER -> {
          when (resolveBootRecoveryMode(shouldHandleBootStart(bootEventToken))) {
            BootRecoveryMode.START_ALL -> facade.startAll()
            BootRecoveryMode.RESUME_SUPERVISION -> {
              facade.resumeSupervision().copy(message = "Duplicate boot recovery resumed supervision")
            }
          }
        }

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
        ACTION_CLEANUP -> facade.runCleanup(
          trigger = CleanupTrigger.fromWire(cleanupTrigger),
          dryRun = dryRun
        )
        else -> FacadeOperationResult(false, "Unknown action: $action")
      }

      Log.i(
        TAG,
        "action=$action command_action=$resultAction component=$component success=${result.success} message=${result.message}"
      )
      result.healthSnapshot?.let { health ->
        Log.i(
          TAG,
          "health root=${health.rootGranted} dns=${health.dnsHealthy} ssh=${health.sshHealthy} vpn=${health.vpnHealthy} management=${health.managementHealthy} managementAuth=${health.managementAuthHealthy} train=${health.trainBotHealthy} satiksme=${health.satiksmeBotHealthy} notifier=${health.siteNotifierHealthy} remote=${health.remoteHealthy} ddns=${health.ddnsHealthy} loop=${health.supervisorLoopHealthy} deploy=${health.deployHealthy} supervisor=${health.supervisorHealthy}"
        )
      }
      if (pixelRunId.isNotBlank() && resultAction.isNotBlank()) {
        facade.writeActionResult(pixelRunId, resultAction, component, result)
      }
      if (action == ACTION_CLEANUP) {
        NightlyCleanupScheduler(this@SupervisorService).scheduleNext(reason = "cleanup_complete:${cleanupTrigger.ifBlank { "manual" }}")
      }
    }

    return START_STICKY
  }

  override fun onDestroy() {
    serviceScope.cancel()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  companion object {
    private const val TAG = "SupervisorService"
    private const val PREFS_NAME = "supervisor_service"
    private const val PREF_LAST_BOOT_EVENT_TOKEN = "last_boot_event_token"
    private const val PREF_LAST_BOOT_COUNT = "last_boot_count"
    const val ACTION_BOOT_START = "lv.jolkins.pixelorchestrator.action.BOOT_START"
    const val ACTION_BOOT_RECOVER = "lv.jolkins.pixelorchestrator.action.BOOT_RECOVER"
    const val ACTION_BOOTSTRAP = "lv.jolkins.pixelorchestrator.action.BOOTSTRAP"
    const val ACTION_START_ALL = "lv.jolkins.pixelorchestrator.action.START_ALL"
    const val ACTION_STOP_ALL = "lv.jolkins.pixelorchestrator.action.STOP_ALL"
    const val ACTION_HEALTH = "lv.jolkins.pixelorchestrator.action.HEALTH"
    const val ACTION_START_COMPONENT = "lv.jolkins.pixelorchestrator.action.START_COMPONENT"
    const val ACTION_STOP_COMPONENT = "lv.jolkins.pixelorchestrator.action.STOP_COMPONENT"
    const val ACTION_RESTART_COMPONENT = "lv.jolkins.pixelorchestrator.action.RESTART_COMPONENT"
    const val ACTION_REDEPLOY_COMPONENT = "lv.jolkins.pixelorchestrator.action.REDEPLOY_COMPONENT"
    const val ACTION_HEALTH_COMPONENT = "lv.jolkins.pixelorchestrator.action.HEALTH_COMPONENT"
    const val ACTION_SYNC_DDNS = "lv.jolkins.pixelorchestrator.action.SYNC_DDNS"
    const val ACTION_EXPORT_BUNDLE = "lv.jolkins.pixelorchestrator.action.EXPORT_BUNDLE"
    const val ACTION_CLEANUP = "lv.jolkins.pixelorchestrator.action.CLEANUP"
    const val EXTRA_COMPONENT = OrchestratorShellCommand.EXTRA_COMPONENT
    const val EXTRA_BOOT_EVENT_TOKEN = "orchestrator_boot_event_token"
    const val EXTRA_PIXEL_RUN_ID = OrchestratorShellCommand.EXTRA_PIXEL_RUN_ID
    const val EXTRA_CLEANUP_DRY_RUN = OrchestratorShellCommand.EXTRA_DRY_RUN
    const val EXTRA_CLEANUP_TRIGGER = "orchestrator_cleanup_trigger"

    internal fun resolveBootRecoveryMode(shouldHandleFullStart: Boolean): BootRecoveryMode {
      return if (shouldHandleFullStart) {
        BootRecoveryMode.START_ALL
      } else {
        BootRecoveryMode.RESUME_SUPERVISION
      }
    }

    fun start(
      context: Context,
      action: String,
      component: String = "",
      bootEventToken: String = "",
      pixelRunId: String = "",
      commandAction: String = "",
      dryRun: Boolean = false,
      cleanupTrigger: String = ""
    ) {
      val intent = Intent(context, SupervisorService::class.java).setAction(action)
      if (commandAction.isNotBlank()) {
        intent.putExtra(OrchestratorShellCommand.EXTRA_ACTION, commandAction)
      }
      if (component.isNotBlank()) {
        intent.putExtra(EXTRA_COMPONENT, component)
      }
      if (bootEventToken.isNotBlank()) {
        intent.putExtra(EXTRA_BOOT_EVENT_TOKEN, bootEventToken)
      }
      if (pixelRunId.isNotBlank()) {
        intent.putExtra(EXTRA_PIXEL_RUN_ID, pixelRunId)
      }
      if (dryRun) {
        intent.putExtra(EXTRA_CLEANUP_DRY_RUN, true)
      }
      if (cleanupTrigger.isNotBlank()) {
        intent.putExtra(EXTRA_CLEANUP_TRIGGER, cleanupTrigger)
      }
      context.startForegroundService(intent)
    }
  }

  private fun shouldHandleBootStart(token: String): Boolean {
    if (token.isBlank()) {
      Log.w(TAG, "boot start request missing token; skipping full start")
      return false
    }
    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    val previous = prefs.getString(PREF_LAST_BOOT_EVENT_TOKEN, "").orEmpty()
    val (action, bootCount) = parseBootEventToken(token)
    if (action == Intent.ACTION_BOOT_COMPLETED && bootCount >= 0) {
      val previousBootCount = prefs.getInt(PREF_LAST_BOOT_COUNT, -1)
      if (previousBootCount == bootCount) {
        Log.i(TAG, "boot start request already handled token=$token")
        return false
      }
      if (previousBootCount < 0 && previous.startsWith("${Intent.ACTION_BOOT_COMPLETED}:")) {
        prefs.edit()
          .putString(PREF_LAST_BOOT_EVENT_TOKEN, token)
          .putInt(PREF_LAST_BOOT_COUNT, bootCount)
          .apply()
        Log.i(TAG, "boot start request already handled via legacy token token=$token previous=$previous")
        return false
      }
      prefs.edit()
        .putString(PREF_LAST_BOOT_EVENT_TOKEN, token)
        .putInt(PREF_LAST_BOOT_COUNT, bootCount)
        .apply()
      return true
    }
    if (previous == token) {
      Log.i(TAG, "boot start request already handled token=$token")
      return false
    }
    prefs.edit().putString(PREF_LAST_BOOT_EVENT_TOKEN, token).apply()
    return true
  }

  private fun parseBootEventToken(token: String): Pair<String, Int> {
    val delimiter = token.lastIndexOf(':')
    if (delimiter <= 0 || delimiter >= token.length - 1) {
      return token to -1
    }
    val action = token.substring(0, delimiter)
    val count = token.substring(delimiter + 1).toIntOrNull() ?: return token to -1
    return action to count
  }
}

internal enum class BootRecoveryMode {
  START_ALL,
  RESUME_SUPERVISION
}
