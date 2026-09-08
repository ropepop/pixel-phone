package lv.jolkins.pixelorchestrator.app.ticket

import android.content.Context
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

object TicketScreenConfig {
  const val ACTION_START_SERVER = "lv.jolkins.pixelorchestrator.ticket.START_SERVER"
  const val ACTION_STOP_SERVER = "lv.jolkins.pixelorchestrator.ticket.STOP_SERVER"

  const val SERVICE_PORT = 9388
  const val VIVI_PACKAGE = "com.pv.vivi"
  const val ROOT_HARDWARE_H264_CAPTURE_MODE = "root_hardware_h264"
  const val ROOT_HARDWARE_H264_TRANSPORT = "hardware-h264-annexb"
  const val ROOT_HARDWARE_H264_QUALITY_PROFILE = "hardware_h264_crisp_all_intra_1fps"
  const val ROOT_HARDWARE_H264_CODEC_STRING = "avc1.42C028"
  const val ROOT_HARDWARE_H264_FPS = 1
  const val ROOT_HARDWARE_H264_FRAME_DEPENDENCY_MODE = "all_intra"
  const val ROOT_HARDWARE_H264_BITRATE = 8_000_000
  const val ROOT_HARDWARE_H264_TARGET_WIDTH = 994
  const val ROOT_HARDWARE_H264_KEYFRAME_INTERVAL_MILLIS = 1000
  const val ROOT_HARDWARE_H264_CAPTURE_SOURCE = "root_display_capture"
  const val ROOT_HARDWARE_H264_CAPTURE_METHOD = "app_process_mediacodec_surface_secure_screen_capture"
  const val ROOT_HARDWARE_H264_COLOR_CORRECTION = "red_blue_swap_high_brightness_sdr_gpu_paint_r1.08_g1.05_b1.03"
  const val ROOT_HARDWARE_H264_COLOR_STANDARD = "bt709_limited_sdr"
  // ScreenCapture exposes a one-pixel native display ring. Live encoded-frame proof requires
  // two additional source pixels on the left and one on the right/bottom so filtered 1080-to-994
  // sampling and 4:2:0 chroma cannot carry that saturated edge into the encoded outer pixels.
  const val TICKET_MEDIA_LEFT_CROP_SOURCE_PIXELS = 4
  const val TICKET_MEDIA_TOP_CROP_SOURCE_PIXELS = 200
  const val TICKET_MEDIA_RIGHT_CROP_SOURCE_PIXELS = 3
  const val TICKET_MEDIA_BOTTOM_CROP_SOURCE_PIXELS = 3

}

@Serializable
data class TicketStreamHealth(
  val ok: Boolean,
  val serverVersion: String,
  val phoneUptimeMillis: Long = 0L,
  val sessionState: String = "idle",
  val serverRunning: Boolean,
  val viviInstalled: Boolean,
  val streamActive: Boolean,
  val streamVerdict: String = "idle",
  val clients: Int,
  val inactivityActive: Boolean,
  val inactivityTimeoutMillis: Long,
  val inactivityRemainingMillis: Long,
  val autoStartAllowed: Boolean = true,
  val autoStartBlockedReason: String? = null,
  val viviState: TicketViviStateHealth = TicketViviStateHealth(),
  val ticketState: TicketControlStateHealth = TicketControlStateHealth(),
  val streamPipeline: TicketStreamPipeline,
  val controlCodeRequest: TicketControlCodeRequestHealth = TicketControlCodeRequestHealth(),
  val viviReauth: TicketViviReauthHealth = TicketViviReauthHealth(),
  val brightnessGuard: TicketBrightnessGuardHealth = TicketBrightnessGuardHealth(),
  val actionPanelDarkLease: TicketActionPanelDarkLeaseHealth = TicketActionPanelDarkLeaseHealth(),
  val hardwareH264: TicketHardwareH264Health = TicketHardwareH264Health(),
  val recovery: TicketRecoveryHealth = TicketRecoveryHealth(),
  val message: String
)

@Serializable
data class TicketStreamPipeline(
  val videoClients: Int,
  val captureMode: String,
  val streamEpoch: Long,
  val frameSequence: Long,
  val lastKeyFrameSequence: Long,
  val streamConfigured: Boolean,
  val sentFrames: Long,
  val droppedVideoFrames: Long,
  val lastFrameSentAgoMillis: Long?,
  val secureWindowCaptureBypassActive: Boolean,
  val secureWindowCaptureBypassMessage: String,
  val rootH264BlankProbeResult: String
)


@Serializable
data class TicketHardwareH264Health(
  val available: Boolean = false,
  val active: Boolean = false,
  val encoderName: String? = null,
  val configuredEncoderProfile: String = "unknown",
  val configuredEncoderLevel: String = "unknown",
  val configuredEncoderBitrateMode: String = "unknown",
  val captureSource: String = TicketScreenConfig.ROOT_HARDWARE_H264_CAPTURE_SOURCE,
  val captureMethod: String = TicketScreenConfig.ROOT_HARDWARE_H264_CAPTURE_METHOD,
  val captureHelperAvailable: Boolean = false,
  val captureHelperState: String = "unavailable",
  val captureHelperMessage: String = "",
  val state: String = "unavailable",
  val message: String = "",
  val width: Int? = null,
  val height: Int? = null,
  val bitrate: Int? = null,
  val fps: Int? = null,
  val frameDependencyMode: String = TicketScreenConfig.ROOT_HARDWARE_H264_FRAME_DEPENDENCY_MODE,
  val ordinaryCaptureDemandGated: Boolean = false,
  val captureFrameExpected: Boolean = false,
  val captureFrameExpectedAgoMillis: Long? = null,
  val intervalMode: String = "",
  val currentIntervalMillis: Long? = null,
  val colorCorrection: String = TicketScreenConfig.ROOT_HARDWARE_H264_COLOR_CORRECTION,
  val colorStandard: String = TicketScreenConfig.ROOT_HARDWARE_H264_COLOR_STANDARD,
  val frames: Long = 0L,
  val lastFrameBytes: Int = 0,
  val lastFrameAgoMillis: Long? = null,
  val lastStartAgoMillis: Long? = null,
  val helperFrameRecord: String = "thf1",
  val lastFrameSourceToServiceMillis: Long? = null,
  val secureLayerCaptureEnabled: Boolean = true,
  val protectedContentCaptureEnabled: Boolean = true,
  val lastVisibilityCheckResult: String = "not_run",
  val blankFrameFailures: Long = 0L,
  val encoderProcessCount: Int = 0,
  val staleCaptureProcessCount: Int = 0,
  val lastCaptureCleanupResult: String = "not_run",
  val droppedFrames: Long = 0L,
  val unexpectedDeltaFrames: Long = 0L,
  val restartCount: Long = 0L,
  val lastExitReason: String? = null,
  val lastExitAgoMillis: Long? = null,
  val stderrTail: String = ""
)

@Serializable
data class TicketControlCodeRequestHealth(
  val requestId: String? = null,
  val status: String = "idle",
  val reason: String? = null,
  val value: String? = null,
  val totalDurationMillis: Long? = null,
  val browserCaptureReason: String? = null,
  val browserCaptureAgoMillis: Long? = null,
  val completedAgoMillis: Long? = null,
  val duplicateResults: Long = 0L,
  val lastDuplicateRequestId: String? = null,
  val lastDuplicateAgoMillis: Long? = null
)

@Serializable
data class TicketBrightnessGuardHealth(
  val active: Boolean = false,
  val targetPercent: Int = 1,
  val currentDisplayPercent: Float? = null,
  val currentPanelBrightness: Int? = null,
  val currentPanelMaxBrightness: Int? = null,
  val lastEnforcedAgoMillis: Long? = null,
  val failures: Long = 0L,
  val lastReason: String? = null,
  val message: String = "Ticket brightness guard is inactive"
)

@Serializable
data class TicketActionPanelDarkLeaseHealth(
  val active: Boolean = false,
  val protectionMode: String = "dark",
  val physicalVisibleWindowRemainingMillis: Long = 0L,
  val ownerActionId: String = "",
  val ageMillis: Long? = null,
  val lastZeroConfirmationAgoMillis: Long? = null,
  val failure: String = "",
  val failures: Long = 0L,
  val physicalTouchPreempted: Boolean = false,
  val releaseReason: String = "idle",
  val launchExitCode: Int? = null,
  val launchDurationMillis: Long? = null,
  val lastVerifierClassification: String = "not_run",
  val lastVerifierExitCode: Int? = null,
  val lastVerifierDurationMillis: Long? = null
)

@Serializable
data class TicketRecoveryHealth(
  val streamStage: String = "idle",
  val lastWatchdogAction: String = "none",
  val lastStreamRecoveryResult: String = "none",
  val lastStreamRecoveryReason: String? = null,
  val lastStreamRecoveryAgoMillis: Long? = null,
  val lastStreamRecoveryFailureReason: String? = null
)

@Serializable
data class TicketControlStateHealth(
  val state: String = "stopped",
  val stateAgeMillis: Long? = null,
  val lastReason: String = "init"
)

@Serializable
data class TicketViviStateHealth(
  val state: String = "UNKNOWN_VIVI",
  val ticketId: String? = null,
  val observedAgoMillis: Long? = null,
  val source: String = "none",
  val reason: String = "none"
)

@Serializable
data class TicketSessionResponse(
  val ok: Boolean,
  val state: String,
  val message: String
)

data class TicketStreamSize(
  val width: Int,
  val height: Int,
  val sourceWidth: Int,
  val sourceHeight: Int,
  val sourceLeftCrop: Int = 0,
  val sourceTopCrop: Int = 0,
  val sourceRightCrop: Int = 0,
  val sourceBottomCrop: Int = 0
) {
  val sourceVisibleWidth: Int =
    (sourceWidth - sourceLeftCrop - sourceRightCrop).coerceAtLeast(1)
  val sourceVisibleHeight: Int =
    (sourceHeight - sourceTopCrop - sourceBottomCrop).coerceAtLeast(1)

}

internal data class TicketSourceCrop(
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int
) {
  val width: Int get() = (right - left).coerceAtLeast(1)
  val height: Int get() = (bottom - top).coerceAtLeast(1)
}

internal data class TicketNormalizedBounds(
  val leftBasisPoints: Int,
  val topBasisPoints: Int,
  val rightBasisPoints: Int,
  val bottomBasisPoints: Int
)

internal object TicketCaptureGeometry {
  fun sourceCrop(sourceWidth: Int, sourceHeight: Int): TicketSourceCrop {
    val cleanWidth = sourceWidth.coerceAtLeast(1)
    val cleanHeight = sourceHeight.coerceAtLeast(1)
    val left = TicketScreenConfig.TICKET_MEDIA_LEFT_CROP_SOURCE_PIXELS
      .coerceIn(0, (cleanWidth - 1).coerceAtLeast(0))
    val rightCrop = TicketScreenConfig.TICKET_MEDIA_RIGHT_CROP_SOURCE_PIXELS
      .coerceIn(0, (cleanWidth - left - 1).coerceAtLeast(0))
    val top = TicketScreenConfig.TICKET_MEDIA_TOP_CROP_SOURCE_PIXELS
      .coerceIn(0, (cleanHeight - 1).coerceAtLeast(0))
    val bottomCrop = TicketScreenConfig.TICKET_MEDIA_BOTTOM_CROP_SOURCE_PIXELS
      .coerceIn(0, (cleanHeight - top - 1).coerceAtLeast(0))
    return TicketSourceCrop(
      left = left,
      top = top,
      right = cleanWidth - rightCrop,
      bottom = cleanHeight - bottomCrop
    )
  }

  fun mapProbeBoundsToDevice(
    bounds: TicketVisualProbeBounds,
    probeWidth: Int,
    probeHeight: Int,
    sourceWidth: Int,
    sourceHeight: Int
  ): TicketViviGraphicBounds {
    val crop = sourceCrop(sourceWidth, sourceHeight)
    val safeProbeWidth = probeWidth.coerceAtLeast(1)
    val safeProbeHeight = probeHeight.coerceAtLeast(1)
    return TicketViviGraphicBounds(
      left = (crop.left + bounds.left / safeProbeWidth.toFloat() * crop.width)
        .roundToInt().coerceIn(crop.left, crop.right),
      top = (crop.top + bounds.top / safeProbeHeight.toFloat() * crop.height)
        .roundToInt().coerceIn(crop.top, crop.bottom),
      right = (crop.left + bounds.right / safeProbeWidth.toFloat() * crop.width)
        .roundToInt().coerceIn(crop.left, crop.right),
      bottom = (crop.top + bounds.bottom / safeProbeHeight.toFloat() * crop.height)
        .roundToInt().coerceIn(crop.top, crop.bottom)
    )
  }

  fun normalizeProbeBounds(
    bounds: TicketVisualProbeBounds,
    probeWidth: Int,
    probeHeight: Int
  ): TicketNormalizedBounds? {
    if (probeWidth <= 0 || probeHeight <= 0 || bounds.width <= 0 || bounds.height <= 0) {
      return null
    }
    fun basisPoints(value: Int, extent: Int): Int =
      (value / extent.toFloat() * 10_000f).roundToInt().coerceIn(0, 10_000)
    val normalized = TicketNormalizedBounds(
      leftBasisPoints = basisPoints(bounds.left, probeWidth),
      topBasisPoints = basisPoints(bounds.top, probeHeight),
      rightBasisPoints = basisPoints(bounds.right, probeWidth),
      bottomBasisPoints = basisPoints(bounds.bottom, probeHeight)
    )
    return normalized.takeIf {
      it.leftBasisPoints < it.rightBasisPoints &&
        it.topBasisPoints < it.bottomBasisPoints
    }
  }
}

object TicketStreamSizing {
  fun rootHardwareH264(sourceWidth: Int, sourceHeight: Int): TicketStreamSize {
    val crop = TicketCaptureGeometry.sourceCrop(sourceWidth, sourceHeight)
    // Keep the stream close to the native ticket width while remaining below both the existing
    // equivalent-pixel ceiling and the H.264 Level 4 macroblock ceiling on the production Pixel.
    val legacyVisibleSourceHeight = (sourceHeight - crop.top).coerceAtLeast(1)
    val width = minOf(sourceWidth, TicketScreenConfig.ROOT_HARDWARE_H264_TARGET_WIDTH).evenAtLeastTwo()
    val height = ((legacyVisibleSourceHeight / sourceWidth.toFloat()) * width).roundToInt().evenAtLeastTwo()
    return TicketStreamSize(
      width = width,
      height = height,
      sourceWidth = sourceWidth,
      sourceHeight = sourceHeight,
      sourceLeftCrop = crop.left,
      sourceTopCrop = crop.top,
      sourceRightCrop = sourceWidth - crop.right,
      sourceBottomCrop = sourceHeight - crop.bottom
    )
  }

  private fun Int.evenAtLeastTwo(): Int {
    val atLeastTwo = coerceAtLeast(2)
    return if (atLeastTwo % 2 == 0) atLeastTwo else atLeastTwo - 1
  }
}

internal object TicketInactivityPolicy {
  const val TIMEOUT_MILLIS = 10 * 60 * 1_000L
  const val SERVER_BROADCAST_MILLIS = 5_000L
  const val URGENT_BROADCAST_MILLIS = 1_000L
  const val URGENT_WINDOW_MILLIS = 60_000L

  fun remainingMillis(
    lastInputAtMillis: Long,
    nowMillis: Long,
    timeoutMillis: Long = TIMEOUT_MILLIS
  ): Long {
    val elapsed = (nowMillis - lastInputAtMillis).coerceAtLeast(0L)
    return (timeoutMillis - elapsed).coerceIn(0L, timeoutMillis)
  }

  fun timedOut(
    lastInputAtMillis: Long,
    nowMillis: Long,
    timeoutMillis: Long = TIMEOUT_MILLIS
  ): Boolean {
    return remainingMillis(
      lastInputAtMillis = lastInputAtMillis,
      nowMillis = nowMillis,
      timeoutMillis = timeoutMillis
    ) <= 0L
  }

  fun shouldRetain(
    lastInputAtMillis: Long,
    nowMillis: Long,
    activeViewerDemand: Boolean,
    timeoutMillis: Long = TIMEOUT_MILLIS
  ): Boolean {
    return activeViewerDemand && timedOut(lastInputAtMillis, nowMillis, timeoutMillis)
  }

  fun shouldStop(
    lastInputAtMillis: Long,
    nowMillis: Long,
    activeViewerDemand: Boolean,
    timeoutMillis: Long = TIMEOUT_MILLIS
  ): Boolean {
    return !activeViewerDemand && timedOut(lastInputAtMillis, nowMillis, timeoutMillis)
  }

  fun nextTickMillis(remainingMillis: Long): Long {
    return if (remainingMillis <= URGENT_WINDOW_MILLIS) {
      URGENT_BROADCAST_MILLIS
    } else {
      SERVER_BROADCAST_MILLIS
    }
  }
}

internal object TicketSessionStopPolicy {
  const val VIEWER_INACTIVITY_TIMEOUT = "viewer_inactivity_timeout"
  const val BROWSER_EXPLICIT_STOP = "browser_stop"

  private val browserAutoStartBlockedReasons = setOf(
    VIEWER_INACTIVITY_TIMEOUT,
    BROWSER_EXPLICIT_STOP,
    "remote_power_controls_blocked",
    "remote_network_controls_blocked",
    "remote_system_ui_blocked",
    "left_vivi_app"
  )

  fun browserAutoStartAllowedAfterStop(reason: String?): Boolean {
    return reason == null || reason !in browserAutoStartBlockedReasons
  }
}

object TicketPackageSupport {
  fun isInstalled(context: Context, packageName: String): Boolean {
    return runCatching {
      @Suppress("DEPRECATION")
      context.packageManager.getPackageInfo(packageName, 0)
    }.isSuccess
  }
}
