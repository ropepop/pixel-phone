package lv.jolkins.pixelorchestrator.app.phoneautomation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneAutomationWakePolicyTest {
  @Test
  fun disabledAutomationDoesNotRequestWake() {
    assertNull(
      PhoneAutomationWakePolicy.nextWake(
        snapshot = snapshot(enabled = false),
        nowMillis = 1_000L
      )
    )
  }

  @Test
  fun completedRunSchedulesNextDispatchWake() {
    val plan = PhoneAutomationWakePolicy.nextWake(
      snapshot = snapshot(
        lastRunStartedAtMillis = 10_000L,
        lastHandledCompletionAtMillis = 10_500L,
        speedtestState = SpeedtestActivityState.NOT_RUNNING,
        runtimeState = PhoneAutomationRuntimeState.WAITING_FOR_NEXT_DISPATCH
      ),
      nowMillis = 20_000L
    )

    assertEquals(
      PhoneAutomationWakePlan(
        deadlineAtMillis = 70_000L,
        reason = PhoneAutomationWakeReason.NEXT_DISPATCH
      ),
      plan
    )
  }

  @Test
  fun activeRunSchedulesCompletionResultTimeoutWake() {
    val plan = PhoneAutomationWakePolicy.nextWake(
      snapshot = snapshot(
        lastRunStartedAtMillis = 25_000L,
        speedtestState = SpeedtestActivityState.RUNNING,
        runtimeState = PhoneAutomationRuntimeState.WAITING_FOR_SPEEDTEST_RESULT
      ),
      nowMillis = 30_000L
    )

    assertEquals(
      PhoneAutomationWakePlan(
        deadlineAtMillis = 85_000L,
        reason = PhoneAutomationWakeReason.COMPLETION_RESULT_TIMEOUT
      ),
      plan
    )
  }

  @Test
  fun immediateIncompleteRunSchedulesCompletionResultTimeoutWake() {
    val plan = PhoneAutomationWakePolicy.nextWake(
      snapshot = snapshot(
        dispatchInterval = PhoneAutomationDispatchInterval.IMMEDIATE,
        runtimeState = PhoneAutomationRuntimeState.WAITING_FOR_SPEEDTEST_RESULT,
        lastRunStartedAtMillis = 25_000L,
        speedtestState = SpeedtestActivityState.NOT_RUNNING
      ),
      nowMillis = 30_000L
    )

    assertEquals(
      PhoneAutomationWakePlan(
        deadlineAtMillis = 85_000L,
        reason = PhoneAutomationWakeReason.COMPLETION_RESULT_TIMEOUT
      ),
      plan
    )
  }

  @Test
  fun protectedHandoffTimeoutWinsWhenItIsSoonerThanOtherDeadlines() {
    val plan = PhoneAutomationWakePolicy.nextWake(
      snapshot = snapshot(
        lastRunStartedAtMillis = 35_000L,
        speedtestState = SpeedtestActivityState.RUNNING,
        runtimeState = PhoneAutomationRuntimeState.RESTARTING_SPEEDTEST,
        protectedHandoffStartedAtMillis = 40_000L
      ),
      nowMillis = 45_000L
    )

    assertEquals(
      PhoneAutomationWakePlan(
        deadlineAtMillis = 80_000L,
        reason = PhoneAutomationWakeReason.PROTECTED_HANDOFF_TIMEOUT
      ),
      plan
    )
  }

  @Test
  fun runtimeErrorStillRetriesAfterSavedRetryInterval() {
    val plan = PhoneAutomationWakePolicy.nextWake(
      snapshot = snapshot(
        runtimeState = PhoneAutomationRuntimeState.ERROR,
        runtimeErrorRetryAtMillis = 65_000L
      ),
      nowMillis = 10_000L
    )

    assertEquals(
      PhoneAutomationWakePlan(
        deadlineAtMillis = 65_000L,
        reason = PhoneAutomationWakeReason.RUNTIME_ERROR_RETRY
      ),
      plan
    )
  }

  @Test
  fun pendingRecoveryWinsOverCompletionResultTimeout() {
    val plan = PhoneAutomationWakePolicy.nextWake(
      snapshot = snapshot(
        dispatchInterval = PhoneAutomationDispatchInterval.IMMEDIATE,
        runtimeState = PhoneAutomationRuntimeState.WAITING_FOR_RECOVERY_RETRY,
        lastRunStartedAtMillis = 25_000L,
        pendingRecoveryAction = PhoneAutomationPendingRecoveryAction.RESTART,
        pendingRecoveryReason = "runtime_error_recovery",
        pendingRecoveryNotBeforeAtMillis = 35_000L
      ),
      nowMillis = 31_000L
    )

    assertEquals(
      PhoneAutomationWakePlan(
        deadlineAtMillis = 35_000L,
        reason = PhoneAutomationWakeReason.PENDING_RECOVERY
      ),
      plan
    )
  }

  @Test
  fun handoffResumeDoesNotSchedulePendingRecoveryWake() {
    val plan = PhoneAutomationWakePolicy.nextWake(
      snapshot = snapshot(
        dispatchInterval = PhoneAutomationDispatchInterval.IMMEDIATE,
        runtimeState = PhoneAutomationRuntimeState.RESTARTING_SPEEDTEST,
        lastRunStartedAtMillis = 25_000L,
        protectedHandoffStartedAtMillis = 30_000L,
        pendingRecoveryAction = PhoneAutomationPendingRecoveryAction.RESTART,
        pendingRecoveryPhase = PhoneAutomationPendingRecoveryPhase.HANDOFF_RESUME,
        pendingRecoveryReason = "runtime_error_recovery",
        pendingRecoveryNotBeforeAtMillis = 35_000L
      ),
      nowMillis = 31_000L
    )

    assertEquals(
      PhoneAutomationWakePlan(
        deadlineAtMillis = 70_000L,
        reason = PhoneAutomationWakeReason.PROTECTED_HANDOFF_TIMEOUT
      ),
      plan
    )
  }

  @Test
  fun setupBlockedAutomationDoesNotRequestWake() {
    assertNull(
      PhoneAutomationWakePolicy.nextWake(
        snapshot = snapshot(
          setupState = PhoneAutomationSetupState.SETUP_BLOCKED,
          runtimeState = PhoneAutomationRuntimeState.SETUP_BLOCKED
        ),
        nowMillis = 1_000L
      )
    )
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
