package lv.jolkins.pixelorchestrator.app

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverActionPolicyTest {
  @Test
  fun bootCompletedStartsRecoveryAndReschedulesWake() {
    val decision = BootReceiverActionPolicy.decisionFor(Intent.ACTION_BOOT_COMPLETED)

    assertEquals(SupervisorService.ACTION_BOOT_RECOVER, decision.supervisorAction)
    assertTrue(decision.shouldRescheduleWake)
  }

  @Test
  fun packageReplaceRefreshesAutomationAndReschedulesWake() {
    val decision = BootReceiverActionPolicy.decisionFor(Intent.ACTION_MY_PACKAGE_REPLACED)

    assertEquals(SupervisorService.ACTION_REFRESH_PHONE_AUTOMATION, decision.supervisorAction)
    assertTrue(decision.shouldRescheduleWake)
  }

  @Test
  fun timeChangeReschedulesWakeWithoutStartingSupervisor() {
    val decision = BootReceiverActionPolicy.decisionFor(BootReceiver.ACTION_TIME_SET)

    assertNull(decision.supervisorAction)
    assertTrue(decision.shouldRescheduleWake)
  }

  @Test
  fun timezoneChangeReschedulesWakeWithoutStartingSupervisor() {
    val decision = BootReceiverActionPolicy.decisionFor(Intent.ACTION_TIMEZONE_CHANGED)

    assertNull(decision.supervisorAction)
    assertTrue(decision.shouldRescheduleWake)
  }

  @Test
  fun unknownActionIsIgnored() {
    val decision = BootReceiverActionPolicy.decisionFor("custom.intent.UNKNOWN")

    assertNull(decision.supervisorAction)
    assertFalse(decision.shouldRescheduleWake)
  }
}
