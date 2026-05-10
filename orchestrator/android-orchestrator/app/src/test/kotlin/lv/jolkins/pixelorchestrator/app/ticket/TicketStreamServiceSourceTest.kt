package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class TicketStreamServiceSourceTest {
  @Test
  fun publicStreamStartupCanOnlySelectRootFfmpegH264() {
    val source = ticketStreamServiceSource()
    val start = source.substringBetween("private suspend fun startTicketSession()", "private suspend fun handleBrowserStopRequest")

    assertTrue(start.contains("rootFfmpegH264CaptureEngine.probe(sourceSize.first, sourceSize.second)"))
    assertTrue(start.contains("activeCaptureMode = CAPTURE_MODE_ROOT_FFMPEG_H264"))
    assertTrue(start.contains("enableSecureWindowCaptureBypass()"))
    assertTrue(start.contains("scheduleRootFfmpegH264CaptureStart(\"session_start_root_ffmpeg_h264_capture\""))
    assertTrue(start.contains("Root FFmpeg H.264 capture is unavailable; stream was not started"))
    assertFalse(start.contains("requestCapturePermission("))
    assertFalse(start.contains("MediaProjection"))
    assertFalse(start.contains("rootPngCaptureEngine"))
    assertFalse(start.contains("rootAv1CaptureEngine"))
    assertFalse(start.contains("rootCaptureEngine"))
  }

  @Test
  fun obsoleteCaptureEnginesAndPublicHealthFieldsAreAbsent() {
    val source = ticketStreamServiceSource()
    val config = ticketScreenConfigSource()
    val manifest = androidManifestSource()

    listOf(
      "TicketRootCaptureEngine.kt",
      "TicketRootPngCaptureEngine.kt",
      "TicketRootAv1CaptureEngine.kt",
      "TicketCapturePermissionActivity.kt"
    ).forEach { assertFalse("$it should be removed", ticketSourcePath(it).exists()) }

    val forbidden = listOf(
      "root_screencap_png",
      "root_screenrecord_h264",
      "root_av1",
      "mediaprojection_av1",
      "TicketAv1Support",
      "TicketH264Support",
      "capturePermissionPending",
      "projectionReady",
      "TicketWebRtcHealth",
      "RTCPeerConnection",
      "webrtc_ice_config"
    )
    forbidden.forEach { needle ->
      assertFalse("TicketStreamService still contains $needle", source.contains(needle))
      assertFalse("TicketScreenConfig still contains $needle", config.contains(needle))
    }
    assertFalse(manifest.contains("FOREGROUND_SERVICE_MEDIA_PROJECTION"))
    assertFalse(manifest.contains("foregroundServiceType=\"mediaProjection\""))
  }

  @Test
  fun ffmpegProfileIsFixedForReadableLowLatencyH264() {
    val config = ticketScreenConfigSource()
    val engine = rootFfmpegH264CaptureEngineSource()

    assertTrue(config.contains("const val ROOT_FFMPEG_H264_CAPTURE_MODE = \"root_ffmpeg_h264\""))
    assertTrue(config.contains("const val ROOT_FFMPEG_H264_TARGET_WIDTH = 900"))
    assertTrue(config.contains("const val ROOT_FFMPEG_H264_FPS = 10"))
    assertTrue(config.contains("const val ROOT_FFMPEG_H264_BITRATE = 5_000_000"))
    assertTrue(config.contains("const val ROOT_FFMPEG_H264_KEYFRAME_INTERVAL_MILLIS = 1_000"))
    assertTrue(engine.contains("-c:v libx264 -preset ultrafast -tune zerolatency"))
    assertTrue(engine.contains("-threads 1"))
    assertTrue(engine.contains("repeat-headers=1"))
    assertTrue(engine.contains("bframes=0"))
    assertTrue(engine.contains("-g ${'$'}keyInterval -bf 0 -refs 1 -profile:v baseline -level 4.0"))
  }

  @Test
  fun secureBypassAndNonBlackSurfaceProbeGateLiveCapture() {
    val source = ticketStreamServiceSource()
    val start = source.substringBetween("private fun scheduleRootFfmpegH264CaptureStart", "private suspend fun verifyRootFfmpegSecureCaptureVisible")
    val verify = source.substringBetween("private suspend fun verifyRootFfmpegSecureCaptureVisible", "private fun scheduleTicketRecovery")

    assertTrue(start.contains("verifyRootFfmpegSecureCaptureVisible(reason)"))
    assertTrue(start.indexOf("verifyRootFfmpegSecureCaptureVisible(reason)") < start.indexOf("ensureEncoderIfPossible()"))
    assertTrue(start.contains("rootFfmpegH264CaptureEngine.stop(\"secure_capture_blocked:${'$'}reason\")"))
    assertTrue(start.contains("updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, \"secure_capture_blocked\")"))
    assertTrue(start.contains("waiting_first_visible_frame"))
    assertTrue(verify.contains("verifyViviTicketDetailHierarchy(reason)"))
    assertTrue(verify.contains("captureRootSurfaceProbeRaw(probeWidth, probeHeight)"))
    assertTrue(verify.contains("rootSurfaceRawFrameLooksVisible(frame)"))
  }

  @Test
  fun ffmpegRawFramesAreFilteredBeforeEncodingAndHealthReportsSuppression() {
    val service = ticketStreamServiceSource()
    val engine = rootFfmpegH264CaptureEngineSource()
    val config = ticketScreenConfigSource()

    assertTrue(engine.contains("rawRgbaFrameLooksVisible(pixels, sourceWidth, sourceHeight)"))
    assertTrue(engine.contains("suppressedRawFrames += 1"))
    assertTrue(engine.contains("firstVisibleRawFrameAtMillis"))
    assertTrue(config.contains("val suppressedRawFrames: Long = 0L"))
    assertTrue(config.contains("val firstVisibleRawFrameAgoMillis: Long? = null"))
    assertTrue(service.contains("failRootFfmpegH264Capture(\"secure_capture_blocked_raw_frames\")"))
    assertTrue(service.contains("ROOT_FFMPEG_RAW_FRAME_SUPPRESSION_FAILURE_THRESHOLD"))
    assertTrue(service.contains("streamVerdict = streamVerdict(ffmpegCapture, nowMillis)"))
  }

  @Test
  fun firstViewerStartsFfmpegAndLastViewerStopsIt() {
    val source = ticketStreamServiceSource()
    val websocket = source.substringBetween("private suspend fun acceptWebSocket", "private suspend fun startTicketSession")
    val detach = source.substringBetween("private suspend fun noteClientDetached", "private suspend fun stopTicketSession")

    assertTrue(websocket.contains("if (video) {"))
    assertTrue(websocket.contains("ensureEncoderIfPossible()"))
    assertTrue(websocket.contains("sendConfigAndWarmStart(client, size)"))
    assertTrue(detach.contains("rootFfmpegH264CaptureEngine.stop(reason)"))
    assertTrue(detach.contains("resetFrameEpoch(\"client_detached_${'$'}reason\", active = false)"))
    assertFalse(detach.contains("stopProjection()"))
  }

  @Test
  fun stoppedStreamCleansStaleClientsAndBoundsSessionStart() {
    val source = ticketStreamServiceSource()
    val start = source.substringBetween("private suspend fun startTicketSession()", "private suspend fun startTicketSessionLocked")
    val cleanup = source.substringBetween("private fun cleanupInactiveClientsIfNeeded", "private fun totalClientCount")
    val health = source.substringBetween("private fun health()", "private fun launchVivi")

    assertTrue(start.contains("withTimeoutOrNull(SESSION_START_TIMEOUT_MILLIS)"))
    assertTrue(start.contains("cleanupInactiveClientsIfNeeded(\"session_start_preflight\")"))
    assertTrue(start.contains("cleanupInactiveClientsIfNeeded(\"session_start_timeout\")"))
    assertTrue(start.contains("state = \"start_timeout\""))
    assertTrue(cleanup.contains("closeAllClients(\"inactive_stream_${'$'}reason\")"))
    assertTrue(health.contains("cleanupInactiveClientsIfNeeded(\"health\")"))
  }

  @Test
  fun ffmpegStopClearsStaleFrameHealth() {
    val engine = rootFfmpegH264CaptureEngineSource()
    val service = ticketStreamServiceSource()
    val stop = engine.substringBetween("fun stop(reason: String = \"stopped\")", "fun requestKeyFrame")
    val cleanup = engine.substringBetween("suspend fun cleanupStaleProcesses()", "fun snapshot")
    val resetFrameEpoch = service.substringBetween("private fun resetFrameEpoch", "private fun ensureFrameEpoch")

    assertTrue(stop.contains("clearFrameState()"))
    assertTrue(cleanup.contains("clearFrameState()"))
    assertTrue(engine.contains("private fun clearFrameState()"))
    assertTrue(engine.contains("lastFrameAtMillis = 0L"))
    assertTrue(engine.contains("frames = 0L"))
    assertTrue(resetFrameEpoch.contains("lastFrameSentAtMillis = 0L"))
    assertTrue(resetFrameEpoch.contains("lastKeyFrameEncodedAtMillis = 0L"))
  }

  @Test
  fun notificationLockdownStartsBeforeRootCaptureIsScheduled() {
    val source = ticketStreamServiceSource()
    val start = source.substringBetween("private suspend fun startTicketSession()", "private suspend fun handleBrowserStopRequest")

    assertTrue(start.contains("enableNotificationLockdown(\"session_start\")"))
    assertTrue(start.indexOf("enableNotificationLockdown(\"session_start\")") < start.indexOf("scheduleRootFfmpegH264CaptureStart"))
  }

  @Test
  fun touchBrightnessOwnershipParksTicketBrightnessGuard() {
    val source = ticketStreamServiceSource()
    val guard = source.substringBetween("private fun scheduleTicketBrightnessGuard", "private fun ticketBrightnessGuardShouldContinue")
    val shouldContinue = source.substringBetween("private fun ticketBrightnessGuardShouldContinue", "private fun ticketBrightnessGuardPausedForPhysicalUse")
    val enforce = source.substringBetween("private suspend fun enforceTicketSafeBrightness", "private fun refreshPhoneAutomation")

    assertTrue(guard.contains("touchBrightnessOwnsTicketBrightness()"))
    assertTrue(guard.contains("Ticket brightness guard parked because touch brightness owns panel brightness"))
    assertTrue(guard.contains("releaseTicketScreenAwake()"))
    assertTrue(guard.contains("hideBlackoutOverlay()"))
    assertTrue(shouldContinue.contains("touchBrightnessOwnsTicketBrightness()"))
    assertTrue(enforce.contains("touchBrightnessOwnsTicketBrightness()"))
    assertTrue(enforce.indexOf("touchBrightnessOwnsTicketBrightness()") < enforce.indexOf("ScreenBrightnessControl.buildSetPercentScript"))
  }

  @Test
  fun localViewerUsesWebCodecsH264Only() {
    val source = ticketStreamServiceSource()

    assertTrue(source.contains("new VideoDecoder({"))
    assertTrue(source.contains("new EncodedVideoChunk({type: frame.kind"))
    assertTrue(source.contains("decoderConfig.avc = {format: 'annexb'}"))
    assertTrue(source.contains("ctx.drawImage(frame, 0, 0, canvas.width, canvas.height)"))
    listOf(
      "RTCPeerConnection",
      "webrtc_ice_config",
      "drawPngFrame",
      "av01.",
      "This browser cannot decode AV1",
      "Savieno WebRTC video"
    ).forEach { assertFalse("local viewer still contains $it", source.contains(it)) }
  }

  @Test
  fun controlAndSafariProtectionsRemainInLocalViewer() {
    val source = ticketStreamServiceSource()

    assertTrue(source.contains("REMOTE_QUICK_CLAIM_MAX_X_FRACTION = 0.25f"))
    assertTrue(source.contains("VIVI_CONTROL_CODE_MAX_X_FRACTION = 0.45f"))
    assertTrue(source.contains("SNAP_TARGET_CONTROL_CODE_BUTTON = \"control_code_button\""))
    assertTrue(source.contains("control_code_button_snap_live_ticket_stream_geometry"))
    assertTrue(source.contains("control_code_snap_recent_state_"))
    assertTrue(source.contains("runFastNonTouchInput(\"input tap ${'$'}{target.x} ${'$'}{target.y}\", \"remote_control_code_snap_tap\")"))
    assertTrue(source.contains("control_code_popup_already_open"))
    assertTrue(source.contains("val noOp: Boolean = false"))
    assertTrue(source.contains("if (target.noOp)"))
    assertTrue(source.contains("remote_input_canceled_after_control_exit"))
    assertTrue(source.contains("completeVerifiedTicketDetailControlExitCleanup"))
    assertTrue(source.contains("controlExitCleanupJob"))
    assertTrue(source.contains("scheduleControlExitCleanup"))
    assertTrue(source.contains("runControlExitCleanup"))
    assertTrue(source.contains("tryControlExitDirectCloseFromHierarchy"))
    assertTrue(source.contains("control_exit_direct_close"))
    assertTrue(source.contains("CONTROL_EXIT_RECENT_DIRECT_CLOSE_MILLIS"))
    assertTrue(source.contains("control_exit_surface_clean_after_direct_close"))
    assertTrue(source.contains("control_exit_foreground_check_bypassed"))
    assertTrue(source.contains("control_exit_result_geometry_fallback"))
    assertTrue(source.contains("geometry_close_control_code_result"))
    assertTrue(source.contains("control_exit_cleanup_duplicate_ignored"))
    assertTrue(source.contains("CONTROL_EXIT_SECOND_VERIFY_DELAY_MILLIS"))
    assertTrue(source.contains("CONTROL_EXIT_ROOT_DUMP_TIMEOUT_MILLIS"))
    assertTrue(source.contains("timeoutMillis = CONTROL_EXIT_ROOT_DUMP_TIMEOUT_MILLIS"))
    assertTrue(source.contains("already_closed_after_control_code_result"))
    assertTrue(source.contains("CONTROL_EXIT_RECENT_SURFACE_MEMORY_MILLIS"))
    assertTrue(source.contains("controlCodeSurfaceMemoryState"))
    assertTrue(source.contains("lastControlExitDirtySurfaceState"))
    assertTrue(source.contains("controlCodeModeActive -> state"))
    assertTrue(source.contains("ticketSessionState == TICKET_SESSION_CONTROL_EXIT -> state"))
    assertTrue(source.contains("alignControlExitTicketDetailWithSurface"))
    assertTrue(source.contains("control_exit_initial_surface"))
    assertTrue(source.contains("control_exit_surface_probe"))
    assertTrue(source.contains("rootSurfaceRawFrameLooksLikeGeneratedCodeResult"))
    assertTrue(source.contains("lastSurfaceProbeResult"))
    assertTrue(source.contains("touch-action: pan-y"))
    assertTrue(source.contains("scroll-snap-type: y proximity"))
    assertTrue(source.contains("gesturechange"))
    assertTrue(source.contains("dblclick"))
    assertTrue(source.contains("streamVerticalPanThresholdPx"))
  }

  @Test
  fun generatedControlCodeCommandRunsAutomatedPhoneFlow() {
    val source = ticketStreamServiceSource()

    assertTrue(source.contains("\"generate_control_code\" -> {"))
    assertTrue(source.contains("handleGenerateControlCode(requestId, digits)"))
    assertTrue(source.contains("CONTROL_CODE_REQUEST_DIGITS_REGEX"))
    assertTrue(source.contains("controlCodeRequestMutex.withLock"))
    assertTrue(source.contains("sendCachedControlCodeResult(cleanRequestId)"))
    assertTrue(source.contains("prepareViviForControlCodeRequest(phases)"))
    assertTrue(source.contains("CONTROL_CODE_REQUEST_PREPARE_TIMEOUT_MILLIS"))
    assertTrue(source.contains("runFastControlCodeDeliveryForRequest(cleanDigits, phases, startedAtMillis)"))
    assertTrue(source.contains("openControlCodePopupFastForRequest(phases, requestStartedAtMillis)"))
    assertTrue(source.contains("openControlCodePopupImmediateForRequest(phases, requestStartedAtMillis)"))
    assertTrue(source.contains("controlCodeImmediateStartDecision()"))
    assertTrue(source.contains("CONTROL_CODE_IMMEDIATE_TICKET_DETAIL_MEMORY_MAX_AGE_MILLIS"))
    assertTrue(source.contains("enterControlCodeDigitsFastForRequest(cleanDigits, targets, phases, requestStartedAtMillis)"))
    assertTrue(source.contains("tapControlCodeSubmitFastForRequest(targets, cleanDigits, phases, requestStartedAtMillis)"))
    assertTrue(source.contains("waitForGeneratedControlCodeResultAfterSubmit(phases, requestStartedAtMillis)"))
    assertTrue(source.contains("captureGeneratedControlCodeImageBytes("))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"request_gate_passed\", startedAtMillis)"))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"first_phone_tap\", requestStartedAtMillis)"))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"popup_ready\", requestStartedAtMillis)"))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"digits_typed\", requestStartedAtMillis)"))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"ok_tapped\", requestStartedAtMillis)"))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"result_first_visible\", requestStartedAtMillis)"))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"capture_started\", requestStartedAtMillis)"))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"capture_bytes_ready\", requestStartedAtMillis)"))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"close_tap_sent\", requestStartedAtMillis)"))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"raw_ticket_fast_proof\", requestStartedAtMillis)"))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"phone_raw_recovered\", requestStartedAtMillis)"))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"image_delivered\", startedAtMillis)"))
    assertTrue(source.contains("ProcessBuilder(\"su\", \"-c\", \"screencap -p\")"))
    assertTrue(source.contains("Base64.getEncoder().encodeToString(it)"))
    val captureForTempFileCheck = source.substringBetween("private suspend fun captureGeneratedControlCodeImageBytes", "private suspend fun encodeControlCodeImageBase64")
    assertFalse(captureForTempFileCheck.contains("createTempFile"))
    assertFalse(source.contains("screencap -p \"\${'$'}tmp\""))
    assertFalse(source.contains("base64 \"\${'$'}tmp\""))
    assertTrue(source.contains("sendControlCodeResult("))
    assertTrue(source.contains("type\", \"control_code_result\""))
    assertTrue(source.contains("cleanupPending\", cleanupPending"))
    assertTrue(source.contains("sendControlCodeCleanup("))
    assertTrue(source.contains("type\", \"control_code_cleanup_complete\""))
    assertTrue(source.contains("controlCodeRequestRootHierarchy(phases"))
    assertTrue(source.contains("snapshotVisibleNodes(TicketScreenConfig.VIVI_PACKAGE)"))
    assertTrue(source.contains("hasControlCodeInputForHierarchy(hierarchy)"))
    val fastFlow = source.substringBetween("private suspend fun runFastControlCodeDeliveryForRequest", "private suspend fun openControlCodePopupFastForRequest")
    assertTrue(fastFlow.contains("tapControlCodeSubmitFastForRequest(targets, cleanDigits, phases, requestStartedAtMillis)"))
    assertTrue(fastFlow.contains("captureGeneratedControlCodeImageBytes("))
    assertTrue(fastFlow.indexOf("tapControlCodeSubmitFastForRequest(targets, cleanDigits, phases, requestStartedAtMillis)") < fastFlow.indexOf("captureGeneratedControlCodeImageBytes("))
    assertTrue(fastFlow.indexOf("waitForGeneratedControlCodeResultAfterSubmit(phases, requestStartedAtMillis)") < fastFlow.indexOf("captureGeneratedControlCodeImageBytes("))
    val fastEnter = source.substringBetween("private suspend fun enterControlCodeDigitsFastForRequest", "private suspend fun tapControlCodeSubmitFastForRequest")
    assertTrue(fastEnter.contains("input tap ${'$'}{targets.input.x} ${'$'}{targets.input.y}"))
    assertTrue(fastEnter.contains("input text ${'$'}digits"))
    assertTrue(fastEnter.contains("control_code_input_typed_submit_now"))
    assertFalse("fast digit entry should not wait on root-dump value verification before OK", fastEnter.contains("verifyControlCodeDigitsEnteredFast"))
    assertFalse("fast digit entry should not wait on root-dump popup verification before OK", fastEnter.contains("controlCodeInputStillAvailableFastForRequest"))
    assertFalse("fast digit entry should not abort before OK just because the typed value is not visible in the root dump", fastEnter.contains("control_code_input_verify_failed"))
    assertFalse("fast digit entry should not use accessibility as the primary route", fastEnter.contains("setTextInFocusedInput("))
    assertFalse("fast digit entry should not use accessibility as the primary route", fastEnter.contains("setTextInFirstEditableInput("))
    val fastSubmit = source.substringBetween("private suspend fun tapControlCodeSubmitFastForRequest", "private suspend fun waitForGeneratedControlCodeResultAfterSubmit")
    assertTrue(fastSubmit.contains("input tap ${'$'}{submit.x} ${'$'}{submit.y}"))
    assertTrue(fastSubmit.contains("val submit = fallbackControlCodeShiftedSubmitAction() ?: targets.submit"))
    assertTrue(fastSubmit.contains("fallbackControlCodeShiftedSubmitAction()"))
    assertFalse("fast submit should not spend time on another root dump before tapping OK", fastSubmit.contains("findControlCodeSubmitActionFastForRequest(phases)"))
    assertTrue(fastSubmit.contains("control_code_submit_attempted"))
    assertFalse("fast submit should not use accessibility click before the coordinate OK tap", fastSubmit.substringBefore("control_code_submit_attempted").contains("PhoneAutomationServiceBridge.clickSelectors("))
    assertTrue(source.contains("verifyControlCodeDigitsEntered(digits, phases)"))
    assertTrue(source.contains("rememberControlCodePopupSurface(surface)"))
    assertTrue(source.contains("cachedControlCodePopupSurface()?.input"))
    assertTrue(source.contains("cachedControlCodePopupSurface()?.submit"))
    assertTrue(source.contains("controlCodeInputActionLooseForHierarchy(hierarchy)"))
    assertTrue(source.contains("PhoneAutomationServiceBridge.clickSelectors("))
    assertTrue(source.contains("controlCodeSubmitSelectors()"))
    assertTrue(source.contains("controlCodePopupStillPresentAfterSubmit(phases)"))
    assertTrue(source.contains("controlCodeRequestNeedsSurfaceRecovery()"))
    assertTrue(source.contains("pre_request_surface_cleanup"))
    assertTrue(source.contains("recover_hierarchy_unavailable"))
    assertTrue(source.contains("recover_previous_result"))
    assertTrue(source.contains("openControlCodePopupFromRecoveredStateFastForRequest"))
    assertTrue(source.contains("submitAttempted = true"))
    assertTrue(source.contains("if (!submitAttempted)"))
    assertTrue(source.contains("TicketViviPageEnforcer.strictControlCodeResultValueForHierarchy(hierarchy)"))
    assertTrue(source.contains("control_code_input_missing"))
    assertTrue(source.contains("control_code_input_focus_failed"))
    assertTrue(source.contains("control_code_input_set_failed"))
    assertTrue(source.contains("control_code_input_verify_failed"))
    assertTrue(source.contains("control_code_submit_missing"))
    assertTrue(source.contains("control_code_submit_returned_no_result"))
    assertTrue(source.contains("control_code_submit_timeout"))
    assertTrue(source.contains("ok = delivery.ok"))
    assertTrue(source.contains("imageBytes = delivery.imageBytes"))
    assertTrue(source.contains("imageBase64 = encodeControlCodeImageBase64(imageBytes)"))
    assertTrue(source.contains("imageMime\", imageMime.orEmpty()"))
    assertTrue(source.contains("imageBase64\", imageBase64.orEmpty()"))
    assertTrue(source.contains("generatedHierarchy = generated.hierarchy"))
    assertTrue(source.contains("beginGeneratedControlCodeResultFastClose("))
    assertTrue(source.contains("finishGeneratedControlCodeResultFastCleanup("))
    assertTrue(source.contains("delivery.generatedHierarchy"))
    assertTrue(source.contains("recoverControlCodeRequestSurface(\"control_code_request_failed_cleanup\")"))
    assertTrue(source.contains("cleanupPending = true"))
    assertTrue(source.contains("runControlExitCleanup(reason)"))
    assertTrue(source.contains("CONTROL_EXIT_RESULT_CLOSE_Y_FRACTION = 0.592f"))
    assertTrue(source.contains("scheduleControlExitSoftSettle(\"control_code_request_cleanup_failed\")"))
  }

  @Test
  fun successCleanupStartsCloseBeforeSendingResult() {
    val source = ticketStreamServiceSource()
    val generate = source.substringBetween("private suspend fun handleGenerateControlCode", "private suspend fun runFastControlCodeDeliveryForRequest")
    val success = generate.substringBetween("if (ok) {", "} else if (delivery.cleanupRequired)")
    val fastCleanup = source.substringBetween("private suspend fun beginGeneratedControlCodeResultFastClose", "private suspend fun closeControlCodePopupFromRemote")

    assertTrue(success.contains("beginGeneratedControlCodeResultFastClose("))
    assertTrue(success.contains("finishGeneratedControlCodeResultFastCleanup("))
    assertTrue(success.indexOf("beginGeneratedControlCodeResultFastClose(") < success.indexOf("sendControlCodeResult("))
    assertTrue(success.indexOf("sendControlCodeResult(") < success.indexOf("finishGeneratedControlCodeResultFastCleanup("))
    assertTrue(fastCleanup.contains("val action = controlCodeResultGeometryCloseAction()"))
    assertTrue(fastCleanup.contains("input tap ${'$'}{action.x} ${'$'}{action.y}"))
    assertTrue(fastCleanup.contains("waitForCleanTicketSurfaceFast("))
    assertTrue(fastCleanup.contains("requestKeyFrame(\"control_code_fast_cleanup_close\")"))
    assertFalse("fast cleanup must not root-dump before the first close tap", fastCleanup.substringBefore("runFastNonTouchInput").contains("controlExitHierarchy()"))
    assertFalse("fast cleanup must not re-parse the generated hierarchy before the close tap", fastCleanup.substringBefore("runFastNonTouchInput").contains("classifyForRecovery(generatedHierarchy)"))
    assertFalse("fast cleanup should not take the slow second-confirm path on clean visual proof", fastCleanup.contains("completeVerifiedTicketDetailControlExitCleanup("))
    assertFalse("fast cleanup should not wait on the slow second-confirm delay", fastCleanup.contains("CONTROL_EXIT_SECOND_VERIFY_DELAY_MILLIS"))
  }

  @Test
  fun fastCleanupChecksAccessibilityBeforeSlowSurfaceProbe() {
    val source = ticketStreamServiceSource()
    val fastVerifier = source.substringBetween("private suspend fun waitForCleanTicketSurfaceFast", "private fun completeFastVerifiedTicketDetailControlExitCleanup")

    assertTrue(fastVerifier.contains("ticketAutopilot.observeFastState(\"control_code_fast_cleanup"))
    assertTrue(fastVerifier.indexOf("ticketAutopilot.observeFastState") < fastVerifier.indexOf("controlExitGeneratedResultFastScreencapProbe("))
    assertTrue(fastVerifier.contains("controlExitGeneratedResultFastScreencapProbe("))
    assertFalse("fast verifier should not start the slow root surface app_process probe", fastVerifier.contains("controlExitGeneratedResultSurfaceProbe("))
    assertFalse("stale accessibility result should not delay the immediate visual proof", fastVerifier.substringBetween("if (accessibilityState == TicketViviRecoveryState.CONTROL_CODE_RESULT)", "val state = if (!fastScreencapAttempted)").contains("continue"))
    assertTrue(fastVerifier.contains("accessibility_clean"))
  }

  @Test
  fun fastResultDetectionChecksAccessibilityBeforeSettleOrRootDump() {
    val source = ticketStreamServiceSource()
    val wait = source.substringBetween("private suspend fun waitForGeneratedControlCodeResultAfterSubmit", "private suspend fun controlCodeRequestRootHierarchy")
    val beforeLoop = wait.substringBefore("while (SystemClock.elapsedRealtime() < deadline)")

    assertFalse("fast result detection should not sleep before checking accessibility", beforeLoop.contains("delay(CONTROL_CODE_FAST_RESULT_SETTLE_MILLIS)"))
    assertTrue(wait.contains("ticketAutopilot.observeFastState(\"control_code_result_fast_wait\""))
    assertTrue(wait.indexOf("ticketAutopilot.observeFastState") < wait.indexOf("controlCodeRequestRootHierarchy("))
    assertTrue(wait.contains("result_first_visible"))
  }

  @Test
  fun controlCodeCaptureUsesDirectPngBytesAndEncodesOnlyAfterCloseStarts() {
    val source = ticketStreamServiceSource()
    val capture = source.substringBetween("private suspend fun captureGeneratedControlCodeImageBytes", "private suspend fun encodeControlCodeImageBase64")
    val generate = source.substringBetween("private suspend fun handleGenerateControlCode", "private suspend fun runFastControlCodeDeliveryForRequest")
    val success = generate.substringBetween("if (ok) {", "} else if (delivery.cleanupRequired)")

    assertTrue(capture.contains("ProcessBuilder(\"su\", \"-c\", \"screencap -p\")"))
    assertTrue(capture.contains("readControlCodePngSignature("))
    assertTrue(capture.contains("readRemainingControlCodePngBytes("))
    assertTrue(capture.contains("capture_started"))
    assertTrue(capture.contains("capture_bytes_ready"))
    assertFalse("capture should not write a temp PNG before delivery", capture.contains("createTempFile"))
    assertFalse("capture should not invoke shell base64 before close", capture.contains("base64"))
    assertTrue(success.contains("val cleanupStart = delivery.cleanupStart ?: beginGeneratedControlCodeResultFastClose("))
    assertTrue(success.contains("imageBase64 = encodeControlCodeImageBase64(imageBytes)"))
    assertTrue(success.indexOf("beginGeneratedControlCodeResultFastClose(") < success.indexOf("encodeControlCodeImageBase64(imageBytes)"))
    assertTrue(success.indexOf("encodeControlCodeImageBase64(imageBytes)") < success.indexOf("sendControlCodeResult("))
  }

  @Test
  fun directPngCaptureStartsCloseAfterHeaderBeforeReadingRest() {
    val source = ticketStreamServiceSource()
    val capture = source.substringBetween("private suspend fun captureGeneratedControlCodeImageBytes", "private suspend fun encodeControlCodeImageBase64")

    assertTrue(capture.contains("readControlCodePngSignature("))
    assertTrue(capture.contains("beginGeneratedControlCodeResultFastClose("))
    assertTrue(capture.contains("readRemainingControlCodePngBytes("))
    assertTrue(capture.indexOf("readControlCodePngSignature(") < capture.indexOf("beginGeneratedControlCodeResultFastClose("))
    assertTrue(capture.indexOf("beginGeneratedControlCodeResultFastClose(") < capture.indexOf("readRemainingControlCodePngBytes("))
  }

  @Test
  fun captureFailureClosesGeneratedSurfaceBeforeReportingFailure() {
    val source = ticketStreamServiceSource()
    val generate = source.substringBetween("private suspend fun handleGenerateControlCode", "private suspend fun runFastControlCodeDeliveryForRequest")
    val captureFailure = generate.substringBetween(
      "reason == \"control_code_image_capture_failed\" && delivery.generatedHierarchy.isNotBlank()",
      "} else if (delivery.cleanupRequired)"
    )

    assertTrue(captureFailure.contains("beginGeneratedControlCodeResultFastClose("))
    assertTrue(captureFailure.contains("finishGeneratedControlCodeResultFastCleanup("))
    assertTrue(captureFailure.contains("sendControlCodeResult("))
    assertTrue(captureFailure.indexOf("beginGeneratedControlCodeResultFastClose(") < captureFailure.indexOf("sendControlCodeResult("))
    assertTrue(captureFailure.indexOf("finishGeneratedControlCodeResultFastCleanup(") < captureFailure.indexOf("sendControlCodeResult("))
    assertTrue(captureFailure.contains("sendControlCodeCleanup("))
  }

  @Test
  fun cleanupHierarchyReturnsAuthoritativeAccessibilityBeforeRootDump() {
    val source = ticketStreamServiceSource()
    val helper = source.substringBetween("private suspend fun controlExitHierarchy()", "private fun inactivityRemainingMillis")

    assertTrue(helper.contains("PhoneAutomationServiceBridge.snapshotVisibleNodes(TicketScreenConfig.VIVI_PACKAGE)"))
    assertTrue(helper.contains("if (accessibilityState.controlExitHierarchyIsAuthoritative())"))
    assertTrue(helper.indexOf("if (accessibilityState.controlExitHierarchyIsAuthoritative())") < helper.indexOf("dumpViviHierarchy"))
  }

  @Test
  fun surfaceControlHelperRequestsSecureContentCapture() {
    val source = rootSurfaceCaptureMainSource()

    assertTrue(source.contains("SCREEN_CAPTURE_POLICY_CAPTURE = 1"))
    assertTrue(source.contains("setSecureContentPolicy"))
    assertTrue(source.contains("setProtectedContentPolicy"))
  }

  private fun String.substringBetween(startNeedle: String, endNeedle: String): String {
    val start = indexOf(startNeedle)
    assertTrue("missing start needle: $startNeedle", start >= 0)
    val end = indexOf(endNeedle, start + startNeedle.length)
    assertTrue("missing end needle: $endNeedle", end >= 0)
    return substring(start, end)
  }

  private fun Path.exists(): Boolean = Files.exists(this)

  private fun ticketStreamServiceSource(): String = readFirstExisting(
    Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketStreamService.kt"),
    Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketStreamService.kt")
  )

  private fun ticketScreenConfigSource(): String = readFirstExisting(
    Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketScreenConfig.kt"),
    Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketScreenConfig.kt")
  )

  private fun rootFfmpegH264CaptureEngineSource(): String = readFirstExisting(
    Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketRootFfmpegH264CaptureEngine.kt"),
    Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketRootFfmpegH264CaptureEngine.kt")
  )

  private fun rootSurfaceCaptureMainSource(): String = readFirstExisting(
    Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketRootSurfaceCaptureMain.java"),
    Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketRootSurfaceCaptureMain.java")
  )

  private fun androidManifestSource(): String = readFirstExisting(
    Path.of("app/src/main/AndroidManifest.xml"),
    Path.of("src/main/AndroidManifest.xml")
  )

  private fun ticketSourcePath(fileName: String): Path {
    return listOf(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/$fileName"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/$fileName")
    ).firstOrNull { Files.exists(it) } ?: Path.of("missing-$fileName")
  }

  private fun readFirstExisting(vararg paths: Path): String {
    val path = paths.firstOrNull { Files.exists(it) } ?: error("missing source file: ${paths.joinToString()}")
    return String(Files.readAllBytes(path), Charsets.UTF_8)
  }
}
