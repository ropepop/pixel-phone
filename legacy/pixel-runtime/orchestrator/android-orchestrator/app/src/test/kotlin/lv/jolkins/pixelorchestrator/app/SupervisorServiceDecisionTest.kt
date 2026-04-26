package lv.jolkins.pixelorchestrator.app

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
