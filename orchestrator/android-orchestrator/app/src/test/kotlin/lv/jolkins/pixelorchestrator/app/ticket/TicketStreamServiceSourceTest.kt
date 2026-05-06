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
    assertTrue(verify.contains("verifyViviTicketDetailHierarchy(reason)"))
    assertTrue(verify.contains("captureRootSurfaceProbeRaw(probeWidth, probeHeight)"))
    assertTrue(verify.contains("rootSurfaceRawFrameLooksVisible(frame)"))
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
  fun localViewerUsesWebCodecsH264Only() {
    val source = ticketStreamServiceSource()

    assertTrue(source.contains("new VideoDecoder({"))
    assertTrue(source.contains("new EncodedVideoChunk({type: frame.kind"))
    assertTrue(source.contains("decoderConfig.avc = {format: 'annexb'}"))
    assertTrue(source.contains("ctx.drawImage(frame, 0, 0, canvas.width, canvas.height)"))
    listOf(
      "RTCPeerConnection",
      "webrtc_ice_config",
      "image/png",
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
    assertTrue(source.contains("completeVerifiedTicketDetailControlExitCleanup"))
    assertTrue(source.contains("controlExitCleanupJob"))
    assertTrue(source.contains("scheduleControlExitCleanup"))
    assertTrue(source.contains("runControlExitCleanup"))
    assertTrue(source.contains("control_exit_foreground_check_bypassed"))
    assertTrue(source.contains("control_exit_result_geometry_fallback"))
    assertTrue(source.contains("geometry_close_control_code_result"))
    assertTrue(source.contains("control_exit_cleanup_duplicate_ignored"))
    assertTrue(source.contains("CONTROL_EXIT_SECOND_VERIFY_DELAY_MILLIS"))
    assertTrue(source.contains("CONTROL_EXIT_ROOT_DUMP_TIMEOUT_MILLIS"))
    assertTrue(source.contains("timeoutMillis = CONTROL_EXIT_ROOT_DUMP_TIMEOUT_MILLIS"))
    assertTrue(source.contains("already_closed_after_control_code_result"))
    assertTrue(source.contains("CONTROL_EXIT_RECENT_SURFACE_MEMORY_MILLIS"))
    assertTrue(source.contains("touch-action: pan-y"))
    assertTrue(source.contains("scroll-snap-type: y proximity"))
    assertTrue(source.contains("gesturechange"))
    assertTrue(source.contains("dblclick"))
    assertTrue(source.contains("streamVerticalPanThresholdPx"))
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
