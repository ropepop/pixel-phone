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
  private val outputLiveness by lazy { source("TicketEncoderOutputLiveness.java") }

  @Test
  fun configDefinesOnlyTheThreeAdaptiveTiersAndKeepsTheSdrPipeline() {
    assertTrue(config.contains("ROOT_HARDWARE_H264_STEADY_FPS = 1"))
    assertTrue(config.contains("ROOT_HARDWARE_H264_MODERATE_FPS = 5"))
    assertTrue(config.contains("ROOT_HARDWARE_H264_ACTIVE_FPS = 10"))
    assertTrue(config.contains("ROOT_HARDWARE_H264_MAX_FPS = ROOT_HARDWARE_H264_ACTIVE_FPS"))
    assertTrue(config.contains("ROOT_HARDWARE_H264_CADENCE_COMMAND_PREFIX = \"cadence:\""))
    assertTrue(config.contains("ROOT_HARDWARE_H264_TRANSPORT = \"hardware-h264-annexb\""))
    assertTrue(config.contains("ROOT_HARDWARE_H264_COLOR_STANDARD = \"bt709_limited_sdr\""))
    assertTrue(config.contains("frameEnvelope: String = \"tsf2\""))
  }

  @Test
  fun helperAcceptsCadenceCommandsAndRejectsOtherFpsWithoutASecondEncoder() {
    assertTrue(helper.contains("cmd.startsWith(\"cadence:\")"))
    assertTrue(helper.contains("TicketCaptureCadenceScheduler.isSupportedFps(requested)"))
    assertTrue(helper.contains("requestedCadenceFps.set(requested)"))
    assertTrue(helper.contains("accepted=\" + accepted"))
    assertTrue(helper.contains("MediaFormat.KEY_FRAME_RATE, encoderFps"))
    assertTrue(helper.contains("new TicketCaptureCadenceScheduler"))
    assertTrue(helper.contains("cadenceScheduler.beginCapture(started)"))
    assertTrue(helper.contains("cadenceScheduler.waitMillis(started)"))
    assertTrue(helper.contains("requestImmediateSyncFrame(syncFrameRequested, cadenceScheduler, frameWaitLock)"))
    assertTrue(helper.contains("cadenceScheduler.requestImmediateCapture(SystemClock.elapsedRealtime())"))
    assertTrue(helper.contains("if (!cadenceScheduler.hasImmediateCapturePending())"))
    assertTrue(helper.contains("MOTION_THUMBNAIL_SIZE"))
    assertTrue(helper.contains("MOTION_SAMPLE_INTERVAL_MILLIS = 1_000L"))
    assertTrue(helper.contains("MOTION_SAMPLE_MAX_DURATION_MILLIS"))
    assertTrue(helper.contains("MOTION_MAX_SLOW_SAMPLES"))
    assertTrue(helper.contains("readback=hardware_bitmap_copy"))
    assertTrue(helper.contains("sample_budget_exceeded"))
    assertTrue(helper.contains("motionSampler.isEnabled()"))
    assertTrue(helper.contains("motion_disabled="))
    assertTrue(helper.contains("Math.abs(currentLuma - previousLuma) > 8"))
    assertTrue(helper.contains("TicketMotionCadenceController"))
    assertTrue(helper.contains("if (visualProbeRequest.ticketAction)"))
    assertTrue(helper.contains("visualProbeRequest.untilMillis.set(0L)"))
    assertFalse(helper.contains("for (int catchUp"))
    assertFalse(helper.contains("while (.*catch"))
  }

  @Test
  fun helperWaitsForMediaOutputWhenTheFirstDrainOnlyReturnsCodecConfiguration() {
    assertTrue(helper.contains("TicketEncoderDrainProgress drainProgress"))
    assertTrue(helper.contains("if (drainProgress.encodedFrameOutputs == 0 && sent == 0)"))
    assertTrue(helper.contains("TicketEncoderDrainProgress.fromDequeuedOutput("))
    assertTrue(helper.contains("emitted.codecConfig,"))
    assertTrue(helper.contains("emitted.keyFrame"))
    assertTrue(helper.contains("TicketH264EncoderOutputAssembler"))
    assertTrue(helper.contains("MediaCodec.BUFFER_FLAG_PARTIAL_FRAME"))
    assertTrue(helper.contains("if (eos && data.length == 0)"))
    assertTrue(helper.contains("outputAssembler.reset()"))
    assertFalse(helper.contains("int drained = drainEncoder"))
  }

  @Test
  fun helperUsesOneCoalescedImmediateSyncRecoveryForAStaticOutputDrought() {
    assertTrue(helper.contains("TicketEncoderOutputLiveness outputLiveness"))
    assertTrue(helper.contains("!cadenceTransitionCapture && !cadenceDecision.immediate && !explicitSyncFrame"))
    assertTrue(helper.contains("scheduledCadenceDrain,"))
    assertTrue(helper.contains("requestImmediateSyncFrame(syncFrameRequested, cadenceScheduler, frameWaitLock)"))
    assertTrue(helper.contains("ENCODER_LIVENESS state=sync_requested"))
    assertTrue(outputLiveness.contains("STATIC_EMPTY_DRAIN_THRESHOLD = 2"))
    assertTrue(outputLiveness.contains("targetFps != TicketCaptureCadenceScheduler.STATIC_FPS"))
    assertTrue(outputLiveness.contains("if (recoveryArmed)"))
    assertTrue(outputLiveness.contains("if (encodedFrameOutputs > 0)"))
    assertTrue(outputLiveness.contains("if (madeCodecProgress && !completeMediaOverdue)"))
    assertTrue(outputLiveness.contains("MIN_STATIC_EVIDENCE_INTERVAL_MILLIS"))
    assertTrue(outputLiveness.contains("MAX_PROGRESS_WITHOUT_MEDIA_MILLIS"))
    assertTrue(engine.contains("line.startsWith(\"ENCODER_LIVENESS \")"))
    assertTrue(engine.contains("fields[\"state\"] == \"sync_requested\""))
    assertTrue(engine.contains("cleanLine.startsWith(\"ENCODER_LIVENESS \") || cleanLine.startsWith(\"CONTROL_CODE_VISUAL \")"))
    assertTrue(engine.contains("encoderLivenessRecoveryCount = encoderLivenessRecoveryCountSnapshot"))
    assertTrue(engine.contains("lastEncoderLivenessRecoveryAgoMillis = lastEncoderLivenessRecoveryAgoMillisSnapshot"))
    assertTrue(engine.contains("synchronized(encoderLivenessRecoveryLock)"))
    assertTrue(engine.contains("sourceGeneration != captureGeneration.get()"))
    assertTrue(engine.contains("resetEncoderLivenessRecoveryMetrics()"))
    assertTrue(engine.contains("encoderLivenessRecoveryCount = 0L"))
    assertTrue(engine.contains("lastEncoderLivenessRecoveryAtMillis = 0L"))
    assertTrue(config.contains("val encoderLivenessRecoveryCount: Long = 0L"))
    assertTrue(config.contains("val lastEncoderLivenessRecoveryAgoMillis: Long? = null"))
  }

  @Test
  fun schedulerDocumentsAbsoluteDeadlineNoCatchUpMetrics() {
    assertTrue(scheduler.contains("nextDeadlineMillis"))
    assertTrue(scheduler.contains("skippedTicks"))
    assertTrue(scheduler.contains("deadlineMisses"))
    assertTrue(scheduler.contains("at most one"))
    assertTrue(scheduler.contains("capture decision"))
    assertTrue(scheduler.contains("nextDeadlineMillis += intervalsToAdvance * intervalMillis()") ||
      scheduler.contains("nextDeadlineMillis += (expiredTicks + 1L) * intervalMillis()"))
  }

  @Test
  fun engineExposesInProcessCadenceAndHealthMetrics() {
    assertTrue(engine.contains("fun requestCadence(fps: Int"))
    assertTrue(engine.contains("CADENCE "))
    assertTrue(engine.contains("cadenceDeadlineMisses"))
    assertTrue(engine.contains("lastCadenceCommandAccepted"))
    assertTrue(engine.contains("desiredCadenceFps"))
    assertTrue(engine.contains("flushDesiredCadenceCommand(\"encoder_started\")"))
    assertTrue(engine.contains("writeHardwareCommand(command, \"cadence\", reason)"))
  }

  @Test
  fun servicePublishesCadenceMetricsWithoutChangingTheStreamContract() {
    assertTrue(service.contains("hardwareH264CadenceFps"))
    assertTrue(service.contains("hardwareH264CadenceTier"))
    assertTrue(service.contains("hardwareH264CadenceSkippedTicks"))
    assertTrue(service.contains("val feedbackVersion = 1"))
    assertTrue(service.contains("val fps = TicketScreenConfig.ROOT_HARDWARE_H264_ACTIVE_FPS"))
    assertTrue(service.contains("val sourceFps = TicketScreenConfig.ROOT_HARDWARE_H264_ACTIVE_FPS"))
    assertTrue(service.contains("val keyframeIntervalFrames = TicketScreenConfig.ROOT_HARDWARE_H264_ACTIVE_FPS"))
    assertTrue(service.contains("\"sourceFps\":\$sourceFps"))
    assertTrue(service.contains("\"keyframeIntervalFrames\":\$keyframeIntervalFrames"))
    assertTrue(service.contains("\"feedbackVersion\":\$feedbackVersion"))
    assertTrue(service.contains("requestActiveHardwareCadence(\"video_client_connected\")"))
    assertTrue(service.contains("requestActiveHardwareCadence(\"video_client_activity\")"))
    assertTrue(service.contains("requestSteadyHardwareCadenceBeforeStop(\"all_clients_disconnected\")"))
    assertTrue(service.contains("requestSteadyHardwareCadenceBeforeStop(\"session_stop:\$reason\")"))
    assertTrue(service.contains("requestCadence(activeFps, reason)"))
    assertTrue(service.contains("requestCadence(steadyFps, reason)"))
    assertTrue(service.contains("rootHardwareH264CaptureEngine.startControlCodeRequestBurst("))
    assertTrue(service.contains("rootHardwareH264CaptureEngine.stopControlCodeRequestBurst("))
    assertTrue(service.contains("FRAME_ENVELOPE_VERSION = \"tsf2\""))
  }

  private fun source(relative: String): String {
    val path = listOf(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/$relative"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/$relative")
    ).firstOrNull(Files::exists) ?: error("Missing source file: $relative")
    return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }
}
