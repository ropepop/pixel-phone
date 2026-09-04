package lv.jolkins.pixelorchestrator.app.ticket

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketProofStreamCleanupSourceTest {
  private val service = source("ticket/TicketStreamService.kt")

  @Test
  fun commandLeaseIsIssuedOnlyByTheLockedFreshSessionAdmission() {
    val serializedStart = body(
      service,
      "private suspend fun startTicketSession(",
      "private suspend fun startTicketSessionLocked("
    )
    val lockedStart = body(
      service,
      "private suspend fun startTicketSessionLocked(",
      "private suspend fun runSessionStartSafetyPreflight("
    )
    val admission = lockedStart.substring(
      lockedStart.indexOf("admitted = {"),
      lockedStart.indexOf("if (!armConsumedByAdmission)")
    )

    assertTrue(serializedStart.contains("sessionMutex.withLock"))
    assertTrue(serializedStart.contains("onNewSessionAdmitted = onNewSessionAdmitted"))
    assertTrue(admission.contains("ticketSessionGeneration += 1L"))
    assertTrue(admission.contains("onNewSessionAdmitted?.invoke(ticketSessionGeneration)"))
    assertTrue(
      admission.indexOf("ticketSessionGeneration += 1L") <
        admission.indexOf("onNewSessionAdmitted?.invoke(ticketSessionGeneration)")
    )
    assertFalse(lockedStart.substringBefore("admitted = {").contains("onNewSessionAdmitted?.invoke"))
    assertFalse(lockedStart.substringAfter("if (!armConsumedByAdmission)").contains("onNewSessionAdmitted?.invoke"))
  }

  @Test
  fun reauthSchedulesCleanupOnlyAfterItsTerminalLeaseIsReleased() {
    val wrapper = body(
      service,
      "private suspend fun runViviReauth(",
      "private suspend fun executeViviReauthAttempt("
    )
    val attempt = body(
      service,
      "private suspend fun executeViviReauthAttempt(",
      "private suspend fun verifyViviReauthResult("
    )

    assertTrue(wrapper.contains("var commandStartedProofSessionGeneration: Long? = null"))
    assertTrue(wrapper.contains("sessionMutex.withLock {\n      viviReauthCaptureLeaseActive = true"))
    assertTrue(wrapper.contains("viviReauthCaptureLeaseActive = true"))
    assertTrue(wrapper.contains("onNewSessionAdmitted = { generation ->"))
    assertTrue(wrapper.contains("commandStartedProofSessionGeneration = generation"))
    assertTrue(wrapper.contains("withContext(NonCancellable)"))
    assertTrue(
      wrapper.indexOf("panelLease.releaseAfterFinalConvergence") <
        wrapper.indexOf("viviReauthCaptureLeaseActive = false")
    )
    assertTrue(
      wrapper.indexOf("viviReauthCaptureLeaseActive = false") <
        wrapper.indexOf("scheduleCommandStartedProofStreamCleanupLocked(")
    )
    assertTrue(wrapper.contains("sessionMutex.withLock {\n          viviReauthCaptureLeaseActive = false"))
    assertTrue(wrapper.contains("reason = \"vivi_reauth_proof_stream_idle\""))
    assertTrue(attempt.contains("onNewSessionAdmitted = onNewSessionAdmitted"))
    assertFalse(attempt.contains("noteClientDetachedLocked"))
    assertFalse(attempt.contains("stopTicketSession"))
  }

  @Test
  fun ticketVisualActionUsesTheSameColdStartOnlyCleanupBoundary() {
    val wrapper = body(
      service,
      "private suspend fun runTicketVisualActionV3(",
      "private suspend fun runTicketVisualActionV3WithCaptureLease("
    )
    val action = body(
      service,
      "private suspend fun runTicketVisualActionV3WithCaptureLease(",
      "private suspend fun activateTicketFromVisualAction("
    )

    assertTrue(wrapper.contains("var commandStartedProofSessionGeneration: Long? = null"))
    assertTrue(wrapper.contains("sessionMutex.withLock {\n      ticketVisualActionCaptureLeaseActive = true"))
    assertTrue(wrapper.contains("onNewSessionAdmitted = { sessionGeneration ->"))
    assertTrue(wrapper.contains("commandStartedProofSessionGeneration = sessionGeneration"))
    assertTrue(
      wrapper.indexOf("ticketVisualActionCaptureLeaseActive = false") <
        wrapper.indexOf("scheduleCommandStartedProofStreamCleanupLocked(")
    )
    assertTrue(wrapper.contains("sessionMutex.withLock {\n          ticketVisualActionCaptureLeaseActive = false"))
    assertTrue(wrapper.contains("reason = \"ticket_action_v3_proof_stream_idle\""))
    assertTrue(action.contains("if (!streamActive)"))
    assertTrue(action.contains("onNewSessionAdmitted = onNewSessionAdmitted"))
    assertFalse(action.substringBefore("if (!streamActive)").contains("onNewSessionAdmitted("))
    assertFalse(action.contains("noteClientDetachedLocked"))
  }

  @Test
  fun readOnlyActionAndControlCodeAdmissionsShareTheCleanupSessionLock() {
    val actionAdmission = body(
      service,
      "private suspend fun handleTicketVisualActionV3(",
      "private suspend fun runReadOnlyTicketVisualProofV3("
    )
    val controlClaim = body(
      service,
      "private suspend fun claimControlCodeAutomationForRequest()",
      "private suspend fun releaseControlCodeAutomationForRequest()"
    )
    val controlRelease = body(
      service,
      "private suspend fun releaseControlCodeAutomationForRequest()",
      "private fun recordControlCodeCommandEnvelope("
    )

    assertTrue(actionAdmission.contains("return sessionMutex.withLock"))
    assertTrue(actionAdmission.contains("ticketVisualActionJobOwnershipActive = true"))
    assertTrue(
      actionAdmission.indexOf("ticketVisualActionJobOwnershipActive = true") <
        actionAdmission.indexOf("ticketActionV3Job = serviceScope.launch")
    )
    assertTrue(actionAdmission.contains("finally"))
    assertTrue(actionAdmission.contains("withContext(NonCancellable)"))
    assertTrue(actionAdmission.contains("sessionMutex.withLock {\n              ticketVisualActionJobOwnershipActive = false"))
    assertTrue(controlClaim.contains("sessionMutex.withLock"))
    assertTrue(
      controlClaim.indexOf("sessionMutex.withLock") <
        controlClaim.indexOf("streamStartAdmission.claim()")
    )
    assertTrue(controlRelease.contains("withContext(NonCancellable)"))
    assertTrue(
      controlRelease.indexOf("sessionMutex.withLock") <
        controlRelease.indexOf("streamStartAdmission.release()")
    )
  }

  @Test
  fun graceExpiryIsGenerationClientAndOwnershipFencedUnderTheSessionLock() {
    val scheduler = body(
      service,
      "private fun scheduleCommandStartedProofStreamCleanupLocked(",
      "private fun markViewerInput("
    )
    val socket = body(
      service,
      "private suspend fun acceptWebSocket(",
      "private suspend fun startTicketSessionForVideoClientOpen("
    )
    val inactivity = body(
      service,
      "private suspend fun stopTicketSessionIfStillInactive(",
      "private suspend fun stopTicketSessionLocked("
    )
    val captureDemand = body(
      service,
      "private fun streamCaptureNeededForControlCodeRequest()",
      "private fun ensureRootHardwareH264CaptureIfPossible()"
    )

    assertTrue(scheduler.contains("ticketSessionGeneration != expectedSessionGeneration"))
    assertTrue(scheduler.contains("totalClientCount() != 0"))
    assertTrue(scheduler.contains("markViewerInput(\"command_proof_terminal_cleanup\")"))
    assertTrue(scheduler.contains("CLIENT_DISCONNECT_IDLE_GRACE_MILLIS"))
    assertTrue(scheduler.contains("currentSessionGeneration = ticketSessionGeneration"))
    assertTrue(scheduler.contains("clientCount = totalClientCount()"))
    assertTrue(scheduler.contains("startOwnershipActive = streamStartAdmission.claimCount() > 0L"))
    assertTrue(scheduler.contains("controlOwnershipActive = ticketSpacetimeControlCodeRequestActive()"))
    assertTrue(scheduler.contains("ticketVisualActionCaptureLeaseActive"))
    assertTrue(scheduler.contains("ticketVisualActionJobOwnershipActive"))
    assertTrue(scheduler.contains("ticketActionV3Job?.isActive == true"))
    assertTrue(scheduler.contains("reauthOwnershipActive = viviReauthCaptureLeaseActive"))
    assertTrue(scheduler.contains("TicketProofStreamCleanupDecision.RETRY -> scheduleClientDisconnectGraceLocked("))
    assertTrue(scheduler.contains("TicketProofStreamCleanupDecision.STOP -> noteClientDetachedLocked(stopReason)"))
    assertTrue(socket.contains("clientDisconnectStopJob?.cancel()"))
    assertTrue(socket.contains("clientDisconnectStopJob = null"))
    assertTrue(inactivity.contains("!ticketProofStreamAutomationOwnershipActive()"))
    assertTrue(captureDemand.contains("viviReauthCaptureLeaseActive"))
    assertTrue(captureDemand.contains("ticketVisualActionJobOwnershipActive"))
  }

  private fun body(text: String, startNeedle: String, endNeedle: String): String {
    val start = text.indexOf(startNeedle)
    assertTrue("missing $startNeedle", start >= 0)
    val end = text.indexOf(endNeedle, start + startNeedle.length)
    assertTrue("missing $endNeedle", end >= 0)
    return text.substring(start, end)
  }

  private fun source(relative: String): String {
    val roots = listOf(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/$relative"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/$relative")
    )
    val path = roots.firstOrNull(Files::exists) ?: error("Missing source: $relative")
    return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }
}
