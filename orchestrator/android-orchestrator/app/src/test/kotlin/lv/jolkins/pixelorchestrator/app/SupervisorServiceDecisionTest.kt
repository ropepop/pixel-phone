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

  @Test
  fun supervisorContinuouslyMaintainsPortraitLock() {
    val sourcePath = listOf(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/SupervisorService.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/SupervisorService.kt")
    ).first(Files::exists)
    val source = String(Files.readAllBytes(sourcePath))
    val onCreate = source.substringBetween("override fun onCreate()", "override fun onStartCommand")
    val onDestroy = source.substringBetween("override fun onDestroy()", "override fun onTimeout")
    val maintenance = source.substringBetween("private fun startPortraitLockMaintenance()", "  companion object")

    assertTrue(source.contains("private var portraitLockMaintenanceJob: Job? = null"))
    assertTrue(onCreate.contains("startPortraitLockMaintenance()"))
    assertTrue(onDestroy.contains("portraitLockMaintenanceJob?.cancel()"))
    assertTrue(maintenance.contains("while (isActive)"))
    assertTrue(maintenance.contains("PhonePortraitLock.force(executor)"))
    assertTrue(maintenance.contains("PhonePortraitLock.verify(executor)"))
    assertTrue(maintenance.contains("delay(PORTRAIT_LOCK_MAINTENANCE_INTERVAL_MILLIS)"))
    assertTrue(source.contains("private const val PORTRAIT_LOCK_MAINTENANCE_INTERVAL_MILLIS = 10_000L"))
    assertTrue(!source.contains("USER_ROTATION_FREE"))
    assertTrue(!source.contains("accelerometer_rotation 1"))
    assertTrue(!source.contains("set-ignore-orientation-request false"))
  }

  private fun String.substringBetween(startNeedle: String, endNeedle: String): String {
    val start = indexOf(startNeedle)
    assertTrue("missing start needle: $startNeedle", start >= 0)
    val end = indexOf(endNeedle, start + startNeedle.length)
    assertTrue("missing end needle: $endNeedle", end >= 0)
    return substring(start, end)
  }
}
