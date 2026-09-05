package lv.jolkins.pixelorchestrator.app.ticket

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketCaptureDemandSourceTest {
  private val service by lazy { source("TicketStreamService.kt") }
  private val engine by lazy { source("TicketRootHardwareH264CaptureEngine.kt") }
  private val helper by lazy { source("TicketRootHardwareH264CaptureMain.java") }

  @Test
  fun serviceScopesStrictDemandGenerationToTheExactVideoSocket() {
    val handler = body(
      service,
      "private fun handleVideoClientCommand",
      "internal suspend fun handleTicketSpacetimeCommand"
    )
    assertTrue(service.contains("mutableMapOf<TicketWebSocket, TicketCaptureDemandSession>()"))
    assertTrue(service.contains("captureDemandSessions[client] = TicketCaptureDemandSession()"))
    assertTrue(service.contains("captureDemandSessions.remove(client)"))
    assertTrue(handler.contains("TicketCaptureDemandProtocol.parseRequest(element) ?: return"))
    assertTrue(handler.contains("val session = captureDemandSessions[client] ?: return"))
    assertTrue(handler.contains("streamEpoch != request.streamEpoch"))
    assertTrue(handler.contains("rootHardwareH264CaptureEngine.requestOrdinaryCaptureDemand"))
    assertTrue(
      handler.indexOf("val phoneReceiveUptimeMicros") <
        handler.indexOf("json.parseToJsonElement(message)")
    )
  }

  @Test
  fun helperDemandCommandOnlyLatchesOneOpportunityAndKeepsProcessCodecWarm() {
    val demandBranch = helper.substring(
      helper.indexOf("cmd.startsWith(\"capture_demand:\")"),
      helper.indexOf("cmd.startsWith(\"control_code_visual_probe:\")")
    )
    assertTrue(demandBranch.contains("enableDemandGateAndLatchOrdinaryCapture("))
    assertTrue(demandBranch.contains("frameWaitLock.notifyAll();"))
    assertFalse(demandBranch.contains("encoder.stop"))
    assertFalse(demandBranch.contains("encoder.release"))
    assertFalse(demandBranch.contains("System.exit"))
    assertTrue(helper.contains("sent == 0 || syncFrameRequested.get() || controlCodeVisualProbeActive"))
    assertTrue(helper.contains("WAIT_UNTIL_SIGNAL_MILLIS"))
    assertTrue(helper.contains("cadenceScheduler.notePictureEmitted(SystemClock.elapsedRealtime());"))
    val syncRequest = body(
      helper,
      "private static void requestImmediateSyncFrame",
      "private static StartupPrimerRun runStartupPrimer"
    )
    assertTrue(syncRequest.contains("cadenceScheduler.requestImmediateCapture"))
    assertTrue(syncRequest.contains("frameWaitLock.notifyAll();"))
    assertFalse(syncRequest.contains("if (cadenceScheduler.requestImmediateCapture"))
  }

  @Test
  fun engineAndWatchdogDistinguishIntentionalIdleFromMissingDemandedOutput() {
    assertTrue(engine.contains("fun requestOrdinaryCaptureDemand(validUntilUptimeMillis: Long): Boolean"))
    assertTrue(engine.contains("ordinaryCaptureDemandGated = true"))
    assertTrue(engine.contains("captureFrameExpected = true"))
    assertTrue(engine.contains("captureFrameExpected = false\n        frames += 1"))
    assertTrue(service.contains("streamWatchdogStage = \"demand_idle\""))
    assertTrue(service.contains("health.ordinaryCaptureDemandGated && !health.captureFrameExpected"))
  }

  @Test
  fun configAdvertisesJitContractAndSlowSocketGetsTheFullUsefulnessWindow() {
    assertTrue(service.contains("\"captureDemandVersion\":${'$'}{TicketCaptureDemandProtocol.VERSION}"))
    assertTrue(service.contains("\"captureDemandTtlMillis\":${'$'}{TicketCaptureDemandProtocol.TTL_MILLIS}"))
    assertTrue(service.contains(
      "private const val VIDEO_CLIENT_SLOW_CLOSE_MILLIS = ROOT_KEYFRAME_CACHE_MAX_AGE_MILLIS"
    ))
    assertTrue(service.contains("private const val ROOT_KEYFRAME_CACHE_MAX_AGE_MILLIS = 1_250L"))
  }

  private fun body(text: String, startNeedle: String, endNeedle: String): String {
    val start = text.indexOf(startNeedle)
    require(start >= 0) { "missing $startNeedle" }
    val end = text.indexOf(endNeedle, start + startNeedle.length)
    require(end > start) { "missing $endNeedle" }
    return text.substring(start, end)
  }

  private fun source(relative: String): String {
    val path = listOf(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/$relative"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/$relative")
    ).firstOrNull(Files::exists) ?: error("Missing source file: $relative")
    return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }
}
