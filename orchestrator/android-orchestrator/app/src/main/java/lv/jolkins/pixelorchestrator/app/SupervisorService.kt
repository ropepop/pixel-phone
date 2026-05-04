package lv.jolkins.pixelorchestrator.app

import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lv.jolkins.pixelorchestrator.app.cpufrequency.CpuFrequencyPreferencesStore
import lv.jolkins.pixelorchestrator.app.cpufrequency.CpuFrequencyRuntime
import lv.jolkins.pixelorchestrator.app.cpufrequency.CpuFrequencyRuntimeState
import lv.jolkins.pixelorchestrator.app.cpufrequency.CpuFrequencySettingsSnapshot
import lv.jolkins.pixelorchestrator.health.HealthScope
import lv.jolkins.pixelorchestrator.app.phoneautomation.AndroidPhoneAutomationRuntime
import lv.jolkins.pixelorchestrator.app.phoneautomation.NotifyingPhoneAutomationSettingsStore
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationAccessibilityService
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationNotificationListenerService
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationPendingRecoveryAction
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationPendingRecoveryPhase
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationPreferencesStore
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationHandoffInterruptionDecision
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationPrerequisiteIssue
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationPrerequisiteSnapshot
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationRecoveryRequest
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationCoordinator
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationRuntimeState
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationServiceBridge
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationServicePermissions
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationSettingsSnapshot
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationSettingsStore
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationSupervisorController
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationWakeReason
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationWakeScheduleAction
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationWakeScheduler
import lv.jolkins.pixelorchestrator.app.phoneautomation.AndroidPhoneAutomationWakeAlarmClient
import lv.jolkins.pixelorchestrator.app.phoneautomation.TouchBrightnessRuntime
import lv.jolkins.pixelorchestrator.app.phoneautomation.TouchBrightnessRuntimeState
import lv.jolkins.pixelorchestrator.app.phoneautomation.hasQueuedPendingRecovery
import lv.jolkins.pixelorchestrator.app.phoneautomation.issueFor
import lv.jolkins.pixelorchestrator.app.phoneautomation.isProtectedSpeedtestHandoffInProgress
import lv.jolkins.pixelorchestrator.app.phoneautomation.shouldDeferPhoneAutomationPrerequisiteRecovery
import lv.jolkins.pixelorchestrator.app.ticket.TicketScreenConfig
import lv.jolkins.pixelorchestrator.app.ticket.TicketServicePreferencesStore
import lv.jolkins.pixelorchestrator.app.ticket.TicketServiceRuntimeState
import lv.jolkins.pixelorchestrator.app.ticket.TicketServiceSettingsSnapshot
import lv.jolkins.pixelorchestrator.app.ticket.TicketServiceSettingsStore
import lv.jolkins.pixelorchestrator.app.ticket.TicketStreamService
import lv.jolkins.pixelorchestrator.rootexec.SuRootExecutor
import kotlin.time.Duration

class SupervisorService : Service() {
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private lateinit var phoneAutomationStore: PhoneAutomationSettingsStore
  private lateinit var phoneAutomationRuntime: AndroidPhoneAutomationRuntime
  private lateinit var touchBrightnessRuntime: TouchBrightnessRuntime
  private lateinit var cpuFrequencyStore: CpuFrequencyPreferencesStore
  private lateinit var cpuFrequencyRuntime: CpuFrequencyRuntime
  private lateinit var ticketServiceStore: TicketServiceSettingsStore
  private lateinit var phoneAutomationSupervisorController: PhoneAutomationSupervisorController
  private lateinit var phoneAutomationWakeScheduler: PhoneAutomationWakeScheduler
  private lateinit var prerequisiteMonitor: PhoneAutomationPrerequisiteMonitor
  private var prerequisiteMonitorJob: Job? = null
  private var ticketServiceMonitorJob: Job? = null
  private val ticketServiceEnsureMutex = Mutex()
  private val ticketReadinessRootExecutor = SuRootExecutor()
  private var deferredTouchBrightnessResumeRequested: Boolean = false

  override fun onCreate() {
    super.onCreate()
    try {
      phoneAutomationWakeScheduler = PhoneAutomationWakeScheduler(
        alarmClient = AndroidPhoneAutomationWakeAlarmClient(this)
      )
      val rawPhoneAutomationStore = PhoneAutomationPreferencesStore(this)
      phoneAutomationStore = NotifyingPhoneAutomationSettingsStore(rawPhoneAutomationStore) { snapshot ->
        handlePhoneAutomationSnapshotChanged(snapshot)
      }
      phoneAutomationRuntime = AndroidPhoneAutomationRuntime(
        context = this,
        scope = serviceScope,
        settingsStore = phoneAutomationStore,
        rootExecutor = SuRootExecutor(),
        onSnapshotChanged = ::updateForegroundNotification
      )
      touchBrightnessRuntime = TouchBrightnessRuntime(
        context = this,
        scope = serviceScope,
        settingsStore = phoneAutomationStore,
        rootExecutor = SuRootExecutor(),
        onSnapshotChanged = ::updateForegroundNotification
      )
      cpuFrequencyStore = CpuFrequencyPreferencesStore(this)
      ticketServiceStore = TicketServicePreferencesStore(this)
      cpuFrequencyRuntime = CpuFrequencyRuntime(
        settingsStore = cpuFrequencyStore,
        rootExecutor = SuRootExecutor(),
        scope = serviceScope,
        onChanged = {
          updateForegroundNotification(
            phoneAutomationStore.load(),
            cpuFrequencyStore.load()
          )
        }
      )
      phoneAutomationSupervisorController = PhoneAutomationSupervisorController(
        settingsStore = phoneAutomationStore,
        runtimeController = phoneAutomationRuntime,
        touchBrightnessRuntimeController = touchBrightnessRuntime,
        logger = { message -> Log.i("PhoneAutomationSupervisor", message) }
      )
      prerequisiteMonitor = PhoneAutomationPrerequisiteMonitor(this)
      NotificationHelper.ensureChannel(this)
      promoteToForeground()
      NightlyCleanupScheduler(this).scheduleNext(reason = "service_create")
      reschedulePhoneAutomationWake(reason = "service_create", force = true)
      syncPhoneAutomation(trigger = "service_create")
      syncCpuFrequency(trigger = "service_create")
      syncTicketService(trigger = "service_create", facade = AppGraph.facade(this))
      startPhoneAutomationPrerequisiteMonitor()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Throwable) {
      handleStartupFailure(stage = "on_create", error = error)
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val action = intent?.action
    val commandAction = OrchestratorShellCommand.normalizeAction(intent?.getStringExtra(OrchestratorShellCommand.EXTRA_ACTION))
    val component = intent?.getStringExtra(EXTRA_COMPONENT).orEmpty()
    val bootEventToken = intent?.getStringExtra(EXTRA_BOOT_EVENT_TOKEN).orEmpty()
    val pixelRunId = intent?.getStringExtra(EXTRA_PIXEL_RUN_ID).orEmpty()
    val dryRun = intent?.getBooleanExtra(EXTRA_CLEANUP_DRY_RUN, false) ?: false
    val cleanupTrigger = intent?.getStringExtra(EXTRA_CLEANUP_TRIGGER).orEmpty()
    val wakeReason = intent?.getStringExtra(EXTRA_PHONE_AUTOMATION_WAKE_REASON).orEmpty()
    val wakeDeadlineAtMillis = intent?.getLongExtra(EXTRA_PHONE_AUTOMATION_WAKE_DEADLINE_AT_MILLIS, 0L) ?: 0L
    val wakeToken = intent?.getStringExtra(EXTRA_PHONE_AUTOMATION_WAKE_TOKEN).orEmpty()
    val facade = AppGraph.facade(this)
    val resultAction = commandAction.ifBlank { OrchestratorShellCommand.fromSupervisorAction(action).orEmpty() }
    val ignorePhoneAutomationWake = action == ACTION_PHONE_AUTOMATION_WAKE &&
      shouldIgnorePhoneAutomationWake(
        wakeReason = wakeReason,
        wakeDeadlineAtMillis = wakeDeadlineAtMillis,
        wakeToken = wakeToken
      )
    if (action != ACTION_INTERRUPT_PHONE_AUTOMATION_HANDOFF && !ignorePhoneAutomationWake) {
      syncPhoneAutomation(trigger = action ?: "resume_supervision")
      syncCpuFrequency(trigger = action ?: "resume_supervision")
      if (
        action != ACTION_REFRESH_TICKET_SERVICE &&
        action != ACTION_TICKET_START_SERVER &&
        action != ACTION_TICKET_STOP_SERVER
      ) {
        syncTicketService(trigger = action ?: "resume_supervision", facade = facade)
      }
    }

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
        ACTION_TICKET_START_SERVER -> {
          requestTicketStreamService(
            action = TicketScreenConfig.ACTION_START_SERVER,
            successMessage = "Ticket remote server start requested",
            failurePrefix = "Ticket remote server start failed"
          )
        }
        ACTION_TICKET_STOP_SERVER -> {
          requestTicketStreamService(
            action = TicketScreenConfig.ACTION_STOP_SERVER,
            successMessage = "Ticket remote server stop requested",
            failurePrefix = "Ticket remote server stop failed"
          )
        }
        ACTION_REFRESH_PHONE_AUTOMATION -> FacadeOperationResult(true, "Phone automation refreshed")
        ACTION_REFRESH_CPU_FREQUENCY -> FacadeOperationResult(true, "CPU and GPU cap control refreshed")
        ACTION_REFRESH_TICKET_SERVICE -> {
          syncTicketService(trigger = action, facade = facade)
          FacadeOperationResult(true, "Ticket service reliability refreshed")
        }
        ACTION_PHONE_AUTOMATION_WAKE -> FacadeOperationResult(
          true,
          if (ignorePhoneAutomationWake) {
            "Phone automation wake ignored as stale"
          } else if (wakeReason.isBlank()) {
            "Phone automation wake handled"
          } else {
            "Phone automation wake handled ($wakeReason@$wakeDeadlineAtMillis)"
          }
        )
        ACTION_INTERRUPT_PHONE_AUTOMATION_HANDOFF -> {
          val decision = phoneAutomationSupervisorController.interruptProtectedHandoff(
            detail = "Speedtest restart was interrupted because the app was opened"
          )
          val snapshot = phoneAutomationStore.load()
          updateForegroundNotification(snapshot)
          Log.i(
            TAG,
            "phone_automation_handoff_interrupt decision=$decision enabled=${snapshot.enabled} runtime=${snapshot.runtimeState.wireName}"
          )
          FacadeOperationResult(
            true,
            when (decision) {
              PhoneAutomationHandoffInterruptionDecision.INTERRUPTED -> {
                "Phone automation handoff interrupted"
              }

              PhoneAutomationHandoffInterruptionDecision.IGNORED_NOT_IN_FLIGHT -> {
                "No protected phone automation handoff was active"
              }

              PhoneAutomationHandoffInterruptionDecision.IGNORED_NOT_ENABLED -> {
                "Phone automation is disabled"
              }
            }
          )
        }
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
    prerequisiteMonitorJob?.cancel()
    ticketServiceMonitorJob?.cancel()
    ticketServiceMonitorJob = null
    if (this::cpuFrequencyRuntime.isInitialized) {
      cpuFrequencyRuntime.stop(
        reason = "service_destroyed",
        finalState = CpuFrequencyRuntimeState.STOPPED,
        finalDetail = CpuFrequencyRuntimeState.STOPPED.defaultDetail,
        restoreStock = false
      )
    }
    if (this::touchBrightnessRuntime.isInitialized) {
      touchBrightnessRuntime.stop(reason = "service_destroyed")
    }
    preservePhoneAutomationForServiceLoss(reason = "service_destroyed")
    if (this::phoneAutomationStore.isInitialized) {
      reschedulePhoneAutomationWake(reason = "service_destroyed", force = true)
    }
    serviceScope.cancel()
    super.onDestroy()
  }

  override fun onTimeout(startId: Int) {
    handleForegroundTimeout(startId = startId, fgsType = 0)
  }

  override fun onTimeout(startId: Int, fgsType: Int) {
    handleForegroundTimeout(startId = startId, fgsType = fgsType)
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun requestTicketStreamService(
    action: String,
    successMessage: String,
    failurePrefix: String
  ): FacadeOperationResult =
    try {
      startService(Intent(this, TicketStreamService::class.java).setAction(action))
      FacadeOperationResult(true, successMessage)
    } catch (error: Throwable) {
      Log.w(TAG, "ticket_stream_service_request_failed action=$action", error)
      FacadeOperationResult(false, "$failurePrefix: ${error.message ?: error::class.java.simpleName}")
    }

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
    const val ACTION_TICKET_START_SERVER = "lv.jolkins.pixelorchestrator.action.TICKET_START_SERVER"
    const val ACTION_TICKET_STOP_SERVER = "lv.jolkins.pixelorchestrator.action.TICKET_STOP_SERVER"
    const val ACTION_REFRESH_PHONE_AUTOMATION = "lv.jolkins.pixelorchestrator.action.REFRESH_PHONE_AUTOMATION"
    const val ACTION_REFRESH_CPU_FREQUENCY = "lv.jolkins.pixelorchestrator.action.REFRESH_CPU_FREQUENCY"
    const val ACTION_REFRESH_TICKET_SERVICE = "lv.jolkins.pixelorchestrator.action.REFRESH_TICKET_SERVICE"
    const val ACTION_PHONE_AUTOMATION_WAKE = "lv.jolkins.pixelorchestrator.action.PHONE_AUTOMATION_WAKE"
    const val ACTION_INTERRUPT_PHONE_AUTOMATION_HANDOFF =
      "lv.jolkins.pixelorchestrator.action.INTERRUPT_PHONE_AUTOMATION_HANDOFF"
    const val ACTION_CLEANUP = "lv.jolkins.pixelorchestrator.action.CLEANUP"
    const val EXTRA_COMPONENT = OrchestratorShellCommand.EXTRA_COMPONENT
    const val EXTRA_BOOT_EVENT_TOKEN = "orchestrator_boot_event_token"
    const val EXTRA_PIXEL_RUN_ID = OrchestratorShellCommand.EXTRA_PIXEL_RUN_ID
    const val EXTRA_CLEANUP_DRY_RUN = OrchestratorShellCommand.EXTRA_DRY_RUN
    const val EXTRA_CLEANUP_TRIGGER = "orchestrator_cleanup_trigger"
    const val EXTRA_PHONE_AUTOMATION_WAKE_REASON = "phone_automation_wake_reason"
    const val EXTRA_PHONE_AUTOMATION_WAKE_DEADLINE_AT_MILLIS = "phone_automation_wake_deadline_at_millis"
    const val EXTRA_PHONE_AUTOMATION_WAKE_TOKEN = "phone_automation_wake_token"
    private const val FOREGROUND_NOTIFICATION_ID = 4001
    private const val CONNECTION_DROP_DEBOUNCE_MILLIS = 2_000L
    private const val DEFERRED_TOUCH_BRIGHTNESS_RESUME_TRIGGER = "deferred_touch_brightness_resume"
    private const val TICKET_SERVICE_COMPONENT = "ticket_screen"
    private const val TICKET_SERVICE_MONITOR_INTERVAL_MILLIS = 30_000L

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
      runCatching {
        ContextCompat.startForegroundService(context, intent)
      }.getOrElse { error ->
        Log.e(TAG, "foreground_service_start_failed action=$action", error)
        if (action != ACTION_PHONE_AUTOMATION_WAKE) {
          runCatching {
            PhoneAutomationWakeScheduler.rescheduleFromStore(
              context = context,
              reason = "foreground_service_start_failed:$action",
              force = true
            )
          }
        }
        runCatching {
          NightlyCleanupScheduler(context).scheduleNext(reason = "foreground_service_start_failed:$action")
        }
      }
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

  private fun syncPhoneAutomation(trigger: String) {
    val suppressAutomationRestart = trigger == ACTION_REFRESH_PHONE_AUTOMATION &&
      phoneAutomationStore.load().isProtectedSpeedtestHandoffInProgress()
    val decision = phoneAutomationSupervisorController.syncFromSettings(
      trigger = trigger,
      suppressAutomationRestart = suppressAutomationRestart
    )
    val snapshot = phoneAutomationStore.load()
    Log.i(
      TAG,
      "phone_automation_sync trigger=$trigger decision=$decision enabled=${snapshot.enabled} setup=${snapshot.setupState.wireName} runtime=${snapshot.runtimeState.wireName} touch_enabled=${snapshot.touchBrightnessEnabled} touch_runtime=${snapshot.touchBrightnessState.wireName}"
    )
    reschedulePhoneAutomationWake(snapshot = snapshot, reason = "sync:$trigger", force = false)
    updateForegroundNotification(snapshot)
  }

  private fun shouldIgnorePhoneAutomationWake(
    wakeReason: String,
    wakeDeadlineAtMillis: Long,
    wakeToken: String
  ): Boolean {
    if (
      wakeReason != PhoneAutomationWakeReason.PENDING_RECOVERY.wireName ||
      !this::phoneAutomationStore.isInitialized
    ) {
      return false
    }
    val snapshot = phoneAutomationStore.load()
    val stale = !snapshot.hasQueuedPendingRecovery() ||
      snapshot.pendingRecoveryNotBeforeAtMillis != wakeDeadlineAtMillis ||
      snapshot.pendingRecoveryToken != wakeToken
    if (stale) {
      Log.i(
        TAG,
        "phone_automation_wake_ignored reason=$wakeReason deadline=$wakeDeadlineAtMillis token=$wakeToken current_phase=${snapshot.pendingRecoveryPhase.wireName} current_deadline=${snapshot.pendingRecoveryNotBeforeAtMillis} current_token=${snapshot.pendingRecoveryToken}"
      )
    }
    return stale
  }

  private fun syncCpuFrequency(trigger: String) {
    val snapshot = cpuFrequencyStore.load()
    when {
      !snapshot.enabled -> cpuFrequencyRuntime.stop(
        reason = "disabled:$trigger",
        finalState = CpuFrequencyRuntimeState.DISABLED,
        finalDetail = CpuFrequencyRuntimeState.DISABLED.defaultDetail,
        restoreStock = true
      )
      snapshot.suspended -> cpuFrequencyRuntime.stop(
        reason = "suspended:$trigger",
        finalState = CpuFrequencyRuntimeState.STOPPED,
        finalDetail = CpuFrequencyRuntimeState.STOPPED.defaultDetail,
        restoreStock = true
      )
      else -> cpuFrequencyRuntime.start()
    }
    val latest = cpuFrequencyStore.load()
    Log.i(
      TAG,
      "cpu_frequency_sync trigger=$trigger enabled=${latest.enabled} suspended=${latest.suspended} runtime=${latest.runtimeState.wireName}"
    )
    updateForegroundNotification(phoneAutomationStore.load(), latest)
  }

  private fun syncTicketService(trigger: String, facade: OrchestratorFacade) {
    if (!this::ticketServiceStore.isInitialized) {
      return
    }
    val snapshot = ticketServiceStore.load()
    if (!snapshot.enabled) {
      ticketServiceMonitorJob?.cancel()
      ticketServiceMonitorJob = null
      serviceScope.launch {
        stopTicketServiceReadiness(trigger = trigger, facade = facade)
      }
      updateForegroundNotification(ticketServiceSnapshot = ticketServiceStore.load())
      return
    }

    if (ticketServiceMonitorJob?.isActive == true) {
      serviceScope.launch {
        ensureTicketServiceReady(reason = trigger, facade = facade)
      }
      return
    }
    ticketServiceMonitorJob = serviceScope.launch {
      ensureTicketServiceReady(reason = trigger, facade = facade)
      while (isActive) {
        delay(TICKET_SERVICE_MONITOR_INTERVAL_MILLIS)
        ensureTicketServiceReady(reason = "periodic", facade = facade)
      }
    }
  }

  private suspend fun ensureTicketServiceReady(reason: String, facade: OrchestratorFacade) {
    ticketServiceEnsureMutex.withLock {
      if (!ticketServiceStore.load().enabled) {
        return
      }
      ticketServiceStore.recordEnsureStarted(reason)
      updateForegroundNotification(ticketServiceSnapshot = ticketServiceStore.load())

      val result = facade.startComponent(TICKET_SERVICE_COMPONENT)
      val moduleHealth = result.healthSnapshot?.moduleHealth?.get(TICKET_SERVICE_COMPONENT)
      val localServerReachable = moduleHealth?.details?.get("listener") == "1" || moduleHealth?.healthy == true
      val componentStatus = moduleHealth?.status.orEmpty().ifBlank {
        if (result.success) "running" else "degraded"
      }
      val tunnelProbe = probeTicketTunnelReadiness()
      val ready = result.success && localServerReachable && tunnelProbe.ready
      val detail = when {
        ready -> "Local ticket server and tunnel are ready"
        !localServerReachable -> "Local ticket server is not reachable: ${result.message}"
        !tunnelProbe.ready -> "Ticket tunnel is not ready: ${tunnelProbe.detail}"
        else -> result.message
      }

      val latest = ticketServiceStore.recordEnsureResult(
        reason = reason,
        success = ready,
        result = detail,
        localServerReachable = localServerReachable,
        tunnelReady = tunnelProbe.ready,
        componentStatus = componentStatus
      )
      Log.i(
        TAG,
        "ticket_service_ensure reason=$reason enabled=${latest.enabled} state=${latest.runtimeState.wireName} local=$localServerReachable tunnel=${tunnelProbe.ready} component=$componentStatus detail=$detail"
      )
      updateForegroundNotification(ticketServiceSnapshot = latest)
    }
  }

  private suspend fun stopTicketServiceReadiness(trigger: String, facade: OrchestratorFacade) {
    ticketServiceEnsureMutex.withLock {
      ticketServiceStore.updateRuntimeState(
        TicketServiceRuntimeState.STOPPING,
        "Stopping local ticket server and tunnel readiness"
      )
      updateForegroundNotification(ticketServiceSnapshot = ticketServiceStore.load())
      val result = facade.stopComponent(TICKET_SERVICE_COMPONENT)
      val latest = ticketServiceStore.recordEnsureResult(
        reason = "disabled:$trigger",
        success = false,
        result = result.message,
        localServerReachable = false,
        tunnelReady = false,
        componentStatus = "stopped"
      )
      ticketServiceStore.updateRuntimeState(
        TicketServiceRuntimeState.DISABLED,
        TicketServiceRuntimeState.DISABLED.defaultDetail
      )
      Log.i(
        TAG,
        "ticket_service_stop trigger=$trigger success=${result.success} message=${result.message} state=${latest.runtimeState.wireName}"
      )
      updateForegroundNotification(ticketServiceSnapshot = ticketServiceStore.load())
    }
  }

  private suspend fun probeTicketTunnelReadiness(): TicketTunnelProbe {
    val result = ticketReadinessRootExecutor.runScript(
      script = """
        BASE="/data/local/pixel-stack/apps/ticket-screen"
        CONF_ENV="/data/local/pixel-stack/conf/apps/ticket-screen.env"
        RUNTIME_ENV="${'$'}BASE/env/ticket-screen.env"
        RUN_DIR="${'$'}BASE/run"
        LOOP_PID_FILE="${'$'}RUN_DIR/ticket-web-tunnel-service-loop.pid"
        CLOUDFLARED_PID_FILE="${'$'}RUN_DIR/ticket-screen-cloudflared.pid"
        TUNNEL_LOOP_BIN="${'$'}BASE/bin/ticket-web-tunnel-service-loop"

        [ -r "${'$'}CONF_ENV" ] && . "${'$'}CONF_ENV"
        [ -r "${'$'}RUNTIME_ENV" ] && . "${'$'}RUNTIME_ENV"

        is_true() {
          case "${'$'}{1:-}" in
            1|true|TRUE|yes|YES|on|ON) return 0 ;;
            *) return 1 ;;
          esac
        }

        pid_cmdline() {
          pid="${'$'}1"
          if [ -r "/proc/${'$'}pid/cmdline" ]; then
            tr '\000' ' ' < "/proc/${'$'}pid/cmdline" 2>/dev/null || true
            return 0
          fi
          ps -p "${'$'}pid" -o ARGS= 2>/dev/null || true
        }

        pid_matches() {
          pid="${'$'}1"
          needle="${'$'}2"
          [ -n "${'$'}pid" ] || return 1
          kill -0 "${'$'}pid" >/dev/null 2>&1 || return 1
          case "$(pid_cmdline "${'$'}pid")" in
            *"${'$'}needle"*) return 0 ;;
            *) return 1 ;;
          esac
        }

        read_pid() {
          [ -r "${'$'}1" ] && sed -n '1p' "${'$'}1" 2>/dev/null | tr -d '\r'
        }

        if ! is_true "${'$'}{TICKET_SCREEN_TUNNEL_ENABLED:-1}"; then
          echo "ready=1"
          echo "detail=tunnel_disabled"
          exit 0
        fi

        loop_pid="$(read_pid "${'$'}LOOP_PID_FILE" || true)"
        cloudflared_pid="$(read_pid "${'$'}CLOUDFLARED_PID_FILE" || true)"
        loop_ready=0
        cloudflared_ready=0
        pid_matches "${'$'}loop_pid" "${'$'}TUNNEL_LOOP_BIN" && loop_ready=1
        pid_matches "${'$'}cloudflared_pid" "/state/ticket-screen-cloudflared.yml" && cloudflared_ready=1

        echo "loop=${'$'}loop_ready"
        echo "cloudflared=${'$'}cloudflared_ready"
        echo "loop_pid=${'$'}loop_pid"
        echo "cloudflared_pid=${'$'}cloudflared_pid"

        if [ "${'$'}loop_ready" = "1" ] && [ "${'$'}cloudflared_ready" = "1" ]; then
          echo "ready=1"
          echo "detail=tunnel_loop_and_cloudflared_ready"
          exit 0
        fi
        echo "ready=0"
        echo "detail=tunnel_loop_or_cloudflared_missing"
        exit 1
      """.trimIndent(),
      timeout = Duration.parse("8s")
    )
    val fields = result.stdout.lineSequence()
      .mapNotNull { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) null else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
      }
      .toMap()
    return TicketTunnelProbe(
      ready = result.ok && fields["ready"] == "1",
      detail = fields["detail"].orEmpty().ifBlank {
        result.stderr.ifBlank { result.stdout }.trim().ifBlank { "unknown" }
      }
    )
  }

  private fun startPhoneAutomationPrerequisiteMonitor() {
    prerequisiteMonitorJob?.cancel()
    prerequisiteMonitorJob = serviceScope.launch {
      prerequisiteMonitor.snapshots().collectLatest { prerequisiteSnapshot ->
        handlePhoneAutomationPrerequisiteSnapshot(prerequisiteSnapshot)
      }
    }
  }

  private suspend fun handlePhoneAutomationPrerequisiteSnapshot(
    prerequisiteSnapshot: PhoneAutomationPrerequisiteSnapshot
  ) {
    val settingsSnapshot = phoneAutomationStore.load()
    val issue = prerequisiteSnapshot.issueFor(settingsSnapshot) ?: return
    if (shouldDeferPrerequisiteRecovery(settingsSnapshot, issue)) {
      Log.i(
        TAG,
        "phone_automation_prerequisite_recovery_deferred reason=${issue.reasonKey} detail=${issue.detail} runtime=${settingsSnapshot.runtimeState.wireName} touch_runtime=${settingsSnapshot.touchBrightnessState.wireName}"
      )
      return
    }

    val confirmedIssue = if (issue.connectionOnly) {
      delay(CONNECTION_DROP_DEBOUNCE_MILLIS)
      val latestSettings = phoneAutomationStore.load()
      val latestIssue = prerequisiteMonitor.readSnapshot().issueFor(latestSettings) ?: return
      if (shouldDeferPrerequisiteRecovery(latestSettings, latestIssue)) {
        Log.i(
          TAG,
          "phone_automation_prerequisite_recovery_deferred reason=${latestIssue.reasonKey} detail=${latestIssue.detail} runtime=${latestSettings.runtimeState.wireName} touch_runtime=${latestSettings.touchBrightnessState.wireName}"
        )
        return
      }
      latestIssue
    } else {
      issue
    }

    triggerPrerequisiteRecovery(confirmedIssue)
  }

  private fun shouldDeferPrerequisiteRecovery(
    settingsSnapshot: PhoneAutomationSettingsSnapshot,
    issue: PhoneAutomationPrerequisiteIssue
  ): Boolean {
    return shouldDeferPhoneAutomationPrerequisiteRecovery(
      settingsSnapshot = settingsSnapshot,
      issue = issue
    )
  }

  private fun triggerPrerequisiteRecovery(issue: PhoneAutomationPrerequisiteIssue) {
    val decision = phoneAutomationSupervisorController.recoverFromPrerequisiteDrift(
      PhoneAutomationRecoveryRequest(
        reasonKey = issue.reasonKey,
        detail = issue.detail,
        recoverAutomation = issue.recoverAutomation,
        recoverTouchBrightness = issue.recoverTouchBrightness
      )
    )
    Log.i(
      TAG,
      "phone_automation_prerequisite_recovery decision=$decision reason=${issue.reasonKey} detail=${issue.detail} automation=${issue.recoverAutomation} touch=${issue.recoverTouchBrightness}"
    )
  }

  private fun updateForegroundNotification(
    snapshot: PhoneAutomationSettingsSnapshot = phoneAutomationStore.load(),
    cpuFrequencySnapshot: CpuFrequencySettingsSnapshot = cpuFrequencyStore.load(),
    ticketServiceSnapshot: TicketServiceSettingsSnapshot = loadTicketServiceNotificationSnapshot()
  ) {
    val manager = getSystemService(NotificationManager::class.java)
    manager.notify(
      FOREGROUND_NOTIFICATION_ID,
      NotificationHelper.buildForegroundNotification(this, snapshot, cpuFrequencySnapshot, ticketServiceSnapshot)
    )
  }

  private fun loadTicketServiceNotificationSnapshot(): TicketServiceSettingsSnapshot {
    return if (this::ticketServiceStore.isInitialized) {
      ticketServiceStore.load()
    } else {
      TicketServiceSettingsSnapshot()
    }
  }

  private fun promoteToForeground() {
    val notification = NotificationHelper.buildForegroundNotification(
      this,
      phoneAutomationStore.load(),
      cpuFrequencyStore.load(),
      ticketServiceStore.load()
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      ServiceCompat.startForeground(
        this,
        FOREGROUND_NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
      )
    } else {
      startForeground(FOREGROUND_NOTIFICATION_ID, notification)
    }
  }

  private fun handlePhoneAutomationSnapshotChanged(snapshot: PhoneAutomationSettingsSnapshot) {
    reschedulePhoneAutomationWake(snapshot = snapshot, reason = "store_change")
    updateForegroundNotification(snapshot)
    if (
      phoneAutomationSupervisorController.shouldResumeDeferredTouchBrightness(snapshot) &&
      !deferredTouchBrightnessResumeRequested
    ) {
      deferredTouchBrightnessResumeRequested = true
      Log.i(
        TAG,
        "touch_brightness_recovery_resume_requested runtime=${snapshot.runtimeState.wireName} touch_runtime=${snapshot.touchBrightnessState.wireName}"
      )
      serviceScope.launch {
        try {
          syncPhoneAutomation(trigger = DEFERRED_TOUCH_BRIGHTNESS_RESUME_TRIGGER)
        } finally {
          deferredTouchBrightnessResumeRequested = false
        }
      }
    }
  }

  private fun reschedulePhoneAutomationWake(
    snapshot: PhoneAutomationSettingsSnapshot = phoneAutomationStore.load(),
    reason: String,
    force: Boolean = false
  ) {
    if (!this::phoneAutomationWakeScheduler.isInitialized) {
      return
    }
    val result = phoneAutomationWakeScheduler.reschedule(
      snapshot = snapshot,
      reason = reason,
      force = force
    )
    when (result.action) {
      PhoneAutomationWakeScheduleAction.SCHEDULED -> {
        val plan = result.plan
        Log.i(
          TAG,
          "phone_automation_wake_scheduled reason=$reason wake_reason=${plan?.reason?.wireName} deadline=${plan?.deadlineAtMillis}"
        )
      }

      PhoneAutomationWakeScheduleAction.CANCELED -> {
        Log.i(TAG, "phone_automation_wake_canceled reason=$reason detail=${result.detail}")
      }

      PhoneAutomationWakeScheduleAction.NOT_SCHEDULED -> {
        Log.w(TAG, "phone_automation_wake_not_scheduled reason=$reason detail=${result.detail}")
      }

      PhoneAutomationWakeScheduleAction.UNCHANGED -> Unit
    }
  }

  private fun handleForegroundTimeout(startId: Int, fgsType: Int) {
    Log.e(TAG, "foreground_service_timeout startId=$startId fgsType=$fgsType")
    runCatching {
      preservePhoneAutomationForServiceLoss(reason = "foreground_service_timeout")
    }
    runCatching {
      reschedulePhoneAutomationWake(reason = "foreground_service_timeout", force = true)
    }
    runCatching {
      NightlyCleanupScheduler(this).scheduleNext(reason = "foreground_service_timeout")
    }
    runCatching {
      stopForeground(STOP_FOREGROUND_REMOVE)
    }
    stopSelfResult(startId)
  }

  private fun handleStartupFailure(stage: String, error: Throwable) {
    Log.e(TAG, "supervisor_service_startup_failed stage=$stage", error)
    runCatching {
      val snapshot = preservePhoneAutomationForServiceLoss(reason = "startup_failure:$stage")
        ?: if (this::phoneAutomationStore.isInitialized) {
          phoneAutomationStore.load()
        } else {
          PhoneAutomationPreferencesStore(this).load()
        }
      if (!this::phoneAutomationWakeScheduler.isInitialized) {
        phoneAutomationWakeScheduler = PhoneAutomationWakeScheduler(
          alarmClient = AndroidPhoneAutomationWakeAlarmClient(this)
        )
      }
      reschedulePhoneAutomationWake(snapshot = snapshot, reason = "startup_failure:$stage", force = true)
    }
    runCatching {
      NightlyCleanupScheduler(this).scheduleNext(reason = "startup_failure:$stage")
    }
    runCatching {
      stopForeground(STOP_FOREGROUND_REMOVE)
    }
    stopSelf()
  }

  private fun preservePhoneAutomationForServiceLoss(reason: String): PhoneAutomationSettingsSnapshot? {
    val store = when {
      this::phoneAutomationStore.isInitialized -> phoneAutomationStore
      else -> PhoneAutomationPreferencesStore(this)
    }
    if (this::phoneAutomationRuntime.isInitialized) {
      phoneAutomationRuntime.cancelForServiceLoss(reason)
      return store.load()
    }

    val snapshot = store.load()
    if (!snapshot.enabled) {
      return snapshot
    }
    val pendingAction = when {
      snapshot.pendingRecoveryAction != PhoneAutomationPendingRecoveryAction.NONE -> {
        snapshot.pendingRecoveryAction
      }

      snapshot.runtimeState == PhoneAutomationRuntimeState.STARTING -> {
        PhoneAutomationPendingRecoveryAction.START
      }

      snapshot.runtimeState == PhoneAutomationRuntimeState.RESTARTING_SPEEDTEST -> {
        PhoneAutomationPendingRecoveryAction.RESTART
      }

      else -> {
        PhoneAutomationPendingRecoveryAction.NONE
      }
    }
    return if (pendingAction == PhoneAutomationPendingRecoveryAction.NONE) {
      snapshot
    } else {
      store.updatePendingRecovery(
        action = pendingAction,
        reason = "service_loss_recovery",
        phase = PhoneAutomationPendingRecoveryPhase.QUEUED_RETRY,
        notBeforeAtMillis = System.currentTimeMillis() + PhoneAutomationCoordinator.PENDING_RECOVERY_DELAY_MILLIS,
        token = "${System.currentTimeMillis()}_${pendingAction.wireName}"
      )
    }
  }
}

internal enum class BootRecoveryMode {
  START_ALL,
  RESUME_SUPERVISION
}

private data class TicketTunnelProbe(
  val ready: Boolean,
  val detail: String
)

internal class PhoneAutomationPrerequisiteMonitor(
  context: Context,
  private val bridge: PhoneAutomationServiceBridge = PhoneAutomationServiceBridge
) {
  private val appContext = context.applicationContext
  private val accessibilityComponent = ComponentName(
    appContext,
    PhoneAutomationAccessibilityService::class.java
  )
  private val notificationComponent = ComponentName(
    appContext,
    PhoneAutomationNotificationListenerService::class.java
  )

  fun readSnapshot(): PhoneAutomationPrerequisiteSnapshot {
    return PhoneAutomationPrerequisiteSnapshot(
      accessibilityGloballyEnabled = isAccessibilityGloballyEnabled(),
      accessibilityServiceEnabled = PhoneAutomationServicePermissions.containsEnabledService(
        currentValue = Settings.Secure.getString(
          appContext.contentResolver,
          Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ),
        componentName = accessibilityComponent
      ),
      notificationListenerEnabled = PhoneAutomationServicePermissions.containsEnabledService(
        currentValue = Settings.Secure.getString(
          appContext.contentResolver,
          ENABLED_NOTIFICATION_LISTENERS_SETTING
        ),
        componentName = notificationComponent
      ),
      accessibilityConnected = bridge.isAccessibilityServiceConnected(),
      notificationConnected = bridge.isNotificationListenerConnected()
    )
  }

  fun snapshots(): Flow<PhoneAutomationPrerequisiteSnapshot> {
    return combine(
      settingsChanges().onStart { emit(Unit) },
      bridge.accessibilityAvailability,
      bridge.notificationListenerAvailability
    ) { _, _, _ ->
      readSnapshot()
    }.distinctUntilChanged()
  }

  private fun settingsChanges(): Flow<Unit> = callbackFlow {
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
      override fun onChange(selfChange: Boolean) {
        trySend(Unit)
      }

      override fun onChange(selfChange: Boolean, uri: Uri?) {
        trySend(Unit)
      }
    }

    watchedUris().forEach { uri ->
      appContext.contentResolver.registerContentObserver(uri, false, observer)
    }
    trySend(Unit)

    awaitClose {
      appContext.contentResolver.unregisterContentObserver(observer)
    }
  }

  private fun watchedUris(): List<Uri> {
    return listOf(
      Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
      Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
      Settings.Secure.getUriFor(ENABLED_NOTIFICATION_LISTENERS_SETTING)
    )
  }

  private fun isAccessibilityGloballyEnabled(): Boolean {
    return runCatching {
      Settings.Secure.getInt(
        appContext.contentResolver,
        Settings.Secure.ACCESSIBILITY_ENABLED
      ) == 1
    }.getOrDefault(false)
  }

  companion object {
    private const val ENABLED_NOTIFICATION_LISTENERS_SETTING = "enabled_notification_listeners"
  }
}
