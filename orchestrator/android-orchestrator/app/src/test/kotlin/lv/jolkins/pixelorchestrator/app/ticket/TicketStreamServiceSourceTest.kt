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
    assertTrue(cleanup.contains("protectedControlClients.isNotEmpty() || controlCodeRequestActive()"))
    assertTrue(cleanup.contains("inactive_stream_cleanup_deferred"))
    assertTrue(cleanup.indexOf("startupClientGraceActive") < cleanup.indexOf("closeAllClients(\"inactive_stream_${'$'}reason\")"))
    assertTrue(cleanup.indexOf("protectedControlActive") < cleanup.indexOf("closeAllClients(\"inactive_stream_${'$'}reason\")"))
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
    assertTrue("hardware stream should start after wake so helper startup does not race the locked/home screen", start.indexOf("prepareViviForRootHardwareH264FastOpen(reason, wakeStartedAtMillis)") < start.indexOf("ensureEncoderIfPossible()"))
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
    val fastOpen = source.substringBetween("private suspend fun prepareViviForRootHardwareH264FastOpen", "private suspend fun prepareViviForRootHardwareH264Capture")

    assertTrue(source.contains("TICKET_WAKE_MEMORY_TICKET_DETAIL_MAX_AGE_MILLIS"))
    assertTrue(fast.contains("viviStateMemory.recentTicketDetailWithin(TICKET_WAKE_MEMORY_TICKET_DETAIL_MAX_AGE_MILLIS)"))
    assertTrue(fast.contains("focusedWindowSnapshot()"))
    assertTrue(fast.contains("focused.contains(TicketScreenConfig.VIVI_PACKAGE)"))
    assertTrue(fast.contains("wake_recent_ticket_detail_fast_ready"))
    assertTrue(start.contains("val prepareResult = prepareViviForRootHardwareH264FastOpen(reason, wakeStartedAtMillis)"))
    assertTrue(fastOpen.contains("var result = fastWakeReadyFromRecentTicketDetail(reason, wakeStartedAtMillis)"))
    assertTrue(fastOpen.contains("val launchedViviForWake = result == null && !viviFocusedForFastPublicOpen(reason)"))
    assertTrue(fastOpen.contains("if (launchedViviForWake)"))
    assertTrue(fastOpen.contains("launchViviForWake(reason, timeoutMillis = launchTimeoutMillis)"))
    assertTrue(fastOpen.contains("result = fastWakeReadyFromRecentTicketDetailAfterLaunch("))
    assertTrue(fastOpen.contains("budgetMillis = TICKET_FAST_PUBLIC_OPEN_BUDGET_MILLIS"))
    assertTrue(source.contains("private suspend fun fastWakeReadyFromRecentTicketDetailAfterLaunch"))
    assertTrue(source.contains("TICKET_WAKE_POST_LAUNCH_FAST_READY_TIMEOUT_MILLIS"))
    assertTrue(source.contains("TICKET_WAKE_POST_LAUNCH_FAST_READY_POLL_MILLIS"))
    assertTrue(fast.contains("wakeStartedAtMillis + budgetMillis"))
    assertTrue(fastOpen.indexOf("fastWakeReadyFromRecentTicketDetail") < fastOpen.indexOf("launchViviForWake(reason, timeoutMillis = launchTimeoutMillis)"))
    assertTrue(fastOpen.indexOf("launchViviForWake(reason, timeoutMillis = launchTimeoutMillis)") < fastOpen.indexOf("fastWakeReadyFromRecentTicketDetailAfterLaunch"))
    assertTrue(fastOpen.contains("observeTicketDetailForFastPublicOpenVisibleProof(reason, wakeStartedAtMillis)"))
    assertTrue(fastOpen.indexOf("observeTicketDetailForFastPublicOpenVisibleProof(reason, wakeStartedAtMillis)") < fastOpen.indexOf("observeTicketDetailForFastPublicOpenRootProof"))
    assertTrue(fastOpen.contains("result ?: observeTicketDetailForFastPublicOpenRootProof"))
    assertFalse("public fast-open helper must not promote startup into the long wake recovery budget", fastOpen.contains("TICKET_WAKE_RECOVERY_BUDGET_MILLIS"))
  }

  @Test
  fun hardwareWakeFastRecentTicketDetailRejectsOnlyNewerDefinitiveNonDetailMemory() {
    val source = ticketStreamServiceSource()
    val fast = source.substringBetween("private suspend fun fastWakeReadyFromRecentTicketDetail", "private fun remainingWakeBudgetMillis")

    assertTrue("wake fast readiness must inspect the latest observed ViVi state before trusting an older ticket-detail snapshot", fast.contains("val current = viviStateMemory.current()"))
    assertTrue("newer forced-home/empty-list observations must invalidate older TICKET_DETAIL memory before focus-only fast readiness can pass", fast.contains("current.observedAtMillis > recent.observedAtMillis"))
    assertTrue(fast.contains("currentStateInvalidatesRecentTicketDetailFastWake(current.state)"))
    assertFalse("an older non-detail observation must not poison a newer RS return proof", fast.contains("TICKET_WAKE_CURRENT_NON_DETAIL_INVALIDATION_MAX_AGE_MILLIS"))
    assertTrue(fast.indexOf("viviStateMemory.recentTicketDetailWithin(TICKET_WAKE_MEMORY_TICKET_DETAIL_MAX_AGE_MILLIS)") < fast.indexOf("val current = viviStateMemory.current()"))
    assertTrue(fast.contains("wake_recent_ticket_detail_fast_ready_current_non_detail"))
  }

  @Test
  fun repeatedStartDuringHardwareWakeDoesNotMarkLiveOrStartEncoderBeforeWakeReady() {
    val source = ticketStreamServiceSource()
    val startLocked = source.substringBetween("private suspend fun startTicketSessionLocked()", "private fun scheduleDeferredSessionStartMaintenance")
    val activeBranch = startLocked.substringBetween("    if (streamActive) {", "    val sourceSize = currentDisplaySize()")

    assertTrue(activeBranch.contains("activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264"))
    assertTrue(activeBranch.contains("!hardwareCaptureVerified"))
    assertTrue(activeBranch.contains("TicketSessionResponse(ok = true, state = \"starting\""))
    assertTrue(activeBranch.indexOf("!hardwareCaptureVerified") < activeBranch.indexOf("updateTicketSessionState(TICKET_SESSION_LIVE, \"session_start_already_active\")"))
    assertTrue(activeBranch.indexOf("TicketSessionResponse(ok = true, state = \"starting\"") < activeBranch.indexOf("ensureEncoderIfPossible()"))
  }

  @Test
  fun publicHardwareStartupUsesOnlyFastBoundedWakeProofAfterDirectLaunch() {
    val source = ticketStreamServiceSource()
    val start = source.substringBetween("private fun scheduleRootHardwareH264CaptureStart", "private suspend fun verifyRootHardwareSecureCaptureVisible")
    val fastOpen = source.substringBetween("private suspend fun prepareViviForRootHardwareH264FastOpen", "private suspend fun prepareViviForRootHardwareH264Capture")

    assertTrue(source.contains("private const val TICKET_FAST_PUBLIC_OPEN_BUDGET_MILLIS = 5_000L"))
    assertTrue(source.contains("private const val TICKET_FAST_PUBLIC_OPEN_ROOT_PROOF_TIMEOUT_MILLIS = 2_500L"))
    assertTrue(source.contains("private const val TICKET_FAST_PUBLIC_OPEN_MIN_ROOT_PROOF_TIMEOUT_MILLIS = 1_000L"))
    assertTrue(start.contains("val prepareResult = prepareViviForRootHardwareH264FastOpen(reason, wakeStartedAtMillis)"))
    assertFalse("public hardware startup must not select the 60s recovery budget after a direct launch", start.contains("TICKET_WAKE_RECOVERY_BUDGET_MILLIS"))
    assertTrue(fastOpen.contains("remainingFastPublicOpenBudgetMillis(wakeStartedAtMillis)"))
    assertTrue(fastOpen.contains("observeTicketDetailForFastPublicOpenVisibleProof(reason, wakeStartedAtMillis)"))
    assertTrue(fastOpen.contains("observeTicketDetailForFastPublicOpenRootProof(reason, wakeStartedAtMillis)"))
    assertFalse("public fast-open helper must fail fast instead of running wake recovery actions", fastOpen.contains("attemptWakeRecoveryActionForRootWake("))
    assertFalse("public fast-open helper must fail fast instead of relaunching after UNKNOWN/blank states", fastOpen.contains("attemptWakeRelaunchForRootWake("))
    assertFalse("public fast-open helper must not call the long wake observer", fastOpen.contains("observeTicketDetailForWakeWithRoot("))
  }

  @Test
  fun wakeRecoveryBudgetCoversSlowLaunchesRootDumpsAndFourInputActions() {
    val source = ticketStreamServiceSource()

    assertTrue(source.contains("private const val TICKET_WAKE_RECOVERY_MAX_ACTIONS = 4"))
    assertTrue(source.contains("private const val TICKET_WAKE_FAST_ROOT_DUMP_TIMEOUT_MILLIS = 8_000L"))
    assertTrue(source.contains("private const val TICKET_WAKE_COMMAND_TIMEOUT_MILLIS = 3_000L"))
    assertTrue(
      "forced-home recovery may need wake, direct launch, one relaunch, tickets-tab, time-ticket-tab, ticket-card, and slow root dumps; keep the wake recovery budget large enough to spend the allowed actions before reporting phone-not-ready",
      source.contains("private const val TICKET_WAKE_RECOVERY_BUDGET_MILLIS = 60_000L")
    )
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
  fun appLaunchAndReturnActionsAreMarkedNonTouchBeforeTheyCanWakeOrSwitchApps() {
    val source = ticketStreamServiceSource()
    val launchVivi = source.substringBetween(
      "private fun launchVivi()",
      "private suspend fun launchViviUnlessFastTicketDetail"
    )
    val returnToOrchestrator = source.substringBetween(
      "private suspend fun returnToOrchestrator()",
      "private suspend fun readBrightnessState()"
    )

    assertTrue(launchVivi.contains("markTicketNonTouchAction(\"vivi_launch\")"))
    assertTrue(launchVivi.indexOf("markTicketNonTouchAction(\"vivi_launch\")") < launchVivi.indexOf("startActivity("))
    assertTrue(launchVivi.indexOf("startActivity(") < launchVivi.indexOf("markTicketNonTouchAction(\"vivi_launch:complete\")"))
    assertTrue(returnToOrchestrator.contains("runFastNonTouchScript("))
    assertTrue(returnToOrchestrator.contains("\"return_orchestrator\""))
    assertTrue(returnToOrchestrator.contains("NON_TOUCH_ROOT_COMMAND_TIMEOUT_MILLIS.milliseconds"))
    assertFalse(returnToOrchestrator.contains("rootExecutor.run(\"am start -n ${'$'}component\")"))
    assertTrue(returnToOrchestrator.contains("markTicketNonTouchAction(\"return_orchestrator:fallback\")"))
    assertTrue(
      returnToOrchestrator.indexOf("markTicketNonTouchAction(\"return_orchestrator:fallback\")") <
        returnToOrchestrator.indexOf("startActivity(intent)")
    )
    assertTrue(
      returnToOrchestrator.indexOf("startActivity(intent)") <
        returnToOrchestrator.indexOf("markTicketNonTouchAction(\"return_orchestrator:fallback_complete\")")
    )
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
    assertTrue(source.contains("handleGenerateControlCode(client, requestId, digits, owner, app, flow, resultImage, rsQueueHint)"))
    assertTrue(source.contains("CONTROL_CODE_REQUEST_DIGITS_REGEX"))
    assertTrue(source.contains("controlCodeRequestMutex.withLock"))
	    assertTrue(source.contains("sendCachedControlCodeResult(cleanRequestId)"))
	    assertTrue(source.contains("runFastControlCodeDeliveryForRequest(cleanDigits, phases, startedAtMillis)"))
	    assertTrue(source.contains("private val CONTROL_CODE_REQUEST_DIGITS_REGEX = Regex(\"\"\"^[0-9]{2,8}${'$'}\"\"\")"))
	    assertTrue(source.contains("digits.length < 2 || digits.length > 8"))
	    assertTrue(source.contains("showCodeResult('Enter 2-8 digits');"))
	    assertFalse(source.contains("digits.length < 2 || digits.length > 9"))
	    assertFalse(source.contains("Enter 2-9 digits"))
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
    assertTrue(source.contains("enterAndSubmitControlCodeDigitsFastForRequest(cleanDigits, transaction, phases, requestStartedAtMillis)"))
    assertFalse(source.contains("currentControlCodeSubmitRetryObservationForRequest(phases, requestStartedAtMillis)"))
    assertFalse(source.contains("retryObservation.generated != null"))
    assertFalse(source.contains("enterAndSubmitControlCodeDigitsFastForRequest(cleanDigits, retryObservation.transaction, phases, requestStartedAtMillis)"))
    assertTrue(source.contains("waitForGeneratedControlCodeResultAfterSubmit("))
    assertTrue(source.contains("sendTicketStateEvent("))
    assertFalse(source.contains("sendControlCodeFrameReady("))
    assertFalse(source.contains("\"control_code_frame_ready\""))
    assertTrue(source.contains("\"control_code_browser_capture\" -> {"))
    assertTrue(source.contains("handleControlCodeBrowserCapture(requestId, ok, reason, frameEpoch, frameSequence)"))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"request_gate_passed\", startedAtMillis)"))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"first_phone_tap\", requestStartedAtMillis)"))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"popup_ready\", requestStartedAtMillis)"))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"digits_typed\", requestStartedAtMillis)"))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"ok_tapped\", requestStartedAtMillis)"))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"result_first_visible\", requestStartedAtMillis)"))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"result_marker_requested\", requestStartedAtMillis)"))
    assertTrue(source.contains("markControlCodeRequestPhase(phases, \"browser_capture_ack_received\", requestStartedAtMillis)"))
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
    assertFalse("post-submit result detection must not branch into a second root retry lane", fastFlow.contains("currentControlCodeSubmitRetryObservationForRequest(phases, requestStartedAtMillis)"))
    assertFalse("post-submit result detection must not branch into a second root retry lane", fastFlow.contains("retryObservation."))
    assertTrue(fastFlow.contains("markerFirstControlCodeFrameWatermarkForBrowser("))
    assertTrue(fastFlow.contains("reason = \"control_code_marker_ready\""))
    assertTrue(fastFlow.contains("markControlCodeRequestPhase(phases, \"result_marker_requested\", requestStartedAtMillis)"))
    val generateFlow = source.substringBetween("private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    val generateSuccess = generateFlow.substringBetween("if (ok) {", "} else if (delivery.cleanupRequired)")
    assertFalse("ViVi control-code delivery must not capture a phone PNG for the user-facing result", fastFlow.contains("captureGeneratedControlCodeImageBytes("))
    assertFalse("ViVi control-code delivery must not record phone screenshot result phases", generateSuccess.contains("result_image_png_ready"))
    assertFalse("ticket.jolkins/ViVi control-code delivery must never emit Telegram RS-bot image results", generateSuccess.contains("sendRigassatiksmeQrResult("))
    assertFalse("ticket.jolkins/ViVi control-code delivery must not route resultImage into ViVi monthly-ticket capture", generateFlow.contains("runFastViviMonthlyTicketImageDeliveryForRequest"))
    assertTrue(fastFlow.indexOf("waitForGeneratedControlCodeResultAfterSubmit(") < fastFlow.indexOf("reason = \"control_code_marker_ready\""))
    assertFalse(fastFlow.contains("control_code_popup_still_open_after_submit"))
    assertFalse(fastFlow.contains("control_code_submit_retry"))
    assertFalse(source.contains("submit_retry_current_popup_ready"))
    assertFalse(source.contains("control_code_submit_retry_result_already_generated"))
    assertFalse(source.contains("submit_retry_result_root"))
    assertFalse(source.contains("control_code_submit_retry_popup_missing_wait_result"))
    val waitForResult = source.substringBetween("private suspend fun waitForGeneratedControlCodeResultAfterSubmit", "private suspend fun confirmGeneratedControlCodeResultForBrowser")
    assertFalse(waitForResult.contains("controlExitGeneratedResultFastScreencapProbe("))
    assertTrue(waitForResult.contains("controlCodeRequestRootHierarchy("))
    assertTrue(waitForResult.contains("\"wait_result_raw_ticket_root\""))
    assertFalse(waitForResult.contains("wait_generated_result_final_root"))
    assertFalse(waitForResult.contains("control_code_request_result_detected_by_final_root"))
    assertFalse(waitForResult.contains("requestControlCodeVisualProbe(\"control_code_after_ok_visual\")"))
    assertTrue(waitForResult.contains("requestControlCodeVisualProbe(\"control_code_after_ok_visual_state\")"))
    assertTrue(waitForResult.contains("recentControlCodeVisualProbeAfter(visualProbeStartedAtMillis)"))
    assertTrue(source.contains("CONTROL_CODE_MARKER_RESULT_HIERARCHY"))
    assertTrue(source.contains("CONTROL_CODE_FAST_RESULT_ROOT_DUMP_TIMEOUT_MILLIS = 2_500L"))
    assertTrue(source.contains("CONTROL_CODE_FAST_RESULT_TIMEOUT_MILLIS = 18_000L"))
    val fastEnter = source.substringBetween("private suspend fun enterControlCodeDigitsFastForRequest", "private suspend fun tapControlCodeSubmitFastForRequest")
    assertTrue(fastEnter.contains("input tap ${'$'}{transaction.input.x} ${'$'}{transaction.input.y}"))
    assertTrue(fastEnter.contains("val digitKeyEvents = controlCodeDigitKeyEvents(digits)"))
    assertTrue(fastEnter.contains("input keyevent ${'$'}digitKeyEvents"))
    assertFalse(fastEnter.contains("input text ${'$'}digits"))
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
    assertTrue(source.contains("CONTROL_CODE_FAST_POPUP_GEOMETRY_SETTLE_MILLIS = 60L"))
    assertTrue(source.contains("CONTROL_CODE_INPUT_FOCUS_SETTLE_MILLIS = 80L"))
    assertTrue(source.contains("CONTROL_CODE_DIGITS_TYPED_SUBMIT_SETTLE_MILLIS = 40L"))
    assertTrue(source.contains("CONTROL_CODE_FAST_POPUP_INPUT_X_FRACTION = 0.50f"))
    assertTrue(source.contains("CONTROL_CODE_FAST_POPUP_INPUT_Y_FRACTION = 0.511f"))
    assertTrue("already-open popup should be reused before any root hierarchy work", source.indexOf("cachedControlCodePopupTargetsForRequest(phases, requestStartedAtMillis)") < source.indexOf("openControlCodePopupImmediateForRequest(phases, requestStartedAtMillis)"))
    assertTrue(source.contains("CONTROL_CODE_FAST_ROOT_DUMP_TIMEOUT_MILLIS = 600L"))
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
    assertTrue(source.contains("CONTROL_CODE_BROWSER_CAPTURE_ACK_TIMEOUT_MILLIS = 10_000L"))
    assertFalse(source.contains("schedulePendingBrowserCaptureWatchdog"))
    assertTrue(source.contains("control_code_browser_capture_ack_timeout"))
    val browserCaptureWait = source.substringBetween("private suspend fun waitForControlCodeBrowserCapture", "private fun recentControlCodePrepareTicketReady")
    assertTrue("phone must keep the generated Aztec open for a bounded browser capture window", browserCaptureWait.contains("deadlineMillis"))
    assertTrue("phone must synthesize a failed browser capture ack when the browser never confirms", browserCaptureWait.contains("return ControlCodeBrowserCaptureAck("))
    assertTrue("the synthesized browser capture ack must preserve the request id", browserCaptureWait.contains("requestId = requestId"))
    assertTrue(generateSuccess.indexOf("val browserCapture = waitForControlCodeBrowserCapture(") < generateSuccess.indexOf("returnControlCodeSurfaceToRawTicket("))
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
  fun controlCodeGenerationDoesNotBlockControlSocketReader() {
    val source = ticketStreamServiceSource()
    val generateBranch = source.substringBetween(
      "\"generate_control_code\" -> {",
      "      \"prepare_control_code\" -> {"
    )

    assertTrue(
      "control-code generation must run off the websocket reader so browser-capture ack can still be received",
      generateBranch.contains("serviceScope.launch")
    )
    assertTrue(generateBranch.contains("handleGenerateControlCode(client, requestId, digits, owner, app, flow, resultImage, rsQueueHint)"))
    assertFalse(
      "websocket reader must not await the long phone automation request inline",
      generateBranch.contains("\n        handleGenerateControlCode(client, requestId, digits, owner, app, flow, resultImage, rsQueueHint)")
    )
  }

  @Test
  fun fastControlCodePrimaryInputUsesSingleRootMacro() {
    val source = ticketStreamServiceSource()
    val fastFlow = source.substringBetween("private suspend fun runFastControlCodeDeliveryForRequest", "private suspend fun prepareViviForControlCodeRequest")
    val combined = source.substringBetween("private suspend fun enterAndSubmitControlCodeDigitsFastForRequest", "private suspend fun enterControlCodeDigitsFastForRequest")

    assertTrue(fastFlow.contains("enterAndSubmitControlCodeDigitsFastForRequest(cleanDigits, transaction, phases, requestStartedAtMillis)"))
    assertTrue(
      "primary path should enter digits and tap OK before the result wait without launching separate root commands for each step",
      fastFlow.indexOf("enterAndSubmitControlCodeDigitsFastForRequest(cleanDigits, transaction, phases, requestStartedAtMillis)") <
        fastFlow.indexOf("waitForGeneratedControlCodeResultAfterSubmit(")
    )
    assertTrue(combined.contains("control_code_request_type_digits_fast"))
    assertTrue(combined.contains("control_code_request_submit_button_fast"))
    assertTrue(combined.contains("input tap ${'$'}{transaction.input.x} ${'$'}{transaction.input.y}"))
    assertTrue(combined.contains("val digitKeyEvents = controlCodeDigitKeyEvents(digits)"))
    assertTrue(combined.contains("input keyevent ${'$'}digitKeyEvents"))
    assertFalse(combined.contains("input text ${'$'}digits"))
    assertTrue(combined.contains("input tap ${'$'}{submit.x} ${'$'}{submit.y}"))
    assertTrue(combined.contains("resolveControlCodeSubmitAfterDigitsFastForRequest(transaction, phases)"))
    assertTrue(combined.contains("markControlCodeRequestPhase(phases, \"digits_typed\", requestStartedAtMillis)"))
    assertTrue(combined.contains("markControlCodeRequestPhase(phases, \"ok_tapped\", requestStartedAtMillis)"))
    assertTrue("digits should be typed before resolving the OK tap target", combined.indexOf("input keyevent ${'$'}digitKeyEvents") < combined.indexOf("resolveControlCodeSubmitAfterDigitsFastForRequest(transaction, phases)"))
    assertFalse("combined input/submit macro must not use accessibility", combined.contains("PhoneAutomationServiceBridge.clickSelectors("))
  }

  @Test
  fun immediateFreshPopupPathOpensTypesAndSubmitsInOneRootCommand() {
    val source = ticketStreamServiceSource()
    val fastFlow = source.substringBetween("private suspend fun runFastControlCodeDeliveryForRequest", "private fun requestFreshControlCodeFrameWatermark")
    val immediate = source.substringBetween("private suspend fun runImmediateControlCodeOpenTypeSubmitForRequest", "private suspend fun openControlCodePopupFastForRequest")

    assertTrue(
      "the known-live ticket path should try the one-command open/type/OK macro before the older two-step popup path",
      fastFlow.indexOf("runImmediateControlCodeOpenTypeSubmitForRequest(cleanDigits, phases, requestStartedAtMillis)") <
        fastFlow.indexOf("openControlCodePopupFastForRequest(phases, requestStartedAtMillis)")
    )
    assertTrue(immediate.contains("control_code_request_open_type_submit_fast"))
    assertTrue(immediate.contains("ticket_macro_input_tap ${'$'}{action.x} ${'$'}{action.y}"))
    assertTrue(immediate.contains("ticket_macro_input_tap ${'$'}{transaction.input.x} ${'$'}{transaction.input.y}"))
    assertTrue(immediate.contains("val digitKeyEvents = controlCodeDigitKeyEvents(digits)"))
    assertTrue(immediate.contains("ticket_macro_input_keyevents ${'$'}digitKeyEvents"))
    assertFalse(immediate.contains("ticket_macro_input_text ${'$'}digits"))
    assertTrue(immediate.contains("ticket_macro_input_tap ${'$'}{submit.x} ${'$'}{submit.y}"))
    assertTrue(immediate.contains("ticket_macro_panel_sysfs_dark"))
    assertTrue(immediate.contains("ticket_macro_wait_panel_bursts"))
    assertTrue(immediate.contains("CONTROL_CODE_FAST_PANEL_SLEEP_CLAMP_POST_MILLIS"))
    assertTrue(immediate.contains("control_code_macro_phase"))
    assertTrue(immediate.contains("detail=first_digit_start"))
    assertTrue(immediate.contains("detail=ok_tap_sent"))
    assertTrue(
      "the immediate path should settle after typing before its single OK tap, so the button is enabled without adding any post-OK tail",
      immediate.indexOf("ticket_macro_input_keyevents ${'$'}digitKeyEvents") <
        immediate.indexOf("sleep 0.060") &&
        immediate.indexOf("sleep 0.060") <
        immediate.indexOf("ticket_macro_input_tap ${'$'}{submit.x} ${'$'}{submit.y}")
    )
    assertFalse("freshly opened popup should not spend normal-path time clearing an already-empty field", immediate.contains("KEYCODE_DEL"))
    assertFalse("freshly opened popup should not spend normal-path time moving the cursor before first digit", immediate.contains("KEYCODE_MOVE_END"))
    assertFalse("fast macro should not spawn per-tap brightness bursts inside the already-clamped root command", immediate.contains("ticket_macro_start_panel_burst"))
    assertFalse("normal immediate path must not keep waiting in the shell after OK before generated-result detection can start", immediate.contains("detail=ok_tap_sent'\n      sleep 0.12"))
    assertFalse("normal immediate path must not send a trailing second OK tap before generated-result detection", immediate.contains("ticket_macro_input_tap ${'$'}{submit.x} ${'$'}{submit.y}\n    \"\"\".trimIndent()"))
  }

  @Test
  fun immediateControlCodeStartDoesNotDependOnOptionalSecureProbe() {
    val source = ticketStreamServiceSource()
    val decision = source.substringBetween(
      "private fun controlCodeImmediateStartDecision()",
      "private fun fallbackControlCodeShiftedSubmitAction()"
    )
    val liveProof = source.substringBetween(
      "private fun recentLiveRawTicketProofForControlCode",
      "private fun controlCodeRootObservationMillis"
    )

    assertTrue(decision.contains("streamActive || activeCaptureMode"))
    assertTrue(decision.contains("ticketSessionState != TICKET_SESSION_LIVE"))
    assertTrue(decision.contains("viviStateMemory.current()"))
    assertTrue(decision.contains("current.state == TicketViviRecoveryState.TICKET_DETAIL"))
    assertTrue(decision.contains("viviStateMemory.recentTicketDetailWithin"))
    assertTrue(decision.contains("currentViviStateIsInconclusiveFastObservation(current)"))
    assertTrue(decision.contains("recentLiveRawTicketProofForControlCode("))
    assertTrue(decision.contains("control_code_button_immediate_live_stream_recent_ticket_detail"))
    assertTrue(
      "fresh live stream proof must be trusted before an inconclusive fast-empty observation can force recovery",
      decision.indexOf("currentViviStateIsInconclusiveFastObservation(current)") <
        decision.indexOf("control_code_immediate_recent_state_")
    )
    assertTrue(liveProof.contains("hardwareCaptureVerified"))
    assertTrue(liveProof.contains("ticketSessionState != TICKET_SESSION_LIVE"))
    assertTrue(liveProof.contains("lastPixelTicketState != TICKET_PIXEL_STATE_RAW_TICKET"))
    assertTrue(liveProof.contains("lastPixelTicketEventSentAtMillis"))
    assertTrue(liveProof.contains("viviStateMemory.recentTicketDetailWithin(maxAgeMillis)"))
    assertFalse(decision.contains("lastRootH264BlankProbeResult != \"visible\""))
    assertFalse(decision.contains("control_code_immediate_secure_probe_not_visible"))
  }

  @Test
  fun immediatePopupOpenUsesSettledGeometryWithoutBlockingVisualProbe() {
    val source = ticketStreamServiceSource()
    val immediate = source.substringBetween(
      "private suspend fun openControlCodePopupImmediateForRequest",
      "private suspend fun openControlCodePopupFromVerifiedStateFastForRequest"
    )
    val visualReady = source.substringBetween(
      "private suspend fun waitForControlCodePopupVisualReadyFast",
      "private suspend fun waitForControlCodePopupTargetsFast"
    )

    assertTrue(immediate.contains("delay(CONTROL_CODE_FAST_POPUP_GEOMETRY_SETTLE_MILLIS)"))
    assertFalse("successful immediate popup tap must not wait on visual popup proof before typing", immediate.contains("waitForControlCodePopupVisualReadyFast("))
    assertFalse("successful immediate popup tap must not wait on slow root target discovery before typing", immediate.contains("waitForControlCodePopupTargetsFast("))
    assertTrue(visualReady.contains("requestControlCodeVisualProbe(\"control_code_popup_visual_ready\""))
    assertTrue(visualReady.contains("visualProbe.result == \"control_popup\""))
    assertTrue(visualReady.contains("openedControlCodePopupTransactionTargets("))
    assertTrue(visualReady.contains("source = \"visual_popup:${'$'}source\""))
    assertTrue(visualReady.contains("control_code_popup_visual_ready"))
    assertTrue(source.contains("CONTROL_CODE_FAST_POPUP_VISUAL_WAIT_MILLIS = 650L"))
    assertTrue("a successful popup tap must build a transaction instead of failing on a transient root miss", immediate.contains("openedControlCodePopupTransactionTargets("))
    assertTrue(immediate.contains("source = \"immediate_after_tap_settled_geometry\""))
    assertTrue(immediate.contains("control_code_popup_transaction_ready"))
    assertFalse("production immediate path must not return the old geometry-only popup fallback", immediate.contains("fallbackControlCodePopupTargetsAfterImmediateOpen("))
  }

  @Test
  fun verifiedPopupOpenUsesSettledGeometryInsteadOfSlowRootTargetsAfterTap() {
    val source = ticketStreamServiceSource()
    val verifiedOpen = source.substringBetween(
      "private suspend fun openControlCodePopupFromVerifiedStateFastForRequest",
      "private suspend fun resolveControlCodeHierarchyForFastRequest"
    )

    val afterSuccessfulTap = verifiedOpen.substringAfter("markControlCodeRequestPhase(phases, \"first_phone_tap\", requestStartedAtMillis)")
    assertFalse(afterSuccessfulTap.contains("waitForControlCodePopupVisualReadyFast("))
    assertFalse(
      "after a successful verified popup tap, typing must not be delayed by visual proof or slow popup root target discovery",
      afterSuccessfulTap.contains("waitForControlCodePopupTargetsFast(")
    )
    assertTrue(afterSuccessfulTap.contains("source = \"verified_after_tap_settled_geometry\""))
    assertTrue(afterSuccessfulTap.contains("control_code_popup_transaction_ready_verified"))
  }

  @Test
  fun fastControlCodeSubmitUsesPopupOkTargetAfterTyping() {
    val source = ticketStreamServiceSource()
    val combinedSubmit = source.substringBetween("private suspend fun enterAndSubmitControlCodeDigitsFastForRequest", "private suspend fun enterControlCodeDigitsFastForRequest")
    val fastEnter = source.substringBetween("private suspend fun enterControlCodeDigitsFastForRequest", "private suspend fun tapControlCodeSubmitFastForRequest")
    val fastSubmit = source.substringBetween("private suspend fun tapControlCodeSubmitFastForRequest", "private suspend fun waitForGeneratedControlCodeResultAfterSubmit")
    val resolver = source.substringBetween("private suspend fun resolveControlCodeSubmitAfterDigitsFastForRequest", "private suspend fun waitForGeneratedControlCodeResultAfterSubmit")
    val transactionBuilder = source.substringBetween("private fun openedControlCodePopupTransaction", "private fun fallbackControlCodePopupTargetsAfterImmediateOpen")

    assertTrue(source.contains("private data class FastControlCodePopupTransaction"))
    assertFalse("fast path should not keep a single static popup target set", source.contains("private data class FastControlCodeTargets"))
    assertTrue(transactionBuilder.contains("preKeyboardSubmit"))
    assertTrue(transactionBuilder.contains("keyboardOpenSubmit"))
    assertTrue(transactionBuilder.contains("fallbackControlCodeKeyboardOpenSubmitAction()"))
    assertTrue(fastEnter.contains("transaction.input"))
    assertTrue(fastSubmit.contains("resolveControlCodeSubmitAfterDigitsFastForRequest(transaction, phases)"))
    assertTrue(fastSubmit.contains("control_code_submit_current_popup_target"))
    assertTrue("after typing, OK tap should re-read the visible popup when possible", resolver.contains("controlCodeRequestRootHierarchy(phases, \"submit_after_digits_root\""))
    assertTrue("after typing, OK tap should prefer the current visible popup submit target", resolver.contains("current.preKeyboardSubmit"))
    assertTrue("after typing, OK tap should use keyboard-open geometry only when the live popup read is unavailable", resolver.contains("return transaction.keyboardOpenSubmit"))
    assertFalse("after typing, OK tap must not reuse the old one-shot target field", fastSubmit.contains("val submit = targets.submit"))
    assertFalse("after typing, OK tap must not spend time on an impossible sub-second root dump", source.contains("CONTROL_CODE_CURRENT_SUBMIT_ROOT_DUMP_TIMEOUT_MILLIS"))
    assertTrue("digits must be typed before resolving the final OK target", combinedSubmit.indexOf("input keyevent ${'$'}digitKeyEvents") < combinedSubmit.indexOf("resolveControlCodeSubmitAfterDigitsFastForRequest(transaction, phases)"))
    assertFalse("fast submit must not use the slower Android text-entry path", combinedSubmit.contains("input text ${'$'}digits"))
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
  fun fastResultDetectionUsesPostOkVisualPathAfterSubmit() {
    val source = ticketStreamServiceSource()
    val delivery = source.substringBetween("private suspend fun runFastControlCodeDeliveryForRequest", "private suspend fun prepareViviForControlCodeRequest")
    val wait = source.substringBetween("private suspend fun waitForGeneratedControlCodeResultAfterSubmit", "private suspend fun confirmGeneratedControlCodeResultForBrowser")
    val waitBody = wait.substringBefore("private suspend fun confirmGeneratedControlCodeResultForBrowser")
    val preRootWait = wait.substringBefore("controlCodeRequestRootHierarchy(")
    val rootConfirm = source.substringBetween("private suspend fun confirmGeneratedControlCodeResultForBrowser", "private fun markerFirstControlCodeFrameWatermarkForBrowser")
    val eventSender = source.substringBetween("private fun sendTicketStateEvent", "private fun sendControlCodeResult")

    assertFalse(wait.contains("control_code_request_result_assumed_fast"))
    assertFalse(wait.contains("control_code_request_result_assumed_after_submit"))
    assertFalse("the old pre-settle bitmap shortcut must stay removed", wait.contains("control_code_request_result_detected_by_visual_probe"))
    assertTrue("post-submit result proof must poll immediately unless a nonzero settle is configured", preRootWait.contains("if (CONTROL_CODE_POST_SUBMIT_FRAME_SETTLE_MILLIS > 0L)"))
    assertTrue(source.contains("CONTROL_CODE_POST_SUBMIT_FRAME_SETTLE_MILLIS = 0L"))
    assertTrue("post-submit success must wait for the strict phone-side visual state proof", wait.contains("wait_result_phone_visual_generated_state"))
    assertTrue("post-submit proof must actively ask the H.264 helper for a visual state", wait.contains("requestControlCodeVisualProbe(\"control_code_after_ok_visual_state\")"))
    assertTrue("post-submit proof must read the fresh visual state returned by the H.264 helper", wait.contains("recentControlCodeVisualProbeAfter(visualProbeStartedAtMillis)"))
    assertTrue("the entry popup must be a negative state, not a successful result", wait.contains("control_code_visual_popup_still_open"))
    assertTrue("visual generated state may confirm the phone marker", wait.contains("visualProbe.result == \"generated\""))
    assertTrue("post-submit raw-ticket visual state must get one bounded root confirmation instead of timing out on the real ViVi result screen", wait.contains("visualProbe.result == \"raw_ticket\""))
    assertTrue(wait.contains("controlCodeRequestRootHierarchy("))
    assertTrue(wait.contains("\"wait_result_raw_ticket_root\""))
    assertTrue(waitBody.contains("TicketViviRecoveryState.CONTROL_CODE_RESULT"))
    assertTrue(wait.contains("control_code_request_phone_visual_root_confirmed_after_submit"))
    assertTrue(wait.contains("resultProof = \"phone_visual_root_confirmed\""))
    assertTrue(wait.contains("control_code_visual_raw_ticket_after_submit_wait"))
    assertTrue(source.contains("CONTROL_CODE_RAW_TICKET_VISUAL_REJECT_LOG_COUNT = 2L"))
    assertFalse("raw-ticket visual state must not be promoted into generated proof", wait.contains("control_code_request_phone_visual_raw_ticket_after_submit"))
    assertFalse("raw-ticket visual state must not be a browser-capture proof", wait.contains("resultProof = \"phone_visual_raw_ticket_after_submit\""))
    assertTrue(source.contains("CONTROL_CODE_RAW_TICKET_ROOT_CONFIRM_TIMEOUT_MILLIS = 700L"))
    assertFalse("post-submit result proof must not parse or expose the private generated value", waitBody.contains("strictControlCodeResultValueForHierarchy"))
    assertTrue(wait.contains("confirmGeneratedControlCodeResultForBrowser("))
    assertFalse(rootConfirm.contains("requestControlCodeVisualProbe(\"control_code_result_after_root\")"))
    assertTrue(rootConfirm.contains("markerFirstControlCodeFrameWatermarkForBrowser("))
    assertFalse("marker-first success must not wait for an extra browser frame after visual/root proof", source.contains("private suspend fun waitForFreshControlCodeFrameWatermarkForBrowser"))
    assertFalse("marker-first success must not report completion through the old browser-frame-ready gate", source.contains("control_code_request_result_browser_frame_ready"))
    assertTrue(wait.contains("control_code_request_phone_visual_generated_after_submit"))
    assertFalse(wait.contains("control_code_result_timeout"))
    assertFalse(wait.contains("control_code_request_result_wait_failed"))
    assertTrue(wait.contains("control_code_generated_state_timeout"))
    assertTrue(delivery.contains("generated.streamEpoch to generated.minFrameSequence"))
    assertTrue(delivery.contains("minFrameSequence = watermark.second"))
    assertTrue("the ViVi path must emit phone-side visual proof metadata", source.contains("resultProof = \"phone_visual\""))
    assertTrue("phone visual proof must be the default marker proof", rootConfirm.contains("resultProof: String = \"phone_visual\""))
    assertFalse("ViVi control-code delivery must not wait on Pixel-image result acknowledgements", source.contains("waitForControlCodeResultAck("))
    assertTrue("normal ViVi marker delivery must wait for browser capture ack before cleanup", source.contains("waitForControlCodeBrowserCapture("))
    assertFalse(source.contains("CONTROL_CODE_BROWSER_CAPTURE_ACK_TIMEOUT_MILLIS = 12_000L"))
    assertTrue(eventSender.contains("resultProof: String = \"\""))
    assertTrue(eventSender.contains("resultFrameEpoch: Long = 0L"))
    assertTrue(eventSender.contains("resultMinFrameSequence: Long = 0L"))
    assertTrue(eventSender.contains("resultProofAtMillis: Long = 0L"))
    assertTrue(eventSender.contains("put(\"resultProof\", resultProof)"))
    assertTrue(eventSender.contains("put(\"resultFrameEpoch\", resultFrameEpoch)"))
    assertTrue(eventSender.contains("put(\"resultMinFrameSequence\", resultMinFrameSequence)"))
    assertTrue(eventSender.contains("put(\"resultProofAt\","))
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
    assertTrue("success path should close after the result is accepted or fallback marker is sent", success.contains("returnControlCodeSurfaceToRawTicket("))
    assertTrue("ViVi success must mark the result handled before raw-ticket cleanup so it does not send an unwatermarked control_code_result over the recovered raw ticket", success.contains("resultSent = true"))
    assertFalse(success.contains("sendControlCodeImageResult("))
    assertFalse(success.contains("waitForControlCodeResultAck("))
    val successAfterTicketEvent = success.substringAfter("sendTicketStateEvent(")
    assertTrue(successAfterTicketEvent.indexOf("resultSent = true") < successAfterTicketEvent.indexOf("returnControlCodeSurfaceToRawTicket("))
    assertTrue(successAfterTicketEvent.contains("waitForControlCodeBrowserCapture("))
    assertTrue(successAfterTicketEvent.indexOf("waitForControlCodeBrowserCapture(") < successAfterTicketEvent.indexOf("returnControlCodeSurfaceToRawTicket("))
    assertFalse(successAfterTicketEvent.contains("holdGeneratedControlCodeResultForBrowserFrame("))
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
  fun fastResultDetectionUsesPostOkVisualProbeWithBoundedRawTicketRootConfirmation() {
    val source = ticketStreamServiceSource()
    val wait = source.substringBetween("private suspend fun waitForGeneratedControlCodeResultAfterSubmit", "private suspend fun confirmGeneratedControlCodeResultForBrowser")
    val waitBody = wait.substringBefore("private suspend fun confirmGeneratedControlCodeResultForBrowser")

    assertFalse("fast result detection must not use accessibility observation", wait.contains("ticketAutopilot.observeFastState"))
    assertFalse("fast result detection must not snapshot accessibility nodes", wait.contains("snapshotVisibleNodes("))
    assertFalse("post-submit marker readiness must not use the removed raw capture helper", wait.contains("controlExitGeneratedResultFastScreencapProbe("))
    assertFalse("the old pre-OK/pre-settle visual shortcut must not come back", wait.contains("control_code_request_result_detected_by_visual_probe"))
    assertFalse("the removed standalone visual wait helper must not come back", wait.contains("waitForControlCodeVisualResultAfterSubmit("))
    assertTrue("post-submit raw-ticket visual state must be confirmed through one bounded root read", wait.contains("controlCodeRequestRootHierarchy("))
    assertTrue(wait.contains("\"wait_result_raw_ticket_root\""))
    assertTrue(waitBody.contains("TicketViviRecoveryState.CONTROL_CODE_RESULT"))
    assertTrue(wait.contains("rawTicketVisualCount == CONTROL_CODE_RAW_TICKET_VISUAL_REJECT_LOG_COUNT"))
    assertTrue(wait.contains("control_code_visual_raw_ticket_after_submit_wait"))
    assertTrue(wait.contains("CONTROL_CODE_RAW_TICKET_ROOT_CONFIRM_TIMEOUT_MILLIS"))
    assertTrue(wait.contains("control_code_request_phone_visual_generated_after_submit"))
    assertTrue(wait.contains("if (CONTROL_CODE_POST_SUBMIT_FRAME_SETTLE_MILLIS > 0L)"))
    assertTrue(source.contains("CONTROL_CODE_POST_SUBMIT_FRAME_SETTLE_MILLIS = 0L"))
    assertTrue(wait.contains("visualProbeStartedAtMillis"))
    assertFalse(wait.contains("requestControlCodeVisualProbe(\"control_code_after_ok_visual\")"))
    assertTrue(wait.contains("requestControlCodeVisualProbe(\"control_code_after_ok_visual_state\")"))
    assertTrue(wait.contains("recentControlCodeVisualProbeAfter(visualProbeStartedAtMillis)"))
    assertFalse(wait.contains("control_code_request_result_detected_by_post_ok_visual"))
    assertTrue(wait.contains("control_code_request_phone_visual_root_confirmed_after_submit"))
    assertTrue(source.contains("CONTROL_CODE_MARKER_RESULT_HIERARCHY"))
    val rootConfirm = source.substringBetween("private suspend fun confirmGeneratedControlCodeResultForBrowser", "private fun markerFirstControlCodeFrameWatermarkForBrowser")
    val markerFirst = source.substringBetween("private fun markerFirstControlCodeFrameWatermarkForBrowser", "private suspend fun captureGeneratedControlCodeImageBytes")
    assertTrue(rootConfirm.contains("markerFirstControlCodeFrameWatermarkForBrowser("))
    assertTrue(wait.contains("if (CONTROL_CODE_POST_SUBMIT_FRAME_SETTLE_MILLIS > 0L)"))
    assertFalse(wait.contains("requestControlCodeVisualProbe(\"control_code_result_after_root\")"))
    assertTrue(rootConfirm.contains("result_first_visible"))
    assertTrue(markerFirst.contains("result_marker_frame_ready"))
    assertFalse("visual success must not block on the old browser-frame wait loop", source.contains("private suspend fun waitForFreshControlCodeFrameWatermarkForBrowser"))
  }

  @Test
  fun markerResultWaitDoesNotFailFromInconclusiveRootState() {
    val source = ticketStreamServiceSource()
    val wait = source.substringBetween("private suspend fun waitForGeneratedControlCodeResultAfterSubmit", "private suspend fun confirmGeneratedControlCodeResultForBrowser")

    assertTrue(source.contains("CONTROL_CODE_POST_SUBMIT_FRAME_SETTLE_MILLIS = 0L"))
    assertTrue(source.contains("CONTROL_CODE_FAST_RESULT_TIMEOUT_MILLIS = 18_000L"))
    assertTrue(wait.contains("control_code_waiting_result_marker"))
    assertTrue(wait.contains("control_code_request_phone_visual_generated_after_submit"))
    assertFalse(wait.contains("control_code_popup_still_open_after_submit"))
    assertTrue(wait.contains("control_code_visual_popup_still_open"))
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
    assertTrue(helper.contains("startCommandReader(syncFrameRequested, burstUntilMillis, burstHoldMillis, controlCodeVisualProbeUntilMillis, controlCodeVisualProbeLastReportMillis, controlCodeVisualProbeReason)"))
    assertTrue(helper.contains("MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME"))
    assertTrue(helper.contains("encoder.setParameters(params)"))
    assertTrue(helper.contains("extendBurst(burstUntilMillis, burstHoldMillis)"))
  }

  @Test
  fun hardwareHelperSupportsControlCodeVisualProbe() {
    val engine = rootHardwareH264CaptureEngineSource()
    val helper = rootHardwareH264CaptureMainSource()
    val visualProbe = helper.substringBetween("private static String classifyControlCodeVisualState", "private static boolean frameHasControlCodeInputPopup")

    assertTrue(engine.contains("fun requestControlCodeVisualProbe(reason: String)"))
    assertTrue(engine.contains("fun recentControlCodeVisualProbeAfter(startedAtMillis: Long)"))
    assertTrue(engine.contains("line.startsWith(\"CONTROL_CODE_VISUAL \")"))
    assertTrue(engine.contains("lastControlCodeVisualProbeResult"))
    assertTrue(helper.contains("cmd.equals(\"control_code_visual_probe\")"))
    assertTrue(helper.contains("classifyControlCodeVisualState(source.bitmap, sourceCrop)"))
    assertTrue("popup state must be rejected before generated-result detection", visualProbe.indexOf("frameHasControlCodeInputPopup(probe)") in 0 until visualProbe.indexOf("frameHasGeneratedControlCodeResultHeader(probe)"))
    assertTrue("generated-result detection must run before raw-ticket detection", visualProbe.indexOf("frameHasGeneratedControlCodeResultHeader(probe)") in 0 until visualProbe.indexOf("frameHasRawTicketCodeGraphic(probe)"))
    assertTrue(visualProbe.contains("return \"control_popup\";"))
    assertTrue(visualProbe.contains("return \"generated\";"))
    assertTrue(visualProbe.contains("return \"raw_ticket\";"))
    assertTrue(helper.contains("frameHasControlCodeInputPopup(probe)"))
    assertTrue(helper.contains("frameHasControlCodePopupOrangeOkButton(probe)"))
    assertTrue(helper.contains("frameHasGeneratedControlCodeResultHeader(probe)"))
    assertTrue(helper.contains("frameHasGeneratedControlCodeResultChip(probe)"))
    assertTrue(helper.contains("redRatio >= 0.24"))
    assertTrue(helper.contains("label.lightRatio >= 0.48"))
    assertTrue(helper.contains("chipRows >= 3"))
    assertTrue(helper.contains("rowDark >= 30"))
    assertTrue(helper.contains("darkRatio >= 0.58"))
    assertTrue(helper.contains("lightRatio <= 0.42"))
    assertTrue(helper.contains("contrast >= 60.0"))
    assertTrue(helper.contains("CONTROL_CODE_VISUAL result=generated"))
    assertTrue(helper.contains("CONTROL_CODE_VISUAL result=\" + state"))
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
  fun foregroundGuardRequiresCheapCurrentUiProofBeforeSkippingRootProbe() {
    val source = ticketStreamServiceSource()
    val guard = source.substringBetween("private suspend fun enforceViviTicketPageIfNeeded", "private suspend fun dumpViviHierarchy")

    assertTrue(guard.contains("recentForegroundGuardTicketDetailStillFresh(now)"))
    assertTrue(guard.contains("shouldLogForegroundGuardRecentTicketDetailSkip(now)"))
    assertTrue(guard.contains("active_guard_recent_ticket_detail"))
    assertTrue(guard.contains("observeFastViviState(\"active_guard:${'$'}reason\")"))
    val fastIndex = guard.indexOf("observeFastViviState(\"active_guard:${'$'}reason\")")
    val recentIndex = guard.indexOf("recentForegroundGuardTicketDetailStillFresh(now)")
    val rootIndex = guard.indexOf("observeRootViviState(")
    assertTrue(
      "active guard must first sample the current cheap ViVi UI before allowing a recent-detail root skip; otherwise stale TICKET_DETAIL memory masks route/home after long pauses",
      fastIndex >= 0 && recentIndex >= 0 && rootIndex >= 0 && fastIndex < recentIndex && recentIndex < rootIndex
    )
    assertFalse(
      "stale recent-detail memory must not skip the cheap current-UI observation",
      guard.substring(0, fastIndex.coerceAtLeast(0)).contains("recentForegroundGuardTicketDetailStillFresh(now)")
    )
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
      "fresh current raw-ticket proof may still prevent the slow active-guard root dump after the cheap UI sample succeeds",
      recentIndex < rootIndex
    )
  }

  @Test
  fun activeSessionStartReusesFreshHardwareStreamBeforeRootRevalidation() {
    val source = ticketStreamServiceSource()
    val start = source.substringBetween("private suspend fun startTicketSessionLocked()", "val sourceSize = currentDisplaySize()")
    val active = start.substringAfter("if (streamActive) {")
    val reuse = source.substringBetween(
      "private fun canReuseActiveHardwareStreamWithoutRootRevalidation",
      "private suspend fun validateActiveTicketSessionBeforeReuse"
    )

    assertTrue(source.contains("private suspend fun validateActiveTicketSessionBeforeReuse("))
    assertTrue(source.contains("private fun canReuseActiveHardwareStreamWithoutRootRevalidation("))
    assertTrue(active.contains("canReuseActiveHardwareStreamWithoutRootRevalidation(\"session_start_already_active\")"))
    assertTrue(active.contains("validateActiveTicketSessionBeforeReuse("))
    assertTrue(
      "a fresh rooted H.264 stream with recent ticket proof should reuse immediately before slower page revalidation",
      active.indexOf("canReuseActiveHardwareStreamWithoutRootRevalidation(\"session_start_already_active\")") <
        active.indexOf("validateActiveTicketSessionBeforeReuse(")
    )
    assertTrue(
      "the fast reuse path must be able to publish active before the fallback recovery path runs",
      active.indexOf("canReuseActiveHardwareStreamWithoutRootRevalidation(\"session_start_already_active\")") <
        active.indexOf("updateTicketSessionState(TICKET_SESSION_LIVE, \"session_start_already_active\")")
    )
    assertTrue(reuse.contains("lastFrameSentAtMillis"))
    assertTrue(reuse.contains("LIVE_FRAME_MAX_AGE_MILLIS"))
    assertTrue(reuse.contains("viviStateMemory.current()"))
    assertTrue(reuse.contains("viviStateMemory.recentTicketDetailWithin(ACTIVE_STREAM_REUSE_TICKET_DETAIL_MAX_AGE_MILLIS)"))
    assertTrue(reuse.contains("ACTIVE_STREAM_REUSE_TICKET_DETAIL_MAX_AGE_MILLIS"))
    assertFalse(
      "fast active-stream reuse must not run a root page dump on the start critical path",
      reuse.contains("observeRootViviState(") || reuse.contains("dumpViviHierarchy(") || reuse.contains("controlCodeRequestRootHierarchy(")
    )
  }

  @Test
  fun publicHealthUsesRecentRawTicketProofWhenActiveGuardFastSnapshotIsInconclusive() {
    val source = ticketStreamServiceSource()
    val health = source.substringBetween("private fun health()", "private fun markTicketNonTouchAction")
    val effective = source.substringBetween(
      "private fun effectiveViviHealthForPublicStream",
      "private fun controlCodeRootObservationMillis"
    )

    assertTrue(health.contains("val rawViviHealth = viviStateMemory.health(nowMillis)"))
    assertTrue(health.contains("val viviHealth = effectiveViviHealthForPublicStream(rawViviHealth, nowMillis, hardwareCapture)"))
    assertTrue(effective.contains("rawViviHealth.state != TicketViviRecoveryState.UNKNOWN_VIVI.name"))
    assertTrue(effective.contains("rawViviHealth.state != TicketViviRecoveryState.BLANK.name"))
    assertTrue(effective.contains("lastPixelTicketState != TICKET_PIXEL_STATE_RAW_TICKET"))
    assertTrue(effective.contains("lastPixelTicketEventSentAtMillis"))
    assertTrue(effective.contains("lastFrameSentAtMillis"))
    assertTrue(effective.contains("LIVE_FRAME_MAX_AGE_MILLIS"))
    assertTrue(effective.contains("viviStateMemory.recentTicketDetailWithin(ACTIVE_STREAM_REUSE_TICKET_DETAIL_MAX_AGE_MILLIS)"))
    assertTrue(effective.contains("source = \"effective_stream_recent_ticket_detail\""))
    assertFalse(
      "public health effective proof must not run root page inspection",
      effective.contains("observeRootViviState(") || effective.contains("dumpViviHierarchy(")
    )
  }

  @Test
  fun wakeFastRecentTicketDetailProofUsesLongLivedFocusedViviMemory() {
    val source = ticketStreamServiceSource()
    val fastReady = source.substringBetween("private suspend fun fastWakeReadyFromRecentTicketDetail", "private fun remainingWakeBudgetMillis")

    assertTrue(fastReady.contains("val ageMillis ="))
    assertTrue(
      "wake fast-ready still expires remembered TICKET_DETAIL proof at the long ticket-memory horizon",
      fastReady.contains("ageMillis !in 0..TICKET_WAKE_RECENT_DETAIL_FAST_READY_MAX_AGE_MILLIS")
    )
    assertTrue(fastReady.contains("focused.contains(TicketScreenConfig.VIVI_PACKAGE)"))
    assertTrue(fastReady.contains("wake_recent_ticket_detail_fast_ready_stale"))
    assertTrue(source.contains("private const val TICKET_WAKE_RECENT_DETAIL_FAST_READY_MAX_AGE_MILLIS = TICKET_WAKE_MEMORY_TICKET_DETAIL_MAX_AGE_MILLIS"))
  }

  @Test
  fun wakeFastRecentTicketDetailMemorySurvivesServiceRestartWithinSameSafetyWindow() {
    val source = ticketStreamServiceSource()
    val onCreate = source.substringBetween("override fun onCreate()", "override fun onStartCommand")
    val persist = source.substringBetween("private fun persistViviStateMemory", "private fun restoreViviStateMemory")
    val restore = source.substringBetween("private fun restoreViviStateMemory", "private fun ageMillis")

    assertTrue(source.contains("TicketViviStateMemory(::persistViviStateMemory)"))
    assertTrue(onCreate.contains("restoreViviStateMemory()"))
    assertTrue(persist.contains("KEY_VIVI_MEMORY_TICKET_WALL_MILLIS"))
    assertTrue(persist.contains("snapshot.state == TicketViviRecoveryState.TICKET_DETAIL"))
    assertTrue(restore.contains("viviStateMemory.seedTicketDetail"))
    assertTrue(restore.contains("TICKET_WAKE_MEMORY_TICKET_DETAIL_MAX_AGE_MILLIS"))
    assertTrue(restore.contains("viviStateMemory.seed("))
    assertTrue(restore.contains("currentWallMillis < ticketWallMillis"))
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
    assertFalse(fastFlow.contains("captureGeneratedControlCodeImageBytes("))
    assertFalse(fastFlow.contains("resultProof = \"phone_root_image\""))
    assertFalse(fastFlow.contains("imageBytes = imageBytes"))
    assertFalse("ViVi marker path must not carry RS image bytes", fastFlow.contains("resultImageBytes = imageBytes"))
    assertFalse("ticket.jolkins/ViVi marker path must never send Telegram RS-bot image results", generate.contains("sendRigassatiksmeQrResult("))
    assertFalse("ticket.jolkins/ViVi marker path must not route resultImage into ViVi monthly-ticket capture", generate.contains("runFastViviMonthlyTicketImageDeliveryForRequest"))
    assertFalse(generate.contains("PendingBrowserControlCodeCapture("))
    assertFalse(generate.contains("sendControlCodeFrameReady("))
    assertTrue("generated result must wait for browser capture ack before cleanup", generate.substringBetween("if (ok) {", "} else if (delivery.cleanupRequired)").contains("waitForControlCodeBrowserCapture("))
    assertFalse(source.contains("handleControlCodeBrowserCaptureAck("))
  }

  @Test
  fun rawTicketAfterSubmitDoesNotCompleteAsGeneratedControlCodeProof() {
    val source = ticketStreamServiceSource()
    val success = source.substringBetween("if (ok) {", "} else if (delivery.cleanupRequired)")

    assertFalse("raw-ticket phone proof must not bypass generated-result cleanup", success.contains("completeControlCodeRawTicketAfterSubmitCleanup("))
    assertFalse("raw-ticket phone proof must not be a successful proof value", source.contains("phone_visual_raw_ticket_after_submit"))
    assertFalse("cleanup must not branch on a raw-ticket proof", success.contains("delivery.resultProof =="))
    assertTrue("generated proof cleanup must still return the phone to the raw ticket", success.contains("returnControlCodeSurfaceToRawTicket("))
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
    val fastOpen = source.substringBetween("private suspend fun prepareViviForRootHardwareH264FastOpen", "private suspend fun prepareViviForRootHardwareH264Capture")
    val prepare = source.substringBetween("private suspend fun prepareViviForRootHardwareH264Capture", "private fun scheduleRootHardwareH264CaptureStart")
    val wake = source.substringBetween("private suspend fun wakeTicketScreenForSessionStart", "private suspend fun waitForTicketScreenInteractiveForWake")

    assertTrue(hardwareStart.contains("val wakeStartedAtMillis = beginTicketWake(reason)"))
    assertTrue(hardwareStart.contains("wakeTicketScreenForSessionStart(reason, wakeStartedAtMillis)"))
    assertTrue(fastOpen.contains("launchViviForWake(reason, timeoutMillis = launchTimeoutMillis)"))
    assertTrue(hardwareStart.contains("prepareViviForRootHardwareH264FastOpen("))
    assertFalse("public startup must not call the long recovery prepare helper", hardwareStart.contains("prepareViviForRootHardwareH264Capture("))
    assertTrue(hardwareStart.contains("ensureEncoderIfPossible()"))
    assertTrue(hardwareStart.contains("requestKeyFrame(\"vivi_ready_encoder_start:\$reason\")"))
    assertFalse(hardwareStart.contains("vivi_launch_encoder_prewarm"))
    assertTrue(hardwareStart.indexOf("wakeTicketScreenForSessionStart(reason, wakeStartedAtMillis)") < hardwareStart.indexOf("prepareViviForRootHardwareH264FastOpen("))
    assertTrue(hardwareStart.indexOf("if (!prepareResult.success)") < hardwareStart.indexOf("hardwareFrameBroadcastAllowed = true"))
    assertTrue(hardwareStart.indexOf("hardwareFrameBroadcastAllowed = true") < hardwareStart.indexOf("ensureEncoderIfPossible()"))
    assertTrue(hardwareStart.contains("requestFreshTicketStateFrameWatermark(\"vivi_ready:\$reason\")"))
    assertFalse("hardware startup should not carry a fixed app-settle sleep", hardwareStart.contains("delay(PRE_CAPTURE_APP_SETTLE_MILLIS)"))
    assertFalse("normal hardware wake should not use accessibility fast readiness", fastOpen.contains("waitForFastTicketDetail("))
    assertTrue(fastOpen.contains("observeTicketDetailForFastPublicOpenRootProof("))
    assertFalse("normal public startup should not call the long wake observer", fastOpen.contains("observeTicketDetailForWakeWithRoot("))
    assertFalse("normal public startup should not use wake recovery actions", fastOpen.contains("attemptWakeRecoveryActionForRootWake("))
    assertFalse("normal public startup should not relaunch after UNKNOWN/blank states", fastOpen.contains("attemptWakeRelaunchForRootWake("))
    assertTrue("explicit recovery prepare should still route through the long wake observer", prepare.contains("observeTicketDetailForWakeWithRoot("))
    assertTrue("explicit recovery prepare keeps its caller-selected budget", prepare.contains("budgetMillis = budgetMillis"))
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
  fun nonTouchPanelClampStopsBackgroundClampsWithoutSelfTerminatingSuccessfulInput() {
    val source = ticketStreamServiceSource()
    val wrapper = source.substringBetween("private fun wrapNonTouchPanelSleepClamp", "private fun shellQuote")
    val stopClamps = wrapper.substringBetween("      ticket_panel_stop_clamps() {", "      ticket_panel_trap_cleanup()")

    assertTrue(stopClamps.contains("touch ") && stopClamps.contains("ticket_clamp_stop"))
    assertTrue("successful input commands must still wait for the clamp loops to observe the stop file", stopClamps.contains("wait ") && stopClamps.contains("ticket_sysfs_clamp_pid") && stopClamps.contains("ticket_display_clamp_pid"))
    assertFalse(
      "Android mksh can surface SIGTERM from killed background shell functions to the parent script; a successful input then exits 143 and the service records ok=false, so stop clamps by touching the stop file and waiting instead of killing them",
      stopClamps.contains("kill ")
    )
    assertTrue("the wrapper must preserve the actual input command result after clamp cleanup", wrapper.contains("exit ") && wrapper.contains("ticket_command_rc"))
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
    assertTrue(source.contains("private const val TICKET_WAKE_RECOVERY_BUDGET_MILLIS = 60_000L"))
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
  fun browserCaptureAckHandlerHoldsCleanupUntilBrowserProof() {
    val source = ticketStreamServiceSource()

    assertTrue(source.contains("\"control_code_browser_capture\" -> {"))
    assertTrue(source.contains("handleControlCodeBrowserCapture(requestId, ok, reason, frameEpoch, frameSequence)"))
    assertTrue(source.contains("beginControlCodeBrowserCaptureWait(cleanRequestId)"))
    assertTrue(source.contains("waitForControlCodeBrowserCapture("))
    assertTrue(source.contains("clearControlCodeBrowserCaptureWait(cleanRequestId)"))
    assertTrue(source.contains("control_code_browser_capture_ack_timeout"))
    assertFalse(source.contains("\"control_code_result_ack\" -> {"))
    assertFalse(source.contains("handleControlCodeResultAck(requestId, ok, reason)"))
    assertFalse(source.contains("beginControlCodeResultAckWait(cleanRequestId)"))
    assertFalse(source.contains("waitForControlCodeResultAck("))
    assertFalse(source.contains("clearControlCodeResultAckWait(cleanRequestId)"))
    assertFalse(source.contains("control_code_result_ack_timeout"))
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
    assertTrue(source.contains("markerFirstControlCodeFrameWatermarkForBrowser("))
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
    assertFalse(delivery.contains("imageBytes = imageBytes"))
    assertFalse(delivery.contains("resultProof = \"phone_root_image\""))
    assertFalse(generate.contains("sendControlCodeImageResult("))
    assertFalse(generate.contains("waitForControlCodeResultAck("))
  }

  @Test
  fun rsMonthlyTicketPipelineHasTypedOperationBoundary() {
    val source = ticketStreamServiceSource()
    val operation = rigasSatiksmeMonthlyTicketOperationSource()
    val outcome = ticketRequestOutcomeSource()
    val handler = source.substringBetween("private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr", "private fun cancelPendingRigasSatiksmeReturnCleanup")

    assertTrue("RS monthly ticket generation must have a dedicated operation class", operation.contains("class RigasSatiksmeMonthlyTicketOperation"))
    assertTrue("RS operation must return one typed request outcome", operation.contains("suspend fun run(") && operation.contains("TicketRequestOutcome"))
    assertTrue(outcome.contains("data class TicketRequestOutcome"))
    listOf("val ok: Boolean", "val reason: String", "val failedPhase: String?", "val phases: Map<String, Long>", "val imageBytes: ByteArray?", "val sourceApp: String", "val ticketFlow: String", "val cleanupRequired: Boolean").forEach {
      assertTrue("TicketRequestOutcome missing $it", outcome.contains(it))
    }
    assertTrue("TicketStreamService should delegate RS generation to the operation boundary", handler.contains("RigasSatiksmeMonthlyTicketOperation("))
    assertTrue(handler.contains(".run("))
    assertFalse("RS handler must not own the flow/capture branch logic after extraction", handler.contains("if (!flow.ok)"))
    assertFalse("RS handler must not own image-capture failure branching after extraction", handler.contains("if (imageBytes == null || imageBytes.isEmpty())"))
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
    val rsLaunch = service.substringBetween("private suspend fun launchRigasSatiksmeAppForVisualAutomation", "private suspend fun resetRigasSatiksmeAppForVisualAutomation")
    val rsReset = service.substringBetween("private suspend fun resetRigasSatiksmeAppForVisualAutomation", "private suspend fun waitForRigasSatiksmeVisualForeground")

    assertTrue(config.contains("const val RIGAS_SATIKSME_PACKAGE = \"com.flutter.rspassenger\""))
    assertTrue(config.contains("const val RIGAS_SATIKSME_LAUNCH_ACTIVITY = \"com.flutter.rspassenger/.MainActivity\""))
    assertTrue(config.contains("const val TICKET_QR_APP_VIVI = \"vivi\""))
    assertTrue(config.contains("const val TICKET_QR_APP_RIGAS_SATIKSME = \"rigas_satiksme\""))
    assertTrue(config.contains("const val TICKET_QR_OWNER_TICKET = \"ticket\""))
    assertTrue(config.contains("const val TICKET_QR_OWNER_RIGAS_SATIKSME = \"rigassatiksme\""))
    assertTrue(config.contains("const val TICKET_QR_FLOW_CONTROL_CODE = \"control_code\""))
    assertTrue(config.contains("const val TICKET_QR_FLOW_MONTHLY_TICKET = \"monthly_ticket\""))
    assertTrue(config.contains("const val TICKET_QR_RESULT_SOURCE_APP_RIGAS_SATIKSME = RIGAS_SATIKSME_PACKAGE"))
    assertTrue(config.contains("const val TICKET_QR_RESULT_FLOW_RIGAS_SATIKSME_ANDROID_MONTHLY = \"rigas_satiksme_android_monthly_ticket_control\""))
    assertTrue(dispatcher.contains("val owner = element[\"owner\"]?.jsonPrimitive?.contentOrNull.orEmpty()"))
    assertTrue(dispatcher.contains("val app = element[\"app\"]?.jsonPrimitive?.contentOrNull.orEmpty()"))
    assertTrue(dispatcher.contains("val flow = element[\"flow\"]?.jsonPrimitive?.contentOrNull.orEmpty()"))
    assertTrue(dispatcher.contains("handleGenerateControlCode(client, requestId, digits, owner, app, flow, resultImage, rsQueueHint)"))
    assertTrue(handle.contains("TicketScreenConfig.TICKET_QR_APP_RIGAS_SATIKSME"))
    assertTrue(handle.contains("TicketScreenConfig.TICKET_QR_FLOW_MONTHLY_TICKET"))
    assertTrue("ticket.jolkins control-code messages must declare the ticket owner", handle.contains("val requestedOwner = owner.trim()"))
    assertTrue("ticket.jolkins control-code messages must declare the ViVi app", handle.contains("val requestedApp = app.trim()"))
    assertTrue("ticket.jolkins control-code messages must declare the control-code flow", handle.contains("val requestedFlow = flow.trim()"))
    assertTrue("missing owner/app/flow must fail before phone input starts", handle.contains("command_owner_flow_required"))
    assertTrue("RS monthly-ticket commands must declare the RS owner", handle.contains("requestedOwner != TicketScreenConfig.TICKET_QR_OWNER_RIGAS_SATIKSME"))
    assertTrue("ViVi ticket commands must declare the ticket owner", handle.contains("requestedOwner != TicketScreenConfig.TICKET_QR_OWNER_TICKET"))
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
    assertTrue("Rīgas Satiksme failures should report immediately with cleanup still pending", rsFlow.contains("cleanupPending = outcome.cleanupRequired"))
    assertTrue("Rīgas Satiksme path must publish cleanup completion back to the browser", rsFlow.contains("sendControlCodeCleanup("))
    assertTrue(rsDriver.contains("TicketScreenConfig.RIGAS_SATIKSME_PACKAGE") && rsDriver.contains("startActivity(launchIntent)"))
    assertFalse("RS batch launch should not force-stop ViVi while queued RS jobs may still need the phone", rsDriver.contains("am force-stop ${'$'}{TicketScreenConfig.VIVI_PACKAGE}"))
    assertFalse("RS pressure latency must not cold-force-stop the RS app for every queued request", rsLaunch.contains("am force-stop ${'$'}{TicketScreenConfig.RIGAS_SATIKSME_PACKAGE}"))
    assertTrue("Persistent unknown RS app state should get one no-data-loss force-stop/relaunch recovery", rsReset.contains("am force-stop ${'$'}{TicketScreenConfig.RIGAS_SATIKSME_PACKAGE}") && rsDriver.contains("resetRigasSatiksmeAppForVisualAutomation(reason)"))
    assertFalse("Default RS launch should not spend the fast path budget on a pre-entry root readiness dump", rsDriver.contains("wait_rs_initial_ready /sdcard/pixel-rs-initial-window.xml"))
    assertTrue("Queued RS pressure jobs should pass a warm previous-QR hint into the runner so they do not pay uiautomator home discovery every time", rsFlow.contains("reusePreviousRigasSatiksmeQr = cancelPendingRigasSatiksmeReturnCleanup(\"new_rs_monthly_ticket_request\")") && rsDriver.contains("reusePreviousRigasSatiksmeQr: Boolean"))
    assertFalse("A fixed 3.8s launch sleep prevents warm queued requests from reaching the 15s average target", rsDriver.contains("sleep 3.8"))
    val semanticDriver = rigasSatiksmeSemanticDriverSource()
    val directDriver = rigasSatiksmeDirectTapDriverSource()
    assertTrue("RS monthly-ticket path should use the direct tap phone driver", rsDriver.contains("RigasSatiksmeDirectTapDriver("))
    assertTrue("RS direct driver must read the RS app state through bounded uiautomator proofs instead of the black secure screenshot feed", rsDriver.contains("snapshotRigasSatiksmeUiAutomatorNodes(") && rsDriver.contains("TicketUiautomatorDump.command("))
    assertFalse("RS direct path must not require the phone automation accessibility service", rsDriver.contains("ensureRigasSatiksmeSemanticAutomationReady"))
    assertTrue("Direct driver must tap known RS app targets explicitly", directDriver.contains("REGISTER_TRIP_X") && directDriver.contains("MANUAL_CODE_CHOICE_Y") && directDriver.contains("CONFIRM_CODE_Y"))
    assertTrue("Semantic classifier must prove the final control ticket against the submitted code", semanticDriver.contains("has(cleanDigits)") && semanticDriver.contains("TICKET_CONTROL_MATCHING"))
    assertTrue("RS text input must stay isolated to the direct manual-code entry path", rsDriver.contains("enterRigasSatiksmeManualCode") && rsDriver.contains("input text ${'$'}cleanDigits"))
    assertTrue("RS digit entry should clear the field with one compact keyevent command instead of many shell subprocesses", rsDriver.contains("input keyevent KEYCODE_MOVE_END KEYCODE_DEL KEYCODE_DEL"))
    assertFalse("RS digit fallback must not use the old slow delete loop", rsDriver.contains("for i in 1 2 3 4 5 6 7 8 9"))
    assertTrue("Semantic stale QR handling should back out before fresh entry", semanticDriver.contains("TICKET_CONTROL_STALE") && semanticDriver.contains("gateway.performBack()"))
    assertFalse("RS monthly-ticket path must not keep the coordinate fast path", rsDriver.contains("RS_FAST_FLOW_STATUS"))
    assertTrue("RS monthly-ticket app driving must go through the isolated direct tap gateway", rsDriver.contains("tapRigasSatiksmeVisualTarget(") && directDriver.contains("gateway.tapRatio("))
    assertFalse("Default cold/unknown RS launch should not pay for the old initial hierarchy dump", rsDriver.contains("pixel-rs-initial-window.xml"))
    assertFalse("RS monthly-ticket generation must not keep an in-flow recovery re-drive", service.contains("recoverRigasSatiksmeMonthlyTicketFlowBeforeFailure"))
    assertFalse("RS monthly-ticket generation must not keep hierarchy-derived recovery taps", rsDriver.contains("tap_rs_desc_center"))
    assertFalse("RS monthly-ticket generation must not keep shell keyboard driving", rsDriver.contains("input keyevent KEYCODE_ENTER"))
    assertTrue(semanticDriver.contains("ticketForControlSelectors"))
    assertTrue(service.contains("hasRigasSatiksmeMonthlyTicketMarker(hierarchy)"))
    assertTrue(service.contains("30 dienu biļete"))
    assertTrue(semanticDriver.contains("TICKET_CONTROL_MATCHING"))
    assertTrue("RS result validation must prove the returned control screen contains the submitted code, not a stale prior ticket", semanticDriver.contains("has(cleanDigits)"))
    assertTrue(semanticDriver.contains("TICKET_CONTROL_MATCHING"))
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
    val semanticDriver = rigasSatiksmeSemanticDriverSource()

    assertTrue(
      "If semantic RS app proof shows the ticket-control QR screen is visible, the flow must capture and send the image without depending on a shell tap result",
      semanticDriver.contains("RigasSatiksmeSemanticState.TICKET_CONTROL_MATCHING")
    )
    assertTrue(semanticDriver.contains("ok = true"))
    assertFalse(
      "A nonzero shell exit must not turn an already-visible RS control screen into failed/generated with no image",
      rsDriver.contains("ok = result.ok && status == \"rs_monthly_ticket_control_screen\"")
    )
  }

  @Test
  fun semanticAccessibilityTapCanUseParentBoundsForFlutterLabels() {
    val source = phoneAutomationAccessibilityServiceSource()
    val tap = source.substringBetween(
      "private fun tapNodeOrParentCenter",
      "private fun AccessibilityNodeInfo.textValue"
    )

    assertTrue("Semantic taps should not fail just because a Flutter label node has empty bounds", source.contains("tapNodeOrParentCenter(node)"))
    assertTrue("Semantic taps should walk up to the labeled control parent when needed", tap.contains("current = current.parent"))
    assertTrue("Semantic taps must still be geometry derived from the matched accessibility node tree, not fixed screen coordinates", tap.contains("bounds.width() > 0 && bounds.height() > 0"))
    assertFalse("RS app driving must stay off fixed coordinate tap scripts", tap.contains("input tap"))
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
  fun rsMonthlyTicketResultScreenshotUsesBoundedSecureCaptureWithoutViviGraphicCrop() {
    val source = ticketStreamServiceSource()
    val rsCapture = source.substringBetween("private suspend fun captureRigasSatiksmeMonthlyTicketImageBytes", "private suspend fun fastVisibleHierarchy")
    val capture = source.substringBetween("private suspend fun captureGeneratedControlCodeImageBytes", "private fun decodeBase64Png")
    val viviCrop = source.substringBetween("private fun cropControlCodeImage", "private fun looksLikePng")

    assertTrue("RS monthly-ticket result capture must keep the RS screenshot path separate from ViVi graphic cropping", rsCapture.contains("cropToControlCodeGraphic = false"))
    assertTrue("RS monthly-ticket result capture should use a bounded readable width so three-code batches can finish under the live target", rsCapture.contains("maxOutputWidth = RIGAS_SATIKSME_RESULT_CAPTURE_MAX_WIDTH"))
    assertTrue(source.contains("private const val RIGAS_SATIKSME_RESULT_CAPTURE_MAX_WIDTH = 720"))
    assertFalse("RS monthly-ticket capture must be one-shot; capture failures should report quickly instead of retrying in the Pixel request path", rsCapture.contains("repeat("))
    assertFalse("Android RS capture should not perform the Telegram top/bottom delivery crop", rsCapture.contains("cropRigasSatiksmeMonthlyTicketScreenshot"))
    assertFalse("Android service should not own the RS Telegram delivery crop constant", source.contains("RIGAS_SATIKSME_RESULT_IMAGE_VERTICAL_CROP_PIXELS"))
    assertTrue(capture.contains("cropToControlCodeGraphic: Boolean = true"))
    assertTrue(capture.contains("maxOutputWidth: Int? = null"))
    assertTrue(capture.contains("rootHardwareH264CaptureEngine.captureSecurePngBase64("))
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
    assertTrue(helper.contains("captureSecurePngBase64(sourceWidth, sourceHeight, width, height)"))
    assertTrue(helper.contains("target_width="))
    assertTrue(helper.contains("target_height="))
    assertTrue(helper.contains("SecureScreenCapture(sourceWidth, sourceHeight)"))
    assertTrue(helper.contains("PNG_BASE64_BEGIN"))
    assertTrue(helper.contains("PNG_BASE64_END"))
    assertTrue(helper.contains("Base64.NO_WRAP"))
    assertTrue(decoder.contains("PNG_BASE64_BEGIN"))
    assertTrue(decoder.contains("PNG_BASE64_END"))
  }

  @Test
  fun rsMonthlyTicketFlowUsesStateCheckedCoordinatePathWithRootProofs() {
    val source = ticketStreamServiceSource()
    val rsDriver = source.substringBetween("private suspend fun runRigasSatiksmeMonthlyTicketFlow", "private suspend fun captureRigasSatiksmeMonthlyTicketImageBytes")
    val semanticDriver = rigasSatiksmeSemanticDriverSource()
    val directDriver = rigasSatiksmeDirectTapDriverSource()

    assertTrue("RS flow should delegate to the direct tap/proof driver", rsDriver.contains("RigasSatiksmeDirectTapDriver("))
    assertFalse("RS flow must not gate launch on accessibility automation availability", rsDriver.contains("ensureRigasSatiksmeSemanticAutomationReady"))
    assertTrue("RS direct driver must read app state with bounded uiautomator proofs because the RS secure screenshot feed can be black", rsDriver.contains("TicketUiautomatorDump.command("))
    assertFalse("RS shell navigation must not depend on plain screencap for secure app pixels", rsDriver.contains("screencap -p | base64"))
    assertTrue("RS semantic driver must classify the final control ticket against the submitted digits", semanticDriver.contains("has(cleanDigits)") && semanticDriver.contains("TICKET_CONTROL_MATCHING"))
    assertTrue("RS direct driver must preserve stale-code and rejected-code as specific failures", directDriver.contains("\"rs_monthly_ticket_stale_code\"") && directDriver.contains("\"code_rejected_by_rs\""))
    assertFalse("RS flow must not keep the measured coordinate fast path", rsDriver.contains("RS_FAST_FLOW_STATUS"))
    assertTrue("RS coordinate taps must stay isolated behind the direct tap gateway", rsDriver.contains("tapRigasSatiksmeVisualTarget(") && directDriver.contains("gateway.tapRatio("))
    assertTrue("RS tap coordinates must use current display size and fixed RS ratios", rsDriver.contains("currentDisplaySize()") && directDriver.contains("555.0 / 2424.0"))
    assertTrue("RS flow may use non-touch digit input only inside the direct RS manual-code entry path", rsDriver.contains("enterRigasSatiksmeManualCode") && rsDriver.contains("input text ${'$'}cleanDigits"))
    assertTrue("RS submit must be followed by state proof so a missed confirm is not reported as success", directDriver.contains("POST_SUBMIT_SETTLE_MILLIS") && directDriver.contains("proof_1"))
    assertFalse("RS digit fallback must not use one shell process per delete key", rsDriver.contains("do input keyevent KEYCODE_DEL; done"))
    assertFalse("RS validation must not keep old fast-hierarchy fallback events", rsDriver.contains("rs_monthly_ticket_fast_hierarchy_wait_finished"))
    assertFalse("Fast path should not spend the request budget on repeated uiautomator polling loops", rsDriver.contains("wait_for_text()"))
    assertFalse("Fast path should not spend the request budget on repeated uiautomator polling loops", rsDriver.contains("wait_for_any_text()"))
    assertFalse("A fixed 3.5s submit sleep dominates successful request latency", rsDriver.contains("sleep 3.5"))
    assertFalse("A fixed 2.5s launch sleep slows already-warm app starts", rsDriver.contains("sleep 2.5"))
    assertFalse("A fixed 2.0s monthly-ticket open sleep should be readiness-gated", rsDriver.contains("sleep 2.0"))
    assertFalse("A fixed 1.5s navigation sleep should be readiness-gated", rsDriver.contains("sleep 1.5"))
    assertFalse("A fixed 1.4s manual-code fallback sleep should be readiness-gated", rsDriver.contains("sleep 1.4"))
    assertFalse("A fixed 0.9s monthly-ticket list sleep should be readiness-gated", rsDriver.contains("sleep 0.9"))
    assertFalse("RS Flutter fast hierarchy is empty in production; do not spend 1.8s polling it before root fallback", rsDriver.contains("hierarchyWaitStartedAt <= 1_800L") || rsDriver.contains("<= 1_800L)"))
    assertFalse("Semantic RS proof should not use root hierarchy dumps as its success authority", rsDriver.contains("dumpVisibleHierarchyWithRoot(\n      path = \"/data/local/tmp/pixel-rs-ticket-window.xml\""))
    assertFalse("RS proof must not retry blank dumps inside the request", rsDriver.contains("dumpVisibleHierarchyWithRootRetry("))
  }

  @Test
  fun rsMonthlyTicketRootHierarchyFilesUseLocalTmpNotSharedSdcard() {
    val source = ticketStreamServiceSource()
    val rsDriver = source.substringBetween("private suspend fun runRigasSatiksmeMonthlyTicketFlow", "private suspend fun captureRigasSatiksmeMonthlyTicketImageBytes")

    assertFalse("RS/ViVi root hierarchy proof files must not live under shared /sdcard because APK redeploys can change app UID and strand stale unreadable files", rsDriver.contains("/sdcard/pixel-rs"))
    assertFalse("RS return hierarchy proof files must not live under shared /sdcard", rsDriver.contains("/sdcard/pixel-vivi-fast-return-window.xml"))
    assertFalse("Semantic RS request path should not create RS root hierarchy files", rsDriver.contains("/data/local/tmp/pixel-rs-start-window.xml"))
    assertFalse("Semantic RS request path should not create RS root hierarchy files", rsDriver.contains("/data/local/tmp/pixel-rs-ticket-window.xml"))
  }

  @Test
  fun rsMonthlyTicketRootFallbackRereadsDumpFileWhenWrapperStdoutIsEmpty() {
    val source = ticketStreamServiceSource()
    val rootDump = source.substringBetween("private suspend fun dumpVisibleHierarchyWithRoot", "private fun classifyRigasSatiksmeMonthlyTicketHierarchy")

    assertTrue("A successful uiautomator dump can leave the hierarchy file but return empty stdout from the app-root wrapper; reread the file before declaring missing", rootDump.contains("if (result.stdout.isBlank())"))
    assertTrue("The reread must use the exact dump path, not a stale hard-coded path", rootDump.contains("java.io.File(path)") && rootDump.contains("/system/bin/cat ${'$'}path"))
    assertTrue("The fallback should emit its own event so empty-wrapper incidents remain visible", rootDump.contains("root_visible_hierarchy_file_read"))
  }

  @Test
  fun rsMonthlyTicketRootHierarchyFallbackUsesWakeExecutorOutsideInputWorker() {
    val source = ticketStreamServiceSource()
    val rootDump = source.substringBetween("private suspend fun dumpVisibleHierarchyWithRoot", "private fun classifyRigasSatiksmeMonthlyTicketHierarchy")
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
  fun rsMonthlyTicketUsesBoundedStartStateRecoveryBeforeFinalProof() {
    val source = ticketStreamServiceSource()
    val semanticDriver = rigasSatiksmeSemanticDriverSource()

    assertTrue(
      "The RS request must use semantic state observation before app actions",
      semanticDriver.contains("val state = classify(snapshot, cleanDigits)")
    )
    assertTrue(
      "The RS request must use semantic final proof against the requested digits",
      semanticDriver.contains("TICKET_CONTROL_MATCHING")
    )
    assertFalse(source.contains("dumpVisibleHierarchyWithRootRetry"))
    assertFalse(source.contains("root_visible_hierarchy_retry"))
    assertFalse(source.contains("RS_SEMANTIC_ROOT_DUMP_ATTEMPTS"))
    assertFalse(source.contains("RS_SEMANTIC_ROOT_DUMP_RETRY_DELAY_MILLIS"))
    assertFalse(source.contains("recoverRigasSatiksmeMonthlyTicketFlowBeforeFailure"))
    assertTrue(semanticDriver.contains("TICKET_CONTROL_STALE"))
    assertTrue(semanticDriver.contains("MANUAL_CODE_ENTRY"))
  }

  @Test
  fun rsMonthlyTicketDefaultFastPathUsesInitialRootDumpBeforeManualCodeEntry() {
    val semanticDriver = rigasSatiksmeSemanticDriverSource()

    assertTrue(
      "Default RS requests should classify semantic state before clicking REGISTER A TRIP",
      semanticDriver.indexOf("val state = classify(snapshot, cleanDigits)") <
        semanticDriver.indexOf("gateway.click(registerTripSelectors")
    )
    assertFalse(semanticDriver.contains("/data/local/tmp/pixel-rs-start-window.xml"))
    assertTrue(
      "Final semantic proof must follow app actions before image capture",
      semanticDriver.contains("gateway.click(confirmSelectors") &&
        semanticDriver.contains("RigasSatiksmeSemanticState.TICKET_CONTROL_MATCHING")
    )
  }

  @Test
  fun rsMonthlyTicketFlowFailsFastAfterSingleRootProof() {
    val source = ticketStreamServiceSource()
    val rsDriver = source.substringBetween("private suspend fun runRigasSatiksmeMonthlyTicketFlow", "private suspend fun captureRigasSatiksmeMonthlyTicketImageBytes")
    val handler = source.substringBetween("private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr", "private fun cancelPendingRigasSatiksmeReturnCleanup")
    val semanticDriver = rigasSatiksmeSemanticDriverSource()

    assertFalse("RS request must not branch from cheap fast snapshot to fallback proof", rsDriver.contains("statusFromHierarchy"))
    assertFalse("RS request must not re-drive the app before reporting a failure", rsDriver.contains("recoverRigasSatiksmeMonthlyTicketFlowBeforeFailure("))
    assertTrue(
      "The semantic proof must classify the RS app snapshot against the requested digits before success",
      semanticDriver.contains("hasControl && hasQr && has(cleanDigits) && hasMonthlyMarker") &&
        semanticDriver.contains("TICKET_CONTROL_MATCHING")
    )
    assertTrue(
      "The classifier must require both RS monthly-ticket control markers and the requested digits for success",
      semanticDriver.contains("hasControl && hasQr && has(cleanDigits) && hasMonthlyMarker")
    )
    val operation = rigasSatiksmeMonthlyTicketOperationSource()
    assertTrue(
      "Image capture/result must remain gated behind a successful RS monthly-ticket flow",
      operation.indexOf("if (!flow.ok)") in 0 until operation.indexOf("val imageBytes = captureImage(") &&
        handler.indexOf("RigasSatiksmeMonthlyTicketOperation(") < handler.indexOf("sendRigassatiksmeQrResult(")
    )
  }

  @Test
  fun rsMonthlyTicketFastPathDismissesLateTripRegisteredModalBeforeProof() {
    val semanticDriver = rigasSatiksmeSemanticDriverSource()

    assertTrue(
      "RS can show the 'Trip is registered' modal late; semantic path should click the OK control by label",
      semanticDriver.contains("RigasSatiksmeSemanticState.TRIP_REGISTERED") && semanticDriver.contains("gateway.click(okSelectors")
    )
    assertTrue(
      "The late-modal OK action must happen before opening the control ticket",
      semanticDriver.indexOf("gateway.click(okSelectors") < semanticDriver.indexOf("gateway.click(ticketForControlSelectors")
    )
    assertFalse(
      "Late-modal handling should not add another slow uiautomator dump before semantic proof",
      semanticDriver.contains("uiautomator dump")
    )
  }

  @Test
  fun rsMonthlyTicketFlowDoesNotUseUnboundedInAppRetryBeforeReportingNavigationFailure() {
    val source = ticketStreamServiceSource()
    val rsDriver = source.substringBetween("private suspend fun runRigasSatiksmeMonthlyTicketFlow", "private suspend fun captureRigasSatiksmeMonthlyTicketImageBytes")
    val semanticDriver = rigasSatiksmeSemanticDriverSource()

    assertFalse(rsDriver.contains("recoverRigasSatiksmeMonthlyTicketFlowBeforeFailure("))
    assertFalse(source.contains("rs_monthly_ticket_recovery_started"))
    assertFalse(source.contains("rs_monthly_ticket_recovery_finished"))
    assertFalse(source.contains("rs_monthly_ticket_recovery_fallback"))
    assertTrue("The only in-flow recovery should be bounded semantic state handling before code entry", semanticDriver.contains("maxStateAttempts") && semanticDriver.contains("MANUAL_CODE_ENTRY"))
  }

  @Test
  fun rsMonthlyTicketStaleQrBacksOutBeforeFreshEntry() {
    val source = ticketStreamServiceSource()
    val rsDriver = source.substringBetween("private suspend fun runRigasSatiksmeMonthlyTicketFlow", "private suspend fun captureRigasSatiksmeMonthlyTicketImageBytes")
    val visualDriver = rigasSatiksmeVisualDriverSource()

    assertTrue(visualDriver.contains("CONTROL_STALE") && visualDriver.contains("gateway.pressBack("))
    assertFalse(source.contains("TICKET_RS_MONTHLY_IN_APP_RECOVERY_TIMEOUT_MILLIS"))
    assertFalse(source.contains("recoverRigasSatiksmeMonthlyTicketFlowBeforeFailure"))
    assertTrue(rsDriver.contains("input keyevent KEYCODE_BACK"))
  }

  @Test
  fun rsMonthlyTicketRecoveryPathIsAbsent() {
    val source = ticketStreamServiceSource()

    assertFalse(source.contains("private suspend fun recoverRigasSatiksmeMonthlyTicketFlowBeforeFailure"))
    assertFalse(source.contains("ticket_safe_uiautomator_dump /data/local/tmp/pixel-rs-recovery-start-window.xml"))
    assertFalse(source.contains("pixel-rs-ticket-recovery-window.xml"))
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
    assertTrue("successful RS return must refresh ViVi ticket-detail memory so the next public stream load can skip slow foreground recovery", cleanup.contains("rememberRigasSatiksmeReturnedToViviTicketDetail("))
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
    val rootDump = source.substringBetween("private suspend fun dumpVisibleHierarchyWithRoot", "private fun classifyRigasSatiksmeMonthlyTicketHierarchy")

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
    val operation = rigasSatiksmeMonthlyTicketOperationSource()
    val flowFailure = operation.substringBetween("if (!flow.ok) {", "    }\n\n    val imageBytes = captureImage")
    val imageFailure = operation.substringBetween("if (imageBytes == null || imageBytes.isEmpty()) {", "    }\n\n    markPhase")
    val failureReturn = rsFlow.substringBetween("if (!outcome.ok) {", "return@withLock\n        }")

    assertTrue(flowFailure.contains("cleanupRequired = true"))
    assertTrue(imageFailure.contains("cleanupRequired = true"))
    assertTrue(
      "RS flow failures should be sent to broker before cleanup is scheduled so users do not wait for cleanup or hit broker timeout",
      failureReturn.indexOf("sendControlCodeResult(") < failureReturn.indexOf("pendingImmediateCleanup")
    )
    assertTrue(
      "RS immediate cleanup should run only after leaving the request mutex",
      rsFlow.indexOf("} finally {\n      protectedControlClients.remove(replyClient)") < rsFlow.indexOf("returnRigasSatiksmeMonthlyTicketFlowToViviTicket(")
    )
  }

  @Test
  fun rsMonthlyTicketQueuedFailureDefersViviCleanupOutsideRequestMutex() {
    val source = ticketStreamServiceSource()
    val handler = source.substringBetween("private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr", "private fun cancelPendingRigasSatiksmeReturnCleanup")
    val commandParser = source.substringBetween("\"generate_control_code\" -> {", "\"prepare_control_code\" -> {")

    assertTrue("Broker RS queue hints must be parsed from generate_control_code commands", commandParser.contains("rsQueueHint"))
    assertTrue("RS handler must receive queue context for batch cleanup decisions", handler.contains("queueHint: RigasSatiksmeQueueHint"))
    assertTrue("Queued RS work should schedule idle cleanup instead of blocking the next request on immediate ViVi return", handler.contains("shouldDeferRigasSatiksmeReturnCleanup(queueHint"))
    assertTrue("Immediate cleanup should run after leaving controlCodeRequestMutex, not inside the hot result path", handler.contains("pendingImmediateCleanup"))
    val deferDecision = source.substringBetween("private fun shouldDeferRigasSatiksmeReturnCleanup", "private fun rigasSatiksmeFailureRequiresImmediateCleanup")
    assertTrue("Ticket priority should still force an immediate return to ViVi", deferDecision.contains("queueHint.ticketPriorityActive"))
    assertFalse("Non-critical RS failures should defer cleanup even when later Telegram jobs arrived after the command hint was sent", deferDecision.contains("pendingAfterThis <= 0"))
    val mutexBody = handler.substringBetween("controlCodeRequestMutex.withLock {", "} finally")
    assertFalse("Slow ViVi return cleanup must not execute while holding the RS request mutex", mutexBody.contains("returnRigasSatiksmeMonthlyTicketFlowToViviTicket("))
  }

  @Test
  fun rsMonthlyTicketBatchCommandRunsIsolatedBatchLane() {
    val source = ticketStreamServiceSource()
    val config = ticketScreenConfigSource()
    val dispatcher = source.substringBetween("\"generate_rigassatiksme_qr_batch\" -> {", "\"generate_control_code\" -> {")
    val batchHandler = source.substringBetween("private suspend fun handleGenerateRigasSatiksmeQrBatch", "private fun sendControlCodeResult")
    val health = source.substringBetween("private fun health(): TicketStreamHealth", "private fun effectiveViviHealthForPublicStream")

    assertTrue("Broker batch command must be accepted by the phone control websocket", dispatcher.contains("generate_rigassatiksme_qr_batch"))
    assertTrue("Batch command must preserve the broker batch id", dispatcher.contains("val batchId = element[\"batchId\"]?.jsonPrimitive?.contentOrNull.orEmpty()"))
    assertTrue("Batch command must parse all RS jobs from one phone command", dispatcher.contains("element[\"jobs\"]?.jsonArray"))
    assertTrue("Batch command must carry ticket priority into cleanup decisions", dispatcher.contains("ticketPriorityActive"))
    assertTrue("Batch command must declare the RS monthly-ticket owner/app/flow", dispatcher.contains("TicketScreenConfig.TICKET_QR_OWNER_RIGAS_SATIKSME"))
    assertTrue("Batch handler must run each job through the isolated RS operation", batchHandler.contains("for (job in cleanJobs)") && batchHandler.contains("RigasSatiksmeMonthlyTicketOperation("))
    assertTrue("Batch handler must emit per-request RS result messages", batchHandler.contains("sendRigassatiksmeQrResult("))
    assertFalse("Batch failures must not be reported through the shared ViVi control-code result bucket", batchHandler.contains("sendControlCodeResult("))
    assertTrue("Batch starts must cancel delayed ViVi cleanup so queued RS jobs stay in the RS app", batchHandler.contains("cancelPendingRigasSatiksmeReturnCleanup(\"new_rs_monthly_ticket_batch\")"))
    assertTrue("Batch completion should defer ViVi return when no ticket priority is active", batchHandler.contains("scheduleRigasSatiksmeReturnCleanupAfterIdle("))
    assertTrue("Ticket priority should still force the phone back to ViVi after a batch", batchHandler.contains("ticketPriorityActive"))
    assertTrue("Health must expose RS batch/request state separately from ViVi control-code state", config.contains("data class TicketRigasSatiksmeBatchHealth") && health.contains("rigasSatiksmeBatch = TicketRigasSatiksmeBatchHealth("))
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
  fun publicStreamStartupTraceIsExposedInHealth() {
    val service = ticketStreamServiceSource()
    val config = ticketScreenConfigSource()

    assertTrue(config.contains("val startupTrace: TicketStartupTraceHealth = TicketStartupTraceHealth()"))
    assertTrue(config.contains("data class TicketStartupTraceHealth("))
    assertTrue(service.contains("private fun beginStartupTrace(reason: String)"))
    assertTrue(service.contains("private fun startupTraceSnapshot(nowMillis: Long): TicketStartupTraceHealth"))
    assertTrue(service.contains("startupTrace = startupTraceSnapshot(nowMillis)"))
    assertTrue(service.contains("recordStartupTracePhase(\"wake_\$phase\""))
    assertTrue(service.contains("recordStartupTracePhase(\"root_capture_start_requested\""))
    assertTrue(service.contains("recordStartupTracePhase(\"first_visible_frame_sent\""))
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
      "private suspend fun runFastNonTouchInput(",
      "private suspend fun runFastNonTouchScript(command: String, reason: String, timeout: Duration): RootResult"
    )
    val wrapper = source.substringBetween(
      "private suspend fun runFastNonTouchScript(command: String, reason: String, timeout: Duration): RootResult",
      "private fun shellQuote"
    )

    assertTrue(rsFastReturn.contains("runFastNonTouchScript("))
    assertTrue(rsFastReturn.contains("\"rs_monthly_ticket_fast_return\""))
    assertFalse(rsFastReturn.contains("inputRootExecutor.runScript("))

    assertTrue(rsFlow.contains("startActivity(launchIntent)"))
    assertTrue(rsFlow.contains("\"rs_monthly_ticket_visual_launch_finished\""))
    assertTrue(rsFlow.contains("runRigasSatiksmeVisualInput("))
    assertTrue(rsFlow.contains("inputRootExecutor.runScript(command, RIGAS_SATIKSME_VISUAL_INPUT_TIMEOUT_MILLIS.milliseconds)"))

    assertTrue(rootDump.contains("runFastNonTouchWakeScript("))
    assertTrue(rootDump.contains("\"root_visible_hierarchy_dump:${'$'}reason\""))
    assertFalse(rootDump.contains("wakeRootExecutor.runScript("))

    assertFalse(source.contains("rs_monthly_ticket_recovery"))
    assertFalse(source.contains("recoverRigasSatiksmeMonthlyTicketFlowBeforeFailure"))


    assertTrue(launchViviForWake.contains("runFastNonTouchWakeScript("))
    assertTrue(launchViviForWake.contains("\"wake_launch_vivi:${'$'}reason\""))
    assertFalse(launchViviForWake.contains("wakeRootExecutor.runScript("))

    assertTrue(wakeTicketScreenForSessionStart.contains("runFastNonTouchWakeScript("))
    assertTrue(wakeTicketScreenForSessionStart.contains("\"wake_session_start\""))
    assertTrue(wakeTicketScreenForSessionStart.contains("val screenAlreadyInteractive = ticketScreenInteractive()"))
    assertTrue(wakeTicketScreenForSessionStart.contains("val shouldSendWakeCommand = !screenAlreadyInteractive"))
    assertTrue(wakeTicketScreenForSessionStart.contains("screen_wake_skipped_interactive"))
    assertFalse(wakeTicketScreenForSessionStart.contains("wakeRootExecutor.runScript("))

    assertTrue(requestTicketScreenWake.contains("runFastNonTouchScript("))
    assertTrue(requestTicketScreenWake.contains("\"wake_request\""))
    assertFalse(requestTicketScreenWake.contains("rootExecutor.runScript("))

    assertTrue(source.contains("private const val NON_TOUCH_PANEL_SLEEP_CLAMP_INTERVAL_MILLIS = 5L"))
    assertTrue(source.contains("private const val NON_TOUCH_PANEL_SLEEP_CLAMP_POST_DISPLAY_INTERVAL_MILLIS = 1_250L"))
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
      "sysfs panel clamp must run before slower Android settings resets",
      wrapper.indexOf("ticket_panel_sysfs_dark") < wrapper.indexOf("ticket_panel_display_dark_once")
    )
    assertTrue(
      "the hot non-touch clamp must skip the slower Android display loop when a panel backlight file is available",
      wrapper.contains("if [ -z \"\${'$'}ticket_panel_dir\" ]; then") &&
        wrapper.contains("ticket_panel_display_clamp >/dev/null 2>&1 &")
    )
    assertFalse(
      "the active input clamp must not call DisplayManager repeatedly; direct sysfs writes own the sensitive tap window",
      wrapper.contains("cmd display set-brightness 0 --unit percentage")
    )

    assertTrue(fastInputWrapper.contains("inputRootExecutor.runScript("))
    assertTrue(fastInputWrapper.contains("postMillis: Long = NON_TOUCH_PANEL_SLEEP_CLAMP_POST_MILLIS"))
    assertTrue(fastInputWrapper.contains("wrapNonTouchPanelSleepClamp("))
    assertTrue(fastInputWrapper.contains("postMillis = postMillis"))
    assertTrue(fastInputWrapper.contains("commandTimeout = NON_TOUCH_ROOT_COMMAND_TIMEOUT_MILLIS.milliseconds"))
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
      "post-command clamping must keep a direct sysfs loop active while the slower Android display APIs run",
      clamp.contains("ticket_panel_post_sysfs_clamp >/dev/null 2>&1 &") &&
        clamp.contains("wait \"\${'$'}ticket_panel_post_sysfs_pid\"")
    )
    assertTrue(
      "post-command DisplayManager resets must be bounded separately from the 5ms sysfs loop to avoid a long settings storm",
      clamp.contains("val postDisplayWrites =") &&
        clamp.contains("NON_TOUCH_PANEL_SLEEP_CLAMP_POST_DISPLAY_INTERVAL_MILLIS") &&
        clamp.contains("ticket_panel_post_display_write_index")
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
  fun foregroundGuardTreatsEmptyFastHierarchyAsInconclusiveNotLiveProof() {
    val source = ticketStreamServiceSource()
    val guard = source.substringBetween("private suspend fun enforceViviTicketPageIfNeeded", "private suspend fun attemptActiveGuardRecoveryAction")
    val fastObserver = source.substringBetween("private suspend fun observeFastViviState", "private suspend fun observeRootViviState")
    val rootObserver = source.substringBetween("private suspend fun observeRootViviState", "private suspend fun controlCodeRequestHierarchy")

    assertTrue(guard.contains("observeFastViviState(\"active_guard:${'$'}reason\")"))
    assertTrue(
      "active guard should use the cheap Accessibility snapshot before any root uiautomator dump",
      guard.indexOf("observeFastViviState(\"active_guard:${'$'}reason\")") < guard.indexOf("observeRootViviState(")
    )
    assertTrue(
      "the root-owned public health proof must use the long guarded timeout; the default hierarchy timeout leaves uiautomator only about 1.5s and can report split-brain while video is live",
      guard.contains("timeoutMillis = TICKET_ROOT_HIERARCHY_DUMP_TIMEOUT_MILLIS")
    )
    val afterFastBeforeRoot = guard.substring(
      guard.indexOf("observeFastViviState(\"active_guard:${'$'}reason\")"),
      guard.indexOf("observeRootViviState(")
    )
    assertTrue(
      "the current cheap UI sample may skip root only after it proves ticket detail, not when it is empty",
      afterFastBeforeRoot.contains("recentForegroundGuardTicketDetailStillFresh(now)")
    )
    assertFalse(
      "an empty fast hierarchy is not live proof and must not skip root via stale wake/detail memory",
      afterFastBeforeRoot.contains("foregroundGuardCanTrustLiveTicketDetailWithoutRoot(now)") ||
        afterFastBeforeRoot.contains("active_guard_fast_hierarchy_empty_live")
    )
    assertTrue(fastObserver.contains("fastVisibleHierarchy(TicketScreenConfig.VIVI_PACKAGE"))
    assertTrue(fastObserver.contains("TicketViviPageEnforcer.classifyForRecovery(hierarchy)"))
    val fastBlank = fastObserver.substringBetween("if (hierarchy.isBlank()) {", "val state =")
    assertTrue(
      "empty fast snapshots must refresh ViVi memory to UNKNOWN so stale current TICKET_DETAIL cannot be reused on the next guard tick",
      fastBlank.contains("viviStateMemory.record(") &&
        fastBlank.contains("TicketViviRecoveryState.UNKNOWN_VIVI") &&
        fastBlank.contains("source = \"fast_empty\"")
    )
    val rootBlank = rootObserver.substringBetween("if (hierarchy.isNullOrBlank()) {", "val state =")
    assertTrue(
      "empty root snapshots must also refresh ViVi memory to UNKNOWN instead of leaving stale TICKET_DETAIL current",
      rootBlank.contains("viviStateMemory.record(") &&
        rootBlank.contains("TicketViviRecoveryState.UNKNOWN_VIVI") &&
        rootBlank.contains("source = \"root_empty\"")
    )
  }

  @Test
  fun autopilotDoesNotTrustOlderTicketDetailAfterFreshUnknownObservation() {
    val source = ticketAutopilotSource()
    val recent = source.substringBetween("private fun recentSafeTicketDetail", "private suspend fun forceStopVivi")

    assertTrue(recent.contains("current.state == TicketViviRecoveryState.TICKET_DETAIL"))
    assertTrue(recent.contains("currentAgeMillis in 0..FAST_TICKET_DETAIL_MEMORY_MAX_AGE_MILLIS"))
    assertTrue(
      "a fresh UNKNOWN/BLANK/non-detail observation must block fallback to lastTicketDetailSnapshot; otherwise active soft recovery immediately succeeds from stale memory",
      recent.contains("current.state != TicketViviRecoveryState.TICKET_DETAIL") &&
        recent.contains("return null")
    )
    assertFalse(
      "UNKNOWN is exactly the state recorded when fast/root hierarchy is empty, so it must not be exempt from the stale-detail rejection",
      recent.contains("current.state != TicketViviRecoveryState.UNKNOWN_VIVI")
    )
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
        guard.indexOf("foregroundGuardInconclusiveBackoffActive(now)") < guard.indexOf("observeRootViviState(")
    )
    assertTrue(
      "the inconclusive branch must arm the backoff before returning so repeated hierarchy_unavailable/timeout does not create more UiAutomation errors",
      guard.contains("lastForegroundGuardInconclusiveAtMillis = now") &&
        guard.indexOf("lastForegroundGuardInconclusiveAtMillis = now") < guard.indexOf("recordTicketEvent(\"active_guard_inconclusive\"")
    )
    assertTrue(backoff.contains("foregroundGuardInconclusiveBackoffActive(nowMillis: Long): Boolean"))
    assertTrue(backoff.contains("nowMillis - lastForegroundGuardInconclusiveAtMillis"))
    assertTrue(backoff.contains("FOREGROUND_GUARD_INCONCLUSIVE_BACKOFF_MILLIS"))
    assertTrue(
      "after an empty fast/root hierarchy refreshes memory to UNKNOWN, the backoff still needs to engage or the guard will hammer uiautomator every tick",
      backoff.contains("TicketViviRecoveryState.UNKNOWN_VIVI") &&
        backoff.contains("TicketViviRecoveryState.BLANK")
    )
    assertFalse(
      "backoff must not require current TICKET_DETAIL only, because empty hierarchy now correctly records UNKNOWN instead of stale detail",
      backoff.contains("if (current.state != TicketViviRecoveryState.TICKET_DETAIL) {\n      return false\n    }")
    )
  }

  @Test
  fun rootHierarchyDumpsUseGuardedUiautomatorWrapper() {
    val service = ticketStreamServiceSource()
    val observer = ticketScreenObserverSource()
    val helper = ticketUiautomatorDumpSource()
    val onCreate = service.substringBetween("override fun onCreate()", "override fun onStartCommand")
    val serviceDump = service.substringBetween("private suspend fun dumpViviHierarchy(", "private fun recordRootReadiness")
    val wakeDump = service.substringBetween("private suspend fun dumpViviHierarchyForWake", "private suspend fun observeRootViviStateForWake")
    val visibleDump = service.substringBetween("private suspend fun dumpVisibleHierarchyWithRoot", "private fun classifyRigasSatiksmeMonthlyTicketHierarchy")

    assertTrue("All service semantic hierarchy reads must use the wake-root executor lane so active guards, wake prep, and RS proof serialize before Android's singleton UiAutomationService instead of racing into retries", serviceDump.contains("rootExecutor = wakeRootExecutor") && onCreate.contains("rootExecutor = wakeRootExecutor"))
    assertFalse("RS request path must not keep in-app recovery uiautomator probes", service.contains("rs_monthly_ticket_recovery"))
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
    assertFalse("Do not reintroduce guarded or unguarded RS recovery hierarchy dumps", service.contains("pixel-rs-recovery-start-window.xml"))
  }

  @Test
  fun viviLoginUsesRuntimeSecretAndRedactedHealthOnly() {
    val service = ticketStreamServiceSource()
    val config = ticketScreenConfigSource()
    val automationHealth = config.substringBetween("data class TicketAutomationHealth", "@Serializable")
    val healthBuilder = service.substringBetween("automation = TicketAutomationHealth(", "      ),\n      page =")

    assertTrue(service.contains("VIVI_LOGIN_SECRET_FILE"))
    assertTrue(service.contains("/data/local/pixel-stack/conf/apps/ticket-screen-vivi-login.env"))
    assertTrue(service.contains("loadViviLoginCredentials"))
    assertTrue(service.contains("VIVI_LOGIN_EMAIL"))
    assertTrue(service.contains("VIVI_LOGIN_PASSWORD"))
    assertTrue(service.contains("loginViviIfNeeded"))
    assertTrue(service.contains("resolveViviLoginSubmitAfterSecret"))
    assertTrue(service.contains("androidInputTextLiteral"))
    assertTrue(service.contains("if (reason == \"vivi_login_secret\")"))
    assertTrue(service.contains("input text ${'$'}{shellQuote(androidInputTextLiteral(value))}"))
    assertTrue(automationHealth.contains("viviLoginCredentialsConfigured"))
    assertTrue(automationHealth.contains("viviLoginLastStatus"))
    assertTrue(automationHealth.contains("viviLoginLastReason"))
    assertTrue(healthBuilder.contains("viviLoginCredentialsConfigured"))
    assertTrue(healthBuilder.contains("viviLoginLastStatus"))
    assertTrue(healthBuilder.contains("viviLoginLastReason"))
    assertFalse(automationHealth.lowercase().contains("password"))
    assertFalse(healthBuilder.lowercase().contains("password"))
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

  private fun rigasSatiksmeMonthlyTicketOperationSource(): String = readFirstExisting(
    Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/RigasSatiksmeMonthlyTicketOperation.kt"),
    Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/RigasSatiksmeMonthlyTicketOperation.kt")
  )

  private fun rigasSatiksmeSemanticDriverSource(): String = readFirstExisting(
    Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/RigasSatiksmeSemanticDriver.kt"),
    Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/RigasSatiksmeSemanticDriver.kt")
  )

  private fun rigasSatiksmeShellSemanticGatewaySource(): String = readFirstExisting(
    Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/RigasSatiksmeShellSemanticGateway.kt"),
    Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/RigasSatiksmeShellSemanticGateway.kt")
  )

  private fun rigasSatiksmeDirectTapDriverSource(): String = readFirstExisting(
    Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/RigasSatiksmeDirectTapDriver.kt"),
    Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/RigasSatiksmeDirectTapDriver.kt")
  )

  private fun rigasSatiksmeVisualDriverSource(): String = readFirstExisting(
    Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/RigasSatiksmeVisualDriver.kt"),
    Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/RigasSatiksmeVisualDriver.kt")
  )

  private fun phoneAutomationAccessibilityServiceSource(): String = readFirstExisting(
    Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhoneAutomationAccessibilityService.kt"),
    Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhoneAutomationAccessibilityService.kt")
  )

  private fun ticketRequestOutcomeSource(): String = readFirstExisting(
    Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketRequestOutcome.kt"),
    Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketRequestOutcome.kt")
  )

  private fun ticketAutopilotSource(): String = readFirstExisting(
    Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketAutopilot.kt"),
    Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketAutopilot.kt")
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
