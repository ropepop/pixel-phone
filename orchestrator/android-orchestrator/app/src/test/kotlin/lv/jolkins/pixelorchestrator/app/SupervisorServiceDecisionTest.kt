package lv.jolkins.pixelorchestrator.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SupervisorServiceDecisionTest {
  @Test
  fun duplicateBootRecoveryResumesSupervision() {
    assertEquals(
      BootRecoveryMode.RESUME_SUPERVISION,
      SupervisorService.resolveBootRecoveryMode(shouldHandleFullStart = false)
    )
  }

  @Test
  fun freshBootRecoveryStartsAllComponents() {
    assertEquals(
      BootRecoveryMode.START_ALL,
      SupervisorService.resolveBootRecoveryMode(shouldHandleFullStart = true)
    )
  }

  @Test
  fun ticketReadinessProbeIsBoundedAndStableChecksAreThrottled() {
    val sourcePath = listOf(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/SupervisorService.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/SupervisorService.kt")
    ).first(Files::exists)
    val source = String(Files.readAllBytes(sourcePath))
    val ensureStart = source.indexOf("private suspend fun ensureTicketServiceReady")
    assertTrue(ensureStart >= 0)
    val ensureEnd = source.indexOf("private suspend fun stopTicketServiceReadiness", ensureStart)
    val ensureBlock = source.substring(ensureStart, ensureEnd)
    val probeStart = source.indexOf("private suspend fun probeTicketTunnelReadiness")
    assertTrue(probeStart >= 0)
    val probeEnd = source.indexOf("private fun startPhoneAutomationPrerequisiteMonitor", probeStart)
    val probeBlock = source.substring(probeStart, probeEnd)

    assertTrue(source.contains("private const val TICKET_SERVICE_STABLE_RECHECK_MILLIS = 2 * 60 * 1_000L"))
    assertTrue(source.contains("private fun shouldSkipStableTicketEnsure("))
    assertTrue(ensureBlock.contains("shouldSkipStableTicketEnsure(reason, current)"))
    assertTrue(ensureBlock.indexOf("shouldSkipStableTicketEnsure(reason, current)") < ensureBlock.indexOf("facade.startComponent(TICKET_SERVICE_COMPONENT)"))
    assertTrue(probeBlock.contains("timeout 1 tr '\\000' ' '"))
    assertTrue(!probeBlock.contains("\n            tr '\\000' ' '"))
  }
}
