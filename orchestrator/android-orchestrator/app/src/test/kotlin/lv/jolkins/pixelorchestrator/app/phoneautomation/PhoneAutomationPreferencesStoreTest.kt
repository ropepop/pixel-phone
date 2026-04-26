package lv.jolkins.pixelorchestrator.app.phoneautomation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAutomationPreferencesStoreTest {
  @Test
  fun toggleSetupAndRuntimeStatePersistAcrossStoreInstances() {
    val backend = InMemoryPreferencesBackend()
    val firstStore = PhoneAutomationPreferencesStore(backend)

    firstStore.setEnabled(true)
    firstStore.setMaintainCellMapper(false)
    firstStore.setReturnToOrchestratorAfterForegroundWork(false)
    firstStore.setDispatchInterval(PhoneAutomationDispatchInterval.EVERY_10_MINUTES)
    firstStore.setTouchBrightnessEnabled(true)
    firstStore.updateTouchBrightnessState(TouchBrightnessRuntimeState.BLACKOUT_IDLE, "blackout")
    firstStore.updateTouchBrightnessDebugDetail("touches=1 source=synaptics_tcm_touch")
    firstStore.saveTouchBrightnessRestoreState(mode = 1, value = 127)
    firstStore.updateSetupState(PhoneAutomationSetupState.READY, "ready")
    firstStore.updateRuntimeState(
      PhoneAutomationRuntimeState.WAITING_FOR_SPEEDTEST_COMPLETION,
      "waiting"
    )
    firstStore.updateCycleState(
      lastRunStartedAtMillis = 100L,
      lastCompletionNotificationAtMillis = 85L,
      lastResultReadyAtMillis = 95L,
      lastHandledCompletionAtMillis = 90L,
      currentRunLaunchMode = SpeedtestRunLaunchMode.COLD_FRESH_LAUNCH,
      lastAcceptedResultFingerprint = "download=123|upload=45",
      speedtestState = SpeedtestActivityState.RUNNING,
      cellMapperState = CellMapperRecordingState.ACTIVE,
      pendingRecoveryReason = "resume_existing_speedtest"
    )
    firstStore.recordTransientFailure(reason = "temporary listener issue", observedAtMillis = 88L)

    val secondStore = PhoneAutomationPreferencesStore(backend)
    val snapshot = secondStore.load()

    assertTrue(snapshot.enabled)
    assertEquals(false, snapshot.maintainCellMapper)
    assertEquals(false, snapshot.returnToOrchestratorAfterForegroundWork)
    assertEquals(PhoneAutomationDispatchInterval.EVERY_10_MINUTES, snapshot.dispatchInterval)
    assertTrue(snapshot.touchBrightnessEnabled)
    assertEquals(TouchBrightnessRuntimeState.BLACKOUT_IDLE, snapshot.touchBrightnessState)
    assertEquals("blackout", snapshot.touchBrightnessDetail)
    assertEquals("touches=1 source=synaptics_tcm_touch", snapshot.touchBrightnessDebugDetail)
    assertEquals(1, snapshot.touchBrightnessRestoreMode)
    assertEquals(127, snapshot.touchBrightnessRestoreValue)
    assertEquals(PhoneAutomationSetupState.READY, snapshot.setupState)
    assertEquals("ready", snapshot.setupDetail)
    assertEquals(
      PhoneAutomationRuntimeState.WAITING_FOR_SPEEDTEST_COMPLETION,
      snapshot.runtimeState
    )
    assertEquals("waiting", snapshot.runtimeDetail)
    assertEquals(100L, snapshot.lastRunStartedAtMillis)
    assertEquals(85L, snapshot.lastCompletionNotificationAtMillis)
    assertEquals(95L, snapshot.lastResultReadyAtMillis)
    assertEquals(90L, snapshot.lastHandledCompletionAtMillis)
    assertEquals(SpeedtestRunLaunchMode.COLD_FRESH_LAUNCH, snapshot.currentRunLaunchMode)
    assertEquals("download=123|upload=45", snapshot.lastAcceptedResultFingerprint)
    assertEquals(SpeedtestActivityState.RUNNING, snapshot.speedtestState)
    assertEquals(CellMapperRecordingState.ACTIVE, snapshot.cellMapperState)
    assertEquals("temporary listener issue", snapshot.pendingRecoveryReason)
    assertEquals(1, snapshot.transientFailureCount)
    assertEquals(88L, snapshot.lastTransientFailureAtMillis)
  }

  @Test
  fun protectedHandoffTimestampPersistsAndClearsWhenStateLeavesProtectedMode() {
    val backend = InMemoryPreferencesBackend()
    val store = PhoneAutomationPreferencesStore(backend)

    store.setEnabled(true)

    val placeholderSnapshot = PhoneAutomationPreferencesStore(backend).load()
    assertEquals(PhoneAutomationRuntimeState.STARTING, placeholderSnapshot.runtimeState)
    assertEquals("Waiting for the supervision service", placeholderSnapshot.runtimeDetail)
    assertEquals(0L, placeholderSnapshot.protectedHandoffStartedAtMillis)

    store.updateRuntimeState(
      PhoneAutomationRuntimeState.STARTING,
      "Checking phone automation setup"
    )

    val protectedSnapshot = PhoneAutomationPreferencesStore(backend).load()
    assertEquals(PhoneAutomationRuntimeState.STARTING, protectedSnapshot.runtimeState)
    assertEquals("Checking phone automation setup", protectedSnapshot.runtimeDetail)
    assertTrue(protectedSnapshot.protectedHandoffStartedAtMillis > 0L)

    store.updateRuntimeState(
      PhoneAutomationRuntimeState.WAITING_FOR_NEXT_DISPATCH,
      "Waiting for next dispatch"
    )

    val clearedSnapshot = PhoneAutomationPreferencesStore(backend).load()
    assertEquals(0L, clearedSnapshot.protectedHandoffStartedAtMillis)
  }

  @Test
  fun missingCellMapperPreferenceDefaultsToMaintained() {
    val backend = InMemoryPreferencesBackend()
    val legacyStore = PhoneAutomationPreferencesStore(backend)

    legacyStore.setEnabled(true)

    val upgradedStore = PhoneAutomationPreferencesStore(backend)
    val snapshot = upgradedStore.load()

    assertTrue(snapshot.enabled)
    assertTrue(snapshot.maintainCellMapper)
    assertEquals(PhoneAutomationDispatchInterval.default, snapshot.dispatchInterval)
  }

  @Test
  fun missingReturnToOrchestratorPreferenceDefaultsToEnabled() {
    val backend = InMemoryPreferencesBackend()

    val snapshot = PhoneAutomationPreferencesStore(backend).load()

    assertTrue(snapshot.returnToOrchestratorAfterForegroundWork)
  }

  @Test
  fun returnToOrchestratorPreferencePersistsAcrossStoreInstances() {
    val backend = InMemoryPreferencesBackend()
    val firstStore = PhoneAutomationPreferencesStore(backend)

    firstStore.setReturnToOrchestratorAfterForegroundWork(false)

    val secondStore = PhoneAutomationPreferencesStore(backend)
    assertEquals(false, secondStore.load().returnToOrchestratorAfterForegroundWork)

    secondStore.setReturnToOrchestratorAfterForegroundWork(true)

    val thirdStore = PhoneAutomationPreferencesStore(backend)
    assertTrue(thirdStore.load().returnToOrchestratorAfterForegroundWork)
  }

  @Test
  fun missingDispatchIntervalDefaultsToSixtySeconds() {
    val backend = InMemoryPreferencesBackend()

    val snapshot = PhoneAutomationPreferencesStore(backend).load()

    assertEquals(PhoneAutomationDispatchInterval.EVERY_60_SECONDS, snapshot.dispatchInterval)
  }

  @Test
  fun immediateDispatchIntervalPersistsAcrossStoreInstances() {
    val backend = InMemoryPreferencesBackend()
    val firstStore = PhoneAutomationPreferencesStore(backend)

    firstStore.setDispatchInterval(PhoneAutomationDispatchInterval.IMMEDIATE)

    val snapshot = PhoneAutomationPreferencesStore(backend).load()

    assertEquals(PhoneAutomationDispatchInterval.IMMEDIATE, snapshot.dispatchInterval)
  }

  @Test
  fun immediateCadenceSummaryUsesReadableSummaryText() {
    val snapshot = PhoneAutomationSettingsSnapshot(
      enabled = true,
      maintainCellMapper = false,
      dispatchInterval = PhoneAutomationDispatchInterval.IMMEDIATE,
      setupState = PhoneAutomationSetupState.READY,
      setupDetail = "Ready"
    )

    assertEquals("immediately on completion", snapshot.cadenceSummary())
    assertEquals(
      "Speedtest only immediately on completion: Ready",
      snapshot.setupSummary()
    )
  }

  @Test
  fun enablingTouchBrightnessClearsStaleRestoreSnapshot() {
    val backend = InMemoryPreferencesBackend()
    val store = PhoneAutomationPreferencesStore(backend)

    store.saveTouchBrightnessRestoreState(mode = 0, value = 42)
    store.setTouchBrightnessEnabled(true)

    val snapshot = store.load()
    assertTrue(snapshot.touchBrightnessEnabled)
    assertEquals("Waiting for live touch data", snapshot.touchBrightnessDebugDetail)
    assertEquals(null, snapshot.touchBrightnessRestoreMode)
    assertEquals(null, snapshot.touchBrightnessRestoreValue)
  }

  @Test
  fun legacyDimmedStateLoadsAsBlackoutIdle() {
    val backend = InMemoryPreferencesBackend()
    backend.applyMutations(
      mapOf(
        "touch_brightness_state" to "dimmed"
      )
    )

    val snapshot = PhoneAutomationPreferencesStore(backend).load()

    assertEquals(TouchBrightnessRuntimeState.BLACKOUT_IDLE, snapshot.touchBrightnessState)
  }
}

private class InMemoryPreferencesBackend : PhoneAutomationPreferencesBackend {
  private val values = linkedMapOf<String, Any>()

  override fun contains(key: String): Boolean {
    return values.containsKey(key)
  }

  override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
    return values[key] as? Boolean ?: defaultValue
  }

  override fun getString(key: String, defaultValue: String): String {
    return values[key] as? String ?: defaultValue
  }

  override fun getLong(key: String, defaultValue: Long): Long {
    return values[key] as? Long ?: defaultValue
  }

  override fun applyMutations(mutations: Map<String, Any>) {
    values.putAll(mutations)
  }
}
