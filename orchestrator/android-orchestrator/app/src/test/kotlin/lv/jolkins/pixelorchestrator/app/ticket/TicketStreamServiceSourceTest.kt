package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class TicketStreamServiceSourceTest {
  @Test
  fun publicStreamStartupUsesRootHardwareH264ByDefault() {
    val source = ticketStreamServiceSource()
    val start = source.substringBetween("private suspend fun startTicketSessionLocked()", "private fun hardwareMarkedUnreliable")

    assertTrue(start.contains("rootHardwareH264CaptureEngine.probe()"))
    assertTrue(start.contains("activeCaptureMode = CAPTURE_MODE_ROOT_HARDWARE_H264"))
    assertTrue(start.contains("enableSecureWindowCaptureBypass()"))
    assertTrue(start.contains("scheduleRootHardwareH264CaptureStart(\"session_start_root_hardware_h264_capture\""))
    assertTrue(start.contains("state = \"hardware_h264_unavailable\""))
    assertTrue(start.contains("Hardware H.264 ticket stream is unavailable; stream was not started"))
    assertTrue(start.contains("recordTicketEvent(\"session_capture_mode_selected\""))
    assertFalse(start.contains("selectProductionCaptureMode("))
    assertFalse(start.contains("CAPTURE_MODE_ROOT_FFMPEG_H264"))
    assertFalse(start.contains("scheduleRootFfmpegH264CaptureStart("))
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
  fun hardwareProfileIsFixedForReadableLowLatencyH264() {
    val source = ticketStreamServiceSource()
    val config = ticketScreenConfigSource()
    val engine = rootHardwareH264CaptureEngineSource()
    val helper = rootHardwareH264CaptureMainSource()

    assertTrue(config.contains("const val ROOT_HARDWARE_H264_CAPTURE_MODE = \"root_hardware_h264\""))
    assertTrue(config.contains("const val ROOT_HARDWARE_H264_TARGET_WIDTH = 720"))
    assertTrue(config.contains("const val ROOT_HARDWARE_H264_QUALITY_PROFILE = \"hardware_h264_light_marker_low_latency\""))
    assertTrue(config.contains("const val ROOT_HARDWARE_H264_FPS = 8"))
    assertTrue(config.contains("const val ROOT_HARDWARE_H264_STEADY_FPS = 4"))
    assertTrue(config.contains("const val ROOT_HARDWARE_H264_BURST_HOLD_MILLIS = 6_000L"))
    assertTrue(config.contains("const val ROOT_HARDWARE_H264_BITRATE = 1_200_000"))
    assertTrue(config.contains("const val ROOT_HARDWARE_H264_KEYFRAME_INTERVAL_MILLIS = 1000"))
    assertTrue(config.contains("const val ROOT_HARDWARE_H264_COLOR_CORRECTION = \"red_blue_swap_gpu_paint\""))
    assertTrue(config.contains("const val ROOT_HARDWARE_H264_COLOR_STANDARD = \"bt709_limited_sdr\""))
    assertTrue(engine.contains("TicketRootHardwareH264CaptureMain"))
    assertTrue(helper.contains("MediaCodec.createEncoderByType(\"video/avc\")"))
    assertTrue(helper.contains("MediaFormat.KEY_COLOR_FORMAT"))
    assertTrue(helper.contains("MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface"))
    assertTrue(helper.contains("MediaFormat.KEY_PRIORITY"))
    assertTrue(helper.contains("MediaFormat.KEY_LATENCY"))
    assertTrue(helper.contains("MediaFormat.KEY_OPERATING_RATE"))
    assertTrue(helper.contains("MediaFormat.KEY_BITRATE_MODE"))
    assertTrue(helper.contains("MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR"))
    assertTrue(helper.contains("MediaFormat.KEY_COLOR_STANDARD"))
    assertTrue(helper.contains("MediaFormat.COLOR_STANDARD_BT709"))
    assertTrue(helper.contains("MediaFormat.KEY_COLOR_RANGE"))
    assertTrue(helper.contains("MediaFormat.COLOR_RANGE_LIMITED"))
    assertTrue(helper.contains("MediaFormat.KEY_COLOR_TRANSFER"))
    assertTrue(helper.contains("MediaFormat.COLOR_TRANSFER_SDR_VIDEO"))
    assertTrue(helper.contains("MediaFormat.KEY_I_FRAME_INTERVAL"))
    assertTrue(helper.contains("allKeyFrames ? 0"))
    assertTrue(helper.contains("if (allKeyFrames || explicitSyncFrame)"))
    assertTrue(helper.contains("--steady-fps"))
    assertTrue(helper.contains("--burst-hold-millis"))
    assertTrue(helper.contains("AtomicLong burstUntilMillis"))
    assertTrue(helper.contains("currentTargetFps"))
    assertTrue(helper.contains("fps_target="))
    assertTrue(helper.contains("cmd.equals(\"burst\")"))
    assertTrue(engine.contains("fields[\"fps_target\"]"))
    assertTrue(engine.contains("\"burst\\n\""))
    assertFalse(source.contains("hardware_h264_non_key_frame_dropped"))
    assertTrue(source.contains("hardware_h264_waiting_initial_key_frame"))
    assertTrue(source.contains("!frame.keyFrame && latestKeyFrameEnvelope == null"))
    assertTrue(source.contains("const keyFrameOnlyLatestVideo = true;"))
    assertTrue(helper.contains("ColorMatrixColorFilter"))
    assertTrue(helper.contains("hardwareColorCorrectionPaint()"))
    assertTrue(helper.contains("drawBitmap(source, sourceCrop, destination, paint)"))
    assertTrue(helper.contains("VISIBILITY_SAMPLE_WIDTH = 12"))
    assertTrue(helper.contains("VISIBILITY_SAMPLE_HEIGHT = 20"))
    assertTrue(helper.contains("VISIBILITY_PROBE_INTERVAL_MILLIS = 2_000L"))
    assertFalse("hardware encoder should not clear the full encoder surface before every full-frame draw", helper.contains("canvas.drawColor(Color.BLACK);\n      canvas.drawBitmap(source, sourceCrop, destination, paint)"))
    assertTrue(helper.contains("SecureScreenCapture"))
    assertTrue(helper.contains("ScreenCapture${'$'}ScreenCaptureParams"))
    assertTrue(helper.contains("writeAnnexB"))
    assertTrue(helper.contains("ACCESS_UNIT_DELIMITER"))
    assertTrue(helper.contains("output.write(ACCESS_UNIT_DELIMITER);"))
    assertTrue(helper.contains("BUFFER_FLAG_KEY_FRAME"))
    assertFalse("hardware stream should not use CPU copies for encoder input", helper.contains("drawBitmap(inputSurface, source.copy"))
    assertFalse("hardware stream should not fall back to a software canvas in normal capture", helper.contains("lockCanvas(null)"))
    assertTrue(config.contains("val colorCorrection: String = TicketScreenConfig.ROOT_HARDWARE_H264_COLOR_CORRECTION"))
    assertTrue(config.contains("val colorStandard: String = TicketScreenConfig.ROOT_HARDWARE_H264_COLOR_STANDARD"))
    assertFalse(config.contains("const val ROOT_FFMPEG_H264_CAPTURE_MODE"))
  }

  @Test
  fun streamFreshnessContractDoesNotAllowTwoSecondOldFramesAsLive() {
    val source = ticketStreamServiceSource()
    val verdict = source.substringBetween("private fun streamVerdict", "private fun clientConnectionSnapshot")
    val companion = source.substringBetween("companion object", "private const val TICKET_WAKE_BUDGET_MILLIS")

    assertTrue(verdict.contains("it <= LIVE_FRAME_MAX_AGE_MILLIS"))
    assertFalse(verdict.contains("it <= 2_500L"))
    assertTrue(companion.contains("private const val LIVE_FRAME_MAX_AGE_MILLIS = 2_000L"))
    assertTrue(companion.contains("private const val VIDEO_CLIENT_PENDING_MAX_AGE_MILLIS = 150L"))
    assertTrue(companion.contains("private const val VIDEO_CLIENT_SLOW_CLOSE_MILLIS = 250L"))
  }

  @Test
  fun healthCleanupDoesNotKillFreshStartupClientsBeforeFirstFrame() {
    val source = ticketStreamServiceSource()
    val accept = source.substringBetween("private suspend fun acceptWebSocket", "private suspend fun handleClientCommand")
    val cleanup = source.substringBetween("private fun cleanupInactiveClientsIfNeeded", "private fun totalClientCount")

    assertTrue(accept.contains("extendStartupDisconnectGrace()"))
    assertTrue(accept.indexOf("extendStartupDisconnectGrace()") < accept.indexOf("closeDuplicateViewerClients(info)"))
    assertTrue(cleanup.contains("val startupClientGraceActive = startupDisconnectGraceUntilMillis > SystemClock.elapsedRealtime()"))
    assertTrue(cleanup.contains("startupClientGraceActive"))
    assertTrue(cleanup.contains("inactive_stream_cleanup_deferred"))
    assertTrue(cleanup.indexOf("startupClientGraceActive") < cleanup.indexOf("closeAllClients(\"inactive_stream_${'$'}reason\")"))
  }

  @Test
  fun hardwareProductionPathDoesNotPromoteSoftwareFallback() {
    val source = ticketStreamServiceSource()
    val start = source.substringBetween("private suspend fun startTicketSessionLocked()", "private fun hardwareMarkedUnreliable")
    val ensure = source.substringBetween("private fun ensureEncoderIfPossible()", "private fun ensureRootHardwareH264CaptureIfPossible")
    val restart = source.substringBetween("private fun restartActiveStreamEngine", "private fun configMessage")
    val health = source.substringBetween("private fun health()", "private fun launchVivi")

    assertTrue(start.contains("CAPTURE_MODE_ROOT_HARDWARE_H264"))
    assertTrue(start.contains("activeCaptureMode = CAPTURE_MODE_ROOT_HARDWARE_H264"))
    assertTrue(start.contains("state = \"hardware_h264_unavailable\""))
    assertTrue(start.contains("recordTicketEvent(\"session_capture_mode_selected\""))
    assertFalse(start.contains("selectProductionCaptureMode("))
    assertFalse(start.contains("CAPTURE_MODE_ROOT_FFMPEG_H264"))
    assertFalse(start.contains("hardware_reliability_fallback_to_software"))
    assertFalse(start.contains("scheduleRootFfmpegH264CaptureStart("))
    assertFalse(source.contains("hardware_reliability_fallback_to_software"))
    assertFalse(source.contains("optimized software H.264 capture"))
    assertTrue(ensure.contains("activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264 && hardwareCaptureVerified"))
    assertFalse(ensure.contains("CAPTURE_MODE_ROOT_FFMPEG_H264"))
    assertFalse(ensure.contains("ensureRootFfmpegH264CaptureIfPossible"))
    assertTrue(restart.contains("CAPTURE_MODE_ROOT_HARDWARE_H264"))
    assertFalse(restart.contains("CAPTURE_MODE_ROOT_FFMPEG_H264"))
    assertFalse(restart.contains("ensureRootFfmpegH264CaptureIfPossible"))
    assertTrue(health.contains("val ok = running.get() && vivi && hardwareCapture.available"))
    assertFalse(health.contains("|| ffmpegCapture.available"))
  }

  @Test
  fun ffmpegRawAndPngCaptureHelpersAreRemovedFromProduction() {
    val source = ticketStreamServiceSource()
    val config = ticketScreenConfigSource()

    listOf(
      "TicketRootFfmpegH264CaptureEngine.kt",
      "TicketRootSurfaceCaptureMain.java",
      "TicketRootCroppedPngCaptureMain.java"
    ).forEach { assertFalse("$it should be removed", ticketSourcePath(it).exists()) }

    listOf(
      "rootFfmpegH264CaptureEngine",
      "ensureRootFfmpegH264CaptureIfPossible",
      "handleRootFfmpegH264CaptureFrame",
      "scheduleRootFfmpegH264CaptureStart",
      "startControlCodePngCaptureDirect",
      "encodeControlCodeImageBase64",
      "TicketRootSurfaceCaptureMain",
      "TicketRootCroppedPngCaptureMain"
    ).forEach { assertFalse("TicketStreamService still contains $it", source.contains(it)) }

    assertFalse(config.contains("ROOT_FFMPEG_H264"))
    assertTrue(config.contains("data class TicketFfmpegHealth("))
    assertTrue(config.contains("val mode: String = \"removed\""))
    assertTrue(config.contains("val state: String = \"unavailable\""))
  }

  @Test
  fun hardwareH264HelperDrainsAfterFirstStartupInput() {
    val helper = rootHardwareH264CaptureMainSource()

    assertTrue(helper.contains("int startupBurstFrames = Math.max(4, fps);"))
    assertTrue(helper.contains("int startupPrimeInputs = 1;"))
    assertTrue(helper.contains("int inputPosts = sent == 0 ? startupPrimeInputs : 1;"))
    assertTrue(helper.contains("for (int post = 0; post < inputPosts; post++)"))
    assertTrue(helper.indexOf("CapturedFrame source = capture.capture();") < helper.indexOf("for (int post = 0; post < inputPosts; post++)"))
    assertFalse("startup priming must not do multiple slow screen captures before first encoder output", helper.contains("for (int post = 0; post < inputPosts; post++) {\n        CapturedFrame source = capture.capture();"))
    assertTrue(helper.contains("int drained = drainEncoder(encoder, output, false, drainTimeoutUs);"))
    assertTrue(helper.indexOf("writeAnnexB(output, data);") < helper.indexOf("output.write(ACCESS_UNIT_DELIMITER);"))
    assertTrue(helper.contains("if (sent <= startupBurstFrames && drained == 0) {"))
    assertTrue(helper.contains("sleep = 0L;"))
  }

  @Test
  fun hardwareH264UsesSecureScreenCaptureWithPrivateContentVisible() {
    val config = ticketScreenConfigSource()
    val helper = rootHardwareH264CaptureMainSource()
    val engine = rootHardwareH264CaptureEngineSource()

    assertTrue(config.contains("const val ROOT_HARDWARE_H264_CAPTURE_SOURCE = \"root_display_capture\""))
    assertTrue(config.contains("const val ROOT_HARDWARE_H264_CAPTURE_METHOD = \"app_process_mediacodec_surface_secure_screen_capture\""))
    assertTrue(helper.contains("SurfaceCapture capture = new SecureScreenCapture(sourceWidth, sourceHeight);"))
    assertTrue(helper.contains("ScreenCapture${'$'}ScreenCaptureParams"))
    assertTrue(helper.contains("setSecureContentPolicy"))
    assertTrue(helper.contains("setProtectedContentPolicy"))
    assertTrue(helper.contains("setIncludeSystemOverlays"))
    assertTrue(helper.contains("setPreserveDisplayColors"))
    assertTrue(helper.contains("getDeclaredMethod(\n        \"capture\""))
    assertTrue(helper.contains("frameLooksVisible(source.bitmap, sourceCrop)"))
    assertTrue(helper.contains("boolean shouldProbeVisibility"))
    assertTrue(helper.contains("source.copy(Bitmap.Config.ARGB_8888, false)"))
    assertTrue(helper.contains("secure_screen_capture_blocked_or_blank"))
    assertFalse("production hardware stream must not use the old unoptimized helper", helper.contains("new ModernScreenCapture"))
    assertFalse("production hardware stream must not use unavailable direct display token APIs", helper.contains("getPhysicalDisplayToken"))
    assertFalse("production hardware stream must not use unavailable DisplayCaptureArgs APIs", helper.contains("DisplayCaptureArgs"))
    assertTrue(config.contains("val secureLayerCaptureEnabled: Boolean = true"))
    assertTrue(config.contains("val protectedContentCaptureEnabled: Boolean = true"))
    assertTrue(config.contains("val lastCaptureDurationMillis: Long? = null"))
    assertTrue(config.contains("val lastDrawDurationMillis: Long? = null"))
    assertTrue(config.contains("val lastEncodeDurationMillis: Long? = null"))
    assertTrue(config.contains("val lastVisibilityCheckResult: String = \"not_run\""))
    assertTrue(helper.contains("\"METRIC capture_ms=\""))
    assertTrue(helper.contains("\" draw_ms=\""))
    assertTrue(helper.contains("\" encode_ms=\""))
    assertTrue(engine.contains("fields[\"capture_ms\"]"))
    assertTrue(engine.contains("fields[\"draw_ms\"]"))
    assertTrue(engine.contains("fields[\"encode_ms\"]"))
    assertTrue(engine.contains("lastCaptureDurationMillis"))
    assertTrue(engine.contains("blankFrameFailures"))
  }

  @Test
  fun recentVisibleSecureCaptureProbeIsReusedWithoutAnotherRootProbe() {
    val source = ticketStreamServiceSource()
    val scheduler = source.substringBetween("private fun scheduleRootHardwareSecureCaptureProbe", "private fun requestKeyFrame")

    assertTrue(source.contains("SECURE_CAPTURE_VISIBLE_PROBE_REUSE_MILLIS"))
    assertTrue(source.contains("lastRootH264VisibleProbePassedAtMillis"))
    assertTrue(scheduler.contains("recentVisibleSecureCaptureProbeStillFresh(nowMillis)"))
    assertTrue(scheduler.contains("secure_capture_probe_recent_visible_reused"))
    assertTrue(scheduler.indexOf("recentVisibleSecureCaptureProbeStillFresh(nowMillis)") < scheduler.indexOf("rootH264BlankProbeJob = serviceScope.launch"))
  }

  @Test
  fun normalHardwareStartupDoesNotWaitForSeparateSurfaceProbe() {
    val source = ticketStreamServiceSource()
    val start = source.substringBetween("private fun scheduleRootHardwareH264CaptureStart", "private suspend fun verifyRootHardwareSecureCaptureVisible")
    val frame = source.substringBetween("private fun handleRootHardwareH264CaptureFrame", "private fun scheduleRootHardwareSecureCaptureProbe")
    val verify = source.substringBetween("private suspend fun verifyRootHardwareSecureCaptureVisible", "private fun scheduleTicketRecovery")

    assertFalse("normal hardware startup should not run the slow one-shot surface probe before encoder start", start.contains("verifyRootHardwareSecureCaptureVisible(reason)"))
    assertFalse("normal hardware startup should not stop before first frame on a separate secure-capture probe", start.contains("secure_capture_blocked"))
    assertFalse("ticket-screen readiness failures must not mark hardware unreliable", start.contains("noteHardwareReliabilityFailure(\"secure_capture_blocked"))
    assertTrue("hardware startup frames should be allowed only after wake/root proof succeeds", start.indexOf("if (!prepareResult.success)") < start.indexOf("hardwareFrameBroadcastAllowed = true"))
    assertTrue("hardware stream should start after wake so helper startup does not race the locked/home screen", start.indexOf("prepareViviForRootHardwareH264Capture(reason, wakeStartedAtMillis)") < start.indexOf("ensureEncoderIfPossible()"))
    assertFalse("hardware stream should no longer prewarm before wake readiness", start.contains("vivi_launch_encoder_prewarm"))
    assertTrue(start.contains("hardware_h264_wake_frames_allowed"))
    assertTrue("startup frames must not mark the session live before raw-ticket proof", frame.contains("&& hardwareCaptureVerified"))
    assertTrue(start.contains("hardwareCaptureVerified = true"))
    assertTrue(start.contains("ensureEncoderIfPossible()"))
    assertTrue(start.contains("waiting_first_visible_frame"))
    assertTrue(verify.contains("verifyViviTicketDetailHierarchy(reason)"))
    assertTrue(verify.contains("rootHardwareH264CaptureEngine.snapshot()"))
    assertFalse(verify.contains("captureRootSurfaceProbeRaw("))
  }

  @Test
  fun hardwareStartupHasFastRecentTicketDetailWakePath() {
    val source = ticketStreamServiceSource()
    val fast = source.substringBetween("private suspend fun fastWakeReadyFromRecentTicketDetail", "private fun remainingWakeBudgetMillis")
    val start = source.substringBetween("private fun scheduleRootHardwareH264CaptureStart", "private suspend fun verifyRootHardwareSecureCaptureVisible")

    assertTrue(source.contains("TICKET_WAKE_MEMORY_TICKET_DETAIL_MAX_AGE_MILLIS"))
    assertTrue(fast.contains("viviStateMemory.recentTicketDetailWithin(TICKET_WAKE_MEMORY_TICKET_DETAIL_MAX_AGE_MILLIS)"))
    assertTrue(fast.contains("focusedWindowSnapshot()"))
    assertTrue(fast.contains("focused.contains(TicketScreenConfig.VIVI_PACKAGE)"))
    assertTrue(fast.contains("wake_recent_ticket_detail_fast_ready"))
    assertTrue(start.contains("val fastWakeReady = fastWakeReadyFromRecentTicketDetail(reason, wakeStartedAtMillis)"))
    assertTrue(start.contains("if (fastWakeReady == null)"))
    assertTrue(start.contains("launchViviForWake(reason)"))
    assertTrue(start.indexOf("fastWakeReadyFromRecentTicketDetail") < start.indexOf("launchViviForWake(reason)"))
    assertTrue(start.contains("fastWakeReady ?: prepareViviForRootHardwareH264Capture"))
  }

  @Test
  fun hardwareEncoderReusesVerifiedCleanStopForNextFastStart() {
    val engine = rootHardwareH264CaptureEngineSource()
    val service = ticketStreamServiceSource()
    val onCreate = service.substringBetween("override fun onCreate()", "override fun onStartCommand")
    val loop = engine.substringBetween("private suspend fun runCaptureLoop", "private fun readEncoderOutput")
    val probe = engine.substringBetween("suspend fun probe", "fun start")
    val cleanup = engine.substringBetween("private suspend fun cleanupMatchingProcesses", "private fun cleanupMatchingProcessesDirect")
    val command = engine.substringBetween("private fun rootEncoderCommand", "private fun readStderrTail")

    assertTrue(onCreate.contains("rootHardwareH264CaptureEngine.cleanupStaleProcesses()"))
    assertTrue(onCreate.indexOf("rootHardwareH264CaptureEngine.cleanupStaleProcesses()") < onCreate.indexOf("rootHardwareH264CaptureEngine.probe()"))
    assertTrue(engine.contains("cleanStopReadyForNextStart"))
    assertTrue(engine.contains("consumeCleanStopForFastStart()"))
    assertTrue(engine.contains("ticket_hardware_h264_fast_start_reused_clean_stop"))
    assertTrue(engine.contains("helperClasspath"))
    assertTrue(engine.contains("resolveHelperClasspath()"))
    assertTrue(probe.contains("helperClasspath = resolveHelperClasspath() ?: helperClasspath"))
    assertTrue(loop.contains("helperClasspath = helperClasspath ?: resolveHelperClasspath()"))
    assertTrue(probe.indexOf("resolveHelperClasspath()") < probe.indexOf("probeCaptureHelper(sourceWidth, sourceHeight)"))
    assertTrue(command.contains("val cachedClasspath = helperClasspath"))
    assertTrue(command.contains("CLASSPATH=${'$'}cachedClasspath app_process"))
    assertFalse("hardware encoder startup must not block on a pre-start process scan", loop.contains("reconcileStaleCaptureProcessesBeforeStart()"))
    assertTrue(loop.contains("schedulePostStartProcessSanityCheck()"))
    assertTrue(loop.indexOf("ProcessBuilder(\"su\", \"-c\", rootEncoderCommand") < loop.indexOf("schedulePostStartProcessSanityCheck()"))
    assertTrue(engine.contains("private fun schedulePostStartProcessSanityCheck()"))
    assertTrue(cleanup.contains("if (!wanted)"))
    assertTrue(cleanup.contains("cleanStopReadyForNextStart = true"))
    assertTrue(cleanup.contains("cleanStopReadyForNextStart = false"))
  }

  @Test
  fun cleanHardwareEncoderStopsDoNotCountAsReliabilityFailures() {
    val source = ticketStreamServiceSource()
    val handler = source.substringBetween("private fun handleRootHardwareH264CaptureStateChanged", "private fun noteHardwareReliabilityFailure")
    val classifier = source.substringBetween("private fun unexpectedHardwareEncoderRestart", "private fun noteHardwareReliabilityFailure")

    assertTrue(handler.contains("unexpectedHardwareEncoderRestart(health)"))
    assertTrue(handler.contains("hardware_encoder_restart_ignored"))
    assertTrue(classifier.contains("hardware_encoder_exit_0"))
    assertTrue(classifier.contains("hardware_encoder_exit_143"))
    assertTrue(classifier.contains("return false"))
  }

  @Test
  fun firstViewerStartsHardwareEncoderAndLastViewerStopsIt() {
    val source = ticketStreamServiceSource()
    val websocket = source.substringBetween("private suspend fun acceptWebSocket", "private suspend fun startTicketSession")
    val detach = source.substringBetween("private suspend fun noteClientDetached", "private suspend fun stopTicketSession")

    assertTrue(websocket.contains("if (video) {"))
    assertTrue(websocket.contains("ensureEncoderIfPossible()"))
    assertTrue(websocket.contains("sendConfigAndWarmStart(client, size)"))
    assertTrue(detach.contains("rootHardwareH264CaptureEngine.stop(reason)"))
    assertTrue(detach.contains("resetFrameEpoch(\"client_detached_${'$'}reason\", active = false)"))
    assertFalse(detach.contains("stopProjection()"))
  }

  @Test
  fun stoppedStreamDoesNotCloseFreshViewerSocketsDuringSessionStart() {
    val source = ticketStreamServiceSource()
    val start = source.substringBetween("private suspend fun startTicketSession()", "private suspend fun startTicketSessionLocked")
    val cleanup = source.substringBetween("private fun cleanupInactiveClientsIfNeeded", "private fun totalClientCount")
    val health = source.substringBetween("private fun health()", "private fun launchVivi")

    assertTrue(start.contains("withTimeoutOrNull(SESSION_START_TIMEOUT_MILLIS)"))
    assertFalse(start.contains("cleanupInactiveClientsIfNeeded(\"session_start_preflight\")"))
    assertFalse(start.contains("cleanupInactiveClientsIfNeeded(\"session_start_locked\")"))
    assertTrue(start.contains("cleanupInactiveClientsIfNeeded(\"session_start_timeout\")"))
    assertTrue(start.contains("state = \"start_timeout\""))
    assertTrue(cleanup.contains("closeAllClients(\"inactive_stream_${'$'}reason\")"))
    assertTrue(health.contains("cleanupInactiveClientsIfNeeded(\"health\")"))
  }

  @Test
  fun httpBrowserStopDoesNotStopWhenFreshClientsAreConnected() {
    val source = ticketStreamServiceSource()
    val handler = source.substringBetween("private suspend fun handleBrowserStopRequest", "private suspend fun noteClientDetached")

    assertTrue(handler.contains("val activeClients = totalClientCount()"))
    assertTrue(handler.contains("if (activeClients > 0)"))
    assertTrue(handler.contains("session_stop_ignored_active_clients"))
    assertTrue(handler.contains("TicketSessionResponse(ok = true, state = ticketSessionState, message = lastMessage)"))
    assertTrue(handler.indexOf("if (activeClients > 0)") < handler.indexOf("stopTicketSession(TicketSessionStopPolicy.BROWSER_EXPLICIT_STOP)"))
  }

  @Test
  fun rootCaptureIsScheduledBeforeSlowSessionHousekeeping() {
    val source = ticketStreamServiceSource()
    val start = source.substringBetween("private suspend fun startTicketSession()", "private suspend fun handleBrowserStopRequest")
    val maintenance = source.substringBetween("private fun scheduleDeferredSessionStartMaintenance", "private fun hardwareMarkedUnreliable")

    assertTrue(start.contains("scheduleDeferredSessionStartMaintenance(\"session_start\")"))
    assertTrue(start.indexOf("scheduleRootHardwareH264CaptureStart") < start.indexOf("scheduleDeferredSessionStartMaintenance(\"session_start\")"))
    assertTrue(maintenance.contains("frameSequence == 0L"))
    assertTrue(maintenance.contains("STARTUP_MAINTENANCE_DEFER_MILLIS"))
    assertTrue(maintenance.contains("enableNotificationLockdown(reason)"))
    assertTrue(maintenance.contains("enableSecureWindowCaptureBypass()"))
    assertTrue(maintenance.indexOf("frameSequence == 0L") < maintenance.indexOf("enableNotificationLockdown(reason)"))
  }

  @Test
  fun rootHardwareH264EncoderStartsOnlyAfterWakeReady() {
    val source = ticketStreamServiceSource()
    val start = source.substringBetween("private fun scheduleRootHardwareH264CaptureStart", "private suspend fun verifyRootHardwareSecureCaptureVisible")

    val wake = start.indexOf("wakeTicketScreenForSessionStart(reason, wakeStartedAtMillis)")
    val prepare = start.indexOf("val prepareResult =")
    val failedPrepareGuard = start.indexOf("if (!prepareResult.success)")
    val verified = start.indexOf("hardwareCaptureVerified = true")
    val encoderStart = start.indexOf("ensureEncoderIfPossible()")
    val readyKeyframe = start.indexOf("requestKeyFrame(\"vivi_ready_encoder_start:${'$'}reason\")")

    assertTrue(wake >= 0)
    assertTrue(prepare > wake)
    assertTrue(failedPrepareGuard > prepare)
    assertTrue(verified > failedPrepareGuard)
    assertTrue("hardware encoder must start only after the wake/recovery path confirms ticket detail", encoderStart > verified)
    assertTrue(readyKeyframe > encoderStart)
    assertFalse(
      "long-pause session start must not prewarm the encoder while the phone may still be locked/home/blank",
      start.substring(0, failedPrepareGuard).contains("ensureRootHardwareH264CaptureIfPossible()") ||
        start.substring(0, failedPrepareGuard).contains("ensureEncoderIfPossible()") ||
        start.substring(0, failedPrepareGuard).contains("vivi_launch_encoder_prewarm")
    )
  }

  @Test
  fun hardwareKeyframeRequestsQueueDuringHelperStartup() {
    val engine = rootHardwareH264CaptureEngineSource()
    val request = engine.substringBetween("fun requestKeyFrame(reason: String)", "private fun writeHardwareKeyFrameRequest")
    val flush = engine.substringBetween("private fun flushPendingKeyFrameRequest", "suspend fun cleanupStaleProcesses")
    val loop = engine.substringBetween("private suspend fun runCaptureLoop", "private fun readEncoderOutput")

    assertTrue(engine.contains("pendingKeyFrameReason"))
    assertTrue(request.contains("if (!writeHardwareKeyFrameRequest(reason))"))
    assertTrue(request.contains("pendingKeyFrameReason = reason"))
    assertTrue(flush.contains("ticket_hardware_h264_pending_keyframe_flush"))
    assertTrue(flush.contains("writeHardwareKeyFrameRequest(\"${'$'}reason:${'$'}pending\")"))
    assertTrue(loop.contains("flushPendingKeyFrameRequest(\"encoder_started\")"))
    assertTrue(loop.indexOf("encoderProcess = localEncoder") < loop.indexOf("flushPendingKeyFrameRequest(\"encoder_started\")"))
    assertTrue(loop.indexOf("flushPendingKeyFrameRequest(\"encoder_started\")") < loop.indexOf("readEncoderOutput(localEncoder.inputStream, parser)"))
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
    assertTrue(source.contains("control_code_request_control_exit_cleanup"))
    assertTrue(source.contains("completeVerifiedTicketDetailControlExitCleanup"))
    assertTrue(source.contains("controlExitCleanupJob"))
    assertTrue(source.contains("scheduleControlExitCleanup"))
    assertTrue(source.contains("runControlExitCleanup"))
    assertTrue(source.contains("tryControlExitDirectCloseFromHierarchy"))
    assertTrue(source.contains("control_exit_direct_close"))
    assertTrue(source.contains("CONTROL_EXIT_RECENT_DIRECT_CLOSE_MILLIS"))
    assertFalse(source.contains("control_exit_surface_clean_after_direct_close"))
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
    assertFalse(source.contains("control_exit_initial_surface"))
    assertFalse(source.contains("captureRootSurfaceProbeRaw("))
    assertTrue(source.contains("touch-action: pan-y"))
    assertTrue(source.contains("scroll-snap-type: y proximity"))
    assertTrue(source.contains("gesturechange"))
    assertTrue(source.contains("dblclick"))
    assertTrue(source.contains("streamVerticalPanThresholdPx"))
  }

  @Test
  fun generatedControlCodeCommandRunsAutomatedPhoneFlow() {
    val source = ticketStreamServiceSource()

    assertTrue(source.contains("\"prepare_control_code\" -> {"))
    assertTrue(source.contains("handlePrepareControlCode(reason)"))
    assertTrue(source.contains("private suspend fun handlePrepareControlCode(reason: String)"))
    assertTrue(source.contains("control_code_prepare"))
    assertTrue(source.contains("\"generate_control_code\" -> {"))
    assertTrue(source.contains("handleGenerateControlCode(client, requestId, digits, app, flow, resultImage)"))
    assertTrue(source.contains("CONTROL_CODE_REQUEST_DIGITS_REGEX"))
    assertTrue(source.contains("controlCodeRequestMutex.withLock"))
    assertTrue(source.contains("sendCachedControlCodeResult(cleanRequestId)"))
    assertTrue(source.contains("runFastControlCodeDeliveryForRequest(cleanDigits, phases, startedAtMillis)"))
    assertTrue(source.contains("openControlCodePopupFastForRequest(phases, requestStartedAtMillis)"))
    assertTrue(source.contains("openControlCodePopupImmediateForRequest(phases, requestStartedAtMillis)"))
    assertTrue(source.contains("openControlCodePopupFromVerifiedStateFastForRequest(phases, requestStartedAtMillis)"))
    val fastOpen = source.substringBetween("private suspend fun openControlCodePopupFastForRequest", "private suspend fun openControlCodePopupImmediateForRequest")
    val fastOpenFull = source.substringBetween("private suspend fun openControlCodePopupFastForRequest", "private suspend fun openControlCodePopupFromVerifiedStateFastForRequest")
    assertTrue(fastOpenFull.contains("control_code_phone_not_ready"))
    assertFalse("default request path should not spend time in recovery", fastOpen.contains("prepareViviForControlCodeRequest(phases)"))
    assertFalse("default request path should not run preflight cleanup", fastOpen.contains("pre_request_surface_cleanup"))
    assertTrue(source.contains("controlCodeImmediateStartDecision()"))
    assertTrue(source.contains("CONTROL_CODE_IMMEDIATE_TICKET_DETAIL_MEMORY_MAX_AGE_MILLIS"))
    assertTrue(source.contains("CONTROL_CODE_STALE_PREPARE_TICKET_DETAIL_MEMORY_MAX_AGE_MILLIS = TICKET_WAKE_MEMORY_TICKET_DETAIL_MAX_AGE_MILLIS"))
    assertTrue(source.contains("control_code_button_immediate_stale_prepare_ticket_detail"))
    val geometry = source.substringBetween("private fun controlCodeGeometryTarget", "private suspend fun controlCodeButtonSnapTarget")
    assertTrue(geometry.contains("size.sourceWidth *"))
    assertTrue(geometry.contains("size.sourceHeight *"))
    assertFalse("fast control-code tap must not double-apply the cropped stream offset", geometry.contains("size.sourceY(encodedY)"))
    assertTrue(source.contains("enterControlCodeDigitsFastForRequest(cleanDigits, transaction, phases, requestStartedAtMillis)"))
    assertTrue(source.contains("tapControlCodeSubmitFastForRequest(transaction, cleanDigits, phases, requestStartedAtMillis)"))
    assertTrue(source.contains("waitForGeneratedControlCodeResultAfterSubmit("))
    assertTrue(source.contains("sendTicketStateEvent("))
    assertFalse(source.contains("sendControlCodeFrameReady("))
    assertFalse(source.contains("\"control_code_frame_ready\""))
    assertFalse(source.contains("\"control_code_browser_capture\" -> {"))
    assertFalse(source.contains("handleControlCodeBrowserCaptureAck("))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"request_gate_passed\", startedAtMillis)"))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"first_phone_tap\", requestStartedAtMillis)"))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"popup_ready\", requestStartedAtMillis)"))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"digits_typed\", requestStartedAtMillis)"))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"ok_tapped\", requestStartedAtMillis)"))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"result_first_visible\", requestStartedAtMillis)"))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"result_marker_requested\", requestStartedAtMillis)"))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"close_tap_sent\", requestStartedAtMillis)"))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"raw_ticket_fast_proof\", requestStartedAtMillis)"))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"phone_raw_recovered\", requestStartedAtMillis)"))
    assertTrue(source.contains("sendControlCodeResult("))
    assertTrue(source.contains("type\", \"control_code_result\""))
    assertTrue(source.contains("cleanupPending\", cleanupPending"))
    assertTrue(source.contains("sendControlCodeCleanup("))
    assertTrue(source.contains("type\", \"control_code_cleanup_complete\""))
    assertTrue(source.contains("CONTROL_CODE_RESULT_CACHE_TTL_MILLIS"))
    assertTrue(source.contains("RECENT_CONTROL_CODE_RESULT_CACHE_SIZE = 6"))
    assertTrue(source.contains("pruneRecentControlCodeResultsLocked(nowMillis)"))
    val health = source.substringBetween("private fun health()", "private fun launchVivi")
    assertTrue("control-code health must not publish the private generated value", health.contains("value = null"))
    assertFalse("control-code health must not publish the private generated value", health.contains("value = lastControlCodeRequestValue"))
    assertTrue(source.contains("controlCodeRequestRootHierarchy(phases"))
    assertTrue(source.contains("recoverTicketDetailForControlCodeRequest("))
    assertTrue(source.contains("request_ticket_detail_recovery"))
    assertTrue(source.contains("control_code_request_foreground_unavailable"))
    assertFalse("control-code request root hierarchy must not prefer accessibility", source.substringBetween("private suspend fun controlCodeRequestRootHierarchy", "private fun controlCodeFastTargetsForHierarchy").contains("snapshotVisibleNodes(TicketScreenConfig.VIVI_PACKAGE)"))
    assertTrue(source.contains("hasControlCodeInputForHierarchy(hierarchy)"))
    val fastFlow = source.substringBetween("private suspend fun runFastControlCodeDeliveryForRequest", "private suspend fun openControlCodePopupFastForRequest")
    assertTrue(fastFlow.contains("tapControlCodeSubmitFastForRequest(transaction, cleanDigits, phases, requestStartedAtMillis)"))
    assertTrue(fastFlow.contains("waitForFreshControlCodeFrameWatermarkForBrowser("))
    assertTrue(fastFlow.contains("reason = \"control_code_marker_ready\""))
    assertTrue(fastFlow.contains("markControlCodeRequestPhase(phases, \"result_marker_requested\", requestStartedAtMillis)"))
    val generateFlow = source.substringBetween("private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    val generateSuccess = generateFlow.substringBetween("if (ok) {", "} else if (delivery.cleanupRequired)")
    assertFalse("ViVi control-code delivery must not capture a PNG/RS artifact before returning raw", fastFlow.contains("captureGeneratedControlCodeImageBytes("))
    assertFalse("ViVi control-code delivery must not wait on a result-image capture phase", fastFlow.contains("result_image_captured"))
    assertFalse("ticket.jolkins/ViVi control-code delivery must never emit Telegram RS-bot image results", generateSuccess.contains("sendRigassatiksmeQrResult("))
    assertFalse("ticket.jolkins/ViVi control-code delivery must not route resultImage into ViVi monthly-ticket capture", generateFlow.contains("runFastViviMonthlyTicketImageDeliveryForRequest"))
    assertTrue(fastFlow.indexOf("waitForGeneratedControlCodeResultAfterSubmit(") < fastFlow.indexOf("reason = \"control_code_marker_ready\""))
    assertTrue(fastFlow.contains("control_code_popup_still_open_after_submit"))
    assertTrue(fastFlow.contains("control_code_submit_retry"))
    assertTrue(fastFlow.contains("submit_retry_attempted"))
    assertTrue(fastFlow.contains("submit_retry_digits_retyped"))
    val waitForResult = source.substringBetween("private suspend fun waitForGeneratedControlCodeResultAfterSubmit", "private suspend fun controlCodeRequestRootHierarchy")
    assertFalse(waitForResult.contains("controlExitGeneratedResultFastScreencapProbe("))
    assertTrue(waitForResult.contains("controlCodeRequestRootHierarchy("))
    assertTrue(waitForResult.contains("wait_generated_result_final_root"))
    assertTrue(waitForResult.contains("control_code_request_result_detected_by_final_root"))
    assertTrue(source.contains("CONTROL_CODE_MARKER_RESULT_HIERARCHY"))
    assertTrue(source.contains("CONTROL_CODE_GENERATED_RESULT_HARD_TIMEOUT_MILLIS = 22_000L"))
    assertTrue(source.contains("CONTROL_CODE_GENERATED_RESULT_HARD_ROOT_DUMP_TIMEOUT_MILLIS = 3_000L"))
    assertTrue(source.contains("CONTROL_CODE_GENERATED_RESULT_FAST_PROBE_MAX_ATTEMPTS = 40"))
    assertTrue(source.contains("CONTROL_CODE_GENERATED_RESULT_FAST_PROBE_INTERVAL_MILLIS = 220L"))
    val fastEnter = source.substringBetween("private suspend fun enterControlCodeDigitsFastForRequest", "private suspend fun tapControlCodeSubmitFastForRequest")
    assertTrue(fastEnter.contains("input tap ${'$'}{transaction.input.x} ${'$'}{transaction.input.y}"))
    assertTrue(fastEnter.contains("input text ${'$'}digits"))
    assertTrue(fastEnter.contains("for i in 1 2 3 4 5 6; do input keyevent KEYCODE_DEL; done"))
    assertFalse("fast digit entry should not spend time deleting more characters than the known control-code field can hold", fastEnter.contains("for i in 1 2 3 4 5 6 7 8 9"))
    assertTrue(fastEnter.contains("control_code_input_typed_submit_now"))
    assertTrue(fastEnter.contains("delay(CONTROL_CODE_DIGITS_TYPED_SUBMIT_SETTLE_MILLIS)"))
    assertTrue(
      "digits_typed should stay on the immediate-submit path after only the short input settle",
      fastEnter.indexOf("delay(CONTROL_CODE_DIGITS_TYPED_SUBMIT_SETTLE_MILLIS)") <
        fastEnter.indexOf("markControlCodeRequestPhase(phases, \"digits_typed\", requestStartedAtMillis)")
    )
    assertFalse("fast digit entry must not add the 500ms Aztec-marker wait before OK", fastEnter.contains("control_code_input_typed_hold_before_submit"))
    assertFalse("fast digit entry must not log the Aztec-marker hold before OK", fastEnter.contains("hold_ms=${'$'}CONTROL_CODE_DIGITS_TYPED_SUBMIT_SETTLE_MILLIS"))
    assertFalse("typed digits should use the short input settle instead of an unrelated root poll delay", fastEnter.contains("delay(CONTROL_CODE_FAST_POLL_MILLIS)"))
    assertFalse("fast digit entry should not wait on root-dump value verification before OK", fastEnter.contains("verifyControlCodeDigitsEnteredFast"))
    assertFalse("fast digit entry should not wait on root-dump popup verification before OK", fastEnter.contains("controlCodeInputStillAvailableFastForRequest"))
    assertFalse("fast digit entry should not abort before OK just because the typed value is not visible in the root dump", fastEnter.contains("control_code_input_verify_failed"))
    assertFalse("fast digit entry should not use accessibility as the primary route", fastEnter.contains("setTextInFocusedInput("))
    assertFalse("fast digit entry should not use accessibility as the primary route", fastEnter.contains("setTextInFirstEditableInput("))
    val fastSubmit = source.substringBetween("private suspend fun tapControlCodeSubmitFastForRequest", "private suspend fun waitForGeneratedControlCodeResultAfterSubmit")
    assertTrue(fastSubmit.contains("input tap ${'$'}{submit.x} ${'$'}{submit.y}"))
    assertTrue(fastSubmit.contains("val submit = resolveControlCodeSubmitAfterDigitsFastForRequest(transaction, phases)"))
    assertTrue(fastSubmit.contains("control_code_submit_target"))
    assertTrue(fastSubmit.contains("ok_tap_target_source_popup_transaction"))
    assertFalse("fast submit must not require a fresh root dump after the popup transaction was built", fastSubmit.contains("resolveRootConfirmedControlCodeSubmitTargetForRequest(phases)"))
    assertFalse("fast submit must not re-resolve the popup via root after digits are typed", fastSubmit.contains("rootConfirmedControlCodeSubmitTargetForRequest(phases"))
    assertFalse("fast submit must not hide the keyboard through an extra proof path before using the transaction OK target", fastSubmit.contains("controlCodeSubmitMayNeedKeyboardDismiss(phases)"))
    assertFalse("fast submit must use the root-confirmed popup submit target, not fixed shifted geometry", fastSubmit.contains("fallbackControlCodeShiftedSubmitAction()"))
    assertFalse("fast submit should not spend time on another root dump before tapping OK", fastSubmit.contains("findControlCodeSubmitActionFastForRequest(phases)"))
    assertTrue(fastSubmit.contains("control_code_submit_attempted"))
    assertFalse("fast submit should not use accessibility click before the coordinate OK tap", fastSubmit.substringBefore("control_code_submit_attempted").contains("PhoneAutomationServiceBridge.clickSelectors("))
    assertTrue(source.contains("verifyControlCodeDigitsEntered(digits, phases)"))
    assertTrue(source.contains("rememberControlCodePopupSurface(surface)"))
    assertTrue(source.contains("cachedControlCodePopupSurface()?.input"))
    assertTrue(source.contains("cachedControlCodePopupSurface()?.submit"))
    assertTrue(fastOpen.contains("cachedControlCodePopupTargetsForRequest(phases, requestStartedAtMillis)"))
    assertTrue(fastOpen.contains("control_code_popup_cached_ready"))
    assertTrue(source.contains("openedControlCodePopupTransactionTargets("))
    assertTrue(source.contains("control_code_popup_transaction_ready"))
    assertFalse("normal request path should not return the old geometry-only popup fallback after opening the popup", source.substringBetween("private suspend fun openControlCodePopupImmediateForRequest", "private suspend fun openControlCodePopupFromVerifiedStateFastForRequest").contains("fallbackControlCodePopupTargetsAfterImmediateOpen("))
    assertTrue(source.contains("CONTROL_CODE_FAST_POPUP_GEOMETRY_SETTLE_MILLIS = 120L"))
    assertTrue(source.contains("CONTROL_CODE_INPUT_FOCUS_SETTLE_MILLIS = 80L"))
    assertTrue(source.contains("CONTROL_CODE_DIGITS_TYPED_SUBMIT_SETTLE_MILLIS = 40L"))
    assertTrue(source.contains("CONTROL_CODE_FAST_POPUP_INPUT_X_FRACTION = 0.50f"))
    assertTrue(source.contains("CONTROL_CODE_FAST_POPUP_INPUT_Y_FRACTION = 0.511f"))
    assertTrue("already-open popup should be reused before any root hierarchy work", source.indexOf("cachedControlCodePopupTargetsForRequest(phases, requestStartedAtMillis)") < source.indexOf("openControlCodePopupImmediateForRequest(phases, requestStartedAtMillis)"))
    assertTrue(source.contains("CONTROL_CODE_FAST_ROOT_DUMP_TIMEOUT_MILLIS = 900L"))
    assertTrue(source.contains("CONTROL_CODE_FAST_ROOT_RETRY_COUNT = 1"))
    assertTrue(source.contains("controlCodeInputActionLooseForHierarchy(hierarchy)"))
    assertFalse("normal control-code submit must not fall back to accessibility clicks", fastSubmit.contains("PhoneAutomationServiceBridge.clickSelectors("))
    assertFalse("normal control-code submit must not use selector discovery", fastSubmit.contains("controlCodeSubmitSelectors()"))
    assertFalse("post-submit browser-frame path should not spend time rechecking the popup", source.contains("controlCodePopupStillPresentAfterSubmit(phases)"))
    assertFalse("normal request path should not include recovered geometry fallback", source.contains("openControlCodePopupFromRecoveredStateFastForRequest"))
    assertFalse("normal request path should not branch into surface recovery", source.contains("controlCodeRequestNeedsSurfaceRecovery()"))
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
    assertFalse(source.contains("PendingBrowserControlCodeCapture("))
    assertFalse(source.contains("pendingBrowserControlCodeCapture = pending"))
    assertFalse(source.contains("CONTROL_CODE_BROWSER_CAPTURE_ACK_TIMEOUT_MILLIS"))
    assertFalse(source.contains("schedulePendingBrowserCaptureWatchdog"))
    assertFalse(source.contains("control_code_browser_capture_ack_timeout"))
    assertFalse(source.contains("sendControlCodeFrameReady("))
    assertFalse(source.contains("imageMime\", imageMime.orEmpty()"))
    assertFalse(source.contains("imageBase64\", imageBase64.orEmpty()"))
    assertTrue(source.contains("generatedHierarchy = generated.hierarchy"))
    assertTrue(source.contains("beginGeneratedControlCodeResultFastClose("))
    assertTrue(source.contains("finishGeneratedControlCodeResultFastCleanup("))
    assertTrue(source.contains("delivery.generatedHierarchy"))
    assertFalse("normal request path should not run slow cleanup recovery", source.contains("recoverControlCodeRequestSurface(\"control_code_request_failed_cleanup\")"))
    assertTrue(source.contains("cleanupPending = !cleanupSucceeded"))
    assertFalse("fast generated-result cleanup should not fall back into slow control-exit cleanup", source.substringBetween("private suspend fun finishGeneratedControlCodeResultFastCleanup", "private suspend fun sendFastGeneratedResultCloseTap").contains("runControlExitCleanup(reason)"))
    assertTrue(source.contains("CONTROL_EXIT_RESULT_CLOSE_Y_FRACTION = 0.568f"))
    assertFalse("normal request path should not schedule hidden soft cleanup recovery", source.contains("scheduleControlExitSoftSettle(\"control_code_request_cleanup_failed\")"))
  }

  @Test
  fun fastRequestPathUsesOneRootCheckAndNoSlowRecovery() {
    val source = ticketStreamServiceSource()
    val fastOpen = source.substringBetween("private suspend fun openControlCodePopupFastForRequest", "private suspend fun waitForControlCodePopupTargetsFast")
    val generate = source.substringBetween("private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    val fastCleanup = source.substringBetween("private suspend fun finishGeneratedControlCodeResultFastCleanup", "private suspend fun sendFastGeneratedResultCloseTap")

    assertTrue(fastOpen.contains("openControlCodePopupImmediateForRequest(phases, requestStartedAtMillis)"))
    assertTrue(fastOpen.contains("openControlCodePopupFromVerifiedStateFastForRequest(phases, requestStartedAtMillis)"))
    assertFalse(fastOpen.contains("openControlCodePopupFromRecoveredStateFastForRequest"))
    assertFalse(generate.contains("recoverControlCodeRequestSurface("))
    assertFalse(generate.contains("scheduleControlExitSoftSettle("))
    assertFalse(fastCleanup.contains("runControlExitCleanup("))
  }

  @Test
  fun immediateControlCodeStartDoesNotDependOnOptionalSecureProbe() {
    val source = ticketStreamServiceSource()
    val decision = source.substringBetween(
      "private fun controlCodeImmediateStartDecision()",
      "private fun fallbackControlCodeShiftedSubmitAction()"
    )

    assertTrue(decision.contains("streamActive || activeCaptureMode"))
    assertTrue(decision.contains("ticketSessionState != TICKET_SESSION_LIVE"))
    assertTrue(decision.contains("viviStateMemory.current()"))
    assertTrue(decision.contains("current.state == TicketViviRecoveryState.TICKET_DETAIL"))
    assertTrue(decision.contains("viviStateMemory.recentTicketDetailWithin"))
    assertFalse(decision.contains("lastRootH264BlankProbeResult != \"visible\""))
    assertFalse(decision.contains("control_code_immediate_secure_probe_not_visible"))
  }

  @Test
  fun immediatePopupOpenUsesRootConfirmedPopupTargetsBeforeSubmit() {
    val source = ticketStreamServiceSource()
    val immediate = source.substringBetween(
      "private suspend fun openControlCodePopupImmediateForRequest",
      "private suspend fun openControlCodePopupFromVerifiedStateFastForRequest"
    )

    assertTrue(immediate.contains("delay(CONTROL_CODE_FAST_POPUP_GEOMETRY_SETTLE_MILLIS)"))
    assertTrue("successful immediate popup tap must prove popup targets through root before submit", immediate.contains("waitForControlCodePopupTargetsFast("))
    assertTrue("a successful popup tap must build a transaction instead of failing on a transient root miss", immediate.contains("openedControlCodePopupTransactionTargets("))
    assertTrue(immediate.contains("control_code_popup_transaction_ready"))
    assertFalse("production immediate path must not return the old geometry-only popup fallback", immediate.contains("fallbackControlCodePopupTargetsAfterImmediateOpen("))
  }

  @Test
  fun fastControlCodeSubmitUsesKeyboardOpenOkTargetAfterTyping() {
    val source = ticketStreamServiceSource()
    val fastEnter = source.substringBetween("private suspend fun enterControlCodeDigitsFastForRequest", "private suspend fun tapControlCodeSubmitFastForRequest")
    val fastSubmit = source.substringBetween("private suspend fun tapControlCodeSubmitFastForRequest", "private suspend fun waitForGeneratedControlCodeResultAfterSubmit")
    val transactionBuilder = source.substringBetween("private fun openedControlCodePopupTransaction", "private fun fallbackControlCodePopupTargetsAfterImmediateOpen")

    assertTrue(source.contains("private data class FastControlCodePopupTransaction"))
    assertFalse("fast path should not keep a single static popup target set", source.contains("private data class FastControlCodeTargets"))
    assertTrue(transactionBuilder.contains("preKeyboardSubmit"))
    assertTrue(transactionBuilder.contains("keyboardOpenSubmit"))
    assertTrue(transactionBuilder.contains("fallbackControlCodeKeyboardOpenSubmitAction()"))
    assertTrue(fastEnter.contains("transaction.input"))
    assertTrue(fastSubmit.contains("resolveControlCodeSubmitAfterDigitsFastForRequest(transaction, phases)"))
    assertTrue(fastSubmit.contains("control_code_submit_current_keyboard_target"))
    assertFalse("after typing, OK tap must not use the pre-keyboard submit coordinate", fastSubmit.contains("val submit = transaction.preKeyboardSubmit"))
    assertFalse("after typing, OK tap must not reuse the old one-shot target field", fastSubmit.contains("val submit = targets.submit"))
  }

  @Test
  fun dialogPrepareDoesNotBlockFastSubmitBehindFullWakeRecovery() {
    val source = ticketStreamServiceSource()
    val prepare = source.substringBetween("private suspend fun handlePrepareControlCode", "private suspend fun handleGenerateControlCode")
    val recentPrepare = source.substringBetween("private fun recentControlCodePrepareTicketReady", "private suspend fun handleGenerateControlCode")

    assertFalse("dialog prepare must not hold the submit mutex", prepare.contains("controlCodeRequestMutex.withLock"))
    assertFalse("dialog prepare must not run the full hardware stream readiness loop", prepare.contains("prepareViviForRootHardwareH264Capture("))
    assertTrue(prepare.contains("recentControlCodePrepareTicketReady(cleanReason)"))
    assertTrue(recentPrepare.contains("viviStateMemory.current()"))
    assertTrue(recentPrepare.contains("current.state != TicketViviRecoveryState.TICKET_DETAIL"))
    assertFalse("prepare should trust current raw-ticket state instead of expiring after a short age window", recentPrepare.contains("recentTicketDetailWithin(CONTROL_CODE_IMMEDIATE_TICKET_DETAIL_MEMORY_MAX_AGE_MILLIS)"))
    assertTrue(prepare.contains("ticketWakeInProgress()"))
    assertTrue(prepare.contains("control_code_prepare_deferred_active_wake"))
    assertTrue(prepare.contains("CONTROL_CODE_PREPARE_ROOT_DUMP_TIMEOUT_MILLIS"))
    assertFalse("dialog prepare must not open the phone control-code popup before the request is sent", prepare.contains("openControlCodePopup"))
    assertFalse("dialog prepare must not tap the ViVi control-code button before the request is sent", prepare.contains("controlCodeGeometryTarget("))
  }

  @Test
  fun fastResultDetectionUsesPostOkVisualPathThenRootProofFallbackAfterSubmit() {
    val source = ticketStreamServiceSource()
    val delivery = source.substringBetween("private suspend fun runFastControlCodeDeliveryForRequest", "private suspend fun prepareViviForControlCodeRequest")
    val wait = source.substringBetween("private suspend fun waitForGeneratedControlCodeResultAfterSubmit", "private suspend fun controlCodeRequestRootHierarchy")
    val preRootWait = wait.substringBefore("controlCodeRequestRootHierarchy(")

    assertFalse(wait.contains("control_code_request_result_assumed_fast"))
    assertFalse(wait.contains("control_code_request_result_assumed_after_submit"))
    assertFalse("the old pre-settle bitmap shortcut must stay removed", wait.contains("control_code_request_result_detected_by_visual_probe"))
    assertTrue("post-submit visual probing must start only after the post-OK Aztec settle", preRootWait.indexOf("delay(CONTROL_CODE_POST_SUBMIT_FRAME_SETTLE_MILLIS)") < preRootWait.indexOf("requestControlCodeVisualProbe(\"control_code_after_ok_fast_path\")"))
    assertFalse("the old pre-root visual-probe shortcut must stay removed", wait.contains("waitForControlCodeVisualResultAfterSubmit("))
    assertTrue(wait.contains("controlCodeRequestRootHierarchy("))
    assertTrue(wait.contains("TicketViviRecoveryState.CONTROL_CODE_RESULT"))
    assertTrue(wait.contains("strictControlCodeResultValueForHierarchy"))
    assertTrue(wait.contains("confirmGeneratedControlCodeResultForBrowser("))
    assertTrue(wait.contains("requestControlCodeVisualProbe(\"control_code_result_after_root\")"))
    assertTrue(wait.contains("waitForFreshControlCodeFrameWatermarkForBrowser("))
    assertTrue(wait.contains("control_code_request_result_browser_frame_ready"))
    assertTrue(wait.contains("control_code_request_result_detected_after_submit"))
    assertTrue(wait.contains("control_code_popup_still_open_after_submit"))
    assertTrue(wait.contains("control_code_request_result_wait_failed"))
    assertTrue(delivery.contains("generated.streamEpoch to generated.minFrameSequence"))
    assertTrue(delivery.contains("minFrameSequence = watermark.second"))
  }

  @Test
  fun successCleanupStartsCloseBeforeSendingResult() {
    val source = ticketStreamServiceSource()
    val generate = source.substringBetween("private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    val success = generate.substringBetween("if (ok) {", "} else if (delivery.cleanupRequired)")
    val delivery = source.substringBetween("private suspend fun runFastControlCodeDeliveryForRequest", "private suspend fun prepareViviForControlCodeRequest")
    val returnToRaw = source.substringBetween("private suspend fun returnControlCodeSurfaceToRawTicket", "private suspend fun beginGeneratedControlCodeResultFastClose")
    val fastCleanup = source.substringBetween("private suspend fun beginGeneratedControlCodeResultFastClose", "private suspend fun closeControlCodePopupFromRemote")
    val completeCleanup = source.substringBetween("private suspend fun completeControlExitCleanup", "private suspend fun waitForFreshStreamFrameAfterCleanup")

    assertFalse(success.contains("sendControlCodeFrameReady("))
    assertTrue(success.contains("sendTicketStateEvent("))
    assertFalse("ticket.jolkins/ViVi success path must not send Telegram RS-bot image results", success.contains("sendRigassatiksmeQrResult("))
    assertFalse("ticket.jolkins/ViVi success path must not branch resultImage into ViVi monthly-ticket capture", success.contains("runFastViviMonthlyTicketImageDeliveryForRequest"))
    assertTrue("success path should close after the marker is sent", success.contains("returnControlCodeSurfaceToRawTicket("))
    assertTrue("ViVi success must mark the result handled before raw-ticket cleanup so it does not send an unwatermarked control_code_result over the recovered raw ticket", success.contains("resultSent = true"))
    val successAfterTicketEvent = success.substringAfter("sendTicketStateEvent(")
    assertTrue(successAfterTicketEvent.indexOf("resultSent = true") < successAfterTicketEvent.indexOf("returnControlCodeSurfaceToRawTicket("))
    assertTrue(success.indexOf("returnControlCodeSurfaceToRawTicket(") < success.indexOf("sendControlCodeCleanup("))
    assertTrue(source.contains("CONTROL_CODE_MARKER_RESULT_HIERARCHY"))
    assertTrue(returnToRaw.contains("generatedHierarchy == CONTROL_CODE_MARKER_RESULT_HIERARCHY"))
    assertTrue(returnToRaw.indexOf("generatedHierarchy == CONTROL_CODE_MARKER_RESULT_HIERARCHY") < returnToRaw.indexOf("controlExitHierarchy().orEmpty()"))
    assertTrue(returnToRaw.contains("beginGeneratedControlCodeResultFastClose("))
    assertTrue(returnToRaw.contains("finishGeneratedControlCodeResultFastCleanup("))
    assertTrue(returnToRaw.contains("detectedState = TicketViviRecoveryState.CONTROL_CODE_POPUP.name"))
    assertTrue(completeCleanup.contains("viviStateMemory.record("))
    assertTrue(completeCleanup.contains("state = TicketViviRecoveryState.TICKET_DETAIL"))
    assertTrue(fastCleanup.contains("TicketViviPageEnforcer.controlCodeExitCloseActionForHierarchy(generatedHierarchy)"))
    assertTrue(fastCleanup.contains("?: controlCodeResultGeometryCloseAction()"))
    assertTrue(fastCleanup.indexOf("TicketViviPageEnforcer.controlCodeExitCloseActionForHierarchy(generatedHierarchy)") < fastCleanup.indexOf("controlCodeResultGeometryCloseAction()"))
    assertTrue(fastCleanup.contains("input tap ${'$'}{action.x} ${'$'}{action.y}"))
    assertTrue(fastCleanup.contains("waitForCleanTicketSurfaceFast("))
    assertTrue(fastCleanup.contains("requestKeyFrame(\"control_code_fast_cleanup_close\")"))
    assertFalse("fast cleanup must not root-dump before the first close tap", fastCleanup.substringBefore("runFastNonTouchInput").contains("controlExitHierarchy()"))
    assertFalse("fast cleanup should not take the slow second-confirm path on clean visual proof", fastCleanup.contains("completeVerifiedTicketDetailControlExitCleanup("))
    assertFalse("fast cleanup should not wait on the slow second-confirm delay", fastCleanup.contains("CONTROL_EXIT_SECOND_VERIFY_DELAY_MILLIS"))
  }

  @Test
  fun fastCleanupUsesRootOnlySurfaceProof() {
    val source = ticketStreamServiceSource()
    val fastVerifier = source.substringBetween("private suspend fun waitForCleanTicketSurfaceFast", "private suspend fun completeFastVerifiedTicketDetailControlExitCleanup")

    assertTrue(fastVerifier.contains("controlExitHierarchy()"))
    assertTrue(fastVerifier.contains("TicketViviRecoveryState.TICKET_DETAIL"))
    assertFalse("fast verifier should not start the raw root surface app_process probe", fastVerifier.contains("controlExitGeneratedResultFastScreencapProbe("))
    assertFalse("fast verifier should not start the raw root surface app_process probe", fastVerifier.contains("controlExitGeneratedResultSurfaceProbe("))
    assertFalse("fast verifier should not use the removed raw capture helper", fastVerifier.contains("captureRootSurfaceProbeRaw("))
    assertFalse("fast cleanup should not use accessibility state", fastVerifier.contains("ticketAutopilot.observeFastState"))
    assertFalse("fast cleanup should not publish accessibility detector labels", fastVerifier.contains("accessibility_"))
    assertFalse("fast cleanup should not reject root proof through accessibility", fastVerifier.contains("visual_clean_rejected_by_accessibility_result"))
  }

  @Test
  fun recentSuccessfulCleanupSuppressesLateUnknownSoftCheck() {
    val source = ticketStreamServiceSource()
    val softCheck = source.substringBetween("private fun scheduleControlCodeSoftCheck", "private suspend fun runControlExitCleanup")

    assertTrue(source.contains("CONTROL_CODE_POST_CLEANUP_SOFT_CHECK_GRACE_MILLIS = 8_000L"))
    assertTrue(source.contains("private fun recentSuccessfulControlExitCleanup()"))
    assertTrue(softCheck.contains("recentSuccessfulControlExitCleanup()"))
    assertTrue(softCheck.contains("control_code_soft_check_recent_cleanup_ok"))
    assertTrue(
      softCheck.indexOf("recentSuccessfulControlExitCleanup()") <
        softCheck.indexOf("updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION")
    )
  }

  @Test
  fun fastResultDetectionUsesPostOkVisualProbeBeforeSlowRootFallback() {
    val source = ticketStreamServiceSource()
    val wait = source.substringBetween("private suspend fun waitForGeneratedControlCodeResultAfterSubmit", "private suspend fun controlCodeRequestRootHierarchy")

    assertFalse("fast result detection must not use accessibility observation", wait.contains("ticketAutopilot.observeFastState"))
    assertFalse("fast result detection must not snapshot accessibility nodes", wait.contains("snapshotVisibleNodes("))
    assertFalse("post-submit marker readiness must not use the removed raw capture helper", wait.contains("controlExitGeneratedResultFastScreencapProbe("))
    assertFalse("the old pre-OK/pre-settle visual shortcut must not come back", wait.contains("control_code_request_result_detected_by_visual_probe"))
    assertFalse("the removed standalone visual wait helper must not come back", wait.contains("waitForControlCodeVisualResultAfterSubmit("))
    assertTrue("post-submit marker readiness keeps bounded root hierarchy as fallback", wait.contains("controlCodeRequestRootHierarchy("))
    assertTrue(wait.contains("TicketViviRecoveryState.CONTROL_CODE_RESULT"))
    assertTrue(wait.contains("control_code_request_result_detected_after_submit"))
    assertTrue(wait.contains("delay(CONTROL_CODE_POST_SUBMIT_FRAME_SETTLE_MILLIS)"))
    assertTrue(source.contains("CONTROL_CODE_POST_OK_VISUAL_FAST_PATH_TIMEOUT_MILLIS = 1_250L"))
    assertTrue(wait.contains("postOkVisualProbeStartedAtMillis"))
    assertTrue(wait.contains("requestControlCodeVisualProbe(\"control_code_after_ok_fast_path\")"))
    assertTrue(wait.contains("recentControlCodeVisualProbeAfter(postOkVisualProbeStartedAtMillis)"))
    assertTrue(wait.contains("control_code_request_result_detected_by_post_ok_visual"))
    assertTrue(wait.contains("CONTROL_CODE_MARKER_RESULT_HIERARCHY"))
    assertTrue(
      "the post-OK visual probe starts only after the Aztec/result settle cushion",
      wait.indexOf("delay(CONTROL_CODE_POST_SUBMIT_FRAME_SETTLE_MILLIS)") <
        wait.indexOf("requestControlCodeVisualProbe(\"control_code_after_ok_fast_path\")")
    )
    assertTrue(
      "the visual fast path must run before the slow root fallback so generated Aztec freezes under 2s",
      wait.indexOf("recentControlCodeVisualProbeAfter(postOkVisualProbeStartedAtMillis)") <
        wait.indexOf("controlCodeRequestRootHierarchy(")
    )
    assertTrue(
      "post-root visual confirmation remains available for the root-confirmed fallback path",
      wait.indexOf("TicketViviRecoveryState.CONTROL_CODE_RESULT") <
        wait.indexOf("requestControlCodeVisualProbe(\"control_code_result_after_root\")")
    )
    assertTrue(wait.contains("result_first_visible"))
    assertTrue(wait.contains("result_marker_frame_ready"))
  }

  @Test
  fun markerResultWaitDoesNotFailBeforeViViFinishesGenerating() {
    val source = ticketStreamServiceSource()
    val wait = source.substringBetween("private suspend fun waitForGeneratedControlCodeResultAfterSubmit", "private suspend fun controlCodeRequestRootHierarchy")

    assertTrue(source.contains("CONTROL_CODE_POST_SUBMIT_FRAME_SETTLE_MILLIS = 750L"))
    assertTrue(source.contains("CONTROL_CODE_GENERATED_RESULT_HARD_TIMEOUT_MILLIS = 22_000L"))
    assertTrue(wait.contains("control_code_waiting_result_marker"))
    assertTrue(wait.contains("control_code_request_result_detected_after_submit"))
    assertTrue(wait.contains("control_code_popup_still_open_after_submit"))
    assertFalse("fast marker result wait must not declare no-result while ViVi can still finish generating", wait.contains("return ControlCodeResultWaitOutcome(failureReason = \"control_code_submit_returned_no_result\")"))
  }

  @Test
  fun hardwareKeyframeRequestReachesRunningEncoderHelper() {
    val engine = rootHardwareH264CaptureEngineSource()
    val helper = rootHardwareH264CaptureMainSource()

    assertTrue(engine.contains("private fun writeHardwareKeyFrameRequest(reason: String): Boolean"))
    assertTrue(engine.contains("encoderProcess?.outputStream"))
    assertTrue(engine.contains("\"keyframe\\n\""))
    assertTrue(engine.contains("flush()"))
    assertTrue(helper.contains("AtomicBoolean"))
    assertTrue(helper.contains("AtomicLong burstUntilMillis"))
    assertTrue(helper.contains("startCommandReader(syncFrameRequested, burstUntilMillis, burstHoldMillis, controlCodeVisualProbeUntilMillis, controlCodeVisualProbeReason)"))
    assertTrue(helper.contains("MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME"))
    assertTrue(helper.contains("encoder.setParameters(params)"))
    assertTrue(helper.contains("extendBurst(burstUntilMillis, burstHoldMillis)"))
  }

  @Test
  fun hardwareHelperSupportsControlCodeVisualProbe() {
    val engine = rootHardwareH264CaptureEngineSource()
    val helper = rootHardwareH264CaptureMainSource()

    assertTrue(engine.contains("fun requestControlCodeVisualProbe(reason: String)"))
    assertTrue(engine.contains("fun recentControlCodeVisualProbeAfter(startedAtMillis: Long)"))
    assertTrue(engine.contains("line.startsWith(\"CONTROL_CODE_VISUAL \")"))
    assertTrue(engine.contains("lastControlCodeVisualProbeResult"))
    assertTrue(helper.contains("cmd.equals(\"control_code_visual_probe\")"))
    assertTrue(helper.contains("frameLooksLikeControlCodeResult(source.bitmap, sourceCrop)"))
    assertTrue(helper.contains("CONTROL_CODE_VISUAL result=generated"))
  }

  @Test
  fun popupSubmitRetriesRootStateInsteadOfFailingRefreshingView() {
    val source = ticketStreamServiceSource()
    val verifiedOpen = source.substringBetween("private suspend fun openControlCodePopupFromVerifiedStateFastForRequest", "private suspend fun waitForControlCodePopupTargetsFast")

    assertTrue(verifiedOpen.contains("resolveControlCodeHierarchyForFastRequest("))
    assertTrue(verifiedOpen.contains("control_code_root_retry"))
    assertTrue(verifiedOpen.contains("cachedControlCodePopupTargetsForRequest("))
    assertFalse("an already-open popup should not surface the old refreshing/not-ready failure on the first root miss", verifiedOpen.substringBefore("cachedControlCodePopupTargetsForRequest(").contains("control_code_request_hierarchy_unavailable"))
  }

  @Test
  fun foregroundGuardCannotCloseControlCodeFlowInFlight() {
    val source = ticketStreamServiceSource()
    val guard = source.substringBetween("private suspend fun enforceViviTicketPageIfNeeded", "private suspend fun dumpViviHierarchy")
    val transition = source.substringBetween("private fun markControlCodeTransition", "private fun controlSensitiveWindowActive")
    val sensitive = source.substringBetween("private fun controlSensitiveWindowActive", "private fun markControlCodeModeEntered")
    val result = source.substringBetween("private fun sendControlCodeResult", "private fun sendControlCodeCleanup")

    assertTrue(sensitive.contains("controlCodeRequestActive()"))
    assertTrue(sensitive.contains("status == \"running\""))
    assertFalse(sensitive.contains("status == \"capturing\""))
    assertFalse(sensitive.contains("pendingBrowserControlCodeCapture != null"))
    assertTrue(transition.contains("pauseForegroundGuardForControlCode(reason)"))
    assertTrue(transition.contains("foreground_guard_paused_for_control_code"))
    assertTrue(guard.contains("active_guard_deferred_after_observe"))
    assertTrue(
      "active guard must re-check control sensitivity after a root dump before closing popup/result",
        guard.indexOf("observeRootViviState(\"active_guard:${'$'}reason\")") <
        guard.indexOf("active_guard_deferred_after_observe") &&
        guard.indexOf("active_guard_deferred_after_observe") <
        guard.indexOf("active_guard_return_raw")
    )
    assertTrue(result.contains("foreground_guard_resumed_after_control_code_failure"))
    assertTrue(result.contains("startForegroundGuard()"))
  }

  @Test
  fun foregroundGuardSkipsRedundantRootProbeAfterRecentRawTicketProof() {
    val source = ticketStreamServiceSource()
    val guard = source.substringBetween("private suspend fun enforceViviTicketPageIfNeeded", "private suspend fun dumpViviHierarchy")

    assertTrue(guard.contains("recentForegroundGuardTicketDetailStillFresh(now)"))
    assertTrue(guard.contains("shouldLogForegroundGuardRecentTicketDetailSkip(now)"))
    assertTrue(guard.contains("active_guard_recent_ticket_detail"))
    val recentProof = source.substringBetween("private fun recentForegroundGuardTicketDetailStillFresh", "private fun shouldLogForegroundGuardRecentTicketDetailSkip")
    assertTrue(recentProof.contains("viviStateMemory.current()"))
    assertTrue(recentProof.contains("current.state != TicketViviRecoveryState.TICKET_DETAIL"))
    assertTrue(
      "raw-ticket proof may skip one redundant root dump, but it must expire so ViVi-home drift is re-observed and recovered",
      recentProof.contains("nowMillis - current.observedAtMillis") &&
        recentProof.contains("in 0..FOREGROUND_GUARD_RECENT_TICKET_DETAIL_SKIP_MAX_AGE_MILLIS")
    )
    assertFalse(
      "stale TICKET_DETAIL memory must not suppress active-guard root checks forever",
      recentProof.contains("return nowMillis >= current.observedAtMillis")
    )
    assertTrue(source.contains("lastForegroundGuardRecentTicketDetailSkipAtMillis"))
    assertTrue(source.contains("FOREGROUND_GUARD_RECENT_TICKET_LOG_INTERVAL_MILLIS"))
    assertTrue(source.contains("FOREGROUND_GUARD_RECENT_TICKET_DETAIL_SKIP_MAX_AGE_MILLIS"))
    assertTrue(
      "recent raw-ticket proof should prevent the slow active-guard root dump",
      guard.indexOf("recentForegroundGuardTicketDetailStillFresh(now)") <
        guard.indexOf("observeRootViviState(\"active_guard:${'$'}reason\")")
    )
  }

  @Test
  fun wakeFastRecentTicketDetailProofExpiresBeforeTrustingOnlyViviForeground() {
    val source = ticketStreamServiceSource()
    val fastReady = source.substringBetween("private suspend fun fastWakeReadyFromRecentTicketDetail", "private fun remainingWakeBudgetMillis")

    assertTrue(fastReady.contains("val ageMillis ="))
    assertTrue(
      "wake fast-ready must not trust stale TICKET_DETAIL memory just because some ViVi screen is focused",
      fastReady.contains("ageMillis !in 0..TICKET_WAKE_RECENT_DETAIL_FAST_READY_MAX_AGE_MILLIS")
    )
    assertTrue(fastReady.contains("wake_recent_ticket_detail_fast_ready_stale"))
    assertTrue(source.contains("private const val TICKET_WAKE_RECENT_DETAIL_FAST_READY_MAX_AGE_MILLIS = FOREGROUND_GUARD_RECENT_TICKET_DETAIL_SKIP_MAX_AGE_MILLIS"))
  }

  @Test
  fun foregroundGuardRecoversKnownNonTicketViviScreensInsteadOfOnlyFlaggingNeedsAttention() {
    val source = ticketStreamServiceSource()
    val guard = source.substringBetween("private suspend fun enforceViviTicketPageIfNeeded", "private suspend fun dumpViviHierarchy")

    assertTrue(
      "when the live stream is on ViVi home/route/profile, active guard should tap the recovery target before giving up",
      guard.contains("attemptActiveGuardRecoveryAction(")
    )
    assertTrue(guard.contains("active_guard_recovery_action"))
    assertTrue(
      "active-guard recovery must run after control-code cleanup handling but before needs_attention is broadcast",
      guard.indexOf("active_guard_return_raw_failed") < guard.indexOf("attemptActiveGuardRecoveryAction(") &&
        guard.indexOf("attemptActiveGuardRecoveryAction(") < guard.indexOf("updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION")
    )
    val recovery = source.substringBetween("private suspend fun attemptActiveGuardRecoveryAction", "private fun recentForegroundGuardTicketDetailStillFresh")
    assertTrue(
      "active-guard recovery is touch/input work and must use the input root lane rather than the wake-root hierarchy lane",
      recovery.contains("runFastNonTouchInput(\"input tap ${'$'}{action.x} ${'$'}{action.y}\"") &&
        recovery.contains("runFastNonTouchInput(\"input keyevent KEYCODE_BACK\"")
    )
    assertFalse(
      "active-guard recovery must not queue behind wake-root hierarchy reads",
      recovery.contains("runFastNonTouchWakeInput(")
    )
  }

  @Test
  fun staleIdleStopCannotShutDownNewerBrowserSession() {
    val source = ticketStreamServiceSource()
    val stopHandler = source.substringBetween("\"stop\" -> {", "\"activity\" -> {")
    val staleStop = source.substringBetween("private fun staleExplicitStopSupersededByNewerClient", "private fun queryParam")

    assertTrue(stopHandler.contains("staleExplicitStopSupersededByNewerClient(client, reason)"))
    assertTrue(stopHandler.contains("session_stop_ignored_newer_client"))
    assertTrue(staleStop.contains("if (normalizedReason == \"relay_no_viewers\")"))
    assertTrue(staleStop.contains("if (normalizedReason == \"relay_no_viewers\") {\n      return false\n    }"))
    assertTrue(staleStop.contains("TicketSessionStopPolicy.BROWSER_EXPLICIT_STOP"))
    assertTrue(staleStop.contains("if (totalClientCount() == 0)"))
    assertTrue(staleStop.contains("clientInfo[client]?.generation"))
    assertTrue(staleStop.contains("info.generation > currentGeneration"))
  }

  @Test
  fun controlCodeErrorsAndMarkerSuccessUseSingleReturnToRawRoutine() {
    val source = ticketStreamServiceSource()
    val generate = source.substringBetween("private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    val returnToRaw = source.substringBetween("private suspend fun returnControlCodeSurfaceToRawTicket", "private suspend fun beginGeneratedControlCodeResultFastClose")

    assertTrue(generate.contains("returnControlCodeSurfaceToRawTicket("))
    assertFalse(source.contains("handleControlCodeBrowserCaptureAck("))
    assertTrue(returnToRaw.contains("TicketViviRecoveryState.CONTROL_CODE_POPUP"))
    assertTrue(returnToRaw.contains("TicketViviRecoveryState.CONTROL_CODE_RESULT"))
    assertTrue(returnToRaw.contains("waitForCleanTicketSurfaceFast("))
    assertFalse("single return-to-raw routine must stay root-only and bounded", returnToRaw.contains("runControlExitCleanup("))
    assertFalse("single return-to-raw routine must not use accessibility recovery", returnToRaw.contains("PhoneAutomationServiceBridge."))
  }

  @Test
  fun generatedCodeStateIsRootHealedBeforePrepareOrNotReady() {
    val source = ticketStreamServiceSource()
    val prepare = source.substringBetween("private suspend fun handlePrepareControlCode", "private fun recentControlCodePrepareTicketReady")
    val verifiedOpen = source.substringBetween("private suspend fun openControlCodePopupFromVerifiedStateFastForRequest", "private suspend fun waitForControlCodePopupTargetsFast")
    val heal = source.substringBetween("private suspend fun healGeneratedControlCodeResultForRequest", "private suspend fun returnControlCodeSurfaceToRawTicket")

    assertTrue(prepare.contains("healGeneratedControlCodeResultForRequest("))
    assertTrue(verifiedOpen.contains("healGeneratedControlCodeResultForRequest("))
    val healIndex = verifiedOpen.indexOf("healGeneratedControlCodeResultForRequest(")
    val genericNotReadyIndex = verifiedOpen.indexOf("control_code_phone_not_ready")
    assertTrue(genericNotReadyIndex < 0 || healIndex < genericNotReadyIndex)
    assertTrue(source.contains("inputGateReason.ifBlank { \"control_code_phone_not_ready\" }"))
    assertTrue(heal.contains("TicketViviRecoveryState.CONTROL_CODE_RESULT"))
    assertTrue(heal.contains("phone_stuck_on_generated_code"))
    assertTrue(heal.contains("CONTROL_CODE_GENERATED_HEAL_MAX_CLOSE_ATTEMPTS = 2"))
    assertFalse("root heal must not use accessibility", heal.contains("PhoneAutomationServiceBridge."))
  }

  @Test
  fun controlCodeResultUsesStreamMarkerAndClosesImmediatelyAfterMarker() {
    val source = ticketStreamServiceSource()
    val config = ticketScreenConfigSource()
    val generate = source.substringBetween("private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    val fastFlow = source.substringBetween("private suspend fun runFastControlCodeDeliveryForRequest", "private suspend fun prepareViviForControlCodeRequest")

    assertTrue(config.contains("const val TICKET_MEDIA_TOP_CROP_SOURCE_PIXELS = 200"))
    assertTrue(fastFlow.contains("waitForFreshControlCodeFrameWatermarkForBrowser("))
    assertTrue(fastFlow.contains("reason = \"control_code_marker_ready\""))
    assertTrue(fastFlow.contains("streamEpoch = watermark.first"))
    assertTrue(fastFlow.contains("minFrameSequence = watermark.second"))
    assertFalse("ViVi marker path must not capture a PNG before cleanup", fastFlow.contains("captureGeneratedControlCodeImageBytes("))
    assertFalse("ViVi marker path must not carry RS image bytes", fastFlow.contains("resultImageBytes = imageBytes"))
    assertFalse("ticket.jolkins/ViVi marker path must never send Telegram RS-bot image results", generate.contains("sendRigassatiksmeQrResult("))
    assertFalse("ticket.jolkins/ViVi marker path must not route resultImage into ViVi monthly-ticket capture", generate.contains("runFastViviMonthlyTicketImageDeliveryForRequest"))
    assertFalse(generate.contains("PendingBrowserControlCodeCapture("))
    assertFalse(generate.contains("sendControlCodeFrameReady("))
    assertTrue("generated result must close after the marker is delivered", generate.substringBetween("if (ok) {", "} else if (delivery.cleanupRequired)").contains("returnControlCodeSurfaceToRawTicket("))
    assertFalse(source.contains("handleControlCodeBrowserCaptureAck("))
  }

  @Test
  fun cleanupCompletionRequiresFreshPostCleanupStreamFrame() {
    val source = ticketStreamServiceSource()
    val cleanup = source.substringBetween("private suspend fun completeControlExitCleanup", "private fun recordControlExitCleanup")
    val health = ticketScreenConfigSource().substringBetween("data class TicketControlExitCleanupHealth", "@Serializable")

    assertTrue(cleanup.contains("waitForFreshStreamFrameAfterCleanup(reason, cleanupStartedAtMillis)"))
    assertTrue(cleanup.contains("restartActiveStreamEngine(\"post_cleanup_stale_${'$'}reason\")"))
    assertTrue(cleanup.contains("post_cleanup_stream_stale"))
    assertTrue(source.contains("private suspend fun waitForFreshStreamFrameAfterCleanup"))
    assertTrue(source.contains("lastPostCleanupFreshFrameVerifiedAtMillis"))
    assertTrue(health.contains("val lastFreshFrameVerified: Boolean = false"))
    assertTrue(health.contains("val lastFreshFrameVerifiedAgoMillis: Long? = null"))
  }

  @Test
  fun staleKeyframeRequestRestartsActiveStreamEngine() {
    val source = ticketStreamServiceSource()
    val keyframe = source.substringBetween("private fun requestKeyFrame", "private fun sendStatus")

    assertTrue(keyframe.contains("activeStreamStaleForRecovery(nowMillis)"))
    assertTrue(keyframe.contains("restartActiveStreamEngine(\"stale_keyframe_request_${'$'}reason\")"))
    assertTrue(keyframe.contains("rootHardwareH264CaptureEngine.requestKeyFrame(reason)"))
    assertFalse(keyframe.contains("rootFfmpegH264CaptureEngine.requestKeyFrame(reason)"))
    assertTrue(source.contains("private fun activeStreamStaleForRecovery(nowMillis: Long): Boolean"))
  }

  @Test
  fun wakeTimingHealthIsPublished() {
    val source = ticketStreamServiceSource()
    val config = ticketScreenConfigSource()
    val health = source.substringBetween("private fun health()", "private fun launchVivi")

    assertTrue(config.contains("data class TicketWakeHealth("))
    assertTrue(config.contains("val wake: TicketWakeHealth = TicketWakeHealth()"))
    listOf(
      "val budgetMillis: Long = 5_000L",
      "val lastReason: String? = null",
      "val lastWakeCommandMillis: Long? = null",
      "val lastInteractiveMillis: Long? = null",
      "val lastViviForegroundMillis: Long? = null",
      "val lastTicketReadyMillis: Long? = null",
      "val lastTotalMillis: Long? = null",
      "val lastSucceeded: Boolean? = null",
      "val lastSlowestPhase: String? = null"
    ).forEach { assertTrue("missing wake health field: $it", config.contains(it)) }
    assertTrue(source.contains("private fun beginTicketWake(reason: String): Long"))
    assertTrue(source.contains("private fun recordTicketWakePhase(phase: String, startedAtMillis: Long, nowMillis: Long = SystemClock.elapsedRealtime())"))
    assertTrue(source.contains("private fun finishTicketWake(startedAtMillis: Long, succeeded: Boolean, reason: String)"))
    assertTrue(health.contains("wake = TicketWakeHealth("))
    assertTrue(health.contains("lastWakeCommandMillis = lastWakeCommandMillis"))
    assertTrue(health.contains("lastInteractiveMillis = lastWakeInteractiveMillis"))
    assertTrue(health.contains("lastViviForegroundMillis = lastWakeViviForegroundMillis"))
    assertTrue(health.contains("lastTicketReadyMillis = lastWakeTicketReadyMillis"))
  }

  @Test
  fun ticketAutomationHealthPublishesRootOnlyFailFastPolicy() {
    val source = ticketStreamServiceSource()
    val config = ticketScreenConfigSource()
    val health = source.substringBetween("private fun health()", "private fun launchVivi")

    assertTrue(config.contains("data class TicketAutomationHealth("))
    assertTrue(config.contains("val automation: TicketAutomationHealth = TicketAutomationHealth()"))
    assertTrue(config.contains("val ticketAutomationMode: String = \"root_only\""))
    assertTrue(config.contains("val fallbackPolicy: String = \"fail_fast\""))
    assertTrue(config.contains("val nonRootAccessibilityAllowed: Boolean = false"))
    assertTrue(health.contains("automation = TicketAutomationHealth("))
    assertTrue(health.contains("ticketAutomationMode = \"root_only\""))
    assertTrue(health.contains("fallbackPolicy = \"fail_fast\""))
    assertTrue(health.contains("lastRootReadinessResult = lastRootReadinessResult"))
    assertTrue(health.contains("lastRootReadinessDurationMillis = lastRootReadinessDurationMillis"))
  }

  @Test
  fun hardwareStartupUsesFastWakeReadinessInsteadOfFixedSettleAndSlowRecovery() {
    val source = ticketStreamServiceSource()
    val hardwareStart = source.substringBetween("private fun scheduleRootHardwareH264CaptureStart", "private suspend fun verifyRootHardwareSecureCaptureVisible")
    val prepare = source.substringBetween("private suspend fun prepareViviForRootHardwareH264Capture", "private fun scheduleRootHardwareH264CaptureStart")
    val wake = source.substringBetween("private suspend fun wakeTicketScreenForSessionStart", "private suspend fun waitForTicketScreenInteractiveForWake")

    assertTrue(hardwareStart.contains("val wakeStartedAtMillis = beginTicketWake(reason)"))
    assertTrue(hardwareStart.contains("wakeTicketScreenForSessionStart(reason, wakeStartedAtMillis)"))
    assertTrue(hardwareStart.contains("launchViviForWake(reason)"))
    assertTrue(hardwareStart.contains("prepareViviForRootHardwareH264Capture(reason, wakeStartedAtMillis)"))
    assertTrue(hardwareStart.contains("ensureEncoderIfPossible()"))
    assertTrue(hardwareStart.contains("requestKeyFrame(\"vivi_ready_encoder_start:\$reason\")"))
    assertFalse(hardwareStart.contains("vivi_launch_encoder_prewarm"))
    assertTrue(hardwareStart.indexOf("wakeTicketScreenForSessionStart(reason, wakeStartedAtMillis)") < hardwareStart.indexOf("prepareViviForRootHardwareH264Capture(reason, wakeStartedAtMillis)"))
    assertTrue(hardwareStart.indexOf("if (!prepareResult.success)") < hardwareStart.indexOf("hardwareFrameBroadcastAllowed = true"))
    assertTrue(hardwareStart.indexOf("hardwareFrameBroadcastAllowed = true") < hardwareStart.indexOf("ensureEncoderIfPossible()"))
    assertTrue(hardwareStart.contains("requestFreshTicketStateFrameWatermark(\"vivi_ready:\$reason\")"))
    assertFalse("hardware startup should not carry a fixed app-settle sleep", hardwareStart.contains("delay(PRE_CAPTURE_APP_SETTLE_MILLIS)"))
    assertFalse("normal hardware wake should not use accessibility fast readiness", prepare.contains("waitForFastTicketDetail("))
    assertFalse("ViVi launch should happen before encoder prewarm, not inside the root readiness wait", prepare.contains("launchViviForWake(reason)"))
    assertTrue(prepare.contains("observeTicketDetailForWakeWithRoot(reason, wakeStartedAtMillis)"))
    assertTrue(source.contains("private suspend fun observeTicketDetailForWakeWithRoot"))
    val rootWakeObserver = source.substringBetween("private suspend fun observeTicketDetailForWakeWithRoot", "private fun markWakeReadyIfNeeded")
    val rootWakeRecoveryHelpers = source.substringBetween("private suspend fun attemptWakeRecoveryActionForRootWake", "private fun markWakeReadyIfNeeded")
    assertTrue(rootWakeObserver.contains("while (true)"))
    assertTrue(source.contains("private fun wakeRootDumpTimeoutMillis"))
    assertTrue(rootWakeObserver.contains("var rootUnavailableAttempts = 0"))
    assertTrue(rootWakeObserver.contains("wakeRootDumpTimeoutMillis("))
    assertTrue(rootWakeObserver.contains("rootUnavailableAttempts += 1"))
    assertTrue(rootWakeObserver.contains("rootUnavailableAttempts = 0"))
    val wakeDumpTimeout = source.substringBetween("private fun wakeRootDumpTimeoutMillis", "private fun ticketWakeInProgress")
    assertTrue("normal wake readiness should still start with the short post-launch root dump", wakeDumpTimeout.contains("TICKET_WAKE_FAST_POST_LAUNCH_TIMEOUT_MILLIS"))
    assertTrue("after a timed-out root dump, wake readiness must allow a real uiautomator dump to finish", wakeDumpTimeout.contains("rootUnavailableAttempts > 0") && wakeDumpTimeout.contains("TICKET_WAKE_FAST_ROOT_DUMP_TIMEOUT_MILLIS"))
    assertTrue("wake readiness should not queue behind background hierarchy dumps", rootWakeObserver.contains("observeRootViviStateForWake("))
    assertTrue(rootWakeObserver.contains("delay(minOf(TICKET_WAKE_FAST_POLL_MILLIS"))
    assertTrue(rootWakeObserver.contains("state == TicketViviRecoveryState.CONTROL_CODE_POPUP"))
    assertTrue(rootWakeObserver.contains("returnControlCodeSurfaceToRawTicket("))
    assertTrue(rootWakeObserver.contains("wake_root_popup_returned_raw"))
    assertTrue(rootWakeObserver.contains("return TicketAutopilotResult(true, TicketViviRecoveryState.TICKET_DETAIL, lastStep)"))
    val wakeObserver = source.substringBetween("private suspend fun observeRootViviStateForWake", "private fun markWakeReadyIfNeeded")
    assertTrue(wakeObserver.contains("dumpViviHierarchyForWake(timeoutMillis = timeoutMillis)"))
    assertTrue(source.substringBetween("private suspend fun dumpViviHierarchyForWake", "private suspend fun observeRootViviStateForWake").contains("wakeRootExecutor.runScript("))
    assertFalse("wake readiness should bypass the shared dump lock", wakeObserver.contains("TicketScreenObserver.dumpViviHierarchy"))
    assertTrue("hardware wake should use bounded root-tap recovery for known non-ticket ViVi screens", rootWakeObserver.contains("attemptWakeRecoveryActionForRootWake("))
    assertTrue("hardware wake should retry one direct ViVi launch for blank/outside/unknown wake states", rootWakeObserver.contains("attemptWakeRelaunchForRootWake("))
    assertTrue("hardware wake relaunch should emit a distinct diagnostic event", rootWakeRecoveryHelpers.contains("wake_recovery_relaunch"))
    assertTrue("hardware wake recovery should use short settle delays between taps", rootWakeObserver.contains("TICKET_WAKE_RECOVERY_ACTION_SETTLE_MILLIS"))
    assertFalse("normal hardware wake should not enter the slow autopilot loop", prepare.contains("ticketAutopilot.driveToTicketDetail("))
    assertFalse("normal hardware wake should not use the old 18s soft recovery budget", prepare.contains("ROOT_FFMPEG_H264_START_AUTOPILOT_TIMEOUT_MILLIS"))
    assertFalse("normal hardware wake should not use soft recovery", prepare.contains("TicketAutopilotRestartPolicy.SOFT_RECOVERY"))
    assertFalse("wake command should not wait on dismiss-keyguard in the fast path", wake.contains("wm dismiss-keyguard"))
    assertFalse("wake command should stay minimal and leave shade collapse to launch/prep", wake.contains("cmd statusbar collapse"))
    assertTrue(source.contains("private const val TICKET_WAKE_FAST_INITIAL_TIMEOUT_MILLIS = 0L"))
    assertTrue(source.contains("private const val TICKET_WAKE_FAST_POST_LAUNCH_TIMEOUT_MILLIS = 600L"))
    assertTrue(
      "wrapped wake key/launch commands were observed taking 608-2011ms after long pause; the wake command timeout must include that cushion instead of killing the wake root worker",
      source.contains("private const val TICKET_WAKE_COMMAND_TIMEOUT_MILLIS = 3_000L")
    )
    assertTrue(
      "after a short post-launch root-dump miss, the long-pause wake path must allow slow uiautomator dumps (observed >4s) to finish",
      source.contains("private const val TICKET_WAKE_FAST_ROOT_DUMP_TIMEOUT_MILLIS = 8_000L")
    )
    assertTrue(source.contains("private const val TICKET_HIERARCHY_DEFAULT_TIMEOUT_MILLIS = 3_000L"))
    assertTrue(source.contains("private fun ticketWakeInProgress"))
    assertTrue(source.contains("TICKET_WAKE_RECOVERY_BUDGET_MILLIS + TICKET_WAKE_GUARD_GRACE_MILLIS"))
    assertFalse("foreground guard must not resume root probing while long wake recovery is still inside the extended wake budget", source.contains("TICKET_WAKE_BUDGET_MILLIS + TICKET_WAKE_GUARD_GRACE_MILLIS"))
    assertTrue(source.contains("wake_in_progress=true"))
    val launch = source.substringBetween("private suspend fun launchViviForWake", "private fun remainingWakeBudgetMillis")
    assertTrue("normal wake launch should use the reliable direct ViVi activity start", launch.contains("am start -n ${'$'}{TicketScreenConfig.VIVI_LAUNCH_ACTIVITY}"))
    assertFalse("normal wake launch should not use the non-root app launch path", launch.contains("launchVivi()"))
    assertFalse("normal wake launch should not spend time dismissing keyguard before starting ViVi", launch.contains("cmd window dismiss-keyguard"))
    assertFalse("normal wake launch should not spend time collapsing the shade", launch.contains("cmd statusbar collapse"))
    assertTrue("normal wake commands should use the dedicated wake root executor", source.contains("private val wakeRootExecutor = TicketRootCommandWorker()"))
    assertTrue(wake.contains("runFastNonTouchWakeScript("))
    assertTrue(launch.contains("runFastNonTouchWakeScript("))
    assertTrue(source.contains("wakeRootExecutor.runScript("))
    assertTrue(source.contains("postMillis = TICKET_WAKE_PANEL_SLEEP_CLAMP_POST_MILLIS"))
    assertTrue(source.contains("commandTimeout = TICKET_WAKE_COMMAND_TIMEOUT_MILLIS.milliseconds"))
    assertTrue("readiness failure should stop as phone-not-ready before encoder start", hardwareStart.indexOf("if (!prepareResult.success)") < hardwareStart.indexOf("hardwareCaptureVerified = true"))
    assertTrue(hardwareStart.contains("Phone not ready: root could not confirm the ViVi ticket screen"))
  }

  @Test
  fun hardwareWakeUsesLightweightRecoveryActionsForKnownNonTicketViviScreens() {
    val source = ticketStreamServiceSource()
    val rootWakeObserver = source.substringBetween("private suspend fun observeTicketDetailForWakeWithRoot", "private fun markWakeReadyIfNeeded")
    val helper = source.substringBetween("private suspend fun attemptWakeRecoveryActionForRootWake", "private fun markWakeReadyIfNeeded")

    assertTrue(rootWakeObserver.contains("maxRecoveryActions: Int = TICKET_WAKE_RECOVERY_MAX_ACTIONS"))
    assertTrue(rootWakeObserver.contains("wakeRecoveryActions < maxRecoveryActions"))
    assertTrue(rootWakeObserver.contains("maxOf(budgetMillis, TICKET_WAKE_RECOVERY_BUDGET_MILLIS)"))
    assertTrue(rootWakeObserver.contains("attemptWakeRecoveryActionForRootWake("))
    assertTrue(rootWakeObserver.contains("delay(minOf(TICKET_WAKE_RECOVERY_ACTION_SETTLE_MILLIS"))
    assertTrue(source.contains("private const val TICKET_WAKE_RECOVERY_BUDGET_MILLIS = 24_000L"))
    assertTrue(source.contains("private const val TICKET_WAKE_RECOVERY_MAX_ACTIONS = 4"))
    assertTrue(helper.contains("TicketViviPageEnforcer.recoveryActionForHierarchy(hierarchy)"))
    assertTrue(
      "wake recovery taps are touch/input work; keep them on the input lane so they do not queue behind wake-root hierarchy reads",
      helper.contains("runFastNonTouchInput(")
    )
    assertFalse(
      "known ViVi wake recovery actions must not run on the wake-root hierarchy executor",
      helper.contains("runFastNonTouchWakeInput(")
    )
    assertTrue(helper.contains("wake_recovery_action"))
    assertFalse("wake recovery should stay lightweight, not use the slow autopilot", rootWakeObserver.contains("driveToTicketDetail("))
    assertFalse("wake recovery should not force-stop ViVi", rootWakeObserver.contains("force-stop ${'$'}{TicketScreenConfig.VIVI_PACKAGE}"))
  }

  @Test
  fun hardwareWakeHealsStaleGeneratedCodeBeforeFailingNotReady() {
    val source = ticketStreamServiceSource()
    val rootWakeObserver = source.substringBetween("private suspend fun observeTicketDetailForWakeWithRoot", "private fun markWakeReadyIfNeeded")
    val heal = source.substringBetween("private suspend fun healGeneratedControlCodeResultForRequest", "private suspend fun returnControlCodeSurfaceToRawTicket")

    assertTrue(rootWakeObserver.contains("state == TicketViviRecoveryState.CONTROL_CODE_RESULT"))
    assertTrue(rootWakeObserver.contains("healGeneratedControlCodeResultForRequest("))
    assertTrue(rootWakeObserver.contains("freshFrameRequired = false"))
    assertTrue(rootWakeObserver.contains("attemptedGeneratedWakeHeal = true"))
    assertTrue(rootWakeObserver.contains("return TicketAutopilotResult(true, TicketViviRecoveryState.TICKET_DETAIL, lastStep)"))
    assertTrue(heal.contains("freshFrameRequired: Boolean = true"))
    assertTrue(heal.contains("freshFrameRequested = freshFrameRequired"))
  }

  @Test
  fun staleKeyframeRecoveryIsSuppressedDuringHardwareStartup() {
    val source = ticketStreamServiceSource()
    val stale = source.substringBetween("private fun activeStreamStaleForRecovery(nowMillis: Long): Boolean", "private fun sendStatus")

    assertTrue(stale.contains("if (hardwareStartupStillPreparing())"))
    assertTrue(source.contains("private fun hardwareStartupStillPreparing(): Boolean"))
    assertTrue(source.contains("ticketSessionState == TICKET_SESSION_STARTING"))
    assertTrue(source.contains("!hardwareCaptureVerified"))
    assertTrue(source.contains("encodedFrames == 0L"))
    assertTrue(stale.indexOf("hardwareStartupStillPreparing()") < stale.indexOf("val lastFrameAge"))
  }

  @Test
  fun rootHierarchyDumpLockWaitCountsAgainstWakeTimeout() {
    val source = ticketScreenObserverSource()

    assertTrue(source.contains("withTimeoutOrNull(timeoutMillis.milliseconds)"))
    assertTrue(source.contains("remainingTimeoutMillis(startedAtMillis, timeoutMillis)"))
    assertTrue(source.indexOf("withTimeoutOrNull(timeoutMillis.milliseconds)") < source.indexOf("rootDumpMutex.withLock"))
    assertTrue(source.indexOf("rootDumpMutex.withLock") < source.indexOf("rootExecutor.runScript("))
    assertTrue(source.contains("root hierarchy dump timed out after ${'$'}timeoutMillis ms"))
  }

  @Test
  fun browserCaptureAckHandlerIsRemoved() {
    val source = ticketStreamServiceSource()

    assertFalse(source.contains("handleControlCodeBrowserCaptureAck("))
    assertFalse(source.contains("\"control_code_browser_capture\" -> {"))
    assertFalse(source.contains("control_code_browser_capture_failed"))
  }

  @Test
  fun cleanupHierarchyUsesRootDumpOnly() {
    val source = ticketStreamServiceSource()
    val helper = source.substringBetween("private suspend fun controlExitHierarchy()", "private fun inactivityRemainingMillis")

    assertTrue(helper.contains("dumpViviHierarchy(fresh = true, timeoutMillis = CONTROL_EXIT_ROOT_DUMP_TIMEOUT_MILLIS)"))
    assertFalse("control-exit hierarchy must not use accessibility nodes", helper.contains("PhoneAutomationServiceBridge.snapshotVisibleNodes"))
    assertFalse("control-exit hierarchy must not classify accessibility first", helper.contains("accessibilityState"))
  }

  @Test
  fun normalTicketFlowHasNoNonRootAccessibilityFallbacks() {
    val source = ticketStreamServiceSource()
    val forbiddenSections = listOf(
      source.substringBetween("private suspend fun prepareViviForRootHardwareH264Capture", "private fun scheduleRootHardwareH264CaptureStart"),
      source.substringBetween("private suspend fun verifyViviTicketDetailHierarchy", "private fun recentTicketDetailForSecureCapture"),
      source.substringBetween("private suspend fun prepareViviForControlCodeRequest", "private suspend fun openControlCodePopupFastForRequest"),
      source.substringBetween("private suspend fun tapControlCodeSubmitFastForRequest", "private suspend fun waitForGeneratedControlCodeResultAfterSubmit"),
      source.substringBetween("private suspend fun waitForGeneratedControlCodeResultAfterSubmit", "private suspend fun controlCodeRequestRootHierarchy"),
      source.substringBetween("private suspend fun controlCodeRequestRootHierarchy", "private fun controlCodeFastTargetsForHierarchy"),
      source.substringBetween("private suspend fun waitForCleanTicketSurfaceFast", "private suspend fun completeFastVerifiedTicketDetailControlExitCleanup"),
      source.substringBetween("private suspend fun observeControlExitVerificationState", "private suspend fun completeVerifiedTicketDetailControlExitCleanup")
    )
    val forbidden = listOf(
      "ticketAutopilot.observeFastState",
      "ticketAutopilot.driveToTicketDetail",
      "PhoneAutomationServiceBridge.snapshotVisibleNodes",
      "PhoneAutomationServiceBridge.clickSelectors",
      "PhoneAutomationServiceBridge.performBack",
      "PhoneAutomationServiceBridge.setTextInFocusedInput",
      "PhoneAutomationServiceBridge.setTextInFirstEditableInput",
      "PhoneAutomationServiceBridge.openFirstEditableInput"
    )

    forbiddenSections.forEach { section ->
      forbidden.forEach { needle ->
        assertFalse("normal ticket flow still contains $needle", section.contains(needle))
      }
    }
  }

  @Test
  fun pixelTicketStateEventsAnchorGeneratedAndRawFrames() {
    val source = ticketStreamServiceSource()
    val generate = source.substringBetween("private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    val cleanup = source.substringBetween("private suspend fun completeControlExitCleanup", "private fun recordControlExitCleanup")
    val eventSender = source.substringBetween("private fun sendTicketStateEvent", "private fun sendControlCodeResult")

    assertTrue(source.contains("private const val TICKET_PIXEL_STATE_RAW_TICKET = \"raw_ticket\""))
    assertTrue(source.contains("private const val TICKET_PIXEL_STATE_CONTROL_POPUP = \"control_popup\""))
    assertTrue(source.contains("private const val TICKET_PIXEL_STATE_GENERATED_RESULT = \"generated_result\""))
    assertTrue(source.contains("private const val TICKET_PIXEL_STATE_RETURNING_RAW = \"returning_raw\""))
    assertTrue(source.contains("waitForFreshControlCodeFrameWatermarkForBrowser("))
    assertTrue(source.contains("reason = \"control_code_marker_ready\""))
    assertTrue(generate.contains("sendTicketStateEvent("))
    assertTrue(generate.contains("ticketState = TICKET_PIXEL_STATE_GENERATED_RESULT"))
    assertTrue(cleanup.contains("sendTicketStateEvent("))
    assertTrue(cleanup.contains("ticketState = TICKET_PIXEL_STATE_RAW_TICKET"))
    assertTrue(source.contains("ticketState = TICKET_PIXEL_STATE_RETURNING_RAW"))
    assertTrue(source.contains("if (!hardwareFrameBroadcastAllowed)"))
    assertTrue(source.contains("val firstVisibleFrame = sentFrames == 0L"))
    assertTrue(eventSender.contains("put(\"type\", \"ticket_state_event\")"))
    assertTrue(eventSender.contains("put(\"eventSeq\", eventSeq)"))
    assertTrue(eventSender.contains("put(\"ticketState\", ticketState)"))
    assertTrue(eventSender.contains("put(\"streamEpoch\", eventStreamEpoch)"))
    assertTrue(eventSender.contains("put(\"frameSequence\", eventFrameSequence)"))
    assertTrue(eventSender.contains("put(\"requestId\", requestId)"))
    assertTrue(eventSender.contains("put(\"value\", value)"))
    assertTrue(eventSender.contains("put(\"totalDurationMillis\", totalDurationMillis)"))
    assertTrue(eventSender.contains("put(\"phases\", phaseJson)"))
    assertTrue(generate.contains("totalDurationMillis = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)"))
    assertTrue(generate.contains("phases = phases"))
  }

  @Test
  fun productionControlCodePathSendsAppGeneratedQrImage() {
    val source = ticketStreamServiceSource()
    val generate = source.substringBetween("private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    val rsFlow = source.substringBetween("private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr", "private suspend fun runFastControlCodeDeliveryForRequest")
    val delivery = source.substringBetween("private suspend fun runFastControlCodeDeliveryForRequest", "private suspend fun prepareViviForControlCodeRequest")

    assertFalse(generate.contains("PendingBrowserControlCodeCapture("))
    assertFalse(generate.contains("encodeControlCodeImageBase64("))
    assertFalse("resultImage must not make the ticket.jolkins/ViVi path emit RS-bot image results", generate.contains("sendRigassatiksmeQrResult("))
    assertFalse("resultImage must not make the ticket.jolkins/ViVi path call ViVi monthly-ticket image capture", generate.contains("runFastViviMonthlyTicketImageDeliveryForRequest"))
    assertTrue(rsFlow.contains("captureRigasSatiksmeMonthlyTicketImageBytes("))
    assertTrue(rsFlow.contains("sendRigassatiksmeQrResult("))
    assertTrue(source.contains("put(\"type\", \"rigassatiksme_qr_result\")"))
    assertTrue(source.contains("put(\"imageBase64\", imageBase64)"))
    assertTrue(source.contains("put(\"imageMime\", imageMime)"))
    assertFalse(delivery.contains("captureGeneratedControlCodeImageBytes("))
    assertFalse(delivery.contains("encodeControlCodeImageBase64("))
    assertTrue(delivery.contains("streamEpoch = watermark.first"))
    assertTrue(delivery.contains("minFrameSequence = watermark.second"))
  }

  @Test
  fun rsMonthlyTicketImageResultFailureDoesNotReportGeneratedAsReason() {
    val source = ticketStreamServiceSource()
    val sender = source.substringBetween("private fun sendRigassatiksmeQrResult", "private fun sendControlCodeCleanup")

    assertTrue("RS image result failures that reached the generated screen but missed the PNG must be reported as qr_image_missing so the broker retries instead of recording failed/generated incidents", sender.contains("normalizeRigassatiksmeQrResultReason("))
    assertFalse("The failure reason must not pass through generated when no image bytes were accepted", sender.contains("if (accepted) reason else reason.ifBlank { \"qr_image_missing\" }"))
    assertTrue(sender.contains("put(\"reason\", normalizedReason)"))
  }

  @Test
  fun productionRigasSatiksmeQrPathRequiresRsPassengerMonthlyTicketFlow() {
    val service = ticketStreamServiceSource()
    val config = ticketScreenConfigSource()
    val dispatcher = service.substringBetween("\"generate_control_code\" -> {", "\"prepare_control_code\" -> {")
    val handle = service.substringBetween("private suspend fun handleGenerateControlCode", "private suspend fun runFastControlCodeDeliveryForRequest")
    val rsFlow = service.substringBetween("private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr", "private suspend fun runRigasSatiksmeMonthlyTicketFlow")
    val rsDriver = service.substringBetween("private suspend fun runRigasSatiksmeMonthlyTicketFlow", "private suspend fun runFastControlCodeDeliveryForRequest")

    assertTrue(config.contains("const val RIGAS_SATIKSME_PACKAGE = \"com.flutter.rspassenger\""))
    assertTrue(config.contains("const val RIGAS_SATIKSME_LAUNCH_ACTIVITY = \"com.flutter.rspassenger/.MainActivity\""))
    assertTrue(config.contains("const val TICKET_QR_APP_VIVI = \"vivi\""))
    assertTrue(config.contains("const val TICKET_QR_APP_RIGAS_SATIKSME = \"rigas_satiksme\""))
    assertTrue(config.contains("const val TICKET_QR_FLOW_CONTROL_CODE = \"control_code\""))
    assertTrue(config.contains("const val TICKET_QR_FLOW_MONTHLY_TICKET = \"monthly_ticket\""))
    assertTrue(config.contains("const val TICKET_QR_RESULT_SOURCE_APP_RIGAS_SATIKSME = RIGAS_SATIKSME_PACKAGE"))
    assertTrue(config.contains("const val TICKET_QR_RESULT_FLOW_RIGAS_SATIKSME_ANDROID_MONTHLY = \"rigas_satiksme_android_monthly_ticket_control\""))
    assertTrue(dispatcher.contains("val app = element[\"app\"]?.jsonPrimitive?.contentOrNull.orEmpty()"))
    assertTrue(dispatcher.contains("val flow = element[\"flow\"]?.jsonPrimitive?.contentOrNull.orEmpty()"))
    assertTrue(dispatcher.contains("handleGenerateControlCode(client, requestId, digits, app, flow, resultImage)"))
    assertTrue(handle.contains("TicketScreenConfig.TICKET_QR_APP_RIGAS_SATIKSME"))
    assertTrue(handle.contains("TicketScreenConfig.TICKET_QR_FLOW_MONTHLY_TICKET"))
    assertTrue("ticket.jolkins control-code messages without an app must stay on the ViVi flow", handle.contains("val requestedApp = app.trim().ifBlank { TicketScreenConfig.TICKET_QR_APP_VIVI }"))
    assertTrue("blank flow must not opt ticket.jolkins into the RS monthly-ticket flow", handle.contains("val requestedFlow = flow.trim()"))
    assertTrue("ViVi control-code path must reject unrelated explicit flows", handle.contains("requestedFlow != TicketScreenConfig.TICKET_QR_FLOW_CONTROL_CODE"))
    assertFalse("RS monthly-ticket flow must require an explicit RS app request", handle.contains("app.trim().ifBlank { TicketScreenConfig.TICKET_QR_APP_RIGAS_SATIKSME }"))
    assertFalse("blank flow must not default to monthly_ticket", handle.contains("flow.trim().ifBlank { TicketScreenConfig.TICKET_QR_FLOW_MONTHLY_TICKET }"))
    assertTrue(handle.contains("handleGenerateRigasSatiksmeMonthlyTicketQr("))
    assertTrue("Rīgas Satiksme path must pause the ViVi foreground guard before launching the RS app", rsFlow.contains("markControlCodeTransition(\"rs_monthly_ticket_request\")"))
    assertFalse("Rīgas Satiksme QR path must not dispatch to the ViVi popup delivery", rsFlow.contains("runFastControlCodeDeliveryForRequest("))
    assertFalse("Rīgas Satiksme QR path must not prepare the ViVi ticket app", rsFlow.contains("prepareViviForControlCodeRequest("))
    assertTrue("Rīgas Satiksme QR path must return the phone to the ViVi ticket after finishing", rsFlow.contains("returnRigasSatiksmeMonthlyTicketFlowToViviTicket("))
    assertTrue("Rīgas Satiksme return may need several ViVi page-recovery taps", rsFlow.contains("maxRecoveryActions = TICKET_RS_MONTHLY_RETURN_MAX_RECOVERY_ACTIONS"))
    assertTrue("Rīgas Satiksme return should use an extended budget without slowing normal wake", rsFlow.contains("budgetMillis = TICKET_RS_MONTHLY_RETURN_BUDGET_MILLIS"))
    assertTrue("Rīgas Satiksme failures should report immediately with cleanup still pending", rsFlow.contains("cleanupPending = true"))
    assertTrue("Rīgas Satiksme path must publish cleanup completion back to the browser", rsFlow.contains("sendControlCodeCleanup("))
    assertTrue(rsDriver.contains("TicketScreenConfig.RIGAS_SATIKSME_LAUNCH_ACTIVITY"))
    assertTrue(rsDriver.contains("am force-stop ${'$'}{TicketScreenConfig.VIVI_PACKAGE}"))
    assertFalse("RS pressure latency must not cold-force-stop the RS app for every queued request", rsDriver.contains("am force-stop ${'$'}{TicketScreenConfig.RIGAS_SATIKSME_PACKAGE}"))
    assertFalse("Default RS launch should not spend the fast path budget on a pre-entry root readiness dump", rsDriver.contains("wait_rs_initial_ready /sdcard/pixel-rs-initial-window.xml"))
    assertTrue("Queued RS pressure jobs should pass a warm previous-QR hint into the runner so they do not pay uiautomator home discovery every time", rsFlow.contains("reusePreviousRigasSatiksmeQr = cancelPendingRigasSatiksmeReturnCleanup(\"new_rs_monthly_ticket_request\")") && rsDriver.contains("reusePreviousRigasSatiksmeQr: Boolean"))
    assertFalse("A fixed 3.8s launch sleep prevents warm queued requests from reaching the 15s average target", rsDriver.contains("sleep 3.8"))
    assertTrue(rsDriver.contains("REGISTER A TRIP"))
    assertTrue(rsDriver.contains("Trip is registered"))
    assertTrue("RS monthly-ticket path should use the coordinate fast path before any slow recovery", rsDriver.contains("RS_FAST_FLOW_STATUS"))
    assertTrue("Reuse of the previous RS QR must keep the direct Back behavior from the queued-request fast path", rsDriver.contains("input keyevent KEYCODE_BACK"))
    assertFalse("Default cold/unknown RS launch should not pay for the old initial hierarchy dump", rsDriver.contains("pixel-rs-initial-window.xml"))
    assertTrue("Queued warm RS requests may skip the slow initial hierarchy dump only when Android just canceled a pending RS cleanup", rsDriver.contains("reuse_previous_rs_qr") && rsDriver.contains("= \"true\" ]; then"))
    val fastPathBranches = rsDriver.substringBetween("if [ \"\${'$'}reuse_previous_rs_qr\" = \"true\" ]; then", "fi\n      sleep 0.75")
    val warmPreviousQrBranch = fastPathBranches.substringBefore("\n      else\n")
    val nonReuseFastBranch = fastPathBranches.substringAfter("\n      else\n")
    assertTrue(
      "When Android cancels the idle cleanup before it starts, the phone is still on the previous RS QR; close it directly instead of spending ~2.4s on a warm-probe uiautomator dump",
      warmPreviousQrBranch.contains("input keyevent KEYCODE_BACK >/dev/null 2>&1 || true")
    )
    assertFalse(
      "Warm queued RS jobs must not pay the measured warm-probe uiautomator dump before entering the next code",
      warmPreviousQrBranch.contains("uiautomator dump /sdcard/pixel-rs-warm-probe-window.xml") ||
        warmPreviousQrBranch.contains("pixel-rs-warm-probe-window.xml")
    )
    assertTrue(
      "After directly closing the previous QR, warm queued jobs should use the measured REGISTER A TRIP center before manual-code entry",
      warmPreviousQrBranch.indexOf("input keyevent KEYCODE_BACK >/dev/null 2>&1 || true") <
        warmPreviousQrBranch.indexOf("input tap 540 555 >/dev/null 2>&1 || true")
    )
    assertTrue(
      "Default non-reuse RS jobs should skip the initial root hierarchy dump and tap the measured RS Passenger 2.1.0 REGISTER A TRIP center directly",
      nonReuseFastBranch.contains("input tap 540 555 >/dev/null 2>&1 || true")
    )
    assertFalse(
      "Default non-reuse RS jobs must not spend request latency on wait_rs_initial_ready before manual-code entry",
      nonReuseFastBranch.contains("wait_rs_initial_ready") ||
        nonReuseFastBranch.contains("pixel-rs-initial-window.xml") ||
        nonReuseFastBranch.contains("uiautomator dump") ||
        nonReuseFastBranch.contains("open_rs_control_entry")
    )
    assertTrue("When REGISTER A TRIP is available, the runner must enter that path and submit the inspector code before expecting the control QR", rsDriver.indexOf("input tap 540 555") < rsDriver.indexOf("input tap 540 2228"))
    val recoveryEntry = rsDriver.substringBetween("private suspend fun recoverRigasSatiksmeMonthlyTicketFlowBeforeFailure", "private fun classifyRigasSatiksmeMonthlyTicketHierarchy")
    assertTrue(
      "Recovery still handles the measured live-home screen by preferring REGISTER A TRIP before TICKET FOR CONTROL",
      recoveryEntry.indexOf("tap_rs_desc_center \"REGISTER A TRIP\"") in 0 until recoveryEntry.indexOf("tap_rs_desc_center \"TICKET FOR CONTROL\"")
    )
    assertTrue("The TICKET FOR CONTROL coordinate fallback must still avoid the old 540,555 edge tap; 540,555 is only allowed as REGISTER A TRIP fallback", rsDriver.contains("input tap 540 620") && rsDriver.contains("tap_rs_desc_center \"REGISTER A TRIP\"") && rsDriver.contains("input tap 540 555"))
    assertFalse("Default RS fast path should not double-tap a hierarchy-derived home control button before manual-code entry", nonReuseFastBranch.contains("sleep 0.16"))
    assertTrue(rsDriver.contains("input tap 540 2228"))
    assertTrue(rsDriver.contains("input tap 540 1150"))
    assertTrue("keyboard must be dismissed before tapping CONFIRM", rsDriver.contains("input keyevent KEYCODE_ENTER"))
    assertTrue(rsDriver.contains("input tap 540 1540"))
    assertTrue(rsDriver.contains("input tap 540 1347"))
    assertTrue(rsDriver.contains("input tap 540 735"))
    assertTrue(rsDriver.contains("TICKET FOR CONTROL"))
    assertTrue(service.contains("hasRigasSatiksmeMonthlyTicketMarker(hierarchy)"))
    assertTrue(service.contains("30 dienu biļete"))
    assertTrue(rsDriver.contains("Trip is registered"))
    assertTrue(rsDriver.contains("qr code"))
    assertTrue("RS result validation must prove the returned control screen contains the submitted code, not a stale prior ticket", rsDriver.contains("classifyRigasSatiksmeMonthlyTicketHierarchy(hierarchy, cleanDigits)"))
    assertTrue(rsDriver.contains("rs_monthly_ticket_control_screen"))
    assertTrue(rsDriver.contains("captureRigasSatiksmeMonthlyTicketImageBytes("))
    assertTrue(rsDriver.contains("rs_monthly_ticket_secure_capture_blocked"))
    assertTrue(rsDriver.contains("rs_monthly_ticket_app_screenshot_ready"))
    assertTrue(service.contains("sourceApp = TicketScreenConfig.TICKET_QR_RESULT_SOURCE_APP_RIGAS_SATIKSME"))

    assertTrue(rsFlow.contains("sourceApp = TicketScreenConfig.TICKET_QR_RESULT_SOURCE_APP_RIGAS_SATIKSME"))
    assertTrue(rsFlow.contains("ticketFlow = TicketScreenConfig.TICKET_QR_RESULT_FLOW_RIGAS_SATIKSME_ANDROID_MONTHLY"))
    assertTrue(service.contains("put(\"sourceApp\", sourceApp)"))
    assertTrue(service.contains("put(\"ticketFlow\", ticketFlow)"))
  }

  @Test
  fun rsMonthlyTicketControlScreenHierarchyOverridesNonzeroShellExit() {
    val service = ticketStreamServiceSource()
    val rsDriver = service.substringBetween("private suspend fun runRigasSatiksmeMonthlyTicketFlow", "private suspend fun runFastControlCodeDeliveryForRequest")

    assertTrue(
      "If the app hierarchy proves the RS ticket-control QR screen is visible, the flow must capture and send the image even when the shell script exits nonzero after a late/benign tap miss",
      rsDriver.contains("val reachedControlScreen = status == \"rs_monthly_ticket_control_screen\"")
    )
    assertTrue(rsDriver.contains("ok = reachedControlScreen"))
    assertFalse(
      "A nonzero shell exit must not turn an already-visible RS control screen into failed/generated with no image",
      rsDriver.contains("ok = result.ok && status == \"rs_monthly_ticket_control_screen\"")
    )
  }

  @Test
  fun rsMonthlyTicketSuccessSchedulesIdleCleanupOutsideCriticalResultPath() {
    val source = ticketStreamServiceSource()
    val handler = source.substringBetween("private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr", "private suspend fun returnRigasSatiksmeMonthlyTicketFlowToViviTicket")
    val successAfterResult = handler.substringBetween("sendRigassatiksmeQrResult(", "recordTicketEvent(\n          \"rs_monthly_ticket_control_screen\"")

    assertTrue("Queued RS jobs should cancel pending delayed ViVi cleanup and reuse the warm RS app", handler.contains("cancelPendingRigasSatiksmeReturnCleanup(\"new_rs_monthly_ticket_request\")"))
    assertTrue("Successful RS result should schedule ViVi return after an idle grace instead of blocking broker completion/next queued job", handler.contains("scheduleRigasSatiksmeReturnCleanupAfterIdle("))
    assertFalse("Successful RS path must not synchronously return to ViVi after the result while still inside the control-code mutex", successAfterResult.contains("returnRigasSatiksmeMonthlyTicketFlowToViviTicket("))
    assertTrue(source.contains("private var pendingRigasSatiksmeReturnCleanupJob: Job? = null"))
    assertTrue(source.contains("private var pendingRigasSatiksmeReturnCleanupStarted: Boolean = false"))
    assertTrue(source.contains("TICKET_RS_MONTHLY_IDLE_CLEANUP_DELAY_MILLIS"))
    assertTrue(
      "Only canceling the idle delay before cleanup starts should mark the next RS request as previous-QR reusable; canceling an already-started ViVi return is not a safe direct-Back fast path",
      source.contains("val reusePreviousQr = canceledActiveCleanup && !cleanupStarted") &&
        source.contains("pendingRigasSatiksmeReturnCleanupStarted = true")
    )
  }

  @Test
  fun rsMonthlyTicketResultScreenshotKeepsAndroidCaptureFullWidthForDeliveryCrop() {
    val source = ticketStreamServiceSource()
    val rsCapture = source.substringBetween("private suspend fun captureRigasSatiksmeMonthlyTicketImageBytes", "private suspend fun fastVisibleHierarchy")
    val capture = source.substringBetween("private suspend fun captureGeneratedControlCodeImageBytes", "private fun decodeBase64Png")
    val viviCrop = source.substringBetween("private fun cropControlCodeImage", "private fun looksLikePng")

    assertTrue("RS monthly-ticket result capture must keep the RS screenshot path separate from ViVi graphic cropping", rsCapture.contains("cropToControlCodeGraphic = false"))
    assertFalse("Android RS capture should not perform the Telegram top/bottom delivery crop", rsCapture.contains("cropRigasSatiksmeMonthlyTicketScreenshot"))
    assertFalse("Android service should not own the RS Telegram delivery crop constant", source.contains("RIGAS_SATIKSME_RESULT_IMAGE_VERTICAL_CROP_PIXELS"))
    assertTrue(capture.contains("cropToControlCodeGraphic: Boolean = true"))
    assertFalse("ViVi control-code graphic crop must remain separate from RS Telegram delivery cropping", viviCrop.contains("rigasSatiksme"))
  }

  @Test
  fun rsMonthlyTicketStillCaptureUsesSecureScreenCaptureHelperNotPlainScreencap() {
    val source = ticketStreamServiceSource()
    val capture = source.substringBetween("private suspend fun captureGeneratedControlCodeImageBytes", "private fun decodeBase64Png")
    val decoder = source.substringBetween("private fun decodeBase64Png", "private fun decodeBase64Bytes")
    val engine = rootHardwareH264CaptureEngineSource()
    val helper = rootHardwareH264CaptureMainSource()

    assertFalse("RS monthly-ticket still capture must not use plain screencap, which returns blank for secure RS layers", capture.contains("screencap -p"))
    assertTrue(capture.contains("rootHardwareH264CaptureEngine.captureSecurePngBase64("))
    assertFalse("RS monthly-ticket capture must not special-case ViVi as the source for Telegram RS-bot images", capture.contains("isViviMonthlyTicketControlScreen"))
    assertTrue(engine.contains("suspend fun captureSecurePngBase64("))
    assertTrue(engine.contains("--png-base64"))
    assertTrue(helper.contains("captureSecurePngBase64(sourceWidth, sourceHeight)"))
    assertTrue(helper.contains("SecureScreenCapture(sourceWidth, sourceHeight)"))
    assertTrue(helper.contains("PNG_BASE64_BEGIN"))
    assertTrue(helper.contains("PNG_BASE64_END"))
    assertTrue(helper.contains("Base64.NO_WRAP"))
    assertTrue(decoder.contains("PNG_BASE64_BEGIN"))
    assertTrue(decoder.contains("PNG_BASE64_END"))
  }

  @Test
  fun rsMonthlyTicketFlowUsesCoordinateFastPathWithSingleSemanticDump() {
    val source = ticketStreamServiceSource()
    val rsDriver = source.substringBetween("private suspend fun runRigasSatiksmeMonthlyTicketFlow", "private suspend fun captureRigasSatiksmeMonthlyTicketImageBytes")

    assertTrue("RS flow should use the measured coordinate fast path instead of repeated uiautomator polling", rsDriver.contains("RS_FAST_FLOW_STATUS"))
    assertTrue("Fast path must still do semantic monthly-ticket validation from live visible nodes", rsDriver.contains("fastVisibleHierarchy(TicketScreenConfig.RIGAS_SATIKSME_PACKAGE"))
    assertTrue("RS validation should try one cheap accessibility snapshot and then use bounded root fallback; production RS Flutter snapshots are usually empty", rsDriver.contains("rs_monthly_ticket_fast_hierarchy_wait_finished"))
    assertTrue(rsDriver.contains("classifyRigasSatiksmeMonthlyTicketHierarchy(hierarchy, cleanDigits)"))
    assertTrue("Fast path must use the measured REGISTER A TRIP center before the RS manual-code sequence", rsDriver.contains("input tap 540 555"))
    assertTrue(rsDriver.contains("input tap 540 2228"))
    assertTrue(rsDriver.contains("input tap 540 1150"))
    assertTrue(rsDriver.contains("input keyevent KEYCODE_ENTER"))
    assertTrue(rsDriver.contains("input tap 540 1540"))
    assertTrue(rsDriver.contains("input tap 540 1347"))
    assertTrue(rsDriver.contains("input tap 540 735"))
    assertFalse("Fast path should not spend the request budget on repeated uiautomator polling loops", rsDriver.contains("wait_for_text()"))
    assertFalse("Fast path should not spend the request budget on repeated uiautomator polling loops", rsDriver.contains("wait_for_any_text()"))
    assertFalse("A fixed 3.5s submit sleep dominates successful request latency", rsDriver.contains("sleep 3.5"))
    assertFalse("A fixed 2.5s launch sleep slows already-warm app starts", rsDriver.contains("sleep 2.5"))
    assertFalse("A fixed 2.0s monthly-ticket open sleep should be readiness-gated", rsDriver.contains("sleep 2.0"))
    assertFalse("A fixed 1.5s navigation sleep should be readiness-gated", rsDriver.contains("sleep 1.5"))
    assertFalse("A fixed 1.4s manual-code fallback sleep should be readiness-gated", rsDriver.contains("sleep 1.4"))
    assertFalse("A fixed 0.9s monthly-ticket list sleep should be readiness-gated", rsDriver.contains("sleep 0.9"))
    assertFalse("RS Flutter fast hierarchy is empty in production; do not spend 1.8s polling it before root fallback", rsDriver.contains("hierarchyWaitStartedAt <= 1_800L") || rsDriver.contains("<= 1_800L)"))
    assertTrue("Root fallback remains the semantic source-of-truth after the single cheap fast snapshot", rsDriver.indexOf("fastVisibleHierarchy(TicketScreenConfig.RIGAS_SATIKSME_PACKAGE") < rsDriver.indexOf("dumpVisibleHierarchyWithRootRetry(\n        path = \"/data/local/tmp/pixel-rs-ticket-window.xml\""))
  }

  @Test
  fun rsMonthlyTicketRootHierarchyFilesUseLocalTmpNotSharedSdcard() {
    val source = ticketStreamServiceSource()
    val rsDriver = source.substringBetween("private suspend fun runRigasSatiksmeMonthlyTicketFlow", "private suspend fun captureRigasSatiksmeMonthlyTicketImageBytes")

    assertFalse("RS/ViVi root hierarchy proof files must not live under shared /sdcard because APK redeploys can change app UID and strand stale unreadable files", rsDriver.contains("/sdcard/pixel-rs"))
    assertFalse("RS return hierarchy proof files must not live under shared /sdcard", rsDriver.contains("/sdcard/pixel-vivi-fast-return-window.xml"))
    assertTrue("RS root hierarchy proof files should live in /data/local/tmp where root/shell cleanup is stable across redeploys", rsDriver.contains("/data/local/tmp/pixel-rs-ticket-window.xml"))
  }

  @Test
  fun rsMonthlyTicketRootFallbackRereadsDumpFileWhenWrapperStdoutIsEmpty() {
    val source = ticketStreamServiceSource()
    val rootDump = source.substringBetween("private suspend fun dumpVisibleHierarchyWithRoot", "private suspend fun recoverRigasSatiksmeMonthlyTicketFlowBeforeFailure")

    assertTrue("A successful uiautomator dump can leave the hierarchy file but return empty stdout from the app-root wrapper; reread the file before declaring missing", rootDump.contains("if (result.stdout.isBlank())"))
    assertTrue("The reread must use the exact dump path, not a stale hard-coded path", rootDump.contains("java.io.File(path)") && rootDump.contains("/system/bin/cat ${'$'}path"))
    assertTrue("The fallback should emit its own event so empty-wrapper incidents remain visible", rootDump.contains("root_visible_hierarchy_file_read"))
  }

  @Test
  fun rsMonthlyTicketRootHierarchyFallbackUsesWakeExecutorOutsideInputWorker() {
    val source = ticketStreamServiceSource()
    val rootDump = source.substringBetween("private suspend fun dumpVisibleHierarchyWithRoot", "private suspend fun recoverRigasSatiksmeMonthlyTicketFlowBeforeFailure")
    val runFastScript = source.substringBetween("private suspend fun runFastNonTouchScript", "private fun wrapNonTouchPanelSleepClamp")

    assertTrue(
      "RS/ViVi semantic hierarchy fallback must not run through the long input worker: a timed out coordinate script can leave that worker wedged, so the proof dump has to use the independent wake/root lane",
      rootDump.contains("runFastNonTouchWakeScript(")
    )
    assertFalse(
      "The hierarchy proof helper must not call runFastNonTouchScript, which is backed by inputRootExecutor and couples proof collection to coordinate-script timeouts",
      rootDump.contains("runFastNonTouchScript(")
    )
    assertTrue(
      "The original long-script lane should remain on inputRootExecutor for touch/coordinate scripts",
      runFastScript.contains("inputRootExecutor.runScript")
    )
  }

  @Test
  fun ticketUiautomatorDumpUsesAbsoluteToolPathsForAppRootShell() {
    val dumpSource = ticketUiautomatorDumpSource()

    assertTrue("App-root su shells can have a reduced PATH; use absolute uiautomator path", dumpSource.contains("/system/bin/uiautomator dump"))
    assertTrue("The safe dump wrapper should not depend on PATH for timeout", dumpSource.contains("/system/bin/timeout -k"))
    assertTrue("The safe dump wrapper should not depend on PATH for flock", dumpSource.contains("/system/bin/flock -x -n"))
  }

  @Test
  fun rsMonthlyTicketSemanticRootFallbackRetriesBlankLockMissBeforeRecovery() {
    val source = ticketStreamServiceSource()
    val rsDriver = source.substringBetween("private suspend fun runRigasSatiksmeMonthlyTicketFlow", "private suspend fun captureRigasSatiksmeMonthlyTicketImageBytes")
    val recovery = source.substringBetween("private suspend fun recoverRigasSatiksmeMonthlyTicketFlowBeforeFailure", "private fun classifyRigasSatiksmeMonthlyTicketHierarchy")
    val retry = source.substringBetween("private suspend fun dumpVisibleHierarchyWithRootRetry", "private suspend fun dumpVisibleHierarchyWithRoot")

    assertTrue(
      "A blank root fallback can be a transient uiautomator lock miss; retry semantic proof before re-driving a QR that may already be visible",
      rsDriver.contains("dumpVisibleHierarchyWithRootRetry(\n        path = \"/data/local/tmp/pixel-rs-ticket-window.xml\"")
    )
    assertTrue(
      "Recovery verification should use the same retrying semantic root proof before declaring the RS control screen missing",
      recovery.contains("dumpVisibleHierarchyWithRootRetry(\n        path = \"/data/local/tmp/pixel-rs-ticket-recovery-window.xml\"")
    )
    assertTrue(retry.contains("repeat(RS_SEMANTIC_ROOT_DUMP_ATTEMPTS)"))
    assertTrue(retry.contains("root_visible_hierarchy_retry"))
    assertTrue(source.contains("private const val RS_SEMANTIC_ROOT_DUMP_ATTEMPTS = 3"))
  }

  @Test
  fun rsMonthlyTicketDefaultFastPathSkipsInitialRootDumpBeforeManualCodeEntry() {
    val source = ticketStreamServiceSource()
    val rsDriver = source.substringBetween("private suspend fun runRigasSatiksmeMonthlyTicketFlow", "private suspend fun captureRigasSatiksmeMonthlyTicketImageBytes")
    val defaultBranch = rsDriver.substringBetween(
      "else\n        # Coordinate-first default path:",
      "      fi\n      sleep 0.75"
    )

    assertTrue(
      "Default non-reuse RS requests should tap the measured REGISTER A TRIP center directly",
      defaultBranch.contains("input tap 540 555 >/dev/null 2>&1 || true")
    )
    assertFalse(
      "Default non-reuse fast path must not run the old initial readiness helper before manual-code entry",
      defaultBranch.contains("wait_rs_initial_ready")
    )
    assertFalse(
      "Default non-reuse fast path must not run a uiautomator dump before manual-code entry",
      defaultBranch.contains("uiautomator dump") || defaultBranch.contains("/sdcard/pixel-rs-initial-window.xml")
    )
    assertTrue(
      "Final semantic verification must still follow the coordinate-first branch",
      rsDriver.indexOf("      fi\n      sleep 0.75") <
        rsDriver.indexOf("classifyRigasSatiksmeMonthlyTicketHierarchy(hierarchy, cleanDigits)")
    )
  }

  @Test
  fun rsMonthlyTicketFlowUsesRootHierarchyFallbackBeforeRsRecoveryWhenFastSnapshotIsIncomplete() {
    val source = ticketStreamServiceSource()
    val rsDriver = source.substringBetween("private suspend fun runRigasSatiksmeMonthlyTicketFlow", "private suspend fun captureRigasSatiksmeMonthlyTicketImageBytes")
    val handler = source.substringBetween("private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr", "private fun cancelPendingRigasSatiksmeReturnCleanup")
    val classifier = source.substringBetween("private fun classifyRigasSatiksmeMonthlyTicketHierarchy", "private fun hasRigasSatiksmeMonthlyTicketMarker")

    assertTrue(
      "RS Flutter fast snapshots can be non-empty but incomplete; verify the bounded root dump before re-driving the RS flow",
      rsDriver.contains("statusFromHierarchy != \"rs_monthly_ticket_control_screen\" && statusFromHierarchy != \"wrong_code\"") &&
        rsDriver.contains("val rootHierarchy = dumpVisibleHierarchyWithRootRetry(")
    )
    assertTrue(
      "Root fallback should be tried before in-app recovery so an already-visible QR is captured without another registration attempt",
      rsDriver.indexOf("reason = \"rs_monthly_ticket_flow_fallback\"") < rsDriver.indexOf("recoverRigasSatiksmeMonthlyTicketFlowBeforeFailure(")
    )
    assertTrue(
      "Root fallback must classify the root hierarchy against the requested digits before any recovery or success path",
      rsDriver.indexOf("classifyRigasSatiksmeMonthlyTicketHierarchy(rootHierarchy, cleanDigits)") in
        0 until rsDriver.indexOf("recoverRigasSatiksmeMonthlyTicketFlowBeforeFailure(")
    )
    assertTrue(
      "The classifier must require both RS monthly-ticket control markers and the requested digits for success",
      classifier.indexOf("hasControlScreen && has(cleanDigits)") in 0 until classifier.indexOf("return \"rs_monthly_ticket_control_screen\"")
    )
    assertTrue(
      "Image capture/result must remain gated behind a successful RS monthly-ticket flow",
      handler.indexOf("if (!flow.ok)") in 0 until handler.indexOf("captureRigasSatiksmeMonthlyTicketImageBytes(") &&
        handler.indexOf("captureRigasSatiksmeMonthlyTicketImageBytes(") < handler.indexOf("sendRigassatiksmeQrResult(")
    )
  }

  @Test
  fun rsMonthlyTicketFastPathDismissesLateTripRegisteredModalBeforeRecovery() {
    val source = ticketStreamServiceSource()
    val rsDriver = source.substringBetween("private suspend fun runRigasSatiksmeMonthlyTicketFlow", "private suspend fun captureRigasSatiksmeMonthlyTicketImageBytes")
    val afterRegisterSubmit = rsDriver.substringAfter("input tap 540 1540")
    val beforeFastStatus = afterRegisterSubmit.substringBefore("RS_FAST_FLOW_STATUS")
    val okTapCount = Regex(Regex.escape("input tap 540 1347")).findAll(beforeFastStatus).count()
    val lastOkTap = beforeFastStatus.lastIndexOf("input tap 540 1347")
    val finalControlTap = afterRegisterSubmit.lastIndexOf("input tap 540 735")

    assertTrue(
      "RS can show the 'Trip is registered' modal late; fast path should send at least three centered OK taps so success does not spend ~9s in recovery",
      okTapCount >= 3
    )
    assertTrue(
      "The late-modal OK probe must happen before the final TICKET FOR CONTROL/QR tap, preserving recovery only for real navigation failures",
      lastOkTap in 0 until finalControlTap
    )
    assertFalse(
      "Late-modal handling should stay a cheap tap/cushion, not add another slow uiautomator dump before the semantic root fallback",
      afterRegisterSubmit.substringBefore("RS_FAST_FLOW_STATUS").contains("uiautomator dump")
    )
  }

  @Test
  fun rsMonthlyTicketFlowRetriesInsideRsAppBeforeReportingRecoverableNavigationFailure() {
    val source = ticketStreamServiceSource()
    val rsDriver = source.substringBetween("private suspend fun runRigasSatiksmeMonthlyTicketFlow", "private suspend fun captureRigasSatiksmeMonthlyTicketImageBytes")

    val recoveryCall = rsDriver.indexOf("recoverRigasSatiksmeMonthlyTicketFlowBeforeFailure(")
    assertTrue(
      "False missing-monthly/control states should get a bounded in-app RS recovery before slow ViVi cleanup or broker retry",
      recoveryCall >= 0
    )
    assertTrue(
      "RS in-app recovery must run after the first semantic hierarchy check but before final reason mapping",
      recoveryCall > rsDriver.indexOf("statusFromHierarchy = classifyRigasSatiksmeMonthlyTicketHierarchy(hierarchy, cleanDigits)") &&
        recoveryCall < rsDriver.indexOf("val reason = when (status)")
    )
    assertTrue(source.contains("rs_monthly_ticket_recovery_started"))
    assertTrue(source.contains("rs_monthly_ticket_recovery_finished"))
    assertTrue(source.contains("rs_monthly_ticket_recovery_fallback"))
    val recovery = source.substringBetween("private suspend fun recoverRigasSatiksmeMonthlyTicketFlowBeforeFailure", "private fun classifyRigasSatiksmeMonthlyTicketHierarchy")
    assertTrue("RS recovery must handle the live-home screen seen in pressure tests by tapping the hierarchy-derived TICKET FOR CONTROL button before giving up", recovery.contains("tap_rs_desc_center \"TICKET FOR CONTROL\" /data/local/tmp/pixel-rs-recovery-start-window.xml"))
  }

  @Test
  fun rsMonthlyTicketStaleQrRecoveryHasBudgetForBackoutAndFreshEntry() {
    val source = ticketStreamServiceSource()
    val recovery = source.substringBetween("private suspend fun recoverRigasSatiksmeMonthlyTicketFlowBeforeFailure", "private fun classifyRigasSatiksmeMonthlyTicketHierarchy")

    assertTrue(
      "Stale RS QR recovery starts on an already-open QR, so it must back out before entering a fresh inspector code",
      recovery.contains("stale_control_ticket") &&
        recovery.contains("input keyevent KEYCODE_BACK") &&
        recovery.contains("input tap 540 555")
    )
    assertTrue(
      "Live stale-code smoke timed out at the old 4s ceiling while recovering from a stale QR; keep a dedicated RS in-app recovery cushion",
      source.contains("private const val TICKET_RS_MONTHLY_IN_APP_RECOVERY_TIMEOUT_MILLIS = 8_000L") &&
        recovery.contains("TICKET_RS_MONTHLY_IN_APP_RECOVERY_TIMEOUT_MILLIS.milliseconds")
    )
    assertFalse(
      "RS in-app recovery must not keep the old hard-coded 4s timeout; that is shorter than QR backout plus two guarded dumps and taps",
      recovery.contains("4_000.milliseconds")
    )
  }

  @Test
  fun rsMonthlyTicketStaleQrRecoveryUsesKnownStatusBeforeHierarchyProbe() {
    val recovery = ticketStreamServiceSource().substringBetween(
      "private suspend fun recoverRigasSatiksmeMonthlyTicketFlowBeforeFailure",
      "private fun classifyRigasSatiksmeMonthlyTicketHierarchy"
    )

    val staleMarker = "stale_control_ticket\" ]; then"
    val staleStart = recovery.indexOf(staleMarker)
    assertTrue(
      "stale QR recovery already has an authoritative stale_control_ticket classification; it should press Back before depending on another fragile/slow hierarchy probe",
      recovery.contains("initial_status=\"") &&
        staleStart >= 0 &&
        recovery.substring(0, staleStart).takeLast(120).contains("initial_status")
    )
    val staleBranch = recovery.substring(staleStart).substringBefore("\n      else")
    assertTrue(staleBranch.contains("input keyevent KEYCODE_BACK"))
    assertTrue(staleBranch.contains("input tap 540 555"))
    assertFalse("the stale QR branch must not spend its first step on an in-script uiautomator dump", staleBranch.contains("ticket_safe_uiautomator_dump"))
  }

  @Test
  fun rsMonthlyTicketRecoveryUsesSingleFastSnapshotBeforeRootFallback() {
    val source = ticketStreamServiceSource()
    val recovery = source.substringBetween("private suspend fun recoverRigasSatiksmeMonthlyTicketFlowBeforeFailure", "private fun classifyRigasSatiksmeMonthlyTicketHierarchy")

    assertTrue(
      "Recovery should still try one cheap accessibility snapshot before root fallback",
      recovery.contains("fastVisibleHierarchy(TicketScreenConfig.RIGAS_SATIKSME_PACKAGE, \"rs_monthly_ticket_recovery\")")
    )
    assertFalse(
      "Recovery should not spend multiple seconds polling RS Flutter snapshots that are usually empty before root fallback",
      recovery.contains("waitStartedAt <= 2_200L") || recovery.contains("<= 2_200L") || recovery.contains("delay(180)")
    )
    assertTrue(
      "Recovery must fall back to bounded root hierarchy after the single cheap snapshot",
      recovery.indexOf("fastVisibleHierarchy(TicketScreenConfig.RIGAS_SATIKSME_PACKAGE, \"rs_monthly_ticket_recovery\")") <
        recovery.indexOf("reason = \"rs_monthly_ticket_recovery_fallback\"")
    )
  }

  @Test
  fun rsMonthlyTicketClassifierDoesNotCallGenericIntermediateScreensMissingMonthlyTicket() {
    val source = ticketStreamServiceSource()
    val classifier = source.substringBetween("private fun classifyRigasSatiksmeMonthlyTicketHierarchy", "private fun escapeHierarchyAttribute")

    assertTrue(
      "Only an inspected monthly-ticket list should produce missing_monthly_ticket; generic intermediate screens should stay recoverable control_missing",
      classifier.contains("isRigasSatiksmeMonthlyTicketListMissing(hierarchy)")
    )
    assertFalse(
      "A generic hierarchy without the 1-month text can be a transient app/build state and must not be labeled missing monthly ticket",
      classifier.contains("if (!has(\"1 MONTH\") && !has(\"1 month\")) {\n      return \"missing_monthly_ticket\"")
    )
  }

  @Test
  fun rsMonthlyTicketReturnUsesFastSingleDumpCleanupBeforeSlowRecovery() {
    val source = ticketStreamServiceSource()
    val cleanup = source.substringBetween("private suspend fun returnRigasSatiksmeMonthlyTicketFlowToViviTicket", "private suspend fun runRigasSatiksmeMonthlyTicketFlow")

    assertTrue("RS cleanup should attempt a fast ViVi launch + single semantic dump before slow recovery", cleanup.contains("runRigasSatiksmeMonthlyTicketFastReturnToViviTicket("))
    assertTrue(cleanup.contains("rs_monthly_ticket_fast_return_started"))
    assertTrue(cleanup.contains("fastVisibleHierarchy(TicketScreenConfig.VIVI_PACKAGE"))
    assertTrue("fast cleanup should poll cheap ViVi snapshots briefly before falling back to slow wake recovery", cleanup.contains("rs_monthly_ticket_fast_return_hierarchy_wait_finished"))
    assertTrue(cleanup.contains("pixel-vivi-fast-return-window.xml"))
    assertTrue(cleanup.contains("TicketViviRecoveryState.TICKET_DETAIL"))
    assertTrue("fast return should be attempted before wake/recovery budget is spent", cleanup.indexOf("runRigasSatiksmeMonthlyTicketFastReturnToViviTicket(") < cleanup.indexOf("wakeTicketScreenForSessionStart("))
  }

  @Test
  fun rsMonthlyTicketReturnKeepsNonTouchClampButAvoidsFiveSecondLongPauseTimeouts() {
    val source = ticketStreamServiceSource()
    val fastReturn = source.substringBetween("private suspend fun runRigasSatiksmeMonthlyTicketFastReturnToViviTicket", "private suspend fun runRigasSatiksmeMonthlyTicketFlow")
    val rootDump = source.substringBetween("private suspend fun dumpVisibleHierarchyWithRoot", "private suspend fun recoverRigasSatiksmeMonthlyTicketFlowBeforeFailure")

    assertTrue(
      "Live RS cleanup timed out at 5010ms; fast ViVi return must keep the non-touch panel clamp but use an explicit long-pause timeout cushion",
      fastReturn.contains("runFastNonTouchScript(") && fastReturn.contains("TICKET_RS_MONTHLY_FAST_RETURN_TIMEOUT_MILLIS.milliseconds")
    )
    assertFalse(
      "Fast ViVi return must not keep the old hard-coded 5s root command timeout",
      fastReturn.contains("5_000.milliseconds")
    )
    assertTrue(
      "Root hierarchy fallback hit a 5s timeout after empty fast snapshots; keep non-touch marking/clamping on an independent root lane and give uiautomator enough long-pause budget",
      rootDump.contains("runFastNonTouchWakeScript(") && rootDump.contains("TICKET_ROOT_HIERARCHY_DUMP_TIMEOUT_MILLIS.milliseconds")
    )
    assertTrue(
      "Root hierarchy dumps should remain marked as non-touch work so the physical panel stays clamped while uiautomator runs",
      rootDump.contains("\"root_visible_hierarchy_dump:${'$'}reason\"")
    )
    assertFalse(
      "Root hierarchy fallback must not keep the old hard-coded 5s root command timeout",
      rootDump.contains("5_000.milliseconds")
    )
  }

  @Test
  fun wakeCommandsKeepLongPauseTimeoutCushion() {
    val source = ticketStreamServiceSource()

    assertTrue(
      "Latest long-pause cleanup showed wake/action root commands completing just over 2s; keep an explicit cushion instead of treating those taps as failed recovery",
      source.contains("private const val TICKET_WAKE_COMMAND_TIMEOUT_MILLIS = 3_000L")
    )
    assertTrue(
      "Latest long-pause cleanup showed ViVi launch completing just over 2s; keep the launch timeout aligned with wake/action commands",
      source.contains("private const val TICKET_WAKE_LAUNCH_TIMEOUT_MILLIS = 3_000L")
    )
  }

  @Test
  fun rsMonthlyTicketReturnAllowsLongPauseTicketListRecoveryAfterSuccessfulQr() {
    val source = ticketStreamServiceSource()
    val cleanup = source.substringBetween("private suspend fun returnRigasSatiksmeMonthlyTicketFlowToViviTicket", "private suspend fun runRigasSatiksmeMonthlyTicketFastReturnToViviTicket")

    assertTrue(
      "A live success smoke delivered the RS QR but cleanup exhausted the old 30s return budget after ViVi recovered only as far as the ticket-list card; RS return cleanup runs after user delivery, so keep a dedicated 45s cushion for slow root dumps and one more ticket-card tap",
      source.contains("private const val TICKET_RS_MONTHLY_RETURN_BUDGET_MILLIS = 45_000L")
    )
    assertTrue(
      "RS return cleanup needs more than relaunch + open tickets + two time-ticket taps after long pause/list reload; allow six focused recovery actions without changing normal ticket wake limits",
      source.contains("private const val TICKET_RS_MONTHLY_RETURN_MAX_RECOVERY_ACTIONS = 6")
    )
    assertTrue(cleanup.contains("budgetMillis = TICKET_RS_MONTHLY_RETURN_BUDGET_MILLIS"))
    assertTrue(cleanup.contains("maxRecoveryActions = TICKET_RS_MONTHLY_RETURN_MAX_RECOVERY_ACTIONS"))
  }

  @Test
  fun rsMonthlyTicketFailuresAreReportedBeforeReturnCleanup() {
    val source = ticketStreamServiceSource()
    val rsFlow = source.substringBetween("private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr", "private suspend fun runRigasSatiksmeMonthlyTicketFlow")
    val flowFailure = rsFlow.substringBetween("if (!flow.ok) {", "return\n        }")
    val imageFailure = rsFlow.substringBetween("if (imageBytes == null || imageBytes.isEmpty()) {", "return\n        }")

    assertTrue(flowFailure.contains("cleanupPending = true"))
    assertTrue(imageFailure.contains("cleanupPending = true"))
    assertTrue(
      "RS flow failures should be sent to broker before slow ViVi return cleanup so users do not wait for cleanup or hit broker timeout",
      flowFailure.indexOf("sendControlCodeResult(") < flowFailure.indexOf("returnRigasSatiksmeMonthlyTicketFlowToViviTicket(")
    )
    assertTrue(
      "RS image-capture failures should be sent to broker before slow ViVi return cleanup",
      imageFailure.indexOf("sendControlCodeResult(") < imageFailure.indexOf("returnRigasSatiksmeMonthlyTicketFlowToViviTicket(")
    )
  }

  @Test
  fun failedControlCodeAndRsImageResultsAreNotCachedSoBrokerRetriesReDriveThePhone() {
    val source = ticketStreamServiceSource()
    val sendResult = source.substringBetween("private fun sendControlCodeResult(", "private fun sendRigassatiksmeQrResult")
    val sendRsImageResult = source.substringBetween("private fun sendRigassatiksmeQrResult(", "private fun sendControlCodeCleanup")

    assertTrue(
      "A retried broker job reuses the same requestId. Failed control_code_result messages, including rs_monthly_ticket_control_missing, must not be cached/replayed or attempts 2/3 never re-drive the RS app.",
      sendResult.contains("if (ok) {\n      rememberControlCodeResult(requestId, message)\n    }")
    )
    assertTrue(
      "Failed rigassatiksme_qr_result messages, including qr_image_missing, must not be cached/replayed or repeated attempts can finish from the old failure without re-driving RS.",
      sendRsImageResult.contains("if (accepted) {\n      rememberControlCodeResult(requestId, message)\n    }")
    )
    assertTrue(
      "Successful ViVi control-code results should remain cached for harmless reconnect/duplicate delivery",
      sendResult.contains("rememberControlCodeResult(requestId, message)")
    )
    assertTrue(
      "Successful RS image results should remain cached so websocket reconnects can replay the app-captured screenshot",
      sendRsImageResult.contains("rememberControlCodeResult(requestId, message)")
    )
  }

  @Test
  fun productionControlCodePathDoesNotSurfaceOldRefreshingFailure() {
    val source = ticketStreamServiceSource()

    assertFalse(source.contains("control_code_request_hierarchy_unavailable"))
    assertTrue(source.contains("control_code_phone_state_normalizing"))
  }

  @Test
  fun activeGuardDoesNotTurnFreshStreamIntoAttentionOnInconclusiveRootCheck() {
    val source = ticketStreamServiceSource()
    val guard = source.substringBetween("private suspend fun enforceViviTicketPageIfNeeded", "private suspend fun dumpViviHierarchy")

    assertTrue(guard.contains("active_guard_inconclusive"))
    assertTrue(guard.contains("result.state == TicketViviRecoveryState.UNKNOWN_VIVI"))
    assertTrue(guard.contains("result.state == TicketViviRecoveryState.BLANK"))
    assertTrue(
      guard.indexOf("active_guard_inconclusive") <
        guard.indexOf("updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION")
    )
  }

  @Test
  fun activeGuardReturnsKnownControlSurfacesToRawTicketInsteadOfAttention() {
    val source = ticketStreamServiceSource()
    val guard = source.substringBetween("private suspend fun enforceViviTicketPageIfNeeded", "private suspend fun dumpViviHierarchy")

    assertTrue(guard.contains("result.state == TicketViviRecoveryState.CONTROL_CODE_RESULT"))
    assertTrue(guard.contains("result.state == TicketViviRecoveryState.CONTROL_CODE_POPUP"))
    assertTrue(guard.contains("returnControlCodeSurfaceToRawTicket("))
    assertTrue(guard.contains("active_guard_return_raw"))
    assertTrue(
      guard.indexOf("returnControlCodeSurfaceToRawTicket(") <
        guard.indexOf("updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION")
    )
  }

  @Test
  fun hardwareStreamRestartsAndReconfiguresWhenRotationChangesDimensions() {
    val service = ticketStreamServiceSource()
    val engine = rootHardwareH264CaptureEngineSource()
    val browser = service.substringBetween("function updateLayoutMetrics()", "function clientLog")
    val configure = service.substringBetween("async function configureDecoder(config)", "function sampleVisibleFrame()")
    val ensure = service.substringBetween("private fun ensureRootHardwareH264CaptureIfPossible()", "private fun handleRootHardwareH264CaptureStateChanged")
    val streamChanged = service.substringBetween("private fun streamSizeChanged", "private fun handleRootHardwareH264CaptureStateChanged")
    val requestKeyFrame = service.substringBetween("private fun requestKeyFrame", "private fun activeStreamStaleForRecovery")

    assertTrue(streamChanged.contains("previous.sourceWidth != next.sourceWidth"))
    assertTrue(streamChanged.contains("previous.sourceHeight != next.sourceHeight"))
    assertTrue(streamChanged.contains("previous.sourceTopCrop != next.sourceTopCrop"))
    assertTrue(ensure.contains("val streamConfigChanged = streamSizeChanged(previousSize, size)"))
    assertTrue(ensure.contains("val needsNewEpoch = streamEpoch == 0L || streamConfigChanged"))
    assertTrue(ensure.contains("rootHardwareH264CaptureEngine.start("))
    assertTrue(engine.contains("private data class HardwareH264StartRequest"))
    assertTrue(engine.contains("desiredStartRequest"))
    assertTrue(engine.contains("activeStartRequest"))
    assertTrue(engine.contains("capture_config_changed"))
    assertTrue(engine.contains("root_hardware_h264_capture_config_changed"))
    assertTrue(engine.contains("runCaptureLoop()"))
    assertFalse("a running helper must not ignore new source/target dimensions", engine.contains("if (job?.isActive == true) {\n        publish()\n        return\n      }"))
    assertTrue(configure.contains("applyStreamConfigDimensions(config)"))
    assertTrue(configure.contains("clientLog('stream_dimensions_applied'"))
    assertTrue(browser.contains("function handleViewportChanged()"))
    assertTrue(browser.contains("window.addEventListener('orientationchange', handleViewportChanged)"))
    assertTrue(browser.contains("screen.orientation.addEventListener('change', handleViewportChanged)"))
    assertTrue(requestKeyFrame.contains("reason == \"viewport_changed\""))
    assertTrue(requestKeyFrame.contains("ensureRootHardwareH264CaptureIfPossible()"))
    assertFalse(
      "viewport dimension refresh must also run while hardware verification is still in progress",
      requestKeyFrame.contains("reason == \"viewport_changed\" && activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264 && hardwareCaptureVerified")
    )
  }

  @Test
  fun controlCodeRequestStartsTicketSessionBeforeInputGate() {
    val source = ticketStreamServiceSource()
    val handler = source.substringBetween("private suspend fun handleGenerateControlCode(", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr(")
    val ensure = source.substringBetween("private suspend fun ensureTicketSessionForControlCodeRequest(", "private suspend fun recoverTicketDetailForControlCodeRequest(")
    val verifiedOpen = source.substringBetween("private suspend fun openControlCodePopupFromVerifiedStateFastForRequest(", "private suspend fun resolveControlCodeHierarchyForFastRequest(")

    assertTrue(handler.contains("ensureTicketSessionForControlCodeRequest("))
    assertTrue(handler.contains("reason = \"control_code_request_session_unavailable\""))
    assertTrue(ensure.contains("runControlExitCleanup(\"control_code_request_preflight_control_exit\")"))
    assertTrue(ensure.contains("control_code_request_recover_control_exit"))
    assertTrue(ensure.contains("foregroundViolationReason(allowStartupSystemUi = false)"))
    assertTrue(ensure.contains("recoverTicketDetailForControlCodeRequest("))
    assertTrue(verifiedOpen.contains("control_code_request_recover_root_unavailable"))
    assertTrue(verifiedOpen.contains("recoverTicketDetailForControlCodeRequest("))
    assertTrue(
      "control-code generation must start or join the ticket session before checking streamActive/input gate",
      handler.indexOf("ensureTicketSessionForControlCodeRequest(") <
        handler.indexOf("measureInputPhase(phases, \"gate\") { canForwardRemoteInput() }")
    )
    assertTrue(
      "unsafe ViVi states should get a bounded ticket-detail recovery attempt before being reported as request failures",
      verifiedOpen.indexOf("recoverTicketDetailForControlCodeRequest(") <
        verifiedOpen.indexOf("control_code_request_unsafe_state")
    )
  }

  @Test
  fun rsRootScriptsAreWrappedAsNonTouchInputBeforeTheyCanWakeOrSwitchApps() {
    val source = ticketStreamServiceSource()
    val rsFastReturn = source.substringBetween(
      "private suspend fun runRigasSatiksmeMonthlyTicketFastReturnToViviTicket",
      "private suspend fun runRigasSatiksmeMonthlyTicketFlow"
    )
    val rsFlow = source.substringBetween(
      "private suspend fun runRigasSatiksmeMonthlyTicketFlow",
      "private suspend fun fastVisibleHierarchy"
    )
    val rootDump = source.substringBetween(
      "private suspend fun dumpVisibleHierarchyWithRoot",
      "private suspend fun recoverRigasSatiksmeMonthlyTicketFlowBeforeFailure"
    )
    val rsRecovery = source.substringBetween(
      "private suspend fun recoverRigasSatiksmeMonthlyTicketFlowBeforeFailure",
      "private fun classifyRigasSatiksmeMonthlyTicketHierarchy"
    )
    val launchViviForWake = source.substringBetween(
      "private suspend fun launchViviForWake",
      "private suspend fun fastWakeReadyFromRecentTicketDetail"
    )
    val wakeTicketScreenForSessionStart = source.substringBetween(
      "private suspend fun wakeTicketScreenForSessionStart",
      "private suspend fun waitForTicketScreenInteractiveForWake"
    )
    val requestTicketScreenWake = source.substringBetween(
      "private fun requestTicketScreenWake",
      "private fun cacheForegroundViolation"
    )
    val fastInputWrapper = source.substringBetween(
      "private suspend fun runFastNonTouchInput(command: String, reason: String): RootResult",
      "private suspend fun runFastNonTouchScript(command: String, reason: String, timeout: Duration): RootResult"
    )
    val wrapper = source.substringBetween(
      "private suspend fun runFastNonTouchScript(command: String, reason: String, timeout: Duration): RootResult",
      "private fun shellQuote"
    )

    assertTrue(rsFastReturn.contains("runFastNonTouchScript("))
    assertTrue(rsFastReturn.contains("\"rs_monthly_ticket_fast_return\""))
    assertFalse(rsFastReturn.contains("inputRootExecutor.runScript("))

    assertTrue(rsFlow.contains("runFastNonTouchScript("))
    assertTrue(rsFlow.contains("\"rs_monthly_ticket_flow\""))
    assertFalse(rsFlow.contains("inputRootExecutor.runScript("))

    assertTrue(rootDump.contains("runFastNonTouchWakeScript("))
    assertTrue(rootDump.contains("\"root_visible_hierarchy_dump:${'$'}reason\""))
    assertFalse(rootDump.contains("wakeRootExecutor.runScript("))

    assertTrue(rsRecovery.contains("runFastNonTouchWakeScript("))
    assertTrue(rsRecovery.contains("\"rs_monthly_ticket_recovery\""))
    assertFalse(rsRecovery.contains("inputRootExecutor.runScript("))

    assertTrue(launchViviForWake.contains("runFastNonTouchWakeScript("))
    assertTrue(launchViviForWake.contains("\"wake_launch_vivi:${'$'}reason\""))
    assertFalse(launchViviForWake.contains("wakeRootExecutor.runScript("))

    assertTrue(wakeTicketScreenForSessionStart.contains("runFastNonTouchWakeScript("))
    assertTrue(wakeTicketScreenForSessionStart.contains("\"wake_session_start\""))
    assertFalse(wakeTicketScreenForSessionStart.contains("wakeRootExecutor.runScript("))

    assertTrue(requestTicketScreenWake.contains("runFastNonTouchScript("))
    assertTrue(requestTicketScreenWake.contains("\"wake_request\""))
    assertFalse(requestTicketScreenWake.contains("rootExecutor.runScript("))

    assertTrue(source.contains("private const val NON_TOUCH_PANEL_SLEEP_CLAMP_INTERVAL_MILLIS = 5L"))
    assertTrue(source.contains("private const val NON_TOUCH_PANEL_SLEEP_CLAMP_POST_MILLIS = 2_500L"))
    assertTrue(source.contains("private const val NON_TOUCH_COMMAND_SELF_TIMEOUT_CUSHION_MILLIS = 250L"))
    assertTrue(source.contains("private fun wrapNonTouchPanelSleepClamp("))
    assertTrue(source.contains("postMillis: Long = NON_TOUCH_PANEL_SLEEP_CLAMP_POST_MILLIS"))
    assertTrue(source.contains("commandTimeout: Duration? = null"))
    assertTrue(source.contains("/sys/class/backlight/panel0-backlight"))
    assertTrue(source.contains("ticket_panel_sysfs_clamp"))
    assertTrue(source.contains("ticket_panel_display_clamp"))
    assertTrue(source.contains("echo 0 > "))
    assertTrue(source.contains("ticket_panel_dir/brightness"))
    assertTrue(source.contains("settings put system screen_brightness 0"))
    assertTrue(source.contains("val intervalMicros = NON_TOUCH_PANEL_SLEEP_CLAMP_INTERVAL_MILLIS * 1_000L"))
    assertTrue(source.contains("usleep $" + "intervalMicros"))
    assertTrue(
      "sysfs panel clamp must run before slower DisplayManager brightness calls",
      wrapper.indexOf("echo 0 > ") < wrapper.indexOf("cmd display set-brightness 0 --unit percentage")
    )
    val displayManagerClampCount = Regex("cmd display set-brightness 0 --unit percentage")
      .findAll(wrapper)
      .count()
    assertTrue(
      "non-touch clamp must repeatedly reset DisplayManager brightness while active and during the post-window",
      displayManagerClampCount >= 2
    )

    assertTrue(fastInputWrapper.contains("inputRootExecutor.runScript("))
    assertTrue(fastInputWrapper.contains("wrapNonTouchPanelSleepClamp(command, commandTimeout = NON_TOUCH_ROOT_COMMAND_TIMEOUT_MILLIS.milliseconds)"))
    assertFalse(fastInputWrapper.contains("inputRootExecutor.run(command)"))

    assertTrue(wrapper.contains("private suspend fun runFastNonTouchScript(command: String, reason: String, timeout: Duration): RootResult"))
    assertTrue(source.contains("private const val NON_TOUCH_SCRIPT_REASSERT_INTERVAL_MILLIS = 250L"))
    assertTrue(wrapper.indexOf("PhoneAutomationServiceBridge.markNonTouchInput(\"ticket:${'$'}reason\")") < wrapper.indexOf("inputRootExecutor.runScript(wrapNonTouchPanelSleepClamp(command, commandTimeout = timeout), timeout)"))
    assertTrue(wrapper.contains("delay(NON_TOUCH_SCRIPT_REASSERT_INTERVAL_MILLIS)"))
    assertTrue(wrapper.contains("PhoneAutomationServiceBridge.markNonTouchInput(\"ticket:${'$'}reason:active\")"))
    assertTrue(wrapper.indexOf("PhoneAutomationServiceBridge.markNonTouchInput(\"ticket:${'$'}reason:active\")") < wrapper.indexOf("inputRootExecutor.runScript(wrapNonTouchPanelSleepClamp(command, commandTimeout = timeout), timeout)"))
    assertTrue(wrapper.indexOf("inputRootExecutor.runScript(wrapNonTouchPanelSleepClamp(command, commandTimeout = timeout), timeout)") < wrapper.indexOf("PhoneAutomationServiceBridge.markNonTouchInput(\"ticket:${'$'}reason:complete\")"))
  }

  @Test
  fun nonTouchPanelClampSelfTimesOutAndCleansUpBeforePersistentRootTimeout() {
    val source = ticketStreamServiceSource()
    val clamp = source.substringBetween("private fun wrapNonTouchPanelSleepClamp", "private fun shellQuote")
    val wakeInput = source.substringBetween("private suspend fun runFastNonTouchWakeInput", "private suspend fun runFastNonTouchWakeScript")
    val wakeScript = source.substringBetween("private suspend fun runFastNonTouchWakeScript", "private suspend fun runFastNonTouchScript")
    val timedScript = source.substringBetween("private suspend fun runFastNonTouchScript", "private fun wrapNonTouchPanelSleepClamp")

    assertTrue(
      "root-worker timeouts can leave detached su/provider children behind; clamped non-touch scripts must run under a device-side timeout with room for cleanup",
      clamp.contains("commandTimeoutMillis") && clamp.contains("timeout -k 0.250s") && clamp.contains("NON_TOUCH_COMMAND_SELF_TIMEOUT_CUSHION_MILLIS")
    )
    assertTrue(
      "timed-out clamped commands must stop the active sysfs/display clamp loops instead of waiting for the Kotlin root worker to kill su",
      clamp.contains("ticket_panel_stop_clamps") && clamp.contains("trap 'ticket_panel_trap_cleanup 143' HUP INT TERM")
    )
    assertTrue(
      "if the inner timeout fires, do not launch a long background post-clamp that can become another orphaned settings/cmd storm",
      clamp.contains("ticket_command_timed_out=1") && clamp.contains("if [ \"\${'$'}ticket_command_timed_out\" -eq 0 ]; then")
    )
    assertTrue(wakeInput.contains("commandTimeout = TICKET_WAKE_COMMAND_TIMEOUT_MILLIS.milliseconds"))
    assertTrue(wakeScript.contains("commandTimeout = timeout"))
    assertTrue(timedScript.contains("commandTimeout = timeout"))
  }

  @Test
  fun wakeNonTouchClampCannotConsumeWakeBudgetOnLongPauseStartup() {
    val source = ticketStreamServiceSource()
    val wakeWrapper = source.substringBetween(
      "private suspend fun runFastNonTouchWakeScript(command: String, reason: String, timeout: Duration): RootResult",
      "private suspend fun runFastNonTouchScript(command: String, reason: String, timeout: Duration): RootResult"
    )
    val clamp = source.substringBetween("private fun wrapNonTouchPanelSleepClamp", "private fun shellQuote")

    assertTrue(source.contains("private const val TICKET_WAKE_PANEL_SLEEP_CLAMP_POST_MILLIS = 250L"))
    assertTrue(
      "wake commands must use a short post-clamp so KEYCODE_WAKEUP + ViVi launch leave budget for home-tab recovery",
      wakeWrapper.contains("postMillis = TICKET_WAKE_PANEL_SLEEP_CLAMP_POST_MILLIS") && wakeWrapper.contains("commandTimeout = timeout")
    )
    assertTrue(
      "the 5ms clamp loop must prefer Android sleep over usleep; usleep forks are slow enough to turn a 2.5s post-window into ~30s",
      clamp.contains("sleep 0.005 2>/dev/null || usleep ${'$'}intervalMicros")
    )
    assertTrue(
      "post-command brightness clamping must continue in the background instead of blocking wake command completion",
      clamp.contains("ticket_panel_post_clamp >/dev/null 2>&1 &")
    )
    assertTrue(
      "the end-of-command marker should be reachable before the post-clamp window completes",
      clamp.indexOf("ticket_panel_post_clamp >/dev/null 2>&1 &") < clamp.indexOf("exit \"\${'$'}ticket_command_rc\"")
    )
    assertFalse(
      "the long-pause failure was caused by 500 slow external usleep calls consuming the wake budget before recovery could tap Tickets",
      clamp.contains("usleep ${'$'}intervalMicros 2>/dev/null || sleep 0.005")
    )
  }

  @Test
  fun hardwareHelperRequestsSecureContentCapture() {
    val source = rootHardwareH264CaptureMainSource()

    assertTrue(source.contains("SCREEN_CAPTURE_POLICY_CAPTURE = 1"))
    assertTrue(source.contains("setSecureContentPolicy"))
    assertTrue(source.contains("setProtectedContentPolicy"))
  }

  @Test
  fun foregroundGuardUsesFastHierarchyAndTrustsLiveDetailBeforeRootDump() {
    val source = ticketStreamServiceSource()
    val guard = source.substringBetween("private suspend fun enforceViviTicketPageIfNeeded", "private suspend fun attemptActiveGuardRecoveryAction")
    val fastObserver = source.substringBetween("private suspend fun observeFastViviState", "private suspend fun observeRootViviState")
    val trust = source.substringBetween("private fun foregroundGuardCanTrustLiveTicketDetailWithoutRoot", "private suspend fun dumpViviHierarchy")

    assertTrue(guard.contains("observeFastViviState(\"active_guard:${'$'}reason\")"))
    assertTrue(
      "active guard should use the cheap Accessibility snapshot before any root uiautomator dump",
      guard.indexOf("observeFastViviState(\"active_guard:${'$'}reason\")") < guard.indexOf("observeRootViviState(\"active_guard:${'$'}reason\")")
    )
    assertTrue(
      "if the cheap snapshot is empty but the stream is live on recently proved TICKET_DETAIL, the guard should not run into uiautomator at all",
      guard.contains("foregroundGuardCanTrustLiveTicketDetailWithoutRoot(now)") &&
        guard.indexOf("foregroundGuardCanTrustLiveTicketDetailWithoutRoot(now)") < guard.indexOf("observeRootViviState(\"active_guard:${'$'}reason\")")
    )
    assertTrue(fastObserver.contains("fastVisibleHierarchy(TicketScreenConfig.VIVI_PACKAGE"))
    assertTrue(fastObserver.contains("TicketViviPageEnforcer.classifyForRecovery(hierarchy)"))
    assertTrue(trust.contains("lastWakeSucceeded == true"))
    assertTrue(trust.contains("FOREGROUND_GUARD_RECENT_WAKE_READY_ROOTLESS_MILLIS"))
    assertTrue("rootless trust after an empty fast hierarchy may only use a very fresh current TICKET_DETAIL proof", trust.contains("val current = viviStateMemory.current()"))
    assertTrue(trust.contains("current.state != TicketViviRecoveryState.TICKET_DETAIL"))
    assertTrue(trust.contains("nowMillis - current.observedAtMillis"))
    assertTrue(trust.contains("FOREGROUND_GUARD_RECENT_TICKET_DETAIL_SKIP_MAX_AGE_MILLIS"))
    assertFalse("do not trust 10-minute ticket-detail memory after the cheap snapshot is empty; that masks forced-home/route screens", trust.contains("TICKET_WAKE_MEMORY_TICKET_DETAIL_MAX_AGE_MILLIS"))
  }

  @Test
  fun recoveryTouchActionsUseInputLaneNotWakeHierarchyLane() {
    val source = ticketStreamServiceSource()
    val active = source.substringBetween("private suspend fun attemptActiveGuardRecoveryAction", "private fun recentForegroundGuardTicketDetailStillFresh")
    val wake = source.substringBetween("private suspend fun attemptWakeRecoveryActionForRootWake", "private suspend fun attemptWakeRelaunchForRootWake")

    assertTrue(active.contains("runFastNonTouchInput(\"input tap"))
    assertTrue(active.contains("runFastNonTouchInput(\"input keyevent KEYCODE_BACK"))
    assertFalse("active recovery touch input must not queue behind wake-root hierarchy reads", active.contains("runFastNonTouchWakeInput("))
    assertTrue(wake.contains("runFastNonTouchInput(\"input tap"))
    assertTrue(wake.contains("runFastNonTouchInput(\"input keyevent KEYCODE_BACK"))
    assertFalse("wake recovery touch input must stay on the input/touch lane, not the wake-root hierarchy lane", wake.contains("runFastNonTouchWakeInput("))
  }

  @Test
  fun foregroundGuardBacksOffAfterInconclusiveHierarchyInsteadOfRetryStorm() {
    val source = ticketStreamServiceSource()
    val fields = source.substringBetween("private lateinit var ticketRecoveryCoordinator", "override fun onCreate()")
    val guard = source.substringBetween("private suspend fun enforceViviTicketPageIfNeeded", "private suspend fun attemptActiveGuardRecoveryAction")
    val backoff = source.substringBetween("private fun recentForegroundGuardTicketDetailStillFresh", "private fun shouldLogForegroundGuardRecentTicketDetailSkip")
    val companion = source.substringBetween("companion object", "private const val TICKET_SCREEN_WAKE_HOLD_MILLIS")

    assertTrue(fields.contains("lastForegroundGuardInconclusiveAtMillis"))
    assertTrue(companion.contains("private const val FOREGROUND_GUARD_INCONCLUSIVE_BACKOFF_MILLIS = 30_000L"))
    assertTrue(
      "healthy live streams should not hammer root uiautomator every foreground tick after one inconclusive dump",
      guard.contains("foregroundGuardInconclusiveBackoffActive(now)") &&
        guard.indexOf("foregroundGuardInconclusiveBackoffActive(now)") < guard.indexOf("observeRootViviState(\"active_guard:${'$'}reason\")")
    )
    assertTrue(
      "the inconclusive branch must arm the backoff before returning so repeated hierarchy_unavailable/timeout does not create more UiAutomation errors",
      guard.contains("lastForegroundGuardInconclusiveAtMillis = now") &&
        guard.indexOf("lastForegroundGuardInconclusiveAtMillis = now") < guard.indexOf("recordTicketEvent(\"active_guard_inconclusive\"")
    )
    assertTrue(backoff.contains("foregroundGuardInconclusiveBackoffActive(nowMillis: Long): Boolean"))
    assertTrue(backoff.contains("nowMillis - lastForegroundGuardInconclusiveAtMillis"))
    assertTrue(backoff.contains("FOREGROUND_GUARD_INCONCLUSIVE_BACKOFF_MILLIS"))
  }

  @Test
  fun rootHierarchyDumpsUseGuardedUiautomatorWrapper() {
    val service = ticketStreamServiceSource()
    val observer = ticketScreenObserverSource()
    val helper = ticketUiautomatorDumpSource()
    val onCreate = service.substringBetween("override fun onCreate()", "override fun onStartCommand")
    val serviceDump = service.substringBetween("private suspend fun dumpViviHierarchy(", "private fun recordRootReadiness")
    val wakeDump = service.substringBetween("private suspend fun dumpViviHierarchyForWake", "private suspend fun observeRootViviStateForWake")
    val visibleDump = service.substringBetween("private suspend fun dumpVisibleHierarchyWithRoot", "private suspend fun recoverRigasSatiksmeMonthlyTicketFlowBeforeFailure")
    val recovery = service.substringBetween("private suspend fun recoverRigasSatiksmeMonthlyTicketFlowBeforeFailure", "private fun classifyRigasSatiksmeMonthlyTicketHierarchy")

    assertTrue("All service semantic hierarchy reads must use the wake-root executor lane so active guards, wake prep, RS proof, and recovery serialize before Android's singleton UiAutomationService instead of racing into retries", serviceDump.contains("rootExecutor = wakeRootExecutor") && onCreate.contains("rootExecutor = wakeRootExecutor"))
    assertTrue("RS in-app recovery embeds guarded uiautomator probes, so it must run on the same wake-root lane as other semantic dumps rather than the input/touch root worker", recovery.contains("runFastNonTouchWakeScript(") && !recovery.contains("runFastNonTouchScript("))
    assertTrue("All root uiautomator dumps need a shared non-blocking device-side lock to avoid UiAutomationService already-registered crashes without making a second caller wait until its outer Kotlin timeout", helper.contains("/data/local/tmp/pixel-ticket-uiautomator.lock") && helper.contains("flock -x -n 9"))
    assertTrue("The device-side lock file must stay usable by root and shell callers; a root-owned 0644 lock lets some callers bypass the common uiautomator guard", helper.contains("chmod 0666") && helper.contains("9<>") && !helper.contains("9>${'$'}LOCK_PATH"))
    assertTrue("The wrapper must use a shell-level timeout shorter than the Kotlin timeout so a timed-out dump cannot leave UiAutomation registered", helper.contains("timeout -k 0.100s"))
    assertTrue("Timed-out uiautomator can still leave a complete XML file; salvage non-empty hierarchy output instead of returning empty fallback evidence", helper.contains("ticket_safe_has_hierarchy=1") && helper.contains("grep -q '<hierarchy'") && helper.contains("exit 0"))
    assertTrue("A failed/stale uiautomator process must be cleared before and after dumps without killing unrelated app_process helpers or the current shell", helper.contains("pgrep -f '[c]om.android.commands.uiautomator'") && helper.contains("pidof uiautomator") && helper.contains("kill -9"))
    assertTrue("When uiautomator is killed or fails, hold the lock briefly before the next dump so Android can unregister UiAutomation instead of immediately crashing with already-registered", helper.contains("ticket_safe_rc") && helper.contains("-ne 0") && helper.contains("sleep 0.500"))
    assertTrue("The wrapper must refuse too-small outer budgets instead of launching a 250ms dump that can be killed before the 500ms unregister settle completes", helper.contains("MIN_SAFE_OUTER_TIMEOUT_MILLIS") && helper.contains("timeoutMillis < MIN_SAFE_OUTER_TIMEOUT_MILLIS") && helper.contains("exit 124"))
    assertTrue("The shell timeout calculation must reserve the post-failure UiAutomation unregister settle inside the device-side lock", helper.contains("POST_FAILURE_LOCK_SETTLE_MILLIS") && helper.contains("timeoutMillis - OUTER_TIMEOUT_CUSHION_MILLIS - POST_FAILURE_LOCK_SETTLE_MILLIS"))
    assertTrue(observer.contains("TicketUiautomatorDump.command("))
    assertTrue(wakeDump.contains("TicketUiautomatorDump.command("))
    assertTrue(visibleDump.contains("TicketUiautomatorDump.command("))
    assertTrue(recovery.contains("TicketUiautomatorDump.functionDefinition("))
    assertTrue(recovery.contains("ticket_safe_uiautomator_dump /data/local/tmp/pixel-rs-recovery-start-window.xml nocat"))
    assertFalse("Do not reintroduce unguarded RS recovery hierarchy dumps", recovery.contains("uiautomator dump /data/local/tmp/pixel-rs-recovery-start-window.xml"))
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

  private fun ticketScreenObserverSource(): String = readFirstExisting(
    Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketScreenObserver.kt"),
    Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketScreenObserver.kt")
  )

  private fun ticketUiautomatorDumpSource(): String = readFirstExisting(
    Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketUiautomatorDump.kt"),
    Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketUiautomatorDump.kt")
  )

  private fun rootFfmpegH264CaptureEngineSource(): String = readFirstExisting(
    Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketRootFfmpegH264CaptureEngine.kt"),
    Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketRootFfmpegH264CaptureEngine.kt")
  )

  private fun rootHardwareH264CaptureEngineSource(): String = readFirstExisting(
    Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketRootHardwareH264CaptureEngine.kt"),
    Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketRootHardwareH264CaptureEngine.kt")
  )

  private fun rootHardwareH264CaptureMainSource(): String = readFirstExisting(
    Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketRootHardwareH264CaptureMain.java"),
    Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketRootHardwareH264CaptureMain.java")
  )

  private fun rootCroppedPngCaptureMainSource(): String = readFirstExisting(
    Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketRootCroppedPngCaptureMain.java"),
    Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketRootCroppedPngCaptureMain.java")
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
