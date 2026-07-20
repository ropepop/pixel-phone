package lv.jolkins.pixelorchestrator.app

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class WeeklyCleanupAlarmPolicyTest {
  @Test
  fun usesExactIdleWhenPermissionIsAvailable() {
    assertEquals(
      CleanupScheduleMode.EXACT_IDLE,
      WeeklyCleanupAlarmPolicy.mode(Build.VERSION_CODES.S, canScheduleExactAlarms = true)
    )
  }

  @Test
  fun fallsBackToApproximateIdleWhenExactPermissionIsUnavailable() {
    assertEquals(
      CleanupScheduleMode.APPROXIMATE_IDLE,
      WeeklyCleanupAlarmPolicy.mode(Build.VERSION_CODES.S, canScheduleExactAlarms = false)
    )
  }

  @Test
  fun preAndroidTwelveDoesNotRequireTheExactAlarmPermissionCheck() {
    assertEquals(
      CleanupScheduleMode.EXACT_IDLE,
      WeeklyCleanupAlarmPolicy.mode(Build.VERSION_CODES.R, canScheduleExactAlarms = false)
    )
  }
}
