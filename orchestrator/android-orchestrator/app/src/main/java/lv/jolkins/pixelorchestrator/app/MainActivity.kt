package lv.jolkins.pixelorchestrator.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lv.jolkins.pixelorchestrator.R
import lv.jolkins.pixelorchestrator.app.cpufrequency.GpuFrequencyLiveSnapshot
import lv.jolkins.pixelorchestrator.databinding.ActivityMainBinding
import lv.jolkins.pixelorchestrator.app.cpufrequency.CpuFrequencyCluster
import lv.jolkins.pixelorchestrator.app.cpufrequency.CpuFrequencyLiveSnapshot
import lv.jolkins.pixelorchestrator.app.cpufrequency.CpuFrequencyPreferencesStore
import lv.jolkins.pixelorchestrator.app.cpufrequency.CpuFrequencySettingsSnapshot
import lv.jolkins.pixelorchestrator.app.cpufrequency.CpuFrequencySupport
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationBackgroundReliability
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationBackgroundReliabilitySupport
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationDispatchInterval
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationForegroundInterruptDecision
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationServiceBridge
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationSettingsSnapshot
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationWakeScheduler
import lv.jolkins.pixelorchestrator.health.HealthScope
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationPreferencesStore
import lv.jolkins.pixelorchestrator.app.phoneautomation.isProtectedSpeedtestHandoffInProgress
import lv.jolkins.pixelorchestrator.app.phoneautomation.phoneAutomationForegroundInterruptDecision
import lv.jolkins.pixelorchestrator.app.phoneautomation.phoneAutomationUiPolicy
import lv.jolkins.pixelorchestrator.rootexec.SuRootExecutor

class MainActivity : ComponentActivity() {
  private lateinit var binding: ActivityMainBinding
  private lateinit var phoneAutomationStore: PhoneAutomationPreferencesStore
  private lateinit var cpuFrequencyStore: CpuFrequencyPreferencesStore
  private var phoneAutomationRenderJob: Job? = null
  private var cpuFrequencyRenderJob: Job? = null
  private var protectedHandoffUiSessionActive: Boolean = false
  private var lastPhoneAutomationResumeRefreshAtMillis: Long = 0L
  private var backgroundReliabilityExpanded: Boolean = false
  private val rootExecutor = SuRootExecutor()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)
    phoneAutomationStore = PhoneAutomationPreferencesStore(this)
    cpuFrequencyStore = CpuFrequencyPreferencesStore(this)
    binding.phoneAutomationIntervalSlider.valueFrom = 0f
    binding.phoneAutomationIntervalSlider.valueTo = PhoneAutomationDispatchInterval.entries.lastIndex.toFloat()
    binding.phoneAutomationIntervalSlider.stepSize = 1f

    val facade = AppGraph.facade(this)
    binding.openAccessibilitySettingsButton.setOnClickListener {
      startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
    binding.openNotificationAccessButton.setOnClickListener {
      startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
    binding.openBackgroundReliabilityBatteryButton.setOnClickListener {
      openSettingsIntent(
        primary = PhoneAutomationBackgroundReliabilitySupport.requestBatteryUnrestrictedIntent(this),
        fallback = PhoneAutomationBackgroundReliabilitySupport.batteryOptimizationSettingsIntent(this),
        label = "background_battery"
      )
    }
    binding.openBackgroundReliabilityExactAlarmButton.setOnClickListener {
      openSettingsIntent(
        primary = PhoneAutomationBackgroundReliabilitySupport.requestExactAlarmIntent(this),
        fallback = PhoneAutomationBackgroundReliabilitySupport.appDetailsIntent(this),
        label = "exact_alarm"
      )
    }
    binding.backgroundReliabilityToggleButton.setOnClickListener {
      setBackgroundReliabilityExpanded(!backgroundReliabilityExpanded)
    }

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

    binding.cpuFrequencyRestoreStockButton.setOnClickListener {
      cpuFrequencyStore.setEnabled(false)
      renderCpuFrequencySection(cpuFrequencyStore.load(), cpuFrequencyStore.load().liveSnapshot)
      SupervisorService.start(
        context = this,
        action = SupervisorService.ACTION_REFRESH_CPU_FREQUENCY
      )
    }

    renderPhoneAutomationState()
    renderCpuFrequencySection(cpuFrequencyStore.load(), cpuFrequencyStore.load().liveSnapshot)
    setBackgroundReliabilityExpanded(expanded = false)
    handleIntentActionIfPresent(facade, intent)
  }

  override fun onResume() {
    super.onResume()
    val snapshot = phoneAutomationStore.load()
    renderPhoneAutomationState(snapshot)
    renderCpuFrequencySection(cpuFrequencyStore.load(), cpuFrequencyStore.load().liveSnapshot)
    maybeRefreshPhoneAutomationAfterResume(snapshot)
    maybeInterruptProtectedPhoneAutomationHandoff(snapshot)
    startPhoneAutomationRenderLoop()
    startCpuFrequencyRenderLoop()
  }

  override fun onPause() {
    phoneAutomationRenderJob?.cancel()
    phoneAutomationRenderJob = null
    cpuFrequencyRenderJob?.cancel()
    cpuFrequencyRenderJob = null
    protectedHandoffUiSessionActive = false
    super.onPause()
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
        health.moduleHealth["cpu_frequency"]?.let { cpuFrequency ->
          appendLine("cpu_frequency=${cpuFrequency.healthy}:${cpuFrequency.status}")
        }
        appendLine("ddns=${health.ddnsHealthy}")
        appendLine("supervisor_loop=${health.supervisorLoopHealthy}")
        appendLine("deploy=${health.deployHealthy}")
        appendLine("supervisor=${health.supervisorHealthy}")
      }
    }

    binding.statusText.text = text
    renderPhoneAutomationState()
    lifecycleScope.launch { renderCpuFrequencyState() }
    Log.i(TAG, text.trim())
  }

  private fun renderPhoneAutomationState() {
    renderPhoneAutomationState(phoneAutomationStore.load())
  }

  private fun renderPhoneAutomationState(snapshot: PhoneAutomationSettingsSnapshot) {
    val reliability = PhoneAutomationBackgroundReliabilitySupport.read(this)
    renderBackgroundReliabilitySection(reliability)
    val uiPolicy = phoneAutomationUiPolicy(snapshot)
    binding.phoneAutomationToggle.setOnCheckedChangeListener(null)
    binding.phoneAutomationToggle.isChecked = snapshot.enabled
    binding.phoneAutomationToggle.isEnabled = uiPolicy.phoneAutomationToggleEnabled
    binding.phoneAutomationToggle.setOnCheckedChangeListener { _, enabled ->
      phoneAutomationStore.setEnabled(enabled)
      renderPhoneAutomationState()
      SupervisorService.start(
        context = this,
        action = SupervisorService.ACTION_REFRESH_PHONE_AUTOMATION
      )
    }
    binding.phoneAutomationCellMapperToggle.setOnCheckedChangeListener(null)
    binding.phoneAutomationCellMapperToggle.isChecked = snapshot.maintainCellMapper
    binding.phoneAutomationCellMapperToggle.isEnabled = uiPolicy.cellMapperToggleEnabled
    binding.phoneAutomationCellMapperToggle.setOnCheckedChangeListener { _, maintainCellMapper ->
      phoneAutomationStore.setMaintainCellMapper(maintainCellMapper)
      renderPhoneAutomationState()
      SupervisorService.start(
        context = this,
        action = SupervisorService.ACTION_REFRESH_PHONE_AUTOMATION
      )
    }
    binding.phoneAutomationReturnToOrchestratorToggle.setOnCheckedChangeListener(null)
    binding.phoneAutomationReturnToOrchestratorToggle.isChecked =
      snapshot.returnToOrchestratorAfterForegroundWork
    binding.phoneAutomationReturnToOrchestratorToggle.visibility = if (snapshot.enabled) {
      View.VISIBLE
    } else {
      View.GONE
    }
    binding.phoneAutomationReturnToOrchestratorToggle.isEnabled =
      uiPolicy.returnToOrchestratorToggleEnabled
    binding.phoneAutomationReturnToOrchestratorToggle.setOnCheckedChangeListener { _, enabled ->
      phoneAutomationStore.setReturnToOrchestratorAfterForegroundWork(enabled)
      renderPhoneAutomationState()
      SupervisorService.start(
        context = this,
        action = SupervisorService.ACTION_REFRESH_PHONE_AUTOMATION
      )
    }
    binding.phoneAutomationIntervalText.text = getString(
      R.string.phone_automation_interval_line,
      snapshot.dispatchInterval.sliderLabel
    )
    binding.phoneAutomationIntervalSlider.clearOnChangeListeners()
    binding.phoneAutomationIntervalSlider.value = snapshot.dispatchInterval.sliderIndex.toFloat()
    binding.phoneAutomationIntervalSlider.isEnabled = uiPolicy.dispatchIntervalEnabled
    binding.phoneAutomationIntervalSlider.addOnChangeListener { _, value, fromUser ->
      if (!fromUser) {
        return@addOnChangeListener
      }
      val dispatchInterval = PhoneAutomationDispatchInterval.fromSliderIndex(value.toInt())
      if (dispatchInterval == phoneAutomationStore.load().dispatchInterval) {
        return@addOnChangeListener
      }
      phoneAutomationStore.setDispatchInterval(dispatchInterval)
      renderPhoneAutomationState()
      SupervisorService.start(
        context = this,
        action = SupervisorService.ACTION_REFRESH_PHONE_AUTOMATION
      )
    }
    binding.phoneAutomationSetupText.text = getString(R.string.phone_automation_setup_line, snapshot.setupSummary())
    binding.phoneAutomationRuntimeText.text = getString(R.string.phone_automation_runtime_line, snapshot.runtimeSummary())
    binding.touchBrightnessToggle.setOnCheckedChangeListener(null)
    binding.touchBrightnessToggle.isChecked = snapshot.touchBrightnessEnabled
    binding.touchBrightnessToggle.isEnabled = uiPolicy.touchBrightnessToggleEnabled
    binding.touchBrightnessToggle.setOnCheckedChangeListener { _, enabled ->
      phoneAutomationStore.setTouchBrightnessEnabled(enabled)
      renderPhoneAutomationState()
      SupervisorService.start(
        context = this,
        action = SupervisorService.ACTION_REFRESH_PHONE_AUTOMATION
      )
    }
    binding.touchBrightnessRuntimeText.text = getString(
      R.string.touch_brightness_runtime_line,
      snapshot.touchBrightnessRuntimeSummary()
    )
    binding.touchBrightnessDebugText.text = getString(
      R.string.touch_brightness_debug_line,
      snapshot.touchBrightnessDebugDetail.ifBlank { "Waiting for live touch data" }
    )
    binding.touchBrightnessDebugText.visibility = if (snapshot.touchBrightnessEnabled) {
      View.VISIBLE
    } else {
      View.GONE
    }
    if (!uiPolicy.protectedHandoff) {
      protectedHandoffUiSessionActive = false
    }
  }

  private fun renderBackgroundReliabilitySection(
    reliability: PhoneAutomationBackgroundReliability
  ) {
    binding.backgroundReliabilityBatteryText.text = getString(
      R.string.background_reliability_battery_line,
      getString(
        if (reliability.batteryUnrestricted) {
          R.string.background_reliability_status_unrestricted
        } else {
          R.string.background_reliability_status_restricted
        }
      )
    )
    binding.backgroundReliabilityExactAlarmText.text = getString(
      R.string.background_reliability_exact_alarm_line,
      getString(
        if (reliability.exactAlarmGranted) {
          R.string.background_reliability_status_granted
        } else {
          R.string.background_reliability_status_missing
        }
      )
    )
    binding.openBackgroundReliabilityBatteryButton.isEnabled = !reliability.batteryUnrestricted
    binding.openBackgroundReliabilityExactAlarmButton.visibility =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        View.VISIBLE
      } else {
        View.GONE
      }
    binding.openBackgroundReliabilityExactAlarmButton.isEnabled = !reliability.exactAlarmGranted
    updateBackgroundReliabilityToggleButton()
  }

  private fun setBackgroundReliabilityExpanded(expanded: Boolean) {
    backgroundReliabilityExpanded = expanded
    binding.backgroundReliabilitySection.visibility = if (expanded) {
      View.VISIBLE
    } else {
      View.GONE
    }
    updateBackgroundReliabilityToggleButton()
  }

  private fun updateBackgroundReliabilityToggleButton() {
    binding.backgroundReliabilityToggleButton.text = getString(
      if (backgroundReliabilityExpanded) {
        R.string.background_reliability_hide_button
      } else {
        R.string.background_reliability_show_button
      }
    )
  }

  private fun maybeRefreshPhoneAutomationAfterResume(snapshot: PhoneAutomationSettingsSnapshot) {
    if (!snapshot.enabled && !snapshot.touchBrightnessEnabled) {
      return
    }
    if (snapshot.isProtectedSpeedtestHandoffInProgress()) {
      return
    }
    val now = System.currentTimeMillis()
    if (now - lastPhoneAutomationResumeRefreshAtMillis < ACTIVITY_RESUME_REFRESH_DEBOUNCE_MILLIS) {
      return
    }
    lastPhoneAutomationResumeRefreshAtMillis = now
    PhoneAutomationWakeScheduler.rescheduleFromStore(
      context = this,
      reason = "activity_resume",
      force = false
    )
    SupervisorService.start(
      context = this,
      action = SupervisorService.ACTION_REFRESH_PHONE_AUTOMATION
    )
  }

  private fun maybeInterruptProtectedPhoneAutomationHandoff(snapshot: PhoneAutomationSettingsSnapshot) {
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

      PhoneAutomationForegroundInterruptDecision.SUPPRESS_BLACKOUT_WAKE -> {
        protectedHandoffUiSessionActive = false
        Log.i(
          TAG,
          "phone_automation_handoff_interrupt_suppressed reason=blackout_wake runtime=${snapshot.runtimeState.wireName} blackout_at=${PhoneAutomationServiceBridge.lastBlackoutWakeAtMillis()} started_at=${snapshot.protectedHandoffStartedAtMillis}"
        )
      }

      PhoneAutomationForegroundInterruptDecision.SUPPRESS_SELF_FOREGROUND -> {
        protectedHandoffUiSessionActive = false
        Log.i(
          TAG,
          "phone_automation_handoff_interrupt_suppressed reason=self_foreground runtime=${snapshot.runtimeState.wireName} foreground_expected_at=${PhoneAutomationServiceBridge.lastExpectedOrchestratorForegroundAtMillis()} started_at=${snapshot.protectedHandoffStartedAtMillis}"
        )
      }

      PhoneAutomationForegroundInterruptDecision.INTERRUPT -> {
        if (protectedHandoffUiSessionActive) {
          return
        }
        protectedHandoffUiSessionActive = true
        Log.i(
          TAG,
          "phone_automation_handoff_interrupt_requested reason=foreground_open runtime=${snapshot.runtimeState.wireName}"
        )
        SupervisorService.start(
          context = this,
          action = SupervisorService.ACTION_INTERRUPT_PHONE_AUTOMATION_HANDOFF
        )
      }
    }
  }

  private fun startPhoneAutomationRenderLoop() {
    phoneAutomationRenderJob?.cancel()
    phoneAutomationRenderJob = lifecycleScope.launch {
      while (isActive) {
        renderPhoneAutomationState()
        delay(PHONE_AUTOMATION_RENDER_INTERVAL_MILLIS)
      }
    }
  }

  private fun startCpuFrequencyRenderLoop() {
    cpuFrequencyRenderJob?.cancel()
    cpuFrequencyRenderJob = lifecycleScope.launch {
      while (isActive) {
        renderCpuFrequencyState()
        delay(CPU_FREQUENCY_RENDER_INTERVAL_MILLIS)
      }
    }
  }

  private suspend fun renderCpuFrequencyState() {
    val snapshot = cpuFrequencyStore.load()
    val liveSnapshot = withContext(Dispatchers.IO) {
      CpuFrequencySupport.readLiveSnapshot(rootExecutor).getOrElse { snapshot.liveSnapshot }
    }
    renderCpuFrequencySection(snapshot, liveSnapshot)
  }

  private fun renderCpuFrequencySection(
    snapshot: CpuFrequencySettingsSnapshot,
    liveSnapshot: CpuFrequencyLiveSnapshot
  ) {
    binding.cpuFrequencyToggle.setOnCheckedChangeListener(null)
    binding.cpuFrequencyToggle.isChecked = snapshot.enabled
    binding.cpuFrequencyToggle.setOnCheckedChangeListener { _, enabled ->
      cpuFrequencyStore.setEnabled(enabled)
      renderCpuFrequencySection(cpuFrequencyStore.load(), liveSnapshot)
      SupervisorService.start(
        context = this,
        action = SupervisorService.ACTION_REFRESH_CPU_FREQUENCY
      )
    }
    binding.cpuFrequencyRuntimeText.text = getString(
      R.string.cpu_frequency_runtime_line,
      snapshot.runtimeSummary()
    )
    binding.cpuFrequencyThermalText.text = getString(
      R.string.cpu_frequency_thermal_line,
      liveSnapshot.thermalStatusLabel(),
      if (liveSnapshot.charging) "Yes" else "No",
      liveSnapshot.batteryTempCelsius()
    )
    bindCpuFrequencySlider(
      cluster = CpuFrequencyCluster.LITTLE,
      textView = binding.cpuFrequencyLittleText,
      slider = binding.cpuFrequencyLittleSlider,
      snapshot = snapshot,
      liveSnapshot = liveSnapshot
    )
    bindCpuFrequencySlider(
      cluster = CpuFrequencyCluster.MID,
      textView = binding.cpuFrequencyMidText,
      slider = binding.cpuFrequencyMidSlider,
      snapshot = snapshot,
      liveSnapshot = liveSnapshot
    )
    bindCpuFrequencySlider(
      cluster = CpuFrequencyCluster.BIG,
      textView = binding.cpuFrequencyBigText,
      slider = binding.cpuFrequencyBigSlider,
      snapshot = snapshot,
      liveSnapshot = liveSnapshot
    )
    bindGpuFrequencySlider(
      textView = binding.cpuFrequencyGpuText,
      slider = binding.cpuFrequencyGpuSlider,
      snapshot = snapshot,
      liveSnapshot = liveSnapshot.gpu
    )
  }

  private fun bindCpuFrequencySlider(
    cluster: CpuFrequencyCluster,
    textView: android.widget.TextView,
    slider: Slider,
    snapshot: CpuFrequencySettingsSnapshot,
    liveSnapshot: CpuFrequencyLiveSnapshot
  ) {
    val policy = liveSnapshot.policy(cluster)
    textView.text = getString(
      R.string.cpu_frequency_cluster_line,
      cluster.displayName,
      CpuFrequencySettingsSnapshot.formatKHz(policy.currentFreqKHz),
      CpuFrequencySettingsSnapshot.formatKHz(policy.appliedMaxFreqKHz),
      CpuFrequencySettingsSnapshot.formatKHz(policy.stockMaxFreqKHz)
    )
    val available = policy.availableFreqsKHz
    slider.clearOnChangeListeners()
    slider.clearOnSliderTouchListeners()
    if (available.isEmpty()) {
      slider.isEnabled = false
      return
    }
    slider.isEnabled = true
    slider.valueFrom = 0f
    slider.valueTo = (available.lastIndex).toFloat()
    slider.stepSize = 1f
    slider.setLabelFormatter { value ->
      CpuFrequencySettingsSnapshot.formatKHz(available[value.toInt().coerceIn(0, available.lastIndex)])
    }
    val desired = snapshot.desiredCap(cluster) ?: policy.appliedMaxFreqKHz.takeIf { it > 0L } ?: available.last()
    slider.value = nearestFrequencyIndex(available, desired).toFloat()
    slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
      override fun onStartTrackingTouch(slider: Slider) = Unit

      override fun onStopTrackingTouch(slider: Slider) {
        val value = available[slider.value.toInt().coerceIn(0, available.lastIndex)]
        cpuFrequencyStore.setDesiredCap(cluster, value)
        renderCpuFrequencySection(cpuFrequencyStore.load(), liveSnapshot)
        if (cpuFrequencyStore.load().enabled) {
          SupervisorService.start(
            context = this@MainActivity,
            action = SupervisorService.ACTION_REFRESH_CPU_FREQUENCY
          )
        }
      }
    })
  }

  private fun bindGpuFrequencySlider(
    textView: android.widget.TextView,
    slider: Slider,
    snapshot: CpuFrequencySettingsSnapshot,
    liveSnapshot: GpuFrequencyLiveSnapshot
  ) {
    slider.clearOnChangeListeners()
    slider.clearOnSliderTouchListeners()
    if (!liveSnapshot.available) {
      textView.text = getString(R.string.cpu_frequency_gpu_unavailable_line)
      slider.isEnabled = false
      return
    }
    textView.text = getString(
      R.string.cpu_frequency_gpu_line,
      CpuFrequencySettingsSnapshot.formatKHz(liveSnapshot.currentFreqKHz),
      CpuFrequencySettingsSnapshot.formatKHz(liveSnapshot.appliedMaxFreqKHz),
      CpuFrequencySettingsSnapshot.formatKHz(liveSnapshot.stockMaxFreqKHz),
      liveSnapshot.governorLabel(),
      liveSnapshot.utilizationLabel()
    )
    val available = liveSnapshot.availableFreqsKHz
    if (available.isEmpty()) {
      slider.isEnabled = false
      return
    }
    slider.isEnabled = true
    slider.valueFrom = 0f
    slider.valueTo = available.lastIndex.toFloat()
    slider.stepSize = 1f
    slider.setLabelFormatter { value ->
      CpuFrequencySettingsSnapshot.formatKHz(available[value.toInt().coerceIn(0, available.lastIndex)])
    }
    val desired = snapshot.desiredGpuCap()
      ?: liveSnapshot.appliedMaxFreqKHz.takeIf { it > 0L }
      ?: liveSnapshot.stockMaxFreqKHz.takeIf { it > 0L }
      ?: available.last()
    slider.value = nearestFrequencyIndex(available, desired).toFloat()
    slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
      override fun onStartTrackingTouch(slider: Slider) = Unit

      override fun onStopTrackingTouch(slider: Slider) {
        val value = available[slider.value.toInt().coerceIn(0, available.lastIndex)]
        cpuFrequencyStore.setDesiredGpuCap(value)
        renderCpuFrequencySection(cpuFrequencyStore.load(), cpuFrequencyStore.load().liveSnapshot)
        if (cpuFrequencyStore.load().enabled) {
          SupervisorService.start(
            context = this@MainActivity,
            action = SupervisorService.ACTION_REFRESH_CPU_FREQUENCY
          )
        }
      }
    })
  }

  private fun nearestFrequencyIndex(values: List<Long>, desired: Long): Int {
    val exact = values.indexOf(desired)
    if (exact >= 0) {
      return exact
    }
    val nearestLower = values.indexOfLast { it <= desired }
    return if (nearestLower >= 0) nearestLower else values.lastIndex
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

  private fun openSettingsIntent(
    primary: Intent,
    fallback: Intent,
    label: String
  ) {
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
    private const val PHONE_AUTOMATION_RENDER_INTERVAL_MILLIS = 1_000L
    private const val CPU_FREQUENCY_RENDER_INTERVAL_MILLIS = 2_000L
    private const val ACTIVITY_RESUME_REFRESH_DEBOUNCE_MILLIS = 2_000L
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
