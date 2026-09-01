package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.rootexec.RootResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration

class TicketEncoderLivenessHealthTest {
  @Test
  fun helperDiagnosticsExposeFixedAllIntraEncoderHealth() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      val engine = TicketRootHardwareH264CaptureEngine(
        scope = scope,
        rootExecutor = UnusedRootExecutor,
        onFrame = {},
        onStateChanged = {}
      )

      engine.ingestStderrLine(
        "ENCODER_CONFIG name=c2.google.avc.encoder configured_profile=baseline configured_level=4 " +
          "configured_bitrate_mode=cbr bitrate=8000000 configured_fps=1 " +
          "keyframe_interval_frames=1 frame_dependency_mode=all_intra",
        nowMillis = 1_000L
      )
      engine.ingestStderrLine(
        "ENCODER_STARTUP_PRIMER state=complete result=keyframe input_posts=2 " +
          "media_outputs=2 suppressed_outputs=1 first_keyframe_ms=127",
        nowMillis = 1_025L
      )
      val health = engine.snapshot(nowMillis = 1_450L)

      assertEquals("c2.google.avc.encoder", health.encoderName)
      assertEquals("baseline", health.configuredEncoderProfile)
      assertEquals("4", health.configuredEncoderLevel)
      assertEquals("cbr", health.configuredEncoderBitrateMode)
      assertEquals("all_intra", health.frameDependencyMode)
      assertEquals(1, health.fps)
      assertEquals("fixed_all_intra", health.intervalMode)
      assertEquals(1_000L, health.currentIntervalMillis)
      assertEquals(0L, health.unexpectedDeltaFrames)
      assertEquals("complete", health.startupPrimerState)
      assertEquals("keyframe", health.startupPrimerResult)
      assertEquals(2, health.startupPrimerInputPosts)
      assertEquals(2, health.startupPrimerMediaOutputs)
      assertEquals(1, health.startupPrimerSuppressedOutputs)
      assertEquals(127L, health.startupPrimerFirstKeyFrameMillis)
      assertFalse(health.stderrTail.contains("ENCODER_CONFIG"))
      assertFalse(health.stderrTail.contains("ENCODER_STARTUP_PRIMER"))
      val healthJson = Json.encodeToString(health)
      assertFalse(healthJson.contains("steadyFpsTarget"))
      assertFalse(healthJson.contains("burstFpsTarget"))
      assertFalse(healthJson.contains("adaptiveKeyFrameRequests"))

      val staleGeneration = engine.advanceCaptureGeneration()
      engine.advanceCaptureGeneration()
      engine.ingestStderrLine(
        "METRIC capture_ms=1 fps_target=1 cadence_fps=1 cadence_tier=fixed_all_intra",
        nowMillis = 1_525L,
        sourceGeneration = staleGeneration
      )
      engine.ingestStderrLine(
        "ENCODER_STARTUP_PRIMER state=complete result=fallback_keyframe input_posts=3 " +
          "media_outputs=3 suppressed_outputs=2 first_keyframe_ms=900",
        nowMillis = 1_530L,
        sourceGeneration = staleGeneration
      )
      val afterStaleDiagnostics = engine.snapshot(nowMillis = 1_550L)
      assertNull(afterStaleDiagnostics.lastCaptureDurationMillis)
      assertEquals("keyframe", afterStaleDiagnostics.startupPrimerResult)
    } finally {
      scope.cancel()
    }
  }

  @Test
  fun helperStderrIngestionConsumesLivenessDetailAndUpdatesBoundedHealth() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      val engine = TicketRootHardwareH264CaptureEngine(
        scope = scope,
        rootExecutor = UnusedRootExecutor,
        onFrame = {},
        onStateChanged = {}
      )

      engine.ingestStderrLine("ordinary helper warning", nowMillis = 850L)
      engine.ingestStderrLine(
        "ENCODER_LIVENESS state=waiting raw_detail=must_not_be_retained",
        nowMillis = 900L
      )
      engine.ingestStderrLine("OTHER state=sync_requested", nowMillis = 950L)
      engine.ingestStderrLine(
        "CONTROL_CODE_VISUAL result=ticket_list probe_id=77 method=ticket_action_visual_probe " +
          "cards=private-anchor@1,2,3,4@5,6,7,8@1 slider_bounds=9,10,11,12 " +
          "visual_signature=0123456789abcdef01234567 visual_signature_epoch=abcdef012345",
        nowMillis = 975L
      )
      val visualProbe = engine.recentControlCodeVisualProbeAfter(77L, 900L)
      assertEquals(TicketVisualPhoneState.TICKET_LIST, visualProbe?.ticketActionObservation?.state)
      var health = engine.snapshot(nowMillis = 1_000L)
      assertEquals(0L, health.encoderLivenessRecoveryCount)
      assertNull(health.lastEncoderLivenessRecoveryAgoMillis)
      assertTrue(health.stderrTail.contains("ordinary helper warning"))
      assertTrue(health.stderrTail.contains("OTHER state=sync_requested"))
      assertFalse(health.stderrTail.contains("ENCODER_LIVENESS"))
      assertFalse(health.stderrTail.contains("raw_detail"))
      assertFalse(health.stderrTail.contains("CONTROL_CODE_VISUAL"))
      assertFalse(health.stderrTail.contains("private-anchor"))
      assertFalse(health.stderrTail.contains("9,10,11,12"))
      assertFalse(Json.encodeToString(health).contains("0123456789abcdef01234567"))

      engine.ingestStderrLine(
        "ENCODER_LIVENESS state=sync_requested fps_target=1 consecutive_empty_drains=2 raw_detail=first",
        nowMillis = 1_100L
      )
      engine.ingestStderrLine(
        "ENCODER_LIVENESS state=sync_requested fps_target=1 consecutive_empty_drains=2 raw_detail=second",
        nowMillis = 1_300L
      )
      health = engine.snapshot(nowMillis = 1_425L)

      assertEquals(2L, health.encoderLivenessRecoveryCount)
      assertEquals(125L, health.lastEncoderLivenessRecoveryAgoMillis)
      val healthJson = Json.encodeToString(health)
      assertTrue(healthJson.contains("\"encoderLivenessRecoveryCount\":2"))
      assertTrue(healthJson.contains("\"lastEncoderLivenessRecoveryAgoMillis\":125"))
      assertFalse(healthJson.contains("ENCODER_LIVENESS"))
      assertFalse(healthJson.contains("consecutive_empty_drains"))
      assertFalse(healthJson.contains("raw_detail"))

      engine.resetEncoderLivenessRecoveryMetrics()
      health = engine.snapshot(nowMillis = 1_450L)
      assertEquals(0L, health.encoderLivenessRecoveryCount)
      assertNull(health.lastEncoderLivenessRecoveryAgoMillis)

      val staleSourceGeneration = engine.advanceCaptureGeneration()
      engine.ingestStderrLine(
        "ENCODER_LIVENESS state=sync_requested fps_target=1 consecutive_empty_drains=2",
        nowMillis = 1_500L,
        sourceGeneration = staleSourceGeneration
      )
      health = engine.snapshot(nowMillis = 1_525L)
      assertEquals(1L, health.encoderLivenessRecoveryCount)
      assertEquals(25L, health.lastEncoderLivenessRecoveryAgoMillis)
      assertFalse(health.stderrTail.contains("ENCODER_LIVENESS"))

      val currentSourceGeneration = engine.advanceCaptureGeneration()
      engine.resetEncoderLivenessRecoveryMetrics()
      engine.ingestStderrLine(
        "ENCODER_LIVENESS state=sync_requested raw_detail=stale_process",
        nowMillis = 1_600L,
        sourceGeneration = staleSourceGeneration
      )
      health = engine.snapshot(nowMillis = 1_625L)
      assertEquals(0L, health.encoderLivenessRecoveryCount)
      assertNull(health.lastEncoderLivenessRecoveryAgoMillis)
      assertFalse(health.stderrTail.contains("stale_process"))

      engine.ingestStderrLine(
        "ENCODER_LIVENESS state=sync_requested fps_target=1 consecutive_empty_drains=2",
        nowMillis = 1_700L,
        sourceGeneration = currentSourceGeneration
      )
      health = engine.snapshot(nowMillis = 1_725L)
      assertEquals(1L, health.encoderLivenessRecoveryCount)
      assertEquals(25L, health.lastEncoderLivenessRecoveryAgoMillis)
    } finally {
      scope.cancel()
    }
  }

  private object UnusedRootExecutor : RootExecutor {
    override suspend fun isRootAvailable(): Boolean = false

    override suspend fun run(command: String, timeout: Duration): RootResult = unused(command)

    override suspend fun runScript(script: String, timeout: Duration): RootResult = unused(script)

    private fun unused(command: String) = RootResult(
      exitCode = 1,
      stdout = "",
      stderr = "unused",
      command = command,
      durationMs = 0L
    )
  }
}
