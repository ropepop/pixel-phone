package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.serialization.json.*

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
    return NativeTicketCapture.call("continue_encoder", buildJsonObject {
      put("active", encoderActive); put("state", encoderState); put("gated", ordinaryCaptureDemandGated)
      put("expected", captureFrameExpected); put("firstPending", firstUsefulFramePending)
      put("frameAge", frameAgeMillis); put("startAge", encoderStartAgeMillis)
      put("liveMax", liveFrameMaxAgeMillis); put("wait", startupWaitMillis); put("expectedAge", captureFrameExpectedAgoMillis)
    }).jsonPrimitive.boolean
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
    return NativeTicketCapture.call("waiting_first", buildJsonObject {
      put("active", encoderActive); put("state", encoderState); put("startAge", encoderStartAgeMillis)
      put("frameAge", lastFrameAgeMillis); put("sourceAge", lastFrameSourceToServiceMillis)
      put("grace", graceMillis); put("usefulMax", sourceUsefulnessMillis)
    }).jsonPrimitive.boolean
  }
}
