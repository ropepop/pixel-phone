package lv.jolkins.pixelorchestrator.app.ticket

internal object TicketStreamStartupRecoveryPolicy {
  /** Recheck and restart under the service's existing encoder owner, including slow teardown. */
  fun restartIfNeeded(lock: Any, shouldRestart: () -> Boolean, restart: () -> Unit): Boolean {
    return synchronized(lock) {
      if (!shouldRestart()) return@synchronized false
      restart()
      true
    }
  }

  fun canContinueCurrentEncoder(
    encoderActive: Boolean,
    encoderState: String,
    ordinaryCaptureDemandGated: Boolean,
    captureFrameExpected: Boolean,
    firstUsefulFramePending: Boolean,
    frameAgeMillis: Long?,
    encoderStartAgeMillis: Long?,
    liveFrameMaxAgeMillis: Long,
    startupWaitMillis: Long,
    captureFrameExpectedAgoMillis: Long? = null
  ): Boolean {
    if (encoderActive && ordinaryCaptureDemandGated && !captureFrameExpected) return true
    if (encoderActive && ordinaryCaptureDemandGated && captureFrameExpectedAgoMillis != null &&
      captureFrameExpectedAgoMillis < startupWaitMillis) return true
    if (firstUsefulFramePending) return true
    if (!encoderActive && encoderState != "starting" && encoderState != "restarting") return false
    if (frameAgeMillis != null && frameAgeMillis <= liveFrameMaxAgeMillis) return true
    return encoderStartAgeMillis == null || encoderStartAgeMillis < startupWaitMillis
  }

  fun waitingForFirstUsefulFrame(
    encoderActive: Boolean,
    encoderState: String,
    encoderStartAgeMillis: Long?,
    lastFrameAgeMillis: Long?,
    lastFrameSourceToServiceMillis: Long?,
    graceMillis: Long,
    sourceUsefulnessMillis: Long
  ): Boolean {
    if (
      encoderStartAgeMillis == null ||
      encoderStartAgeMillis < 0L ||
      graceMillis <= 0L ||
      encoderStartAgeMillis >= graceMillis
    ) {
      return false
    }
    if (!encoderActive && encoderState != "starting" && encoderState != "restarting") {
      return false
    }
    val usefulFrameFromCurrentEncoder =
      lastFrameAgeMillis != null &&
        lastFrameAgeMillis >= 0L &&
        lastFrameAgeMillis <= encoderStartAgeMillis &&
        lastFrameSourceToServiceMillis != null &&
        lastFrameSourceToServiceMillis <= sourceUsefulnessMillis
    return !usefulFrameFromCurrentEncoder
  }
}
