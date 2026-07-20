package lv.jolkins.pixelorchestrator.app.dashboard

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lv.jolkins.pixelorchestrator.BuildConfig
import lv.jolkins.pixelorchestrator.app.AppGraph
import lv.jolkins.pixelorchestrator.app.FacadeOperationResult
import lv.jolkins.pixelorchestrator.app.OrchestratorFacade
import lv.jolkins.pixelorchestrator.app.OrchestratorShellCommand
import lv.jolkins.pixelorchestrator.app.SupportBundleExporter
import lv.jolkins.pixelorchestrator.app.SupervisorService
import lv.jolkins.pixelorchestrator.app.cpufrequency.CpuFrequencyPreferencesStore
import lv.jolkins.pixelorchestrator.app.cpufrequency.CpuFrequencySettingsSnapshot
import lv.jolkins.pixelorchestrator.app.cpufrequency.CpuFrequencySupport
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationBackgroundReliabilitySupport
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationForegroundInterruptDecision
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationPreferencesStore
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationServiceBridge
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationWakeScheduler
import lv.jolkins.pixelorchestrator.app.phoneautomation.isProtectedSpeedtestHandoffInProgress
import lv.jolkins.pixelorchestrator.app.phoneautomation.phoneAutomationForegroundInterruptDecision
import lv.jolkins.pixelorchestrator.app.ticket.TicketServicePreferencesStore
import lv.jolkins.pixelorchestrator.app.telemetry.OrchestratorTelemetryComponent
import lv.jolkins.pixelorchestrator.app.telemetry.OrchestratorTelemetryCleanupCategory
import lv.jolkins.pixelorchestrator.app.telemetry.OrchestratorTelemetryDraft
import lv.jolkins.pixelorchestrator.app.telemetry.OrchestratorTelemetryEventType
import lv.jolkins.pixelorchestrator.app.telemetry.OrchestratorTelemetryPriority
import lv.jolkins.pixelorchestrator.app.telemetry.OrchestratorTelemetryResult
import lv.jolkins.pixelorchestrator.app.telemetry.OrchestratorTelemetryRuntime
import lv.jolkins.pixelorchestrator.app.telemetry.OrchestratorTelemetryStatus
import lv.jolkins.pixelorchestrator.health.HealthScope
import lv.jolkins.pixelorchestrator.rootexec.SuRootExecutor

internal class DashboardViewModel(
  application: Application,
  private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {
  private val appContext = application.applicationContext
  private val facade: OrchestratorFacade = AppGraph.facade(appContext)
  private val phoneAutomationStore = PhoneAutomationPreferencesStore(appContext)
  private val cpuFrequencyStore = CpuFrequencyPreferencesStore(appContext)
  private val ticketServiceStore = TicketServicePreferencesStore(appContext)
  private val rootExecutor = SuRootExecutor()

  private val _uiState = MutableStateFlow(
    DashboardUiState(buildIdentity = buildIdentity())
  )
  val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

  private val effectChannel = Channel<DashboardEffect>(
    capacity = 4,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
  )
  val effects: Flow<DashboardEffect> = effectChannel.receiveAsFlow()

  private var phoneRefreshJob: Job? = null
  private var ticketRefreshJob: Job? = null
  private var cpuRefreshJob: Job? = null
  private var protectedHandoffUiSessionActive = false
  private var lastPhoneAutomationResumeRefreshAtMillis = 0L
  private var lastObservedReliability = PhoneAutomationBackgroundReliabilitySupport.read(appContext)

  init {
    refreshFastState()
    restorePersistedHealth()
    resumePendingServiceOperation()
    recordActivity("Orchestrator opened", "Local dashboard session started", successful = null)
    recordTelemetry(
      OrchestratorTelemetryDraft(
        eventType = OrchestratorTelemetryEventType.APP_SESSION,
        component = OrchestratorTelemetryComponent.ORCHESTRATOR,
        status = OrchestratorTelemetryStatus.RUNNING,
        result = OrchestratorTelemetryResult.OK
      )
    )
  }

  fun onForeground() {
    val snapshot = phoneAutomationStore.load()
    recordPermissionChanges()
    refreshFastState()
    maybeRefreshPhoneAutomationAfterResume(snapshot)
    maybeInterruptProtectedPhoneAutomationHandoff(snapshot)
    startRefreshLoops()
  }

  fun onBackground() {
    phoneRefreshJob?.cancel()
    phoneRefreshJob = null
    ticketRefreshJob?.cancel()
    ticketRefreshJob = null
    cpuRefreshJob?.cancel()
    cpuRefreshJob = null
    protectedHandoffUiSessionActive = false
  }

  fun dispatch(action: DashboardAction) {
    when (action) {
      is DashboardAction.SetPhoneAutomation -> {
        phoneAutomationStore.setEnabled(action.enabled)
        refreshPhoneAutomation()
        refreshPhoneAutomationService()
        recordSetting("Speedtest automation", action.enabled)
        recordSettingTelemetry(OrchestratorTelemetryComponent.SPEEDTEST, action.enabled)
      }

      is DashboardAction.SetMaintainCellMapper -> {
        if (_uiState.value.phoneUiPolicy.cellMapperToggleEnabled) {
          phoneAutomationStore.setMaintainCellMapper(action.enabled)
          refreshPhoneAutomation()
          refreshPhoneAutomationService()
          recordSetting("CellMapper maintenance", action.enabled)
          recordSettingTelemetry(OrchestratorTelemetryComponent.CELLMAPPER, action.enabled)
        }
      }

      is DashboardAction.SetReturnToOrchestrator -> {
        if (_uiState.value.phoneUiPolicy.returnToOrchestratorToggleEnabled) {
          phoneAutomationStore.setReturnToOrchestratorAfterForegroundWork(action.enabled)
          refreshPhoneAutomation()
          refreshPhoneAutomationService()
          recordSetting("Return to orchestrator", action.enabled)
          recordSettingTelemetry(OrchestratorTelemetryComponent.AUTOMATION, action.enabled)
        }
      }

      is DashboardAction.SetDispatchInterval -> {
        if (_uiState.value.phoneUiPolicy.dispatchIntervalEnabled) {
          phoneAutomationStore.setDispatchInterval(action.interval)
          refreshPhoneAutomation()
          refreshPhoneAutomationService()
          recordActivity("Dispatch timing changed", action.interval.sliderLabel, successful = true)
          recordSettingTelemetry(OrchestratorTelemetryComponent.SPEEDTEST, enabled = true)
        }
      }

      is DashboardAction.SetTicketService -> {
        ticketServiceStore.setEnabled(action.enabled)
        refreshTicketService()
        SupervisorService.start(appContext, SupervisorService.ACTION_REFRESH_TICKET_SERVICE)
        recordSetting("Ticket service readiness", action.enabled)
        recordSettingTelemetry(OrchestratorTelemetryComponent.TICKET_READINESS, action.enabled)
      }

      is DashboardAction.SetTouchBrightness -> {
        if (_uiState.value.phoneUiPolicy.touchBrightnessToggleEnabled) {
          phoneAutomationStore.setTouchBrightnessEnabled(action.enabled)
          refreshPhoneAutomation()
          refreshPhoneAutomationService()
          recordSetting("Touch brightness", action.enabled)
          recordSettingTelemetry(OrchestratorTelemetryComponent.TOUCH_BRIGHTNESS, action.enabled)
        }
      }

      is DashboardAction.SetCpuFrequency -> {
        cpuFrequencyStore.setEnabled(action.enabled)
        refreshCpuFrequencyFromStore()
        refreshCpuFrequencyService()
        recordSetting("CPU and GPU limits", action.enabled)
        recordSettingTelemetry(OrchestratorTelemetryComponent.CPU, action.enabled)
      }

      is DashboardAction.SetCpuCap -> {
        cpuFrequencyStore.setDesiredCap(action.cluster, action.frequencyKHz)
        refreshCpuFrequencyFromStore()
        if (cpuFrequencyStore.load().enabled) refreshCpuFrequencyService()
        recordActivity(
          "${action.cluster.displayName} CPU limit changed",
          CpuFrequencySettingsSnapshot.formatKHz(action.frequencyKHz),
          successful = true
        )
        recordSettingTelemetry(OrchestratorTelemetryComponent.CPU, enabled = true)
      }

      is DashboardAction.SetGpuCap -> {
        cpuFrequencyStore.setDesiredGpuCap(action.frequencyKHz)
        refreshCpuFrequencyFromStore()
        if (cpuFrequencyStore.load().enabled) refreshCpuFrequencyService()
        recordActivity(
          "GPU limit changed",
          CpuFrequencySettingsSnapshot.formatKHz(action.frequencyKHz),
          successful = true
        )
        recordSettingTelemetry(OrchestratorTelemetryComponent.GPU, enabled = true)
      }

      DashboardAction.RestoreStockFrequencies -> {
        cpuFrequencyStore.setEnabled(false)
        refreshCpuFrequencyFromStore()
        refreshCpuFrequencyService()
        recordActivity("Stock performance restored", "CPU and GPU limits turned off", successful = true)
        recordSettingTelemetry(OrchestratorTelemetryComponent.CPU, enabled = false)
      }

      DashboardAction.OpenAccessibilitySettings -> emitEffect(DashboardEffect.OpenAccessibilitySettings)
      DashboardAction.OpenNotificationAccessSettings -> emitEffect(DashboardEffect.OpenNotificationAccessSettings)
      DashboardAction.OpenBatterySettings -> emitEffect(DashboardEffect.OpenBatterySettings)
      DashboardAction.OpenExactAlarmSettings -> emitEffect(DashboardEffect.OpenExactAlarmSettings)
      DashboardAction.Bootstrap -> runServiceOperation(
        action = "bootstrap",
        title = "Bootstrap stack",
        serviceAction = SupervisorService.ACTION_BOOTSTRAP
      )
      DashboardAction.StartAll -> runServiceOperation(
        action = "start_all",
        title = "Start all services",
        serviceAction = SupervisorService.ACTION_START_ALL
      )
      DashboardAction.StopAll -> runServiceOperation(
        action = "stop_all",
        title = "Stop all services",
        serviceAction = SupervisorService.ACTION_STOP_ALL
      )
      DashboardAction.RunHealthCheck -> runServiceOperation(
        action = "health",
        title = "Full health check",
        serviceAction = SupervisorService.ACTION_HEALTH
      )
      DashboardAction.SyncDdns -> runServiceOperation(
        action = "sync_ddns",
        title = "Sync DDNS",
        serviceAction = SupervisorService.ACTION_SYNC_DDNS
      )
      DashboardAction.RunCleanup -> runServiceOperation(
        action = "cleanup",
        title = "Runtime cleanup",
        serviceAction = SupervisorService.ACTION_CLEANUP
      )
      DashboardAction.ExportSupportBundle -> runServiceOperation(
        action = "export_bundle",
        title = "Redacted support bundle",
        serviceAction = SupervisorService.ACTION_EXPORT_BUNDLE,
        afterResult = { result ->
          if (result.success && result.outputPath.isNotBlank()) {
            runCatching {
              SupportBundleExporter.contentUri(appContext, File(result.outputPath))
            }.getOrNull()?.let { uri -> emitEffect(DashboardEffect.ShareSupportBundle(uri)) }
          }
        }
      )
    }
  }

  fun runIntentAction(
    action: String,
    component: String,
    pixelRunId: String,
    dryRun: Boolean
  ) {
    val normalizedAction = OrchestratorShellCommand.normalizeAction(action)
    if (normalizedAction.isBlank()) return
    val title = normalizedAction.replace('_', ' ').replaceFirstChar { it.uppercase() }
    runOperation(
      action = "intent:$normalizedAction:$component",
      title = title,
      afterResult = { result ->
        if (pixelRunId.isNotBlank()) {
          facade.writeActionResult(pixelRunId, normalizedAction, component, result)
        }
      }
    ) {
      when (normalizedAction) {
        OrchestratorShellCommand.ACTION_BOOTSTRAP -> facade.bootstrapStack()
        OrchestratorShellCommand.ACTION_START_ALL -> facade.startAll()
        OrchestratorShellCommand.ACTION_STOP_ALL -> facade.stopAll()
        OrchestratorShellCommand.ACTION_HEALTH -> facade.runHealthCheck(HealthScope.FULL)
        OrchestratorShellCommand.ACTION_START_COMPONENT -> facade.startComponent(component)
        OrchestratorShellCommand.ACTION_STOP_COMPONENT -> facade.stopComponent(component)
        OrchestratorShellCommand.ACTION_RESTART_COMPONENT -> facade.restartComponent(component)
        OrchestratorShellCommand.ACTION_REDEPLOY_COMPONENT -> facade.redeployComponent(component)
        OrchestratorShellCommand.ACTION_HEALTH_COMPONENT -> facade.healthComponent(component)
        OrchestratorShellCommand.ACTION_SYNC_DDNS -> facade.syncDdnsNow()
        OrchestratorShellCommand.ACTION_EXPORT_BUNDLE -> facade.exportSupportBundle(includeSecrets = false)
        OrchestratorShellCommand.ACTION_CLEANUP -> facade.runCleanup(dryRun = dryRun)
        else -> FacadeOperationResult(false, "Unknown intent action: $normalizedAction")
      }
    }
  }

  private fun startRefreshLoops() {
    phoneRefreshJob?.cancel()
    phoneRefreshJob = viewModelScope.launch {
      while (isActive) {
        refreshPhoneAutomation()
        delay(PHONE_AUTOMATION_REFRESH_INTERVAL_MILLIS)
      }
    }
    ticketRefreshJob?.cancel()
    ticketRefreshJob = viewModelScope.launch {
      while (isActive) {
        refreshTicketService()
        delay(TICKET_SERVICE_REFRESH_INTERVAL_MILLIS)
      }
    }
    cpuRefreshJob?.cancel()
    cpuRefreshJob = viewModelScope.launch {
      while (isActive) {
        refreshCpuFrequencyLive()
        delay(CPU_FREQUENCY_REFRESH_INTERVAL_MILLIS)
      }
    }
  }

  private fun refreshFastState() {
    _uiState.update { current ->
      current.copy(
        phoneAutomation = phoneAutomationStore.load(),
        ticketService = ticketServiceStore.load(),
        cpuFrequency = cpuFrequencyStore.load(),
        reliability = PhoneAutomationBackgroundReliabilitySupport.read(appContext),
        healthLevel = current.healthLevel.asStaleWhenExpired(current.healthGeneratedAtMillis),
        telemetryActivity = telemetryRecentActivity()
      )
    }
  }

  private fun restorePersistedHealth() {
    viewModelScope.launch(Dispatchers.IO) {
      val snapshot = runCatching { facade.loadLastHealthSnapshot() }
        .onFailure { error -> Log.w(TAG, "dashboard_health_restore_failed", error) }
        .getOrNull()
        ?: return@launch
      val generatedAtMillis = snapshot.generatedEpochSeconds * 1_000L
      _uiState.update { current ->
        if (current.healthGeneratedAtMillis > generatedAtMillis) {
          current
        } else {
          current.copy(
            healthLevel = dashboardHealthLevel(snapshot).asStaleWhenExpired(generatedAtMillis),
            healthGeneratedAtMillis = generatedAtMillis,
            modules = dashboardModules(snapshot)
          )
        }
      }
    }
  }

  private fun refreshPhoneAutomation() {
    _uiState.update { current ->
      current.copy(
        phoneAutomation = phoneAutomationStore.load(),
        reliability = PhoneAutomationBackgroundReliabilitySupport.read(appContext),
        healthLevel = current.healthLevel.asStaleWhenExpired(current.healthGeneratedAtMillis),
        telemetryActivity = telemetryRecentActivity()
      )
    }
  }

  private fun refreshTicketService() {
    _uiState.update { it.copy(ticketService = ticketServiceStore.load()) }
  }

  private fun refreshCpuFrequencyFromStore() {
    val snapshot = cpuFrequencyStore.load()
    _uiState.update { it.copy(cpuFrequency = snapshot, cpuLive = snapshot.liveSnapshot) }
  }

  private suspend fun refreshCpuFrequencyLive() {
    val snapshot = cpuFrequencyStore.load()
    val live = withContext(Dispatchers.IO) {
      CpuFrequencySupport.readLiveSnapshot(rootExecutor).getOrElse { snapshot.liveSnapshot }
    }
    _uiState.update { current -> current.copy(cpuFrequency = snapshot, cpuLive = live) }
  }

  private fun refreshPhoneAutomationService() {
    SupervisorService.start(appContext, SupervisorService.ACTION_REFRESH_PHONE_AUTOMATION)
  }

  private fun refreshCpuFrequencyService() {
    SupervisorService.start(appContext, SupervisorService.ACTION_REFRESH_CPU_FREQUENCY)
  }

  private fun runServiceOperation(
    action: String,
    title: String,
    serviceAction: String,
    afterResult: suspend (FacadeOperationResult) -> Unit = {}
  ) {
    if (_uiState.value.operation.inProgress) return
    val runId = "ui-${UUID.randomUUID().toString().replace("-", "")}"
    savedStateHandle[PENDING_RUN_ID] = runId
    savedStateHandle[PENDING_ACTION] = action
    savedStateHandle[PENDING_TITLE] = title
    savedStateHandle[PENDING_SERVICE_ACTION] = serviceAction
    publishRunningOperation(action, title)
    SupervisorService.start(
      context = appContext,
      action = serviceAction,
      pixelRunId = runId,
      commandAction = action,
      cleanupTrigger = if (action == "cleanup") "manual" else ""
    )
    awaitServiceOperation(runId, action, title, afterResult)
  }

  private fun resumePendingServiceOperation() {
    val runId = savedStateHandle.get<String>(PENDING_RUN_ID).orEmpty()
    val action = savedStateHandle.get<String>(PENDING_ACTION).orEmpty()
    val title = savedStateHandle.get<String>(PENDING_TITLE).orEmpty()
    if (runId.isBlank() || action.isBlank() || title.isBlank()) return
    publishRunningOperation(action, title)
    awaitServiceOperation(
      runId = runId,
      action = action,
      title = title,
      afterResult = if (action == "export_bundle") {
        { result ->
          if (result.success && result.outputPath.isNotBlank()) {
            runCatching {
              SupportBundleExporter.contentUri(appContext, File(result.outputPath))
            }.getOrNull()?.let { uri -> emitEffect(DashboardEffect.ShareSupportBundle(uri)) }
          }
        }
      } else {
        { _ -> }
      }
    )
  }

  private fun publishRunningOperation(action: String, title: String) {
    _uiState.update { current ->
      current.copy(
        operation = DashboardOperationState(
          status = DashboardOperationStatus.RUNNING,
          action = action,
          title = title,
          message = "Working in the background…"
        )
      )
    }
  }

  private fun awaitServiceOperation(
    runId: String,
    action: String,
    title: String,
    afterResult: suspend (FacadeOperationResult) -> Unit
  ) {
    viewModelScope.launch {
      val startedAt = SystemClock.elapsedRealtime()
      var result: FacadeOperationResult? = null
      while (isActive && SystemClock.elapsedRealtime() - startedAt < SERVICE_OPERATION_TIMEOUT_MILLIS) {
        result = facade.consumeActionResult(runId, action)
        if (result != null) break
        delay(SERVICE_OPERATION_POLL_MILLIS)
      }
      val completed = result ?: FacadeOperationResult(
        success = false,
        message = "The background operation did not report a result within 15 minutes."
      )
      clearPendingServiceOperation()
      afterResult(completed)
      publishResult(action, title, completed)
      refreshFastState()
      refreshCpuFrequencyLive()
    }
  }

  private fun clearPendingServiceOperation() {
    savedStateHandle[PENDING_RUN_ID] = ""
    savedStateHandle[PENDING_ACTION] = ""
    savedStateHandle[PENDING_TITLE] = ""
    savedStateHandle[PENDING_SERVICE_ACTION] = ""
  }

  private fun runOperation(
    action: String,
    title: String,
    afterResult: suspend (FacadeOperationResult) -> Unit = {},
    block: suspend () -> FacadeOperationResult
  ) {
    if (_uiState.value.operation.inProgress) return
    val startedAtElapsedMillis = SystemClock.elapsedRealtime()
    recordTelemetry(
      OrchestratorTelemetryDraft(
        eventType = OrchestratorTelemetryEventType.MANUAL_ACTION,
        component = telemetryComponentForAction(action),
        status = OrchestratorTelemetryStatus.RUNNING,
        result = OrchestratorTelemetryResult.NONE,
        priority = OrchestratorTelemetryPriority.NORMAL
      )
    )
    _uiState.update { current ->
      current.copy(
        operation = DashboardOperationState(
          status = DashboardOperationStatus.RUNNING,
          action = action,
          title = title,
          message = "Working…"
        )
      )
    }
    viewModelScope.launch {
      val result = try {
        block()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        Log.e(TAG, "dashboard_action_failed action=$action", error)
        FacadeOperationResult(
          success = false,
          message = "The action could not finish: ${error.message ?: error::class.java.simpleName}"
        )
      }
      afterResult(result)
      recordTelemetry(
        OrchestratorTelemetryDraft(
          eventType = OrchestratorTelemetryEventType.MANUAL_ACTION,
          component = telemetryComponentForAction(action),
          status = if (result.success) {
            OrchestratorTelemetryStatus.COMPLETED
          } else {
            OrchestratorTelemetryStatus.FAILED
          },
          result = if (result.success) {
            OrchestratorTelemetryResult.OK
          } else {
            OrchestratorTelemetryResult.FAILED
          },
          priority = if (result.success) {
            OrchestratorTelemetryPriority.NORMAL
          } else {
            OrchestratorTelemetryPriority.HIGH
          },
          durationMillis = (SystemClock.elapsedRealtime() - startedAtElapsedMillis)
            .coerceIn(0L, MAX_TELEMETRY_DURATION_MILLIS)
        )
      )
      if (action == "cleanup") {
        recordCleanupTelemetry(result, startedAtElapsedMillis)
      }
      publishResult(action, title, result)
      refreshFastState()
      refreshCpuFrequencyLive()
    }
  }

  private fun publishResult(action: String, title: String, result: FacadeOperationResult) {
    val health = result.healthSnapshot
    val detail = buildString {
      if (result.outputPath.isNotBlank()) {
        appendLine(
          if (action == "export_bundle") {
            "Ready to share from the private app cache."
          } else {
            "Output: ${result.outputPath}"
          }
        )
      }
      health?.let { snapshot ->
        dashboardModules(snapshot).forEach { module ->
          append(module.label)
          append(": ")
          appendLine(module.status)
        }
      }
    }.trim()
    _uiState.update { current ->
      current.copy(
        operation = DashboardOperationState(
          status = if (result.success) DashboardOperationStatus.SUCCEEDED else DashboardOperationStatus.FAILED,
          action = action,
          title = title,
          message = result.message,
          detail = detail
        ),
        healthLevel = health?.let { dashboardHealthLevel(it) } ?: current.healthLevel,
        healthGeneratedAtMillis = health?.generatedEpochSeconds?.times(1_000L)
          ?: current.healthGeneratedAtMillis,
        modules = health?.let(::dashboardModules) ?: current.modules,
        recentActivity = listOf(
          DashboardActivityItem(
            recordedAtMillis = System.currentTimeMillis(),
            title = title,
            detail = result.message.take(180),
            successful = result.success
          )
        ) + current.recentActivity.take(MAX_RECENT_ACTIVITY - 1)
      )
    }
  }

  private fun recordSetting(title: String, enabled: Boolean) {
    recordActivity(title, if (enabled) "Enabled" else "Disabled", successful = true)
  }

  private fun recordSettingTelemetry(component: OrchestratorTelemetryComponent, enabled: Boolean) {
    recordTelemetry(
      OrchestratorTelemetryDraft(
        eventType = OrchestratorTelemetryEventType.SETTING_CHANGE,
        component = component,
        status = if (enabled) {
          OrchestratorTelemetryStatus.ENABLED
        } else {
          OrchestratorTelemetryStatus.DISABLED
        },
        result = OrchestratorTelemetryResult.OK
      )
    )
  }

  private fun recordPermissionChanges() {
    val current = PhoneAutomationBackgroundReliabilitySupport.read(appContext)
    if (current.batteryUnrestricted != lastObservedReliability.batteryUnrestricted) {
      recordPermissionTelemetry(current.batteryUnrestricted)
    }
    if (current.exactAlarmGranted != lastObservedReliability.exactAlarmGranted) {
      recordPermissionTelemetry(current.exactAlarmGranted)
    }
    lastObservedReliability = current
  }

  private fun recordPermissionTelemetry(granted: Boolean) {
    recordTelemetry(
      OrchestratorTelemetryDraft(
        eventType = OrchestratorTelemetryEventType.PERMISSION_CHANGE,
        component = OrchestratorTelemetryComponent.PERMISSIONS,
        status = if (granted) {
          OrchestratorTelemetryStatus.ENABLED
        } else {
          OrchestratorTelemetryStatus.DISABLED
        },
        result = OrchestratorTelemetryResult.OK
      )
    )
  }

  private fun recordTelemetry(draft: OrchestratorTelemetryDraft) {
    OrchestratorTelemetryRuntime.enqueueIfReady(draft) ?: return
    refreshTelemetryActivity()
    viewModelScope.launch {
      OrchestratorTelemetryRuntime.drainDue()
      refreshTelemetryActivity()
    }
  }

  private fun refreshTelemetryActivity() {
    _uiState.update { current -> current.copy(telemetryActivity = telemetryRecentActivity()) }
  }

  private fun telemetryRecentActivity(): List<DashboardActivityItem> {
    return OrchestratorTelemetryRuntime.recentEvents().map { event ->
      DashboardActivityItem(
        recordedAtMillis = event.createdAtEpochMillis,
        title = "${event.component.wireValue.replace('_', ' ')} · ${event.eventType.wireValue.replace('_', ' ')}",
        detail = "${event.status.wireValue} · ${event.result.wireValue} · ${event.deliveryState.name.lowercase()}",
        successful = when (event.result) {
          OrchestratorTelemetryResult.OK -> true
          OrchestratorTelemetryResult.FAILED,
          OrchestratorTelemetryResult.CANCELLED,
          OrchestratorTelemetryResult.DROPPED,
          OrchestratorTelemetryResult.REJECTED -> false
          OrchestratorTelemetryResult.NONE,
          OrchestratorTelemetryResult.RETRYING -> null
        }
      )
    }
  }

  private fun telemetryComponentForAction(action: String): OrchestratorTelemetryComponent {
    return when {
      action.contains("cleanup") -> OrchestratorTelemetryComponent.CLEANUP
      action.contains("export") -> OrchestratorTelemetryComponent.DIAGNOSTICS
      action.contains("health") -> OrchestratorTelemetryComponent.STACK
      action.contains("ddns") -> OrchestratorTelemetryComponent.MANAGEMENT
      else -> OrchestratorTelemetryComponent.SUPERVISOR
    }
  }

  private fun recordCleanupTelemetry(result: FacadeOperationResult, startedAtElapsedMillis: Long) {
    val durationMillis = (SystemClock.elapsedRealtime() - startedAtElapsedMillis)
      .coerceIn(0L, MAX_TELEMETRY_DURATION_MILLIS)
    result.cleanupSummary?.categories.orEmpty().forEach { category ->
      val safeCategory = telemetryCleanupCategory(category.category) ?: return@forEach
      recordTelemetry(
        OrchestratorTelemetryDraft(
          eventType = OrchestratorTelemetryEventType.CLEANUP_RESULT,
          component = OrchestratorTelemetryComponent.CLEANUP,
          cleanupCategory = safeCategory,
          status = when {
            category.failures > 0 -> OrchestratorTelemetryStatus.FAILED
            category.deleted > 0 -> OrchestratorTelemetryStatus.COMPLETED
            else -> OrchestratorTelemetryStatus.SKIPPED
          },
          result = if (category.failures > 0 || !result.success) {
            OrchestratorTelemetryResult.FAILED
          } else {
            OrchestratorTelemetryResult.OK
          },
          priority = OrchestratorTelemetryPriority.HIGH,
          durationMillis = durationMillis,
          count = category.deleted.toLong().coerceAtLeast(0L),
          byteCount = category.deletedBytes.coerceAtLeast(0L)
        )
      )
    }
  }

  private fun telemetryCleanupCategory(category: String): OrchestratorTelemetryCleanupCategory? {
    return when (category) {
      "ticket_capture_file" -> OrchestratorTelemetryCleanupCategory.TICKET_HIERARCHY_XML
      "action_result" -> OrchestratorTelemetryCleanupCategory.DEPLOYMENT_ACTION_RESULTS
      "support_bundle" -> OrchestratorTelemetryCleanupCategory.SUPPORT_BUNDLES
      "superuser_log_db" -> OrchestratorTelemetryCleanupCategory.ROOT_COMMAND_HISTORY
      "runtime_log", "runtime_log_rotation", "legacy_log" ->
        OrchestratorTelemetryCleanupCategory.STACK_LOGS
      "retired_dns_log" -> OrchestratorTelemetryCleanupCategory.DNS_HISTORY
      "runtime_artifact", "component_artifact" ->
        OrchestratorTelemetryCleanupCategory.DEPLOYMENT_ARCHIVES
      "release_dir", "termux_artifact" -> OrchestratorTelemetryCleanupCategory.RETIRED_ARTIFACTS
      "app_cache", "tmp_artifact" -> OrchestratorTelemetryCleanupCategory.APP_CACHE
      else -> null
    }
  }

  private fun recordActivity(title: String, detail: String, successful: Boolean?) {
    _uiState.update { current ->
      current.copy(
        recentActivity = listOf(
          DashboardActivityItem(
            recordedAtMillis = System.currentTimeMillis(),
            title = title,
            detail = detail,
            successful = successful
          )
        ) + current.recentActivity.take(MAX_RECENT_ACTIVITY - 1)
      )
    }
  }

  private fun emitEffect(effect: DashboardEffect) {
    effectChannel.trySend(effect)
  }

  private fun maybeRefreshPhoneAutomationAfterResume(
    snapshot: lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationSettingsSnapshot
  ) {
    if (!snapshot.enabled && !snapshot.touchBrightnessEnabled) return
    if (snapshot.isProtectedSpeedtestHandoffInProgress()) return
    val now = System.currentTimeMillis()
    if (now - lastPhoneAutomationResumeRefreshAtMillis < ACTIVITY_RESUME_REFRESH_DEBOUNCE_MILLIS) return
    lastPhoneAutomationResumeRefreshAtMillis = now
    PhoneAutomationWakeScheduler.rescheduleFromStore(
      context = appContext,
      reason = "activity_resume",
      force = false
    )
    refreshPhoneAutomationService()
  }

  private fun maybeInterruptProtectedPhoneAutomationHandoff(
    snapshot: lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationSettingsSnapshot
  ) {
    when (
      val decision = phoneAutomationForegroundInterruptDecision(
        snapshot = snapshot,
        lastBlackoutWakeAtMillis = PhoneAutomationServiceBridge.lastBlackoutWakeAtMillis(),
        lastExpectedOrchestratorForegroundAtMillis =
          PhoneAutomationServiceBridge.lastExpectedOrchestratorForegroundAtMillis()
      )
    ) {
      PhoneAutomationForegroundInterruptDecision.IGNORE_NOT_IN_FLIGHT -> {
        protectedHandoffUiSessionActive = false
      }

      PhoneAutomationForegroundInterruptDecision.SUPPRESS_BLACKOUT_WAKE,
      PhoneAutomationForegroundInterruptDecision.SUPPRESS_SELF_FOREGROUND -> {
        protectedHandoffUiSessionActive = false
        Log.i(TAG, "phone_automation_handoff_interrupt_suppressed decision=$decision")
      }

      PhoneAutomationForegroundInterruptDecision.INTERRUPT -> {
        if (protectedHandoffUiSessionActive) return
        protectedHandoffUiSessionActive = true
        Log.i(TAG, "phone_automation_handoff_interrupt_requested reason=foreground_open")
        SupervisorService.start(
          appContext,
          SupervisorService.ACTION_INTERRUPT_PHONE_AUTOMATION_HANDOFF
        )
      }
    }
  }

  private fun buildIdentity(): String {
    val commit = BuildConfig.ORCHESTRATOR_SOURCE_COMMIT.take(8)
    val source = if (BuildConfig.ORCHESTRATOR_SOURCE_DIRTY) "$commit + local changes" else commit
    return "${BuildConfig.ORCHESTRATOR_RELEASE_ID}  •  $source  •  ${BuildConfig.VERSION_NAME}"
  }

  companion object {
    private const val TAG = "OrchestratorDashboard"
    private const val PHONE_AUTOMATION_REFRESH_INTERVAL_MILLIS = 5_000L
    private const val TICKET_SERVICE_REFRESH_INTERVAL_MILLIS = 5_000L
    private const val CPU_FREQUENCY_REFRESH_INTERVAL_MILLIS = 15_000L
    private const val ACTIVITY_RESUME_REFRESH_DEBOUNCE_MILLIS = 2_000L
    private const val MAX_RECENT_ACTIVITY = 20
    private const val MAX_TELEMETRY_DURATION_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    private const val HEALTH_STALE_AFTER_MILLIS = 2 * 60 * 1_000L
    private const val SERVICE_OPERATION_POLL_MILLIS = 250L
    private const val SERVICE_OPERATION_TIMEOUT_MILLIS = 15 * 60 * 1_000L
    private const val PENDING_RUN_ID = "dashboard_pending_run_id"
    private const val PENDING_ACTION = "dashboard_pending_action"
    private const val PENDING_TITLE = "dashboard_pending_title"
    private const val PENDING_SERVICE_ACTION = "dashboard_pending_service_action"
  }

  private fun DashboardHealthLevel.asStaleWhenExpired(generatedAtMillis: Long): DashboardHealthLevel {
    return if (
      generatedAtMillis > 0L &&
      System.currentTimeMillis() - generatedAtMillis > HEALTH_STALE_AFTER_MILLIS
    ) {
      DashboardHealthLevel.STALE
    } else {
      this
    }
  }
}
