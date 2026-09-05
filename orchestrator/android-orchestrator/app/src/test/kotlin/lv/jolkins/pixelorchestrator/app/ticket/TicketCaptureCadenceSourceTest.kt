package lv.jolkins.pixelorchestrator.app.ticket

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketCaptureCadenceSourceTest {
  private val config by lazy { source("TicketScreenConfig.kt") }
  private val engine by lazy { source("TicketRootHardwareH264CaptureEngine.kt") }
  private val helper by lazy { source("TicketRootHardwareH264CaptureMain.java") }
  private val service by lazy { source("TicketStreamService.kt") }
  private val scheduler by lazy { source("TicketCaptureCadenceScheduler.java") }

  @Test
  fun configDefinesOneFpsAllIntraWithoutAdaptiveRuntimeTiers() {
    assertTrue(config.contains("ROOT_HARDWARE_H264_FPS = 1"))
    assertTrue(config.contains("ROOT_HARDWARE_H264_FRAME_DEPENDENCY_MODE = \"all_intra\""))
    assertTrue(config.contains("ROOT_HARDWARE_H264_QUALITY_PROFILE = \"hardware_h264_crisp_all_intra_1fps\""))
    assertTrue(config.contains("ROOT_HARDWARE_H264_BITRATE = 8_000_000"))
    assertTrue(config.contains("ROOT_HARDWARE_H264_TARGET_WIDTH = 994"))
    assertTrue(config.contains("ROOT_HARDWARE_H264_TRANSPORT = \"hardware-h264-annexb\""))
    assertTrue(config.contains("ROOT_HARDWARE_H264_COLOR_STANDARD = \"bt709_limited_sdr\""))
    assertTrue(config.contains("frameEnvelope: String = \"tsf3\""))
    assertFalse(config.contains("ROOT_HARDWARE_H264_STEADY_FPS"))
    assertFalse(config.contains("ROOT_HARDWARE_H264_MAX_FPS"))
    assertFalse(config.contains("ROOT_HARDWARE_H264_MODERATE_FPS"))
    assertFalse(config.contains("ROOT_HARDWARE_H264_ACTIVE_FPS"))
    assertFalse(config.contains("ROOT_HARDWARE_H264_STARTUP_FRAMES"))
  }

  @Test
  fun helperConfiguresOneFpsAllIntraAndHasNoMotionBurstOrAdaptivePath() {
    assertTrue(helper.contains("fixed all-intra capture requires 1 FPS"))
    assertTrue(helper.contains("MediaFormat.KEY_FRAME_RATE, encoderFps"))
    assertTrue(helper.contains("MediaFormat.KEY_I_FRAME_INTERVAL, 0"))
    assertTrue(helper.contains("MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR"))
    assertTrue(helper.contains("requestSyncFrame(encoder);"))
    assertTrue(helper.contains("frame_dependency_mode=all_intra"))
    assertTrue(helper.contains("new TicketCaptureCadenceScheduler"))
    assertTrue(helper.contains("cadenceScheduler.beginCapture(started, proofBypass)"))
    assertTrue(helper.contains("cadenceScheduler.waitMillis(started, proofBypass)"))
    assertFalse(helper.contains("MotionSampler"))
    assertFalse(helper.contains("TicketMotionCadenceController"))
    assertFalse(helper.contains("TicketAdaptiveKeyframeController"))
    assertFalse(helper.contains("MediaFormat.KEY_QUALITY"))
    assertFalse(helper.contains("BITRATE_MODE_CQ"))
    assertFalse(helper.contains("CONTROL_CODE_BURST"))
  }

  @Test
  fun immediateRefreshIsCoalescedAndThereIsNoRuntimeCadenceCommand() {
    assertTrue(scheduler.contains("immediateCaptureBlockedUntilMillis"))
    assertTrue(scheduler.contains("nextDeadlineMillis = nowMillis + intervalMillis()"))
    assertTrue(scheduler.contains("FIXED_FPS = 1"))
    assertTrue(engine.contains("fun requestImmediateRefresh(reason: String): Boolean"))
    assertTrue(helper.contains("requestImmediateSyncFrame(syncFrameRequested, cadenceScheduler, frameWaitLock)"))
    assertFalse(scheduler.contains("isLegacyCommandFps"))
    assertFalse(helper.contains("cmd.startsWith(\"cadence:\")"))
    assertFalse(engine.contains("requestCadence"))
    assertFalse(service.contains("\"stream_cadence\" ->"))
  }

  @Test
  fun additiveDemandGateParksOnlyOrdinaryCaptureAndLeavesProofPathsWarm() {
    assertTrue(helper.contains("cmd.startsWith(\"capture_demand:\")"))
    assertTrue(helper.contains("enableDemandGateAndLatchOrdinaryCapture("))
    assertTrue(helper.contains("sent == 0 || syncFrameRequested.get() || controlCodeVisualProbeActive"))
    assertTrue(helper.contains("frameWaitLock.wait();"))
    assertTrue(scheduler.contains("WAIT_UNTIL_SIGNAL_MILLIS"))
    assertTrue(scheduler.contains("ordinaryDemandOpportunityConsumed"))
    assertTrue(engine.contains("fun requestOrdinaryCaptureDemand("))
    assertFalse(helper.contains("encoder.stop();\n                // demand"))
  }

  @Test
  fun helperResolvesEachPostPrimerInputBeforeCadenceOrJitCanPark() {
    assertTrue(helper.contains("drainEncoderUntilInputResolved("))
    assertTrue(helper.contains("while (codecInputLedger.contains(inputStage))"))
    assertTrue(helper.contains("STARTUP_PRIMER_DRAIN_POLL_MILLIS * 1_000L"))
    assertTrue(helper.contains("Encoder liveness belongs to the service watchdog"))
    assertFalse(helper.contains("CURRENT_INPUT_DRAIN_DEADLINE_MILLIS"))
    assertFalse(helper.contains("current codec input exceeded drain deadline"))
    assertTrue(helper.contains("if (codecInputLedger.size() == 0)"))
    assertFalse(helper.contains("long drainTimeoutUs = sent < 3"))
    assertTrue(helper.contains("MediaFormat.KEY_OPERATING_RATE, encoderFps"))
    assertTrue(service.contains("STREAM_WATCHDOG_NO_ENCODER_RESTART_MILLIS = 3_000L"))
    assertTrue(service.contains("STREAM_WATCHDOG_STALE_FRAME_RESTART_MILLIS = 3_000L"))

    val primerStart = helper.indexOf("private static StartupPrimerRun runStartupPrimer(")
    val primerEnd = helper.indexOf("private static void logStartupPrimerComplete(", primerStart)
    val primerBody = helper.substring(primerStart, primerEnd)
    assertTrue(primerBody.contains("STARTUP_PRIMER_DRAIN_POLL_MILLIS * 1_000L"))
    assertFalse(primerBody.contains("drainEncoderUntilInputResolved("))

    val idleBoundaryGuard = helper.indexOf("if (codecInputLedger.size() == 0)")
    val idleBoundaryForward = helper.indexOf(
      "forwardSelectedBoundaryAccessUnit(output, startupPrimer, cadenceScheduler);",
      idleBoundaryGuard
    )
    assertTrue(idleBoundaryGuard >= 0)
    assertTrue(idleBoundaryForward > idleBoundaryGuard)
  }

  @Test
  fun startupGateClosesOnlyAfterTheFirstSteadySurfacePostAndDrain() {
    val steadyBranchStart = helper.indexOf(
      "boolean closeStartupPrimerAfterDrain ="
    )
    val steadyBranchEnd = helper.indexOf(
      "TicketEncoderDrainProgress drainProgress = encoderRun.drainProgress;",
      steadyBranchStart
    )
    assertTrue(steadyBranchStart >= 0)
    assertTrue(steadyBranchEnd > steadyBranchStart)
    val steadyBranch = helper.substring(steadyBranchStart, steadyBranchEnd)

    val surfacePost = steadyBranch.indexOf(
      "drawBitmap(inputSurface, source.bitmap, sourceCrop, destination, paint);"
    )
    val boundaryDrainStart = steadyBranch.indexOf("startupPrimer.beginBoundaryDrain();")
    val gatedDrain = steadyBranch.indexOf(
      "startupPrimer.finished() ? null : startupPrimer"
    )
    val gateClose = steadyBranch.indexOf("startupPrimer.finish();")

    assertTrue(boundaryDrainStart >= 0)
    assertTrue(surfacePost > boundaryDrainStart)
    assertTrue(surfacePost >= 0)
    assertTrue(gatedDrain > surfacePost)
    assertTrue(gateClose > gatedDrain)
    assertTrue(steadyBranch.contains("expose exactly"))
  }

  @Test
  fun pixelDropsUnexpectedDeltasAndRequestsTheNextSyncFrame() {
    assertTrue(engine.contains("if (!record.keyFrame)"))
    assertTrue(engine.contains("unexpectedDeltaFrames += 1L"))
    assertTrue(engine.contains("droppedFrames += 1L"))
    assertTrue(engine.contains("requestImmediateRefresh(\"unexpected_delta_all_intra\")"))
    assertTrue(service.contains("requestImmediateRefresh(\"service_rejected_unexpected_delta\")"))
    assertTrue(config.contains("val unexpectedDeltaFrames: Long = 0L"))
  }

  @Test
  fun configAndHealthAdvertiseTheExactAllIntraContract() {
    assertTrue(service.contains("val fps = TicketScreenConfig.ROOT_HARDWARE_H264_FPS"))
    assertTrue(service.contains("val sourceFps = TicketScreenConfig.ROOT_HARDWARE_H264_FPS"))
    assertTrue(service.contains("val keyframeIntervalFrames = 1"))
    assertTrue(service.contains("\"frameDependencyMode\":\"\$frameDependencyMode\""))
    assertTrue(service.contains("hardwareH264FrameDependencyMode"))
    assertTrue(service.contains("hardwareH264UnexpectedDeltaFrames"))
    assertTrue(service.contains("FRAME_ENVELOPE_VERSION = \"tsf3\""))
    assertTrue(engine.contains("val candidate = SystemClock.elapsedRealtime().coerceAtLeast(1L)"))
    assertFalse(engine.contains("val candidate = SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)"))
  }

  private fun source(relative: String): String {
    val path = listOf(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/$relative"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/$relative")
    ).firstOrNull(Files::exists) ?: error("Missing source file: $relative")
    return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }
}
