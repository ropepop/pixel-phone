package lv.jolkins.pixelorchestrator.app.phoneautomation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAutomationWakeSchedulerTest {
  @Test
  fun schedulesEarliestWakePlan() {
    val alarmClient = FakePhoneAutomationWakeAlarmClient()
    val scheduler = PhoneAutomationWakeScheduler(
      alarmClient = alarmClient,
      clockMillis = { 45_000L }
    )

    val result = scheduler.reschedule(
      snapshot = snapshot(
        lastRunStartedAtMillis = 35_000L,
        speedtestState = SpeedtestActivityState.RUNNING,
        runtimeState = PhoneAutomationRuntimeState.RESTARTING_SPEEDTEST,
        protectedHandoffStartedAtMillis = 40_000L
      ),
      reason = "test"
    )

    assertEquals(PhoneAutomationWakeScheduleAction.SCHEDULED, result.action)
    assertEquals(PhoneAutomationWakeReason.PROTECTED_HANDOFF_TIMEOUT, result.plan?.reason)
    assertEquals(1, alarmClient.scheduled.size)
    assertEquals(80_000L, alarmClient.scheduled.single().triggerAtMillis)
  }

  @Test
  fun replacesExistingAlarmWhenStateChanges() {
    val alarmClient = FakePhoneAutomationWakeAlarmClient()
    val scheduler = PhoneAutomationWakeScheduler(
      alarmClient = alarmClient,
      clockMillis = { 10_000L }
    )

    scheduler.reschedule(
      snapshot = snapshot(
        lastRunStartedAtMillis = 10_000L,
        lastHandledCompletionAtMillis = 11_000L,
        speedtestState = SpeedtestActivityState.NOT_RUNNING
      ),
      reason = "initial"
    )

    val result = scheduler.reschedule(
      snapshot = snapshot(
        lastRunStartedAtMillis = 30_000L,
        speedtestState = SpeedtestActivityState.RUNNING,
        runtimeState = PhoneAutomationRuntimeState.WAITING_FOR_SPEEDTEST_RESULT
      ),
      reason = "state_changed"
    )

    assertEquals(PhoneAutomationWakeScheduleAction.SCHEDULED, result.action)
    assertEquals(2, alarmClient.scheduled.size)
    assertEquals(PhoneAutomationWakeReason.COMPLETION_RESULT_TIMEOUT, alarmClient.scheduled.last().plan.reason)
    assertEquals(90_000L, alarmClient.scheduled.last().triggerAtMillis)
  }

  @Test
  fun cancelsAlarmWhenAutomationBecomesDisabled() {
    val alarmClient = FakePhoneAutomationWakeAlarmClient()
    val scheduler = PhoneAutomationWakeScheduler(
      alarmClient = alarmClient,
      clockMillis = { 10_000L }
    )

    scheduler.reschedule(
      snapshot = snapshot(
        lastRunStartedAtMillis = 10_000L,
        lastHandledCompletionAtMillis = 11_000L,
        speedtestState = SpeedtestActivityState.NOT_RUNNING
      ),
      reason = "initial"
    )

    val result = scheduler.reschedule(
      snapshot = snapshot(enabled = false),
      reason = "disabled"
    )

    assertEquals(PhoneAutomationWakeScheduleAction.CANCELED, result.action)
    assertEquals(1, alarmClient.cancelCount)
  }

  @Test
  fun blockedAutomationCancelsAlarm() {
    val alarmClient = FakePhoneAutomationWakeAlarmClient()
    val scheduler = PhoneAutomationWakeScheduler(
      alarmClient = alarmClient,
      clockMillis = { 10_000L }
    )

    scheduler.reschedule(
      snapshot = snapshot(
        lastRunStartedAtMillis = 10_000L,
        speedtestState = SpeedtestActivityState.RUNNING,
        runtimeState = PhoneAutomationRuntimeState.WAITING_FOR_SPEEDTEST_RESULT
      ),
      reason = "initial"
    )

    val result = scheduler.reschedule(
      snapshot = snapshot(
        setupState = PhoneAutomationSetupState.SETUP_BLOCKED,
        runtimeState = PhoneAutomationRuntimeState.SETUP_BLOCKED
      ),
      reason = "blocked"
    )

    assertEquals(PhoneAutomationWakeScheduleAction.CANCELED, result.action)
    assertEquals(1, alarmClient.cancelCount)
  }

  @Test
  fun missingExactAlarmAccessLeavesWakeUnscheduled() {
    val alarmClient = FakePhoneAutomationWakeAlarmClient(canScheduleExactAlarms = false)
    val scheduler = PhoneAutomationWakeScheduler(
      alarmClient = alarmClient,
      clockMillis = { 10_000L }
    )

    val result = scheduler.reschedule(
      snapshot = snapshot(
        lastRunStartedAtMillis = 10_000L,
        lastHandledCompletionAtMillis = 11_000L,
        speedtestState = SpeedtestActivityState.NOT_RUNNING
      ),
      reason = "missing_exact_alarm"
    )

    assertEquals(PhoneAutomationWakeScheduleAction.NOT_SCHEDULED, result.action)
    assertEquals(1, alarmClient.cancelCount)
    assertTrue(alarmClient.scheduled.isEmpty())
  }

  @Test
  fun unchangedPlanDoesNotScheduleTwice() {
    val alarmClient = FakePhoneAutomationWakeAlarmClient()
    val scheduler = PhoneAutomationWakeScheduler(
      alarmClient = alarmClient,
      clockMillis = { 10_000L }
    )
    val snapshot = snapshot(
      lastRunStartedAtMillis = 10_000L,
      lastHandledCompletionAtMillis = 11_000L,
      speedtestState = SpeedtestActivityState.NOT_RUNNING
    )

    scheduler.reschedule(snapshot = snapshot, reason = "initial")
    val result = scheduler.reschedule(snapshot = snapshot, reason = "repeat")

    assertEquals(PhoneAutomationWakeScheduleAction.UNCHANGED, result.action)
    assertEquals(1, alarmClient.scheduled.size)
    assertEquals(PhoneAutomationWakeReason.NEXT_DISPATCH, result.plan?.reason)
  }

  @Test
  fun immediateCompletedRunSchedulesWakeAtNow() {
    val alarmClient = FakePhoneAutomationWakeAlarmClient()
    val scheduler = PhoneAutomationWakeScheduler(
      alarmClient = alarmClient,
      clockMillis = { 20_000L }
    )

    val result = scheduler.reschedule(
      snapshot = snapshot(
        dispatchInterval = PhoneAutomationDispatchInterval.IMMEDIATE,
        lastRunStartedAtMillis = 10_000L,
        lastCompletionNotificationAtMillis = 10_400L,
        lastResultReadyAtMillis = 10_450L,
        lastHandledCompletionAtMillis = 10_500L,
        speedtestState = SpeedtestActivityState.NOT_RUNNING
      ),
      reason = "immediate_completion"
    )

    assertEquals(PhoneAutomationWakeScheduleAction.SCHEDULED, result.action)
    assertEquals(PhoneAutomationWakeReason.NEXT_DISPATCH, result.plan?.reason)
    assertEquals(20_000L, alarmClient.scheduled.single().triggerAtMillis)
  }

  @Test
  fun immediateIncompleteRunSchedulesCompletionResultTimeoutWake() {
    val alarmClient = FakePhoneAutomationWakeAlarmClient()
    val scheduler = PhoneAutomationWakeScheduler(
      alarmClient = alarmClient,
      clockMillis = { 20_000L }
    )

    val result = scheduler.reschedule(
      snapshot = snapshot(
        dispatchInterval = PhoneAutomationDispatchInterval.IMMEDIATE,
        runtimeState = PhoneAutomationRuntimeState.WAITING_FOR_SPEEDTEST_RESULT,
        lastRunStartedAtMillis = 10_000L,
        speedtestState = SpeedtestActivityState.NOT_RUNNING
      ),
      reason = "immediate_incomplete"
    )

    assertEquals(PhoneAutomationWakeScheduleAction.SCHEDULED, result.action)
    assertEquals(PhoneAutomationWakeReason.COMPLETION_RESULT_TIMEOUT, result.plan?.reason)
    assertEquals(70_000L, alarmClient.scheduled.single().triggerAtMillis)
  }

  @Test
  fun immediateRuntimeErrorSchedulesRetryWakeAtNow() {
    val alarmClient = FakePhoneAutomationWakeAlarmClient()
    val scheduler = PhoneAutomationWakeScheduler(
      alarmClient = alarmClient,
      clockMillis = { 20_000L }
    )

    val result = scheduler.reschedule(
      snapshot = snapshot(
        dispatchInterval = PhoneAutomationDispatchInterval.IMMEDIATE,
        runtimeState = PhoneAutomationRuntimeState.ERROR,
        runtimeErrorRetryAtMillis = 5_000L
      ),
      reason = "immediate_runtime_error"
    )

    assertEquals(PhoneAutomationWakeScheduleAction.SCHEDULED, result.action)
    assertEquals(PhoneAutomationWakeReason.RUNTIME_ERROR_RETRY, result.plan?.reason)
    assertEquals(20_000L, alarmClient.scheduled.single().triggerAtMillis)
  }

  @Test
  fun pendingRecoverySchedulesWakeBeforeCompletionResultTimeout() {
    val alarmClient = FakePhoneAutomationWakeAlarmClient()
    val scheduler = PhoneAutomationWakeScheduler(
      alarmClient = alarmClient,
      clockMillis = { 20_000L }
    )

    val result = scheduler.reschedule(
      snapshot = snapshot(
        dispatchInterval = PhoneAutomationDispatchInterval.IMMEDIATE,
        runtimeState = PhoneAutomationRuntimeState.WAITING_FOR_RECOVERY_RETRY,
        lastRunStartedAtMillis = 10_000L,
        pendingRecoveryAction = PhoneAutomationPendingRecoveryAction.RESTART,
        pendingRecoveryReason = "runtime_error_recovery",
        pendingRecoveryNotBeforeAtMillis = 24_000L
      ),
      reason = "pending_recovery"
    )

    assertEquals(PhoneAutomationWakeScheduleAction.SCHEDULED, result.action)
    assertEquals(PhoneAutomationWakeReason.PENDING_RECOVERY, result.plan?.reason)
    assertEquals(24_000L, alarmClient.scheduled.single().triggerAtMillis)
  }

  private fun snapshot(
    enabled: Boolean = true,
    dispatchInterval: PhoneAutomationDispatchInterval = PhoneAutomationDispatchInterval.EVERY_60_SECONDS,
    setupState: PhoneAutomationSetupState = PhoneAutomationSetupState.READY,
    runtimeState: PhoneAutomationRuntimeState = PhoneAutomationRuntimeState.WAITING_FOR_NEXT_DISPATCH,
    lastRunStartedAtMillis: Long = 0L,
    lastCompletionNotificationAtMillis: Long = 0L,
    lastResultReadyAtMillis: Long = 0L,
    lastHandledCompletionAtMillis: Long = 0L,
    speedtestState: SpeedtestActivityState = SpeedtestActivityState.NOT_RUNNING,
    protectedHandoffStartedAtMillis: Long = 0L,
    pendingRecoveryAction: PhoneAutomationPendingRecoveryAction = PhoneAutomationPendingRecoveryAction.NONE,
    pendingRecoveryReason: String = "",
    pendingRecoveryNotBeforeAtMillis: Long = 0L,
    pendingRecoveryPhase: PhoneAutomationPendingRecoveryPhase = when {
      pendingRecoveryAction == PhoneAutomationPendingRecoveryAction.NONE -> {
        PhoneAutomationPendingRecoveryPhase.NONE
      }

      pendingRecoveryNotBeforeAtMillis > 0L -> {
        PhoneAutomationPendingRecoveryPhase.QUEUED_RETRY
      }

      else -> {
        PhoneAutomationPendingRecoveryPhase.HANDOFF_RESUME
      }
    },
    pendingRecoveryToken: String = "",
    runtimeErrorRetryAtMillis: Long = 0L,
    updatedAtMillis: Long = 0L
  ): PhoneAutomationSettingsSnapshot {
    return PhoneAutomationSettingsSnapshot(
      enabled = enabled,
      dispatchInterval = dispatchInterval,
      setupState = setupState,
      runtimeState = runtimeState,
      lastRunStartedAtMillis = lastRunStartedAtMillis,
      lastCompletionNotificationAtMillis = lastCompletionNotificationAtMillis,
      lastResultReadyAtMillis = lastResultReadyAtMillis,
      lastHandledCompletionAtMillis = lastHandledCompletionAtMillis,
      speedtestState = speedtestState,
      protectedHandoffStartedAtMillis = protectedHandoffStartedAtMillis,
      pendingRecoveryAction = pendingRecoveryAction,
      pendingRecoveryPhase = pendingRecoveryPhase,
      pendingRecoveryReason = pendingRecoveryReason,
      pendingRecoveryNotBeforeAtMillis = pendingRecoveryNotBeforeAtMillis,
      pendingRecoveryToken = pendingRecoveryToken,
      runtimeErrorRetryAtMillis = runtimeErrorRetryAtMillis,
      updatedAtMillis = updatedAtMillis
    )
  }
}

private class FakePhoneAutomationWakeAlarmClient(
  private val canScheduleExactAlarms: Boolean = true
) : PhoneAutomationWakeAlarmClient {
  val scheduled = mutableListOf<ScheduledWake>()
  var cancelCount: Int = 0

  override fun canScheduleExactAlarms(): Boolean = canScheduleExactAlarms

  override fun schedule(plan: PhoneAutomationWakePlan, triggerAtMillis: Long) {
    scheduled += ScheduledWake(plan = plan, triggerAtMillis = triggerAtMillis)
  }

  override fun cancel() {
    cancelCount += 1
  }
}

private data class ScheduledWake(
  val plan: PhoneAutomationWakePlan,
  val triggerAtMillis: Long
)
