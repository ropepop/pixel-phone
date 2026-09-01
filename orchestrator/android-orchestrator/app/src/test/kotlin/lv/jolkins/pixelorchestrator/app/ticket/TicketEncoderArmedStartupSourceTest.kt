package lv.jolkins.pixelorchestrator.app.ticket

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketEncoderArmedStartupSourceTest {
  private val config by lazy { source("TicketScreenConfig.kt") }
  private val engine by lazy { source("TicketRootHardwareH264CaptureEngine.kt") }
  private val helper by lazy { source("TicketRootHardwareH264CaptureMain.java") }
  private val service by lazy { source("TicketStreamService.kt") }

  @Test
  fun armedHelperMayBuildCaptureParametersButCannotAcquirePixelsOrRunPrimerBeforeActivation() {
    val activationWait = helper.indexOf("activationLatch.await(")
    val secureCaptureConstruction = helper.indexOf("new SecureScreenCapture(")
    val firstCapture = helper.indexOf("capture.capture()")
    val primerConstruction = helper.indexOf("new TicketEncoderStartupPrimer(")
    val firstPrimerRun = helper.indexOf("runStartupPrimer(")

    assertTrue(helper.contains("hasFlag(args, \"--await-activation\")"))
    assertTrue(activationWait >= 0)
    assertTrue(secureCaptureConstruction in 0 until activationWait)
    assertTrue(firstCapture > activationWait)
    assertTrue(primerConstruction > activationWait)
    assertTrue(firstPrimerRun > activationWait)
  }

  @Test
  fun activationCommandCanOpenTheLatchOnlyOnce() {
    val commandReader = body(
      helper,
      "private static Thread startCommandReader(",
      "private static final class ControlCodeVisualProbeRequest"
    )

    assertTrue(commandReader.contains("cmd.equals(\"activate:\" + activationToken)"))
    assertTrue(commandReader.contains("activationToken > 0L"))
    assertTrue(commandReader.contains("captureActivated.compareAndSet(false, true)"))
    assertEquals(1, Regex("activationLatch\\.countDown\\(\\)").findAll(commandReader).count())
  }

  @Test
  fun engineOwnsOneArmedGenerationAndProvidesBoundedCleanup() {
    val finishStop = body(
      engine,
      "private suspend fun finishGenerationStop(",
      "private fun expectedStopWasArmed"
    )
    val join = finishStop.indexOf("plan.running?.join()")
    val reap = finishStop.indexOf("cleanupMatchingProcesses()")
    val openFence = finishStop.indexOf("captureGenerationFence.completeCancellation(plan.generation)")
    val launchReplacement = finishStop.indexOf("launchCaptureLoopLocked(replacementGeneration)")

    assertTrue(engine.contains("fun arm("))
    assertTrue(engine.contains("fun cancelArmedAndJoin("))
    assertTrue(engine.contains("suspend fun stopAndJoin("))
    assertFalse(engine.contains("fun stop(reason:"))
    assertTrue(engine.contains("--await-activation"))
    assertTrue(engine.contains("armedLaunchRequested"))
    assertTrue(engine.contains("armedActivationRequested = true"))
    assertTrue(engine.contains("flushArmedActivation()"))
    assertTrue(engine.contains("armedActivationDelivered"))
    assertTrue(engine.contains("armedFallbackUsed"))
    assertTrue(engine.contains("runGeneration"))
    assertTrue(engine.contains("publishEncoderProcess(runGeneration, localEncoder)"))
    assertTrue(engine.contains("finishGenerationStop(plan, reason, clearFrameStateAfter)"))
    assertTrue(engine.contains("plan.running?.join()"))
    assertTrue(engine.contains("captureGenerationFence.completeCancellation(plan.generation)"))
    assertTrue(join >= 0)
    assertTrue(reap > join)
    assertTrue(openFence > reap)
    assertTrue(launchReplacement > openFence)
    assertFalse(engine.contains("fun activateAllArmed("))
  }

  @Test
  fun serviceArmsOnlyAfterPortraitProofAndActivatesOnlyInsideAdmission() {
    val preflight = body(
      service,
      "private suspend fun runSessionStartSafetyPreflight()",
      "private fun startupPreflightOutcome"
    )
    val portraitCallback = preflight.substringAfter("onPortraitComplete = {")
      .substringBefore("onCancelled = {")
    assertTrue(portraitCallback.contains("portrait?.verified == true"))
    assertTrue(portraitCallback.contains("rootHardwareH264CaptureEngine.arm("))
    assertFalse(portraitCallback.contains("streamActive = true"))
    assertFalse(portraitCallback.contains("broadcastStatus()"))

    val start = body(
      service,
      "private suspend fun startTicketSessionLocked(",
      "private suspend fun runSessionStartSafetyPreflight()"
    )
    val admission = start.substringAfter("streamStartAdmission.admit(")
    val admitted = admission.substringAfter("admitted = {")
      .substringBefore("TicketSessionResponse(ok = true")
    assertTrue(admitted.contains("rootHardwareH264CaptureEngine.activateArmed("))
    assertTrue(admitted.indexOf("rootHardwareH264CaptureEngine.activateArmed(") <
      admitted.indexOf("broadcastStatus()"))
    assertTrue(admission.contains("if (!armConsumedByAdmission) cancelPreflightArm("))
    assertTrue(admission.contains("session_start_root_hardware_h264_capture_fallback"))
  }

  @Test
  fun pixelProducingReliabilityProbeRunsOnlyAfterExactAdmission() {
    val start = body(
      service,
      "private suspend fun startTicketSessionLocked(",
      "private suspend fun runSessionStartSafetyPreflight()"
    )
    val admission = start.indexOf("streamStartAdmission.admit(")
    val admittedMarker = start.indexOf("admissionGranted = true", admission)
    val reliabilityProbe = start.indexOf("hardwareCapture = refreshHardwareReliabilityIfProbePasses(", admission)

    assertTrue(admission >= 0)
    assertTrue(admittedMarker > admission)
    assertTrue(reliabilityProbe > admittedMarker)
    assertTrue(
      start.substring(admission, reliabilityProbe)
        .contains("if (admissionGranted && reliabilityProbeRequired)")
    )
    assertFalse(start.substring(0, admission).contains("refreshHardwareReliabilityIfProbePasses("))
  }

  @Test
  fun preflightCancellationDestroysTheMatchingArmedHandleBeforeReturning() {
    val preflight = body(
      service,
      "private suspend fun runSessionStartSafetyPreflight()",
      "private fun startupPreflightOutcome"
    )
    val cancellation = preflight.substringAfter("onCancelled = {")
      .substringBefore("}\n    )")

    assertTrue(cancellation.contains("armedHandle.getAndSet(null)"))
    assertTrue(cancellation.contains("rootHardwareH264CaptureEngine.cancelArmedAndJoin(handle"))
    assertTrue(cancellation.indexOf("cancelArmedAndJoin") <
      cancellation.indexOf("releaseAcquiredLease"))
  }

  @Test
  fun armingDoesNotChangeTheEstablishedPictureContract() {
    assertTrue(config.contains("ROOT_HARDWARE_H264_FPS = 1"))
    assertTrue(config.contains("ROOT_HARDWARE_H264_BITRATE = 8_000_000"))
    assertTrue(config.contains("ROOT_HARDWARE_H264_TARGET_WIDTH = 994"))
    assertTrue(config.contains("ROOT_HARDWARE_H264_COLOR_STANDARD = \"bt709_limited_sdr\""))
    assertTrue(config.contains("TICKET_MEDIA_LEFT_CROP_SOURCE_PIXELS = 4"))
    assertTrue(config.contains("TICKET_MEDIA_TOP_CROP_SOURCE_PIXELS = 200"))
    assertTrue(config.contains("TICKET_MEDIA_RIGHT_CROP_SOURCE_PIXELS = 3"))
    assertTrue(config.contains("TICKET_MEDIA_BOTTOM_CROP_SOURCE_PIXELS = 3"))

    assertTrue(helper.contains("MediaFormat.KEY_FRAME_RATE, encoderFps"))
    assertTrue(helper.contains("MediaFormat.KEY_I_FRAME_INTERVAL, 0"))
    assertTrue(helper.contains("MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR"))
    assertTrue(helper.contains("MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709"))
    assertTrue(helper.contains("MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED"))
    assertTrue(helper.contains("MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO"))
    assertTrue(helper.contains("MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline"))
    assertTrue(helper.contains("MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4"))
    assertTrue(helper.contains("HIGH_BRIGHTNESS_SDR_RED_GAIN = 1.08f"))
    assertTrue(helper.contains("HIGH_BRIGHTNESS_SDR_GREEN_GAIN = 1.05f"))
    assertTrue(helper.contains("HIGH_BRIGHTNESS_SDR_BLUE_GAIN = 1.03f"))
  }

  private fun source(relative: String): String {
    val path = listOf(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/$relative"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/$relative")
    ).firstOrNull(Files::exists) ?: error("Missing source file: $relative")
    return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }

  private fun body(source: String, start: String, end: String): String {
    val from = source.indexOf(start)
    require(from >= 0) { "Missing start marker: $start" }
    val to = source.indexOf(end, from + start.length)
    require(to > from) { "Missing end marker: $end" }
    return source.substring(from, to)
  }
}
