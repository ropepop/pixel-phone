package lv.jolkins.pixelorchestrator.app.ticket

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
import kotlin.math.sqrt

object TicketScreenConfig {
  const val ACTION_START_SERVER = "lv.jolkins.pixelorchestrator.ticket.START_SERVER"
  const val ACTION_STOP_SERVER = "lv.jolkins.pixelorchestrator.ticket.STOP_SERVER"
  const val ACTION_MEDIA_PROJECTION_RESULT = "lv.jolkins.pixelorchestrator.ticket.MEDIA_PROJECTION_RESULT"
  const val EXTRA_RESULT_CODE = "ticket_media_projection_result_code"
  const val EXTRA_RESULT_DATA = "ticket_media_projection_result_data"

  const val SERVICE_PORT = 9388
  const val VIVI_PACKAGE = "com.pv.vivi"
  const val VIVI_LAUNCH_ACTIVITY = "com.pv.vivi/.MainActivity"
  const val ACCRESCENT_PACKAGE = "app.accrescent.client"
  const val MAX_FPS = 10
  const val MAX_EQUIVALENT_PIXELS = 1920 * 1080
  const val ROOT_PNG_QUALITY_PROFILE = "root_lossless_png"
  const val ROOT_PNG_CODEC_STRING = "png"
  const val ROOT_PNG_TRANSPORT = "root-screencap-png"
  const val ROOT_FFMPEG_H264_CAPTURE_MODE = "root_ffmpeg_h264"
  const val ROOT_FFMPEG_H264_TRANSPORT = "ffmpeg-h264-annexb"
  const val ROOT_FFMPEG_H264_QUALITY_PROFILE = "ffmpeg_h264_clarity"
  const val ROOT_FFMPEG_H264_CODEC_STRING = "avc1.42C028"
  const val ROOT_FFMPEG_H264_FPS = 8
  const val ROOT_FFMPEG_H264_BITRATE = 8_000_000
  const val ROOT_FFMPEG_H264_TARGET_WIDTH = 720
  const val ROOT_FFMPEG_H264_KEYFRAME_INTERVAL_MILLIS = 125
  const val ROOT_FFMPEG_H264_CHROOT = "/data/local/pixel-stack/chroots/pihole"
  const val ROOT_FFMPEG_H264_BINARY = "/usr/bin/ffmpeg"
  const val ROOT_FFMPEG_H264_CAPTURE_SOURCE = "root_surface_capture"
  const val ROOT_FFMPEG_H264_CAPTURE_METHOD = "app_process_screen_capture"
  const val ROOT_CAPTURE_QUALITY_PROFILE = "balanced"
  const val ROOT_CAPTURE_WIDTH = 720
  const val ROOT_CAPTURE_BITRATE = 3_000_000
  const val ROOT_AV1_CAPTURE_MODE = "root_av1"
  const val ROOT_AV1_TRANSPORT = "av1-webcodecs"
  const val AV1_CAPTURE_QUALITY_PROFILE = "root_av1_balanced"
  const val AV1_CAPTURE_BITRATE = 4_000_000
  const val AV1_KEYFRAME_INTERVAL_SECONDS = 1
  const val AV1_KEYFRAME_INTERVAL_MILLIS = AV1_KEYFRAME_INTERVAL_SECONDS * 1_000
  const val AV1_REPEAT_PREVIOUS_FRAME_AFTER_US = 1_000_000L
  const val AV1_MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AV1
  const val AV1_CODEC_STRING = "av01.0.08M.08"
  const val H264_MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
  const val H264_CODEC_STRING = "avc1.42E01E"

  val localStorePackages = listOf(
    ACCRESCENT_PACKAGE,
    "app.grapheneos.apps",
    "com.aurora.store",
    "org.fdroid.fdroid",
    "dev.imranr.obtainium"
  )
}

@Serializable
data class TicketStreamHealth(
  val ok: Boolean,
  val serverVersion: String,
  val sessionState: String = "idle",
  val serverRunning: Boolean,
  val av1HardwareEncoderAvailable: Boolean,
  val h264HardwareEncoderAvailable: Boolean = false,
  val projectionReady: Boolean,
  val capturePermissionPending: Boolean,
  val viviInstalled: Boolean,
  val accrescentInstalled: Boolean,
  val installedStorePackages: List<String>,
  val streamActive: Boolean,
  val clients: Int,
  val inactivityActive: Boolean,
  val inactivityTimeoutMillis: Long,
  val inactivityRemainingMillis: Long,
  val autoStartAllowed: Boolean = true,
  val autoStartBlockedReason: String? = null,
  val serviceReadiness: TicketServiceReadinessHealth = TicketServiceReadinessHealth(),
  val rootCapture: TicketRootCaptureHealth = TicketRootCaptureHealth(),
  val webrtc: TicketWebRtcHealth = TicketWebRtcHealth(),
  val inputGate: TicketInputGateHealth = TicketInputGateHealth(),
  val controlCodeMode: TicketControlCodeModeHealth = TicketControlCodeModeHealth(),
  val controlExitCleanup: TicketControlExitCleanupHealth = TicketControlExitCleanupHealth(),
  val loading: TicketLoadingHealth = TicketLoadingHealth(),
  val page: TicketPageHealth = TicketPageHealth(),
  val notificationLockdown: TicketNotificationLockdownHealth = TicketNotificationLockdownHealth(),
  val brightnessGuard: TicketBrightnessGuardHealth = TicketBrightnessGuardHealth(),
  val visibleFrame: TicketVisibleFrameHealth = TicketVisibleFrameHealth(),
  val ffmpeg: TicketFfmpegHealth = TicketFfmpegHealth(),
  val recovery: TicketRecoveryHealth = TicketRecoveryHealth(),
  val ticketState: TicketControlStateHealth = TicketControlStateHealth(),
  val viviState: TicketViviStateHealth = TicketViviStateHealth(),
  val streamPipeline: TicketStreamPipeline,
  val recentClientTelemetry: List<TicketClientTelemetry> = emptyList(),
  val recentEvents: List<TicketSessionEvent> = emptyList(),
  val message: String
)

@Serializable
data class TicketServiceReadinessHealth(
  val enabled: Boolean = false,
  val state: String = TicketServiceRuntimeState.DISABLED.wireName,
  val detail: String = TicketServiceRuntimeState.DISABLED.defaultDetail,
  val lastEnsureReason: String = "",
  val lastEnsureAgeMillis: Long? = null,
  val lastEnsureSucceeded: Boolean = false,
  val lastEnsureResult: String = "",
  val localServerReachable: Boolean = false,
  val tunnelReady: Boolean = false,
  val componentStatus: String = ""
)

@Serializable
data class TicketStreamPipeline(
  val controlClients: Int,
  val videoClients: Int,
  val captureMode: String = "idle",
  val codec: String = "",
  val transport: String = "",
  val frameEnvelope: String = "tsf2",
  val streamEpoch: Long = 0L,
  val frameSequence: Long = 0L,
  val lastKeyFrameSequence: Long = 0L,
  val qualityProfile: String = TicketScreenConfig.ROOT_CAPTURE_QUALITY_PROFILE,
  val configuredWidth: Int? = null,
  val configuredHeight: Int? = null,
  val configuredBitrate: Int? = null,
  val lastFrameBytes: Int = 0,
  val lastKeyFrameBytes: Int = 0,
  val estimatedSendBitrate: Long = 0L,
  val freshKeyFrameCacheMaxAgeMillis: Long = 0L,
  val encoderRunning: Boolean,
  val streamConfigured: Boolean,
  val encodedFrames: Long,
  val sentFrames: Long,
  val keyFrames: Long,
  val droppedVideoFrames: Long = 0L,
  val slowVideoWrites: Long = 0L,
  val closedSlowVideoClients: Long = 0L,
  val replacedClientSockets: Long = 0L,
  val lastClientGeneration: Long = 0L,
  val lastEncoderStartAgoMillis: Long?,
  val lastConfigSentAgoMillis: Long?,
  val lastFrameEncodedAgoMillis: Long?,
  val lastKeyFrameEncodedAgoMillis: Long?,
  val lastFrameSentAgoMillis: Long?,
  val lastKeyFrameRequestedAgoMillis: Long?,
  val lastVideoClientConnectedAgoMillis: Long?,
  val clients: List<TicketClientConnectionHealth> = emptyList(),
  val secureWindowCaptureBypassActive: Boolean = false,
  val secureWindowCaptureBypassMessage: String = "Secure-window capture bypass is inactive"
)

@Serializable
data class TicketClientConnectionHealth(
  val kind: String,
  val viewerId: String? = null,
  val pageId: String? = null,
  val pageVersion: String? = null,
  val generation: Long = 0L
)

@Serializable
data class TicketRootCaptureHealth(
  val supported: Boolean = false,
  val active: Boolean = false,
  val state: String = "unavailable",
  val message: String = "",
  val encoderName: String? = null,
  val colorFormat: String? = null,
  val width: Int? = null,
  val height: Int? = null,
  val bitrate: Int? = null,
  val frames: Long = 0L,
  val keyFrames: Long = 0L,
  val lastCaptureDurationMillis: Long? = null,
  val lastDecodeDurationMillis: Long? = null,
  val lastConvertDurationMillis: Long? = null,
  val lastEncodeDrainDurationMillis: Long? = null,
  val lastFrameTotalDurationMillis: Long? = null,
  val lastFrameAgoMillis: Long? = null,
  val lastKeyFrameAgoMillis: Long? = null,
  val lastStartAgoMillis: Long? = null,
  val restarts: Long = 0L,
  val restartReasonCounts: Map<String, Long> = emptyMap(),
  val lastRestartReason: String? = null,
  val lastRestartAgoMillis: Long? = null,
  val suppressedRestartRequests: Long = 0L
)

@Serializable
data class TicketFfmpegHealth(
  val available: Boolean = false,
  val active: Boolean = false,
  val version: String? = null,
  val binarySha: String? = null,
  val encoderName: String? = null,
  val chrootPath: String = TicketScreenConfig.ROOT_FFMPEG_H264_CHROOT,
  val binaryPath: String = TicketScreenConfig.ROOT_FFMPEG_H264_BINARY,
  val frameFeederActive: Boolean = false,
  val captureSource: String = TicketScreenConfig.ROOT_FFMPEG_H264_CAPTURE_SOURCE,
  val captureMethod: String = TicketScreenConfig.ROOT_FFMPEG_H264_CAPTURE_METHOD,
  val captureHelperAvailable: Boolean = false,
  val captureHelperState: String = "unavailable",
  val captureHelperMessage: String = "",
  val state: String = "unavailable",
  val message: String = "",
  val width: Int? = null,
  val height: Int? = null,
  val bitrate: Int? = null,
  val fps: Int? = null,
  val frames: Long = 0L,
  val keyFrames: Long = 0L,
  val lastFrameBytes: Int = 0,
  val estimatedBitrate: Long = 0L,
  val lastFrameAgoMillis: Long? = null,
  val lastStartAgoMillis: Long? = null,
  val lastRootFrameReadDurationMillis: Long? = null,
  val lastFfmpegWriteDurationMillis: Long? = null,
  val lastFrameTotalDurationMillis: Long? = null,
  val droppedFrames: Long = 0L,
  val restartCount: Long = 0L,
  val lastExitReason: String? = null,
  val lastExitAgoMillis: Long? = null,
  val stderrTail: String = ""
)

@Serializable
data class TicketWebRtcHealth(
  val enabled: Boolean = false,
  val activePeers: Int = 0,
  val lastOfferAgoMillis: Long? = null,
  val lastFrameForwardedAgoMillis: Long? = null,
  val message: String = "WebRTC is not connected"
)

@Serializable
data class TicketInputGateHealth(
  val tapOnly: Boolean = true,
  val active: Boolean = false,
  val allowed: Boolean = false,
  val reason: String = "no_active_control",
  val lastDecisionAgoMillis: Long? = null,
  val lastCommandReason: String? = null,
  val lastCommandDurationMillis: Long? = null,
  val lastCommandCompletedAgoMillis: Long? = null,
  val lastInputId: String? = null,
  val lastInputKind: String? = null,
  val lastInputAccepted: Boolean? = null,
  val lastInputReason: String? = null,
  val lastInputTotalDurationMillis: Long? = null,
  val lastInputCompletedAgoMillis: Long? = null,
  val duplicateInputResults: Long = 0L,
  val lastDuplicateInputId: String? = null,
  val lastDuplicateInputAgoMillis: Long? = null
)

@Serializable
data class TicketControlCodeModeHealth(
  val active: Boolean = false,
  val entryId: Long = 0L,
  val enteredAgoMillis: Long? = null,
  val transitionGraceActive: Boolean = false,
  val transitionGraceRemainingMillis: Long = 0L
)

@Serializable
data class TicketControlExitCleanupHealth(
  val lastReason: String? = null,
  val lastDetectedState: String? = null,
  val lastCloseAction: String? = null,
  val lastDurationMillis: Long? = null,
  val lastCompletedAgoMillis: Long? = null,
  val lastVerificationResult: String? = null,
  val lastSucceeded: Boolean? = null,
  val lastFreshFrameRequested: Boolean = false
)

@Serializable
data class TicketLoadingHealth(
  val lastPhase: String? = null,
  val lastDurationMillis: Long? = null,
  val lastCompletedAgoMillis: Long? = null,
  val lastOverBudgetPhase: String? = null,
  val lastOverBudgetDurationMillis: Long? = null,
  val lastOverBudgetAgoMillis: Long? = null
)

@Serializable
data class TicketPageHealth(
  val htmlVersion: String = "",
  val cachePolicy: String = "no-store",
  val lastRootHtmlRequestAgoMillis: Long? = null,
  val lastBootstrapRequestAgoMillis: Long? = null,
  val lastCacheCleanupRequestAgoMillis: Long? = null,
  val lastClientPageVersion: String? = null,
  val lastClientPageVersionAgoMillis: Long? = null
)

@Serializable
data class TicketNotificationLockdownHealth(
  val active: Boolean = false,
  val reason: String = "inactive",
  val lastChangedAgoMillis: Long? = null
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
data class TicketVisibleFrameHealth(
  val codec: String = "",
  val lastFrameAgoMillis: Long? = null,
  val lastKeyFrameAgoMillis: Long? = null,
  val message: String = "No visible frame has been sent yet"
)

@Serializable
data class TicketRecoveryHealth(
  val state: String = "idle",
  val currentReason: String? = null,
  val currentMode: String? = null,
  val pendingReason: String? = null,
  val pendingMode: String? = null,
  val lastResult: String = "none",
  val lastStep: String = "idle",
  val startedAgoMillis: Long? = null,
  val completedAgoMillis: Long? = null
)

@Serializable
data class TicketControlStateHealth(
  val state: String = "stopped",
  val stateAgeMillis: Long? = null,
  val lastReason: String = "init",
  val lastOverBudgetState: String? = null,
  val lastOverBudgetDurationMillis: Long? = null,
  val lastOverBudgetReason: String? = null,
  val hardResetCount: Long = 0L,
  val lastHardResetReason: String? = null,
  val lastHardResetAgoMillis: Long? = null
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
data class TicketClientTelemetry(
  val atAgoMillis: Long,
  val message: String
)

@Serializable
data class TicketSessionEvent(
  val atAgoMillis: Long,
  val event: String,
  val detail: String = ""
)

@Serializable
data class TicketInactivityStatus(
  val type: String = "idle",
  val active: Boolean,
  val timeoutMillis: Long,
  val remainingMillis: Long
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
  val sourceHeight: Int
) {
  fun sourceX(encodedX: Int): Int {
    return ((encodedX.coerceIn(0, width) / width.toFloat()) * sourceWidth).roundToInt()
      .coerceIn(0, sourceWidth)
  }

  fun sourceY(encodedY: Int): Int {
    return ((encodedY.coerceIn(0, height) / height.toFloat()) * sourceHeight).roundToInt()
      .coerceIn(0, sourceHeight)
  }
}

object TicketStreamSizing {
  fun rootPng(sourceWidth: Int, sourceHeight: Int): TicketStreamSize {
    return TicketStreamSize(
      width = sourceWidth,
      height = sourceHeight,
      sourceWidth = sourceWidth,
      sourceHeight = sourceHeight
    )
  }

  fun rootH264(sourceWidth: Int, sourceHeight: Int): TicketStreamSize {
    val width = TicketScreenConfig.ROOT_CAPTURE_WIDTH.evenAtLeastTwo()
    val height = ((sourceHeight / sourceWidth.toFloat()) * width).roundToInt().evenAtLeastTwo()
    return TicketStreamSize(
      width = width,
      height = height,
      sourceWidth = sourceWidth,
      sourceHeight = sourceHeight
    )
  }

  fun rootFfmpegH264(sourceWidth: Int, sourceHeight: Int): TicketStreamSize {
    val width = minOf(sourceWidth, TicketScreenConfig.ROOT_FFMPEG_H264_TARGET_WIDTH).evenAtLeastTwo()
    val height = ((sourceHeight / sourceWidth.toFloat()) * width).roundToInt().evenAtLeastTwo()
    return TicketStreamSize(
      width = width,
      height = height,
      sourceWidth = sourceWidth,
      sourceHeight = sourceHeight
    )
  }

  fun fitTo1080Equivalent(sourceWidth: Int, sourceHeight: Int): TicketStreamSize {
    val sourcePixels = sourceWidth.toLong() * sourceHeight.toLong()
    if (sourcePixels <= TicketScreenConfig.MAX_EQUIVALENT_PIXELS) {
      return TicketStreamSize(
        width = sourceWidth.evenAtLeastTwo(),
        height = sourceHeight.evenAtLeastTwo(),
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight
      )
    }
    val scale = sqrt(TicketScreenConfig.MAX_EQUIVALENT_PIXELS.toDouble() / sourcePixels.toDouble())
    return TicketStreamSize(
      width = (sourceWidth * scale).roundToInt().evenAtLeastTwo(),
      height = (sourceHeight * scale).roundToInt().evenAtLeastTwo(),
      sourceWidth = sourceWidth,
      sourceHeight = sourceHeight
    )
  }

  fun av1Clarity(sourceWidth: Int, sourceHeight: Int): TicketStreamSize {
    return rootAv1Balanced(sourceWidth, sourceHeight)
  }

  fun rootAv1Balanced(sourceWidth: Int, sourceHeight: Int): TicketStreamSize {
    val fitted = fitTo1080Equivalent(sourceWidth, sourceHeight)
    return TicketStreamSize(
      width = fitted.width,
      height = fitted.height,
      sourceWidth = sourceWidth,
      sourceHeight = sourceHeight
    )
  }

  private fun Int.evenAtLeastTwo(): Int {
    val atLeastTwo = coerceAtLeast(2)
    return if (atLeastTwo % 2 == 0) atLeastTwo else atLeastTwo - 1
  }
}

internal object TicketInactivityPolicy {
  const val TIMEOUT_MILLIS = 10 * 60 * 1_000L
  const val TICK_MILLIS = 1_000L

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

  fun shouldResetViviToTicket(reason: String): Boolean {
    return false
  }

  fun browserAutoStartAllowedAfterStop(reason: String?): Boolean {
    return reason == null || reason !in browserAutoStartBlockedReasons
  }
}

object TicketAv1Support {
  fun hardwareEncoderName(): String? {
    val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
    return codecs.firstOrNull { codecInfo ->
      codecInfo.isEncoder &&
        !codecInfo.isSoftwareOnlyCompat() &&
        codecInfo.supportedTypes.any { type -> type.equals(TicketScreenConfig.AV1_MIME_TYPE, ignoreCase = true) }
    }?.name
  }

  fun isHardwareEncoderAvailable(): Boolean = hardwareEncoderName() != null

  private fun MediaCodecInfo.isSoftwareOnlyCompat(): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
      isSoftwareOnly
    } else {
      val lower = name.lowercase()
      lower.startsWith("omx.google.") ||
        lower.startsWith("c2.android.") ||
        lower.contains("sw") ||
        lower.contains("software")
    }
  }
}

object TicketH264Support {
  fun hardwareEncoderName(): String? {
    val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
    return codecs.firstOrNull { codecInfo ->
      codecInfo.isEncoder &&
        !codecInfo.isSoftwareOnlyCompat() &&
        codecInfo.supportedTypes.any { type -> type.equals(TicketScreenConfig.H264_MIME_TYPE, ignoreCase = true) }
    }?.name
  }

  fun isHardwareEncoderAvailable(): Boolean = hardwareEncoderName() != null

  private fun MediaCodecInfo.isSoftwareOnlyCompat(): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
      isSoftwareOnly
    } else {
      val lower = name.lowercase()
      lower.startsWith("omx.google.") ||
        lower.startsWith("c2.android.") ||
        lower.contains("sw") ||
        lower.contains("software")
    }
  }
}

object TicketPackageSupport {
  fun isInstalled(context: Context, packageName: String): Boolean {
    return runCatching {
      @Suppress("DEPRECATION")
      context.packageManager.getPackageInfo(packageName, 0)
    }.isSuccess
  }

  fun installedLocalStores(context: Context): List<String> {
    return TicketScreenConfig.localStorePackages.filter { packageName ->
      isInstalled(context, packageName)
    }
  }
}
