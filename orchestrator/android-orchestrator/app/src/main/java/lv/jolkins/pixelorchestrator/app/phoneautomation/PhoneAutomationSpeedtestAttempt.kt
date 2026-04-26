package lv.jolkins.pixelorchestrator.app.phoneautomation

internal data class SpeedtestAttempt(
  val attemptId: String = "",
  val launchMode: SpeedtestRunLaunchMode = SpeedtestRunLaunchMode.NONE,
  val startedAtMillis: Long = 0L,
  val startProofAtMillis: Long = 0L,
  val resultScreenClearedAtMillis: Long = 0L,
  val completionNotificationAtMillis: Long = 0L,
  val stableResultFingerprint: String = "",
  val acceptedAtMillis: Long = 0L
) {
  fun isTracked(): Boolean = attemptId.isNotBlank() && startedAtMillis > 0L

  fun hasStartProof(): Boolean = isTracked() && startProofAtMillis >= startedAtMillis

  fun leftPreviousResultScreen(): Boolean {
    return isTracked() && resultScreenClearedAtMillis >= startedAtMillis
  }

  fun hasCompletionNotification(): Boolean {
    return isTracked() && completionNotificationAtMillis >= startedAtMillis
  }

  fun isAccepted(): Boolean = isTracked() && acceptedAtMillis >= startedAtMillis
}

internal data class SpeedtestCompletionObservation(
  val completionNotificationAtMillis: Long = 0L,
  val resultReadyAtMillis: Long = 0L,
  val resultFingerprint: String = "",
  val resultScreenVisible: Boolean = false
) {
  fun hasVisibleResultSince(startedAtMillis: Long): Boolean {
    return resultScreenVisible && resultReadyAtMillis >= startedAtMillis && resultFingerprint.isNotBlank()
  }
}

internal data class SpeedtestResultFingerprintStability(
  val fingerprint: String = "",
  val consecutivePolls: Int = 0
) {
  fun observe(observation: SpeedtestCompletionObservation): SpeedtestResultFingerprintStability {
    if (!observation.resultScreenVisible || observation.resultFingerprint.isBlank()) {
      return SpeedtestResultFingerprintStability()
    }
    return if (observation.resultFingerprint == fingerprint) {
      copy(consecutivePolls = consecutivePolls + 1)
    } else {
      SpeedtestResultFingerprintStability(
        fingerprint = observation.resultFingerprint,
        consecutivePolls = 1
      )
    }
  }

  fun isStable(): Boolean = fingerprint.isNotBlank() && consecutivePolls >= REQUIRED_POLLS

  private companion object {
    private const val REQUIRED_POLLS = 2
  }
}

internal enum class SpeedtestAttemptDecision {
  KEEP_WAITING,
  ACCEPT_NOTIFICATION_ONLY,
  ACCEPT_STABLE_RESULT,
  CAPTURE_ACCEPTED_RESULT,
  FALLBACK_TO_COLD,
  QUEUE_PENDING_RECOVERY
}

internal fun decideSpeedtestAttempt(
  attempt: SpeedtestAttempt,
  observation: SpeedtestCompletionObservation,
  stability: SpeedtestResultFingerprintStability,
  lastAcceptedResultFingerprint: String
): SpeedtestAttemptDecision {
  if (!attempt.isTracked()) {
    return SpeedtestAttemptDecision.KEEP_WAITING
  }

  val hasSafeResultSignal = attempt.hasStartProof() || attempt.leftPreviousResultScreen()
  val stableVisibleFingerprint = stability.isStable() &&
    observation.hasVisibleResultSince(attempt.startedAtMillis) &&
    stability.fingerprint == observation.resultFingerprint
  val stableFreshFingerprint = stableVisibleFingerprint &&
    (lastAcceptedResultFingerprint.isBlank() || stability.fingerprint != lastAcceptedResultFingerprint)
  val stableStaleFingerprint = stableVisibleFingerprint &&
    lastAcceptedResultFingerprint.isNotBlank() &&
    stability.fingerprint == lastAcceptedResultFingerprint

  if (attempt.isAccepted()) {
    return if (stableFreshFingerprint && stability.fingerprint != attempt.stableResultFingerprint) {
      SpeedtestAttemptDecision.CAPTURE_ACCEPTED_RESULT
    } else {
      SpeedtestAttemptDecision.KEEP_WAITING
    }
  }

  if (stableVisibleFingerprint && !hasSafeResultSignal) {
    return SpeedtestAttemptDecision.KEEP_WAITING
  }
  if (stableFreshFingerprint) {
    return SpeedtestAttemptDecision.ACCEPT_STABLE_RESULT
  }
  if (stableStaleFingerprint) {
    return if (attempt.launchMode == SpeedtestRunLaunchMode.WARM_IN_APP) {
      SpeedtestAttemptDecision.FALLBACK_TO_COLD
    } else {
      SpeedtestAttemptDecision.QUEUE_PENDING_RECOVERY
    }
  }
  if (attempt.hasCompletionNotification()) {
    return SpeedtestAttemptDecision.ACCEPT_NOTIFICATION_ONLY
  }
  return SpeedtestAttemptDecision.KEEP_WAITING
}
