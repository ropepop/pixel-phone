package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class TicketStreamServiceSourceTest {
  @Test
  fun rootFfmpegSessionEnablesSecureWindowBypassBeforeLaunchingVivi() {
    val source = ticketStreamServiceSource()
    val functionStart = source.indexOf("private suspend fun startTicketSession()")
    val start = source.indexOf("activeCaptureMode = CAPTURE_MODE_ROOT_PNG")
    val end = source.indexOf("return@withLock TicketSessionResponse(ok = true", start)
    val enable = source.indexOf("enableSecureWindowCaptureBypass()", functionStart)
    val launch = source.indexOf("scheduleRootPngCaptureStart(", start)
    val encoder = source.indexOf("ensureEncoderIfPossible()", start)
    assertTrue(enable in functionStart until end)
    assertTrue(enable < launch)
    assertTrue(enable < encoder)
  }

  @Test
  fun ticketStartPrefersRootPngAndDoesNotOpenProjectionPermission() {
    val source = ticketStreamServiceSource()
    val start = source.indexOf("private suspend fun startTicketSession()")
    val end = source.indexOf("private suspend fun stopTicketSession", start)
    val block = source.substring(start, end)

    assertTrue(block.contains("val ffmpegCaptureAvailable = rootFfmpegH264CaptureEngine.probe()"))
    assertTrue(block.contains("val rootAv1CaptureAvailable = rootAv1CaptureEngine.probe()"))
    assertTrue(block.contains("val rootPngCaptureAvailable = rootPngCaptureEngine.probe()"))
    assertTrue(block.contains("val rootCaptureAvailable = rootCaptureEngine.probe()"))
    assertTrue(block.contains("val av1HardwareAvailable = TicketAv1Support.isHardwareEncoderAvailable()"))
    assertTrue(block.contains("if (!rootPngCaptureAvailable)"))
    assertTrue(block.contains("Root lossless capture is unavailable; stream was not started"))
    assertTrue(block.contains("activeCaptureMode = CAPTURE_MODE_ROOT_PNG"))
    assertTrue(block.contains("updateTicketSessionState(TICKET_SESSION_STARTING, \"session_start_root_png_prepare\")"))
    assertTrue(block.contains("scheduleRootPngCaptureStart(\"session_start_root_png_capture\""))
    assertTrue(block.contains("state = \"capture_unavailable\""))
    assertTrue(block.contains("pendingStartAfterProjection = false"))
    assertTrue(block.contains("stopProjection()"))
    assertTrue(!block.contains("requestCapturePermission("))
    assertTrue(!block.contains("capture_permission_required"))
    assertTrue(!block.contains("activeCaptureMode = CAPTURE_MODE_ROOT_H264"))
    assertTrue(!block.contains("activeCaptureMode = CAPTURE_MODE_ROOT_AV1"))
  }

  @Test
  fun rootFfmpegStartupLaunchesViviThenStartsCaptureBeforeLongRecovery() {
    val source = ticketStreamServiceSource()
    val start = source.indexOf("private fun scheduleRootPngCaptureStart")
    val end = source.indexOf("private fun scheduleTicketRecovery", start)
    val block = source.substring(start, end)
    val launch = block.indexOf("launchViviUnlessFastTicketDetail(reason)")
    val prepare = block.indexOf("prepareViviForRootPngCapture(reason)")
    val encoder = block.indexOf("ensureEncoderIfPossible()")

    assertTrue(source.contains("private suspend fun prepareViviForRootPngCapture"))
    assertTrue(source.contains("restartPolicy = TicketAutopilotRestartPolicy.SOFT_RECOVERY"))
    assertTrue(source.contains("maxSteps = ROOT_PNG_START_AUTOPILOT_MAX_STEPS"))
    assertTrue(source.contains("timeoutMillis = ROOT_PNG_START_AUTOPILOT_TIMEOUT_MILLIS"))
    assertTrue(source.contains("ensureRootFfmpegH264CaptureIfPossible()"))
    assertTrue(source.contains("private val rootFfmpegH264CaptureEngine = TicketRootFfmpegH264CaptureEngine("))
    assertTrue(launch >= 0)
    assertTrue(prepare >= 0)
    assertTrue(encoder >= 0)
    assertTrue(launch < encoder)
    assertTrue(encoder < prepare)
  }

  @Test
  fun autopilotTapsTicketsTabAfterSoftLaunchAndBeforeUnknownBackFallback() {
    val source = ticketAutopilotSource()
    val outsideStart = source.indexOf("TicketViviRecoveryState.OUTSIDE_VIVI ->")
    val outsideEnd = source.indexOf("TicketViviRecoveryState.BLANK ->", outsideStart)
    val outsideBlock = source.substring(outsideStart, outsideEnd)
    val unknownStart = source.indexOf("TicketViviRecoveryState.SETTINGS_OR_PROFILE,")
    val unknownEnd = source.indexOf("TicketViviRecoveryState.OUTSIDE_VIVI ->", unknownStart)
    val unknownBlock = source.substring(unknownStart, unknownEnd)

    assertTrue(outsideBlock.contains("delay(SOFT_LAUNCH_TICKETS_TAB_SETTLE_MILLIS)"))
    assertTrue(outsideBlock.contains("if (openTicketsTab(observation.xml))"))
    assertTrue(outsideBlock.contains("openTimeTicketTab(\"\")"))
    assertTrue(outsideBlock.contains("tapFirstTicketCardFallback()"))
    assertTrue(source.contains("TIME_TICKET_TAB_X_FRACTION"))
    assertTrue(source.contains("TICKET_TYPE_TAB_Y_FRACTION"))
    assertTrue(unknownBlock.indexOf("openTicketsTab(observation.xml)") < unknownBlock.indexOf("goBack(\"recover_from_${'$'}{state.name.lowercase()}\""))
  }

  @Test
  fun screenSharingPermissionLauncherUsesBoundedRootAssist() {
    val source = ticketStreamServiceSource()
    val start = source.indexOf("private suspend fun requestCapturePermission(force: Boolean = false)")
    val end = source.indexOf("private fun handleProjectionResult", start)
    val block = source.substring(start, end)

    assertTrue(source.contains("private const val AUTO_MEDIA_PROJECTION_PERMISSION_LAUNCH_ENABLED = true"))
    assertTrue(block.contains("if (!AUTO_MEDIA_PROJECTION_PERMISSION_LAUNCH_ENABLED)"))
    assertTrue(block.contains("ticket_capture_permission_auto_launch_blocked"))
    assertTrue(block.contains("scheduleCapturePermissionRootAssist(\"root_launch\")"))
    assertTrue(block.contains("scheduleCapturePermissionRootAssist(\"activity_launch\")"))
    assertTrue(source.contains("private fun capturePermissionAssistScript()"))
    assertTrue(source.contains("uiautomator dump"))
    assertTrue(source.contains("ticket_capture_permission_assist"))
  }

  @Test
  fun browserSelfHealRequiresRootFfmpegInsteadOfProjectionFallback() {
    val source = ticketStreamServiceSource()
    val start = source.indexOf("async function ensureStreaming")
    val end = source.indexOf("function startSelfHealTimer", start)
    val block = source.substring(start, end)
    val refreshStart = source.indexOf("async function refreshHealth()")
    val refreshEnd = source.indexOf("async function start()", refreshStart)
    val refreshBlock = source.substring(refreshStart, refreshEnd)

    assertTrue(block.contains("const ffmpegSupported = Boolean(health.ffmpeg && health.ffmpeg.available);"))
    assertTrue(block.contains("if (!health.viviInstalled || !ffmpegSupported) return;"))
    assertTrue(refreshBlock.contains("!health.viviInstalled || !(health.ffmpeg && health.ffmpeg.available)"))
    assertTrue(!block.contains("const rootPngSupported = Boolean(health.rootCapture && health.rootCapture.supported);"))
    assertTrue(!refreshBlock.contains("!health.viviInstalled || !health.av1HardwareEncoderAvailable"))
  }

  @Test
  fun serviceDestroyRestoresSecureWindowBypassBeforeClosingRootWorker() {
    val source = ticketStreamServiceSource()
    val start = source.indexOf("override fun onDestroy()")
    val end = source.indexOf("super.onDestroy()", start)
    val block = source.substring(start, end)

    assertTrue(block.contains("disableSecureWindowCaptureBypass()"))
    assertTrue(block.indexOf("disableSecureWindowCaptureBypass()") < block.indexOf("rootExecutor.close()"))
  }

  @Test
  fun healthReportsSecureWindowBypassState() {
    val source = ticketStreamServiceSource()

    assertTrue(source.contains("secureWindowCaptureBypassActive = secureWindowCaptureBypassActive"))
    assertTrue(source.contains("secureWindowCaptureBypassMessage = secureWindowCaptureBypassMessage"))
  }

  @Test
  fun remoteInputIsMarkedAsNonTouchBeforeRootInputCommands() {
    val source = ticketStreamServiceSource()
    val autopilot = ticketAutopilotSource()

    assertTrue(source.contains("private suspend fun runNonTouchInput(command: String, reason: String): RootResult"))
    assertTrue(source.contains("private suspend fun runFastNonTouchInput(command: String, reason: String): RootResult"))
    assertTrue(source.contains("PhoneAutomationServiceBridge.markNonTouchInput(\"ticket:${'$'}reason\")"))
    assertTrue(source.contains("runFastNonTouchInput(\"input tap ${'$'}x ${'$'}y\", \"remote_ticket_tap\")"))
    assertTrue(autopilot.contains("runInput(\"input keyevent KEYCODE_BACK\", \"ticket_autopilot:${'$'}reason\")"))
  }

  @Test
  fun remoteTapsRequireFreshViviForegroundBeforeFastSafetyPath() {
    val source = ticketStreamServiceSource()
    val start = source.indexOf("private suspend fun tap(inputId: String?, encodedX: Int, encodedY: Int)")
    val end = source.indexOf("private fun schedulePostRemoteTapForegroundCheck()", start)
    val block = source.substring(start, end)

    assertTrue(source.contains("private suspend fun requireViviForegroundForRemoteTap()"))
    assertTrue(source.contains("foregroundViolationReason(allowStartupSystemUi = false)"))
    assertTrue(block.contains("requireViviForegroundForRemoteTap()"))
    assertTrue(block.indexOf("requireViviForegroundForRemoteTap()") < block.indexOf("isProtectedRemoteTap"))
    assertTrue(block.contains("isLikelyControlCodeCoordinateTap(size, encodedX, encodedY)"))
    assertTrue(block.contains("markControlCodeTransition(\"control_code_coordinate_tap\")"))
    assertTrue(block.contains("requireFastViviSandboxForRemoteTap(size, encodedX, encodedY)"))
    assertTrue(block.contains("runFastNonTouchInput(\"input tap ${'$'}x ${'$'}y\", \"remote_ticket_tap\")"))
    assertTrue(!block.contains("dumpViviHierarchy()"))
    assertTrue(!block.contains("foregroundViolationReason("))
  }

  @Test
  fun postTapForegroundChecksAreDebounced() {
    val source = ticketStreamServiceSource()
    val start = source.indexOf("private fun schedulePostRemoteTapForegroundCheck()")
    val end = source.indexOf("private suspend fun runNonTouchInput", start)
    val block = source.substring(start, end)

    assertTrue(block.contains("postRemoteTapForegroundCheckJob?.cancel()"))
    assertTrue(block.contains("cacheForegroundViolation(violation)"))
    assertTrue(block.contains("ticket_post_tap_foreground_violation"))
    assertTrue(!block.contains("stopTicketSession(violation)"))
  }

  @Test
  fun duplicateInputIdsReturnCachedResultsWithoutDuplicateCommands() {
    val source = ticketStreamServiceSource()
    val config = ticketScreenConfigSource()

    assertTrue(source.contains("sendCachedInputResult(inputId, \"tap\")"))
    assertTrue(source.contains("sendCachedInputResult(inputId, \"key\")"))
    assertTrue(source.contains("rememberInputResult(inputId, message)"))
    assertTrue(source.contains("recentInputResultMessages[id]"))
    assertTrue(source.contains("input_result_duplicate"))
    assertTrue(source.contains("RECENT_INPUT_RESULT_CACHE_SIZE"))
    assertTrue(config.contains("duplicateInputResults: Long = 0L"))
    assertTrue(config.contains("lastDuplicateInputId: String? = null"))
  }

  @Test
  fun foregroundGuardIsNonDestructiveDuringActiveSessions() {
    val source = ticketStreamServiceSource()
    val start = source.indexOf("private fun startForegroundGuard()")
    val end = source.indexOf("private fun cancelForegroundGuard()", start)
    val block = source.substring(start, end)
    val inputStart = source.indexOf("private suspend fun canForwardRemoteInput()")
    val inputEnd = source.indexOf("private suspend fun requireFastViviSandboxForRemoteTap", inputStart)
    val inputBlock = source.substring(inputStart, inputEnd)
    val violationStart = source.indexOf("private fun handleForegroundViolation")
    val violationEnd = source.indexOf("private fun resetForegroundViolationConfirmation", violationStart)
    val violationBlock = source.substring(violationStart, violationEnd)

    assertTrue(block.contains("cacheForegroundViolation(violation)"))
    assertTrue(block.contains("handleForegroundViolation(violation)"))
    assertTrue(violationBlock.contains("ticket_foreground_guard_violation"))
    assertTrue(!block.contains("ticket_foreground_guard_stop"))
    assertTrue(!block.contains("stopTicketSession(violation)"))
    assertTrue(!violationBlock.contains("stopTicketSession("))
    assertTrue(inputBlock.contains("ticket_remote_input_blocked reason=${'$'}violation"))
    assertTrue(!inputBlock.contains("stopTicketSession(violation)"))
  }

  @Test
  fun remoteKeyboardInputRequiresControlCodePopup() {
    val source = ticketStreamServiceSource()

    assertTrue(source.contains("private suspend fun handleRemoteKey(inputId: String?, key: String)"))
    assertTrue(source.contains("TicketRemoteKeyPolicy.commandFor(key)"))
    assertTrue(source.contains("requireControlCodePopupForRemoteKey()"))
    assertTrue(source.contains("TicketViviPageEnforcer.isControlCodePopup(dump.stdout)"))
  }

  @Test
  fun controlCodeModeIsReportedAfterPopupDetectionAndResetWhenGone() {
    val source = ticketStreamServiceSource()
    val config = ticketScreenConfigSource()

    assertTrue(config.contains("data class TicketControlCodeModeHealth"))
    assertTrue(source.contains("controlCodeMode = TicketControlCodeModeHealth("))
    assertTrue(source.contains("private fun markControlCodeModeEntered(reason: String)"))
    assertTrue(source.contains("controlCodeModeEntryId += 1"))
    assertTrue(source.contains("refreshControlCodeModeAfterRemoteTap()"))
    assertTrue(source.contains("markControlCodeModeEntered(\"remote_tap_popup_detected\")"))
    assertTrue(source.contains("resetControlCodeMode(\"remote_tap_popup_gone\")"))
    assertTrue(source.contains("resetControlCodeMode(\"session_stop_${'$'}reason\", broadcast = false)"))
  }

  @Test
  fun loadingTelemetryIsReportedFromBrowserClientLogs() {
    val source = ticketStreamServiceSource()
    val config = ticketScreenConfigSource()

    assertTrue(config.contains("data class TicketLoadingHealth"))
    assertTrue(config.contains("val loading: TicketLoadingHealth = TicketLoadingHealth()"))
    assertTrue(source.contains("updateLoadingTelemetryFromClientLog(message)"))
    assertTrue(source.contains("event != \"loading_finished\" && event != \"loading_over_2s\""))
    assertTrue(source.contains("lastLoadingOverBudgetPhase = phase"))
    assertTrue(source.contains("loading = TicketLoadingHealth("))
    assertTrue(source.contains("recordTicketEvent(\"loading_over_2s\""))
  }

  @Test
  fun ticketServiceReadinessIsReportedInHealth() {
    val source = ticketStreamServiceSource()
    val config = ticketScreenConfigSource()

    assertTrue(config.contains("data class TicketServiceReadinessHealth"))
    assertTrue(config.contains("val serviceReadiness: TicketServiceReadinessHealth = TicketServiceReadinessHealth()"))
    assertTrue(source.contains("serviceReadiness = serviceReadinessSnapshot()"))
    assertTrue(source.contains("TicketServicePreferencesStore(this).load()"))
    assertTrue(source.contains("localServerReachable = running.get() || snapshot.localServerReachable"))
  }

  @Test
  fun rootFfmpegH264ProfileReportsCodecAndFreshFrameEnvelope() {
    val source = ticketStreamServiceSource()
    val config = ticketScreenConfigSource()

    assertTrue(config.contains("const val ROOT_FFMPEG_H264_CAPTURE_MODE = \"root_ffmpeg_h264\""))
    assertTrue(config.contains("const val ROOT_FFMPEG_H264_TRANSPORT = \"ffmpeg-h264-annexb\""))
    assertTrue(config.contains("const val ROOT_FFMPEG_H264_QUALITY_PROFILE = \"ffmpeg_h264_clarity\""))
    assertTrue(config.contains("const val ROOT_FFMPEG_H264_CODEC_STRING = \"avc1.42C028\""))
    assertTrue(config.contains("const val ROOT_FFMPEG_H264_FPS = 8"))
    assertTrue(config.contains("const val ROOT_FFMPEG_H264_BITRATE = 8_000_000"))
    assertTrue(config.contains("const val ROOT_FFMPEG_H264_KEYFRAME_INTERVAL_MILLIS = 125"))
    assertTrue(config.contains("const val ROOT_FFMPEG_H264_CAPTURE_SOURCE = \"root_surface_capture\""))
    assertTrue(config.contains("const val ROOT_FFMPEG_H264_CAPTURE_METHOD = \"app_process_screen_capture\""))
    assertTrue(config.contains("val captureHelperAvailable: Boolean = false"))
    assertTrue(config.contains("fun rootFfmpegH264(sourceWidth: Int, sourceHeight: Int)"))
    assertTrue(rootFfmpegH264CaptureEngineSource().contains("TicketRootSurfaceCaptureMain"))
    assertTrue(rootFfmpegH264CaptureEngineSource().contains("probeCaptureHelper(sourceWidth, sourceHeight)"))
    assertTrue(source.contains("private val rootFfmpegH264CaptureEngine = TicketRootFfmpegH264CaptureEngine("))
    assertTrue(source.contains("private fun ensureRootFfmpegH264CaptureIfPossible()"))
    assertTrue(source.contains("private fun handleRootFfmpegH264CaptureFrame(frame: TicketRootCaptureFrame)"))
    assertTrue(source.contains("private const val FRAME_ENVELOPE_VERSION = \"tsf2\""))
    assertTrue(source.contains("private const val FRAME_ENVELOPE_MAGIC = 0x54534632"))
    assertTrue(source.contains("private const val FRAME_ENVELOPE_HEADER_BYTES = 29"))
    assertTrue(source.contains("\"codec\":\"${'$'}codec\""))
    assertTrue(source.contains("\"transport\":\"${'$'}transport\""))
    assertTrue(source.contains("\"captureMode\":\"${'$'}activeCaptureMode\""))
    assertTrue(source.contains("\"captureSource\":${'$'}{json.encodeToString"))
    assertTrue(source.contains("\"captureMethod\":${'$'}{json.encodeToString"))
    assertTrue(source.contains("\"frameEnvelope\":\"${'$'}FRAME_ENVELOPE_VERSION\""))
    assertTrue(source.contains("\"streamEpoch\":${'$'}streamEpoch"))
    assertTrue(source.contains("\"qualityProfile\":\"${'$'}qualityProfile\""))
    assertTrue(source.contains("\"bitrate\":${'$'}bitrate"))
    assertTrue(source.contains("\"keyFrameIntervalMillis\":${'$'}keyFrameIntervalMillis"))
    assertTrue(source.contains("\"repeatPreviousFrameAfterUs\":${'$'}{TicketScreenConfig.AV1_REPEAT_PREVIOUS_FRAME_AFTER_US}"))
    assertTrue(rootFfmpegH264CaptureEngineSource().contains("-x264-params keyint=1:min-keyint=1:scenecut=0:repeat-headers=1:bframes=0"))
    assertTrue(source.contains("buffer.putInt(FRAME_ENVELOPE_MAGIC)"))
    assertTrue(source.contains("buffer.putLong(epoch)"))
    assertTrue(source.contains("buffer.putLong(sequence)"))
  }

  @Test
  fun brightnessGuardDimsAfterSessionAndRestoresOnlyOnServiceOff() {
    val source = ticketStreamServiceSource()
    val config = ticketScreenConfigSource()
    val stopStart = source.indexOf("private suspend fun stopTicketSession(reason: String)")
    assertTrue(stopStart >= 0)
    val stopEnd = source.indexOf("private fun ticketSessionOpen()", stopStart)
    val stopBlock = source.substring(stopStart, stopEnd)
    val destroyStart = source.indexOf("override fun onDestroy()")
    assertTrue(destroyStart >= 0)
    val destroyEnd = source.indexOf("PhoneAutomationServiceBridge.setBlackoutOverlaySuppressed(false)", destroyStart)
    val destroyBlock = source.substring(destroyStart, destroyEnd)

    assertTrue(config.contains("data class TicketBrightnessGuardHealth"))
    assertTrue(config.contains("val brightnessGuard: TicketBrightnessGuardHealth = TicketBrightnessGuardHealth()"))
    assertTrue(source.contains("private const val TICKET_SAFE_DIM_PERCENT = 1"))
    assertTrue(source.contains("BRIGHTNESS_GUARD_DELAYS_MILLIS = longArrayOf(0L"))
    assertTrue(source.contains("private fun scheduleTicketBrightnessGuard(reason: String)"))
    assertTrue(source.contains("ScreenBrightnessControl.buildSetPercentScript(TICKET_SAFE_DIM_PERCENT)"))
    assertTrue(source.contains("brightnessGuard = TicketBrightnessGuardHealth("))
    assertTrue(stopBlock.contains("stopProjection()"))
    assertTrue(stopBlock.contains("recordTicketEvent(\"root_capture_ready_waiting\", \"session_stop_${'$'}reason\")"))
    assertTrue(stopBlock.contains("scheduleTicketBrightnessGuard(\"session_stopped:${'$'}reason\")"))
    assertTrue(!stopBlock.contains("restoreTicketBrightness("))
    assertTrue(!stopBlock.contains("ticketBrightnessState = null"))
    assertTrue(destroyBlock.contains("TicketServicePreferencesStore(this).load().enabled"))
    assertTrue(destroyBlock.contains("enforceTicketSafeBrightness(\"service_destroyed_service_enabled\")"))
    assertTrue(destroyBlock.contains("restoreTicketBrightness(\"service_destroyed_service_off\")"))
  }

  @Test
  fun projectionPrewarmIsNotUsedForNormalRootFfmpegStop() {
    val source = ticketStreamServiceSource()
    val startBlock = source.substring(
      source.indexOf("private suspend fun startTicketSession()"),
      source.indexOf("private suspend fun stopTicketSession")
    )
    val stopBlock = source.substring(
      source.indexOf("private suspend fun stopTicketSession(reason: String)"),
      source.indexOf("private fun ticketSessionOpen()")
    )

    assertTrue(source.contains("private fun scheduleProjectionPrewarm(reason: String)"))
    assertTrue(source.contains("@Volatile private var mediaProjectionConsumed: Boolean = false"))
    assertTrue(source.contains("@Volatile private var projectionPrewarmPending: Boolean = false"))
    assertTrue(startBlock.contains("activeCaptureMode = CAPTURE_MODE_ROOT_PNG"))
    assertTrue(startBlock.contains("scheduleRootPngCaptureStart(\"session_start_root_png_capture\""))
    assertTrue(!startBlock.contains("requestCapturePermission("))
    assertTrue(!startBlock.contains("if (mediaProjection != null && !mediaProjectionConsumed)"))
    assertTrue(startBlock.contains("stopProjection()"))
    assertTrue(source.contains("mediaProjectionConsumed = true"))
    assertTrue(source.contains("mediaProjectionConsumed = false"))
    assertTrue(stopBlock.contains("stopEncoder()"))
    assertTrue(stopBlock.contains("stopProjection()"))
    assertTrue(stopBlock.contains("recordTicketEvent(\"root_capture_ready_waiting\", \"session_stop_${'$'}reason\")"))
    assertTrue(!stopBlock.contains("scheduleProjectionPrewarm(\"session_stop_${'$'}reason\")"))
    assertTrue(source.contains("projectionReady = mediaProjection != null && !mediaProjectionConsumed"))
    assertTrue(source.contains("capturePermissionPending = pendingStartAfterProjection || projectionPrewarmPending"))
  }

  @Test
  fun keyframeCacheIsFreshAndEpochBound() {
    val source = ticketStreamServiceSource()

    assertTrue(source.contains("private const val ROOT_KEYFRAME_CACHE_MAX_AGE_MILLIS = 1_500L"))
    assertTrue(source.contains("latestKeyFrameStreamEpoch == streamEpoch"))
    assertTrue(source.contains("latestKeyFrameSequence = sequence"))
    assertTrue(source.contains("resetFrameEpoch(\"root_capture_restart_${'$'}reason\", active = true)"))
    assertTrue(source.contains("restartRootCaptureForNewEpoch(\"keyframe_requested:${'$'}reason\")"))
    assertTrue(source.contains("private const val ROOT_URGENT_KEYFRAME_RESTART_COOLDOWN_MILLIS = 3_000L"))
    assertTrue(source.contains("private fun isUrgentFreshKeyFrameReason(reason: String): Boolean"))
    assertTrue(source.contains("reason == \"viewer_join\""))
    assertTrue(source.contains("reason == \"control_join\""))
    assertTrue(source.contains("reason == \"first_frame_retry\""))
    assertTrue(!source.contains("reason == \"stale_frame_retry\""))
    assertTrue(!source.contains("ROOT_KEYFRAME_CACHE_MAX_AGE_MILLIS = 180_000L"))
  }

  @Test
  fun controlCodeButtonTapGetsTransitionGraceBeforeSoftRecovery() {
    val source = ticketStreamServiceSource()
    val config = ticketScreenConfigSource()
    val start = source.indexOf("private fun scheduleControlCodeSoftCheck")
    assertTrue(start >= 0)
    val end = source.indexOf("private fun ignoreProtectedRemoteInput", start)
    val block = source.substring(start, end)

    assertTrue(source.contains("private const val CONTROL_CODE_TRANSITION_GRACE_MILLIS = 3_000L"))
    assertTrue(source.contains("controlCodeTransitionGraceUntilMillis"))
    assertTrue(source.contains("markControlCodeTransition(\"control_code_button_tap\")"))
    assertTrue(source.contains("markControlCodeTransition(\"control_code_coordinate_tap\")"))
    assertTrue(source.contains("private fun isLikelyControlCodeCoordinateTap"))
    assertTrue(block.contains("transitionDelayMillis"))
    assertTrue(block.contains("CONTROL_CODE_SOFT_CHECK_DELAY_MILLIS + transitionDelayMillis"))
    assertTrue(config.contains("transitionGraceActive: Boolean = false"))
    assertTrue(config.contains("transitionGraceRemainingMillis: Long = 0L"))
  }

  @Test
  fun resetTicketCommandSchedulesUnifiedRecovery() {
    val source = ticketStreamServiceSource()
    val start = source.indexOf("\"reset_ticket\" -> {")
    assertTrue(start >= 0)
    val end = source.indexOf("\"long_press\"", start)
    val block = source.substring(start, end)

    assertTrue(block.contains("scheduleTicketRecovery("))
    assertTrue(block.contains("remote_reset_ticket"))
    assertTrue(block.contains("TicketRecoveryMode.FRESH_RESET"))
    assertTrue(block.contains("sendStatus(client)"))
  }

  @Test
  fun controlReleaseAndExpiryUseSoftExitInsteadOfFreshReset() {
    val source = ticketStreamServiceSource()
    val resetStart = source.indexOf("\"reset_ticket\" -> {")
    assertTrue(resetStart >= 0)
    val resetEnd = source.indexOf("\"long_press\"", resetStart)
    val resetBlock = source.substring(resetStart, resetEnd)
    val exitStart = source.indexOf("private fun isSoftControlExitReason")
    assertTrue(exitStart >= 0)
    val exitEnd = source.indexOf("private fun scheduleControlExitSoftSettle", exitStart)
    val exitBlock = source.substring(exitStart, exitEnd)
    val settleStart = source.indexOf("private fun scheduleControlExitSoftSettle")
    assertTrue(settleStart >= 0)
    val settleEnd = source.indexOf("private fun scheduleControlCodeSoftCheck", settleStart)
    val settleBlock = source.substring(settleStart, settleEnd)

    assertTrue(resetBlock.contains("if (isSoftControlExitReason(reason))"))
    assertTrue(resetBlock.contains("scheduleControlExitSoftSettle(reason)"))
    assertTrue(resetBlock.indexOf("scheduleControlExitSoftSettle(reason)") < resetBlock.indexOf("TicketRecoveryMode.FRESH_RESET"))
    assertTrue(exitBlock.contains("reason == \"control_released\""))
    assertTrue(exitBlock.contains("reason == \"control_expired\""))
    assertTrue(exitBlock.contains("reason.startsWith(\"control_code_closed_\")"))
    assertTrue(settleBlock.contains("TICKET_SESSION_CONTROL_EXIT"))
    assertTrue(settleBlock.contains("control_exit_soft_settle"))
    assertTrue(settleBlock.contains("transitionInFlight"))
    assertTrue(settleBlock.contains("postRemoteTapForegroundCheckJob?.cancel()"))
    assertTrue(settleBlock.contains("if (!transitionInFlight)"))
    assertTrue(!settleBlock.contains("TicketRecoveryMode.FRESH_RESET"))
    assertTrue(!settleBlock.contains("forceFreshLaunch = true"))
  }

  @Test
  fun codecFailuresRestartActiveStream() {
    val source = ticketStreamServiceSource()
    val restartStart = source.indexOf("private fun restartActiveStream(reason: String)")
    assertTrue(restartStart >= 0)
    val restartEnd = source.indexOf("private fun stopEncoder()", restartStart)
    val restartBlock = source.substring(restartStart, restartEnd)
    val drainStart = source.indexOf("private suspend fun drainEncoder")
    val drainEnd = source.indexOf("private fun restartActiveStream", drainStart)
    val drainBlock = source.substring(drainStart, drainEnd)
    val commandStart = source.indexOf("\"restart_stream\" -> {")
    val commandEnd = source.indexOf("\"reset_ticket\"", commandStart)
    val commandBlock = source.substring(commandStart, commandEnd)

    assertTrue(drainBlock.contains("Log.w(TAG, \"encoder_failed\", error)"))
    assertTrue(drainBlock.contains("restartActiveStream(\"encoder_failed\")"))
    assertTrue(commandBlock.contains("restartActiveStream(\"browser_restart_stream\")"))
    assertTrue(restartBlock.contains("if (activeCaptureMode == CAPTURE_MODE_ROOT_FFMPEG_H264)"))
    assertTrue(restartBlock.contains("rootFfmpegH264CaptureEngine.requestKeyFrame(\"restart_stream:${'$'}reason\")"))
    assertTrue(restartBlock.contains("if (activeCaptureMode == CAPTURE_MODE_ROOT_H264)"))
    assertTrue(restartBlock.contains("restartRootCaptureForNewEpoch(reason)"))
    assertTrue(restartBlock.contains("stopEncoder()"))
    assertTrue(restartBlock.contains("ensureEncoderIfPossible()"))
    assertTrue(restartBlock.contains("requestKeyFrame()"))
  }

  @Test
  fun ticketRecoveryUsesSingleFlightSoftRecoveryBeforeFreshRelaunch() {
    val source = ticketStreamServiceSource()
    val coordinator = ticketRecoveryCoordinatorSource()
    val autopilot = ticketAutopilotSource()

    assertTrue(source.contains("private lateinit var ticketRecoveryCoordinator: TicketRecoveryCoordinator"))
    assertTrue(source.contains("private fun scheduleTicketRecovery("))
    assertTrue(coordinator.contains("active?.isActive == true"))
    assertTrue(coordinator.contains("pendingReason = reason"))
    assertTrue(coordinator.contains("pendingMode = mode"))
    assertTrue(coordinator.contains("ticket_recovery_coalesced"))
    assertTrue(coordinator.contains("TicketAutopilot("))
    assertTrue(coordinator.contains("forceFreshLaunch = fresh"))
    assertTrue(coordinator.contains("TicketAutopilotRestartPolicy.SOFT_RECOVERY"))
    assertTrue(coordinator.contains("TicketAutopilotRestartPolicy.FRESH_RECOVERY"))
    assertTrue(coordinator.contains("if (fresh)"))
    assertTrue(autopilot.contains("am force-stop ${'$'}{TicketScreenConfig.VIVI_PACKAGE}"))
    assertTrue(autopilot.contains("context.startActivity("))
    assertTrue(autopilot.contains("ticket_autopilot_stuck_soft_relaunch"))
  }

  @Test
  fun sessionStopOnlySchedulesFreshRecoveryForPolicyReasons() {
    val source = ticketStreamServiceSource()
    val start = source.indexOf("private suspend fun stopTicketSession(reason: String)")
    assertTrue(start >= 0)
    val end = source.indexOf("private fun ticketSessionOpen()", start)
    val block = source.substring(start, end)

    assertTrue(block.contains("val response = sessionMutex.withLock"))
    assertTrue(block.contains("TicketSessionStopPolicy.shouldResetViviToTicket(reason)"))
    assertTrue(block.contains("scheduleTicketRecovery(reason, TicketRecoveryMode.FRESH_RESET)"))
    assertTrue(block.indexOf("}") < block.indexOf("scheduleTicketRecovery(reason, TicketRecoveryMode.FRESH_RESET)"))
    assertTrue(!block.contains("resetViviToTicket("))
  }

  @Test
  fun browserStopIsExplicitOnlyAndDetachParksCaptureForNextViewer() {
    val source = ticketStreamServiceSource()
    val stopRequestStart = source.indexOf("private suspend fun handleBrowserStopRequest")
    assertTrue(stopRequestStart >= 0)
    val stopRequestEnd = source.indexOf("private suspend fun noteClientDetached", stopRequestStart)
    val stopBlock = source.substring(stopRequestStart, stopRequestEnd)
    val commandStart = source.indexOf("\"stop\" -> {")
    val commandEnd = source.indexOf("\"activity\"", commandStart)
    val commandBlock = source.substring(commandStart, commandEnd)
    val disconnectStart = source.indexOf("private fun scheduleClientDisconnectGrace()")
    val disconnectEnd = source.indexOf("private fun markViewerInput", disconnectStart)
    val disconnectBlock = source.substring(disconnectStart, disconnectEnd)

    assertTrue(stopBlock.contains("explicit"))
    assertTrue(stopBlock.contains("TicketSessionStopPolicy.BROWSER_EXPLICIT_STOP"))
    assertTrue(stopBlock.contains("noteClientDetached(\"http_stop_without_explicit\")"))
    assertTrue(commandBlock.contains("explicit"))
    assertTrue(commandBlock.contains("browser_ws_stop_without_explicit"))
    assertTrue(disconnectBlock.contains("noteClientDetached(\"browser_left_ticket_screen\")"))
    assertTrue(!disconnectBlock.contains("TicketRecoveryMode.ACTIVE_SOFT"))
    assertTrue(!disconnectBlock.contains("stopTicketSession(\"browser_left_ticket_screen\")"))
    assertTrue(source.contains("recordTicketEvent(\"root_capture_ready_waiting\", \"client_detached_${'$'}reason\")"))
    val detachStart = source.indexOf("private suspend fun noteClientDetached")
    val detachEnd = source.indexOf("private suspend fun stopTicketSession", detachStart)
    val detachBlock = source.substring(detachStart, detachEnd)
    assertTrue(detachBlock.contains("releaseTicketScreenAwake()"))
  }

  @Test
  fun foregroundAndControlCodeUseConfirmedSoftRecovery() {
    val source = ticketStreamServiceSource()
    val foregroundStart = source.indexOf("private fun handleForegroundViolation")
    assertTrue(foregroundStart >= 0)
    val foregroundEnd = source.indexOf("private fun resetForegroundViolationConfirmation", foregroundStart)
    val foregroundBlock = source.substring(foregroundStart, foregroundEnd)
    val controlStart = source.indexOf("private fun scheduleControlCodeSoftCheck")
    assertTrue(controlStart >= 0)
    val controlEnd = source.indexOf("private fun ignoreProtectedRemoteInput", controlStart)
    val controlBlock = source.substring(controlStart, controlEnd)

    assertTrue(foregroundBlock.contains("FOREGROUND_RECOVERY_CONFIRMATION_COUNT"))
    assertTrue(foregroundBlock.contains("FOREGROUND_RECOVERY_COOLDOWN_MILLIS"))
    assertTrue(foregroundBlock.contains("TicketRecoveryMode.ACTIVE_SOFT"))
    assertTrue(!foregroundBlock.contains("stopTicketSession("))
    assertTrue(controlBlock.contains("control_code_soft_check"))
    assertTrue(controlBlock.contains("ticketAutopilot.observeFastState(\"control_code_soft_check:${'$'}reason\")"))
    assertTrue(controlBlock.contains("TicketViviRecoveryState.CONTROL_CODE_POPUP"))
    assertTrue(controlBlock.contains("TicketViviRecoveryState.CONTROL_CODE_RESULT"))
    assertTrue(controlBlock.contains("isSoftControlExitReason(reason)"))
    assertTrue(controlBlock.contains("trySoftCloseControlCodeSurface(reason)"))
    assertTrue(controlBlock.contains("control_exit_popup_closed"))
    assertTrue(source.contains("ticketSessionState == TICKET_SESSION_CONTROL_EXIT || ticketSessionState == TICKET_SESSION_NEEDS_ATTENTION"))
    assertTrue(source.contains("control_code_enter_ignored"))
    assertTrue(source.contains("controlExitPopupLikelyUntilMillis"))
    assertTrue(source.contains("controlCodeExitCloseActionForHierarchy(hierarchy)"))
    assertTrue(source.contains("verifyAndCompleteControlExitCleanup"))
    assertTrue(source.contains("recordControlExitCleanup"))
    assertTrue(source.contains("controlExitCleanup = TicketControlExitCleanupHealth("))
    assertTrue(source.contains("requestKeyFrame(\"control_exit_cleanup\")"))
    assertTrue(source.contains("dumpViviHierarchy(fresh = true)"))
    assertTrue(source.contains("private suspend fun controlExitHierarchy()"))
    assertTrue(source.contains("PhoneAutomationServiceBridge.snapshotVisibleNodes(TicketScreenConfig.VIVI_PACKAGE)"))
    assertTrue(source.contains("CONTROL_EXIT_SURFACE_OBSERVE_TIMEOUT_MILLIS"))
    assertTrue(source.contains("control_exit_cleanup_${'$'}reason"))
    assertTrue(source.contains("TicketAutopilotRestartPolicy.NEVER"))
    assertTrue(source.contains("controlExitMaySettle()"))
    assertTrue(controlBlock.contains("control_code_soft_check_needs_attention"))
    assertTrue(controlBlock.contains("TICKET_SESSION_NEEDS_ATTENTION"))
    assertTrue(!controlBlock.contains("scheduleTicketRecovery("))
    assertTrue(!controlBlock.contains("forceFreshLaunch = true"))
    assertTrue(source.contains("ticketSessionState == TICKET_SESSION_NEEDS_ATTENTION"))
  }

  @Test
  fun activeTicketSessionHoldsAndWakesTheScreen() {
    val source = ticketStreamServiceSource()
    val start = source.indexOf("private suspend fun startTicketSession()")
    assertTrue(start >= 0)
    val stop = source.indexOf("private suspend fun handleBrowserStopRequest", start)
    val startBlock = source.substring(start, stop)
    val foregroundStart = source.indexOf("private suspend fun foregroundViolationReason")
    assertTrue(foregroundStart >= 0)
    val foregroundEnd = source.indexOf("private fun handleForegroundViolation", foregroundStart)
    val foregroundBlock = source.substring(foregroundStart, foregroundEnd)
    val violationStart = source.indexOf("private fun handleForegroundViolation")
    val violationEnd = source.indexOf("private fun resetForegroundViolationConfirmation", violationStart)
    val violationBlock = source.substring(violationStart, violationEnd)

    assertTrue(source.contains("PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP"))
    assertTrue(source.contains("private const val TICKET_SCREEN_WAKE_HOLD_MILLIS = 30_000L"))
    assertTrue(startBlock.contains("requestTicketScreenWake(\"session_start\")"))
    assertTrue(source.contains("releaseTicketScreenAwake()"))
    assertTrue(source.contains("holdTicketScreenAwake(\"viewer_input_${'$'}reason\")"))
    assertTrue(source.contains("releaseTicketScreenAwake()"))
    assertTrue(foregroundBlock.contains("return \"screen_not_interactive\""))
    assertTrue(violationBlock.contains("violation == \"screen_not_interactive\""))
    assertTrue(violationBlock.contains("foreground_screen_wake"))
    assertTrue(!violationBlock.substringBefore("violation == \"screen_not_interactive\"").contains("scheduleTicketRecovery("))
  }

  @Test
  fun screenObservationCentralizesRootUiautomatorDump() {
    val source = ticketStreamServiceSource()
    val autopilot = ticketAutopilotSource()
    val observer = ticketScreenObserverSource()

    assertTrue(source.contains("TicketScreenObserver.dumpViviHierarchy("))
    assertTrue(autopilot.contains("TicketScreenObserver.dumpViviHierarchy("))
    assertTrue(observer.contains("private val rootDumpMutex = Mutex()"))
    assertTrue(observer.contains("lastRootDumpAtMillis"))
    assertTrue(observer.contains("forceFresh: Boolean = false"))
    assertTrue(observer.contains("if (!forceFresh)"))
    assertTrue(observer.contains("uiautomator dump /sdcard/pixel-ticket-window.xml"))
  }

  @Test
  fun activeSessionGuardUsesStrictAutopilot() {
    val source = ticketStreamServiceSource()
    val autopilot = ticketAutopilotSource()

    assertTrue(source.contains("private lateinit var ticketAutopilot: TicketAutopilot"))
    assertTrue(source.contains("ticketAutopilot.driveToTicketDetail("))
    assertTrue(source.contains("forceFreshLaunch = false"))
    assertTrue(source.contains("allowControlCodePopup = true"))
    assertTrue(source.contains("result.success && result.state == TicketViviRecoveryState.CONTROL_CODE_POPUP"))
    assertTrue(source.contains("markControlCodeModeEntered(\"foreground_guard_control_code_popup\")"))
    assertTrue(source.contains("result.success && result.state == TicketViviRecoveryState.TICKET_DETAIL"))
    assertTrue(source.contains("recordTicketEvent(\"active_guard_live\", reason)"))
    assertTrue(source.contains("updateTicketSessionState(TICKET_SESSION_LIVE, \"active_guard_ticket_detail_${'$'}reason\")"))
    assertTrue(source.contains("resetForegroundViolationConfirmation()"))
    assertTrue(source.contains("restartPolicy = TicketAutopilotRestartPolicy.NEVER"))
    assertTrue(source.contains("ACTIVE_AUTOPILOT_MAX_STEPS"))
    assertTrue(!source.contains("Active sessions intentionally allow normal ViVi navigation and popups."))
    assertTrue(autopilot.contains("TicketViviRecoveryState.CART_OR_CHECKOUT"))
    assertTrue(autopilot.contains("STUCK_ACTION_LIMIT"))
    assertTrue(autopilot.contains("ticket_autopilot_uncertain_hold"))
    assertTrue(autopilot.contains("if (restartPolicy == TicketAutopilotRestartPolicy.NEVER)"))
    assertTrue(autopilot.contains("ticket_autopilot_soft_recovery_needs_attention"))
    assertTrue(autopilot.contains("forceStopVivi(\"stuck_${'$'}reason\""))
  }

  @Test
  fun autopilotLaunchesViviWithRootFallbackForPostRebootRecovery() {
    val autopilot = ticketAutopilotSource()
    val config = ticketScreenConfigSource()

    assertTrue(config.contains("const val VIVI_LAUNCH_ACTIVITY = \"com.pv.vivi/.MainActivity\""))
    assertTrue(autopilot.contains("context.startActivity("))
    assertTrue(autopilot.contains("am start -n ${'$'}{TicketScreenConfig.VIVI_LAUNCH_ACTIVITY}"))
    assertTrue(autopilot.contains("ticket_autopilot_root_launch_failed"))
  }

  @Test
  fun remoteTapGuardAllowsControlCodeButtonBeforeCoordinateBlocks() {
    val source = ticketStreamServiceSource()
    val enforcer = ticketViviPageEnforcerSource()

    assertTrue(enforcer.contains("fun isControlCodeButtonTap(xml: String, x: Int, y: Int): Boolean"))
    assertTrue(enforcer.contains("if (isControlCodeButtonTap(xml, x, y))"))
    assertTrue(source.contains("TicketViviPageEnforcer.isControlCodeButtonTap(dump.stdout, x, y)"))
    assertTrue(source.contains("control_code_button_tap"))
    assertTrue(source.contains("vivi_tap_allowed_by_hierarchy"))
  }

  @Test
  fun fastViviStateMemoryIsReportedAndUsedBeforeNavigation() {
    val source = ticketStreamServiceSource()
    val config = ticketScreenConfigSource()
    val autopilot = ticketAutopilotSource()

    assertTrue(config.contains("data class TicketViviStateHealth"))
    assertTrue(config.contains("data class TicketSessionEvent"))
    assertTrue(config.contains("data class TicketControlStateHealth"))
    assertTrue(config.contains("lastInputTotalDurationMillis"))
    assertTrue(source.contains("private val viviStateMemory = TicketViviStateMemory()"))
    assertTrue(source.contains("val viviHealth = viviStateMemory.health(nowMillis)"))
    assertTrue(source.contains("viviState = viviHealth"))
    assertTrue(source.contains("recentEvents = recentTicketEventsSnapshot(nowMillis)"))
    assertTrue(source.contains("sessionState = effectiveSessionState"))
    assertTrue(source.contains("ticketState = TicketControlStateHealth("))
    assertTrue(source.contains("lastInputTotalDurationMillis = lastInputResultTotalDurationMillis"))
    assertTrue(source.contains("launchViviUnlessFastTicketDetail("))
    assertTrue(source.contains("ticket_recovery_skipped_ticket_detail"))
    assertTrue(autopilot.contains("observeFastState("))
    assertTrue(autopilot.contains("PhoneAutomationServiceBridge.snapshotVisibleNodes"))
    assertTrue(autopilot.contains("ticket_autopilot_fast_succeeded"))
  }

  @Test
  fun recoveryHealthIsReported() {
    val source = ticketStreamServiceSource()

    assertTrue(source.contains("val recoverySnapshot = ticketRecoveryCoordinator.snapshot(nowMillis)"))
    assertTrue(source.contains("recovery = recoverySnapshot"))
  }

  @Test
  fun streamResponsivenessUsesCachedKeyframesAndNonBlockingVideoWrites() {
    val source = ticketStreamServiceSource()
    val config = ticketScreenConfigSource()

    assertTrue(source.contains("latestKeyFrameEnvelope"))
    assertTrue(source.contains("sendCachedKeyFrameOrRequest(client, element[\"reason\"]?.jsonPrimitive?.contentOrNull ?: \"video_client_request\")"))
    assertTrue(source.contains("ROOT_KEYFRAME_CACHE_MAX_AGE_MILLIS"))
    assertTrue(source.contains("ROOT_KEYFRAME_RESTART_COOLDOWN_MILLIS"))
    assertTrue(source.contains("rootCaptureEngine.noteSuppressedRestartRequest(\"cached_keyframe_available\")"))
    assertTrue(source.contains("private fun sendVideoFrame(client: TicketWebSocket, frame: ByteArray, keyFrame: Boolean)"))
    assertTrue(source.contains("droppedVideoFrames += 1"))
    assertTrue(source.contains("closedSlowVideoClients += 1"))
    assertTrue(config.contains("val droppedVideoFrames: Long = 0L"))
    assertTrue(config.contains("val slowVideoWrites: Long = 0L"))
  }

  @Test
  fun browserClientLifecycleReplacesDuplicateViewerSocketsAndStopClosesClients() {
    val source = ticketStreamServiceSource()

    assertTrue(source.contains("data class TicketClientInfo"))
    assertTrue(source.contains("queryParam(query, \"viewer\")"))
    assertTrue(source.contains("queryParam(query, \"page\")"))
    assertTrue(source.contains("queryParam(query, \"pageVersion\")"))
    assertTrue(source.contains("closeDuplicateViewerClients(info)"))
    assertTrue(source.contains("existing.video == info.video"))
    assertTrue(source.contains("replacedClientSockets += 1"))
    assertTrue(source.contains("clientConnectionSnapshot()"))
    assertTrue(source.contains("closeAllClients(\"session_stop_${'$'}reason\")"))
    assertTrue(source.contains("if (ticketSessionOpen()) {\n          noteClientDetached(\"browser_left_ticket_screen\")"))
  }

  @Test
  fun activeSessionStartIsIdempotent() {
    val source = ticketStreamServiceSource()
    val start = source.indexOf("private suspend fun startTicketSession")
    val probe = source.indexOf("val rootCaptureAvailable = rootCaptureEngine.probe()", start)
    val alreadyActive = source.indexOf("if (streamActive) {", start)
    val block = source.substring(alreadyActive, probe)

    assertTrue(start >= 0)
    assertTrue(alreadyActive in start until probe)
    assertTrue(block.contains("markViewerInput(\"session_start_already_active\")"))
    assertTrue(block.contains("ensureEncoderIfPossible()"))
    assertTrue(block.contains("return@withLock TicketSessionResponse(ok = true, state = \"active\", message = lastMessage)"))
  }

  @Test
  fun rootCaptureRestartDiagnosticsAndCleanupAreReported() {
    val source = rootCaptureEngineSource()
    val config = ticketScreenConfigSource()

    assertTrue(source.contains("restartReasonCounts"))
    assertTrue(source.contains("lastRestartReason"))
    assertTrue(source.contains("suppressedRestartRequests"))
    assertTrue(source.contains("expectedRestartStop"))
    assertTrue(source.contains("cleanupMatchingScreenrecord()"))
    assertTrue(source.contains("stopProcess(killChildren = true)"))
    assertTrue(source.contains("needle='${'$'}{escapedNeedle}'"))
    assertTrue(config.contains("val restartReasonCounts: Map<String, Long> = emptyMap()"))
    assertTrue(config.contains("val lastRestartReason: String? = null"))
  }

  @Test
  fun publicReloadDiagnosticsAvoidRepeatedCacheClearsAndUseLongReconnectGrace() {
    val source = ticketStreamServiceSource()
    val config = ticketScreenConfigSource()

    assertTrue(source.contains("private const val CLIENT_DISCONNECT_IDLE_GRACE_MILLIS = 30_000L"))
    assertTrue(source.contains("private const val RECENT_CLIENT_TELEMETRY_LIMIT = 80"))
    assertTrue(source.contains("method == \"GET\" && path == \"/api/v1/bootstrap\""))
    assertTrue(source.contains("method == \"GET\" && path == \"/api/v1/cache-cleanup\""))
    assertTrue(source.contains("X-Ticket-Server-Version"))
    assertTrue(source.contains("X-Ticket-Cache-Policy"))
    assertTrue(source.contains("lastRootHtmlRequestAtMillis"))
    assertTrue(source.contains("lastBootstrapRequestAtMillis"))
    assertTrue(source.contains("lastCacheCleanupRequestAtMillis"))
    assertTrue(source.contains("clearSiteCache = true"))
    assertTrue(source.contains("clearSiteCache = false"))
    assertTrue(source.contains("if (clearSiteCache) append(\"Clear-Site-Data:"))
    assertTrue(config.contains("data class TicketPageHealth"))
    assertTrue(config.contains("val page: TicketPageHealth = TicketPageHealth()"))
    assertTrue(config.contains("data class TicketClientConnectionHealth"))
  }

  @Test
  fun ticketTunnelStartChecksHealthyLoopBeforeCleanup() {
    val source = ticketStartScriptSource()
    val start = source.indexOf("start_tunnel_loop_if_needed()")
    assertTrue(start >= 0)
    val block = source.substring(start)

    assertTrue(block.indexOf("pid=\"$(read_pid_file \"${'$'}{TUNNEL_LOOP_PID_FILE}\" || true)\"") < block.indexOf("cleanup_extra_tunnel_loops"))
    assertTrue(block.contains("cloudflared_pid=\"$(read_pid_file \"${'$'}{CLOUDFLARED_PID_FILE}\" || true)\""))
    assertTrue(block.indexOf("if pid_matches_target \"${'$'}{pid}\" \"${'$'}{TUNNEL_LOOP_BIN}\"; then") < block.indexOf("cleanup_extra_tunnel_loops"))
    assertTrue(block.contains("cleanup_extra_tunnel_loops \"${'$'}{pid}\" \"${'$'}{cloudflared_pid}\""))
    assertTrue(block.contains("cleanup_extra_tunnel_loops \"\" \"\""))
    assertTrue(source.contains("kill_matching_with_signal \"-9\" \"${'$'}{TUNNEL_LOOP_BIN}\" \"${'$'}{keep_loop_pid}\""))
    assertTrue(source.contains("keep_pid=\"${'$'}{3:-}\""))
    assertTrue(source.contains("\"${'$'}keep_pid\") ;;"))
  }

  private fun ticketStreamServiceSource(): String {
    val path = listOf(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketStreamService.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketStreamService.kt")
    ).first(Files::exists)
    return String(
      Files.readAllBytes(path)
    )
  }

  private fun rootAv1CaptureEngineSource(): String {
    val path = listOf(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketRootAv1CaptureEngine.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketRootAv1CaptureEngine.kt")
    ).first(Files::exists)
    return String(
      Files.readAllBytes(path)
    )
  }

  private fun rootFfmpegH264CaptureEngineSource(): String {
    val path = listOf(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketRootFfmpegH264CaptureEngine.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketRootFfmpegH264CaptureEngine.kt")
    ).first(Files::exists)
    return String(
      Files.readAllBytes(path)
    )
  }

  private fun ticketRecoveryCoordinatorSource(): String {
    val path = listOf(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketRecoveryCoordinator.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketRecoveryCoordinator.kt")
    ).first(Files::exists)
    return String(
      Files.readAllBytes(path)
    )
  }

  private fun ticketAutopilotSource(): String {
    val path = listOf(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketAutopilot.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketAutopilot.kt")
    ).first(Files::exists)
    return String(
      Files.readAllBytes(path)
    )
  }

  private fun ticketScreenObserverSource(): String {
    val path = listOf(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketScreenObserver.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketScreenObserver.kt")
    ).first(Files::exists)
    return String(
      Files.readAllBytes(path)
    )
  }

  private fun ticketScreenConfigSource(): String {
    val path = listOf(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketScreenConfig.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketScreenConfig.kt")
    ).first(Files::exists)
    return String(
      Files.readAllBytes(path)
    )
  }

  private fun rootCaptureEngineSource(): String {
    val path = listOf(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketRootCaptureEngine.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketRootCaptureEngine.kt")
    ).first(Files::exists)
    return String(
      Files.readAllBytes(path)
    )
  }

  private fun ticketStartScriptSource(): String {
    val path = listOf(
      Path.of("app/src/main/assets/runtime/entrypoints/pixel-ticket-start.sh"),
      Path.of("src/main/assets/runtime/entrypoints/pixel-ticket-start.sh")
    ).first(Files::exists)
    return String(
      Files.readAllBytes(path)
    )
  }

  private fun ticketViviPageEnforcerSource(): String {
    val path = listOf(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketViviPageEnforcer.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketViviPageEnforcer.kt")
    ).first(Files::exists)
    return String(
      Files.readAllBytes(path)
    )
  }
}
