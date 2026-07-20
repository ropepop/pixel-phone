package lv.jolkins.pixelorchestrator.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal class WeeklyCleanupScheduler(
  context: Context
) {
  private val appContext = context.applicationContext

  fun scheduleNext(reason: String): CleanupScheduleResult {
    val alarmManager = appContext.getSystemService(AlarmManager::class.java)
      ?: return failure("AlarmManager unavailable for cleanup scheduling ($reason)")

    val nextRunMillis =
      WeeklyCleanupSchedulePolicy.nextRunAfter(
        nowMillis = System.currentTimeMillis(),
        zoneId = ZoneId.systemDefault()
      )
    val pendingIntent = cleanupPendingIntent(buildRunId(nextRunMillis))
    alarmManager.cancel(pendingIntent)
    val mode = WeeklyCleanupAlarmPolicy.mode(
      sdkInt = Build.VERSION.SDK_INT,
      canScheduleExactAlarms = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
    )
    when (mode) {
      CleanupScheduleMode.EXACT_IDLE ->
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextRunMillis, pendingIntent)
      CleanupScheduleMode.APPROXIMATE_IDLE ->
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextRunMillis, pendingIntent)
      CleanupScheduleMode.NONE -> return failure("No supported cleanup alarm mode ($reason)")
    }
    Log.i(
      TAG,
      "cleanup_schedule_armed reason=$reason mode=${mode.name.lowercase()} next_run=${Instant.ofEpochMilli(nextRunMillis)} zone=${ZoneId.systemDefault().id}"
    )
    return CleanupScheduleResult(
      success = true,
      scheduledAtMillis = nextRunMillis,
      mode = mode,
      reason = if (mode == CleanupScheduleMode.APPROXIMATE_IDLE) "exact_alarm_permission_unavailable" else ""
    )
  }

  private fun cleanupPendingIntent(pixelRunId: String): PendingIntent {
    val intent = Intent(appContext, SupervisorService::class.java).apply {
      action = SupervisorService.ACTION_CLEANUP
      putExtra(OrchestratorShellCommand.EXTRA_ACTION, OrchestratorShellCommand.ACTION_CLEANUP)
      putExtra(OrchestratorShellCommand.EXTRA_PIXEL_RUN_ID, pixelRunId)
      putExtra(SupervisorService.EXTRA_CLEANUP_DRY_RUN, false)
      putExtra(SupervisorService.EXTRA_CLEANUP_TRIGGER, CleanupTrigger.SCHEDULED.wireValue())
    }
    return PendingIntent.getForegroundService(
      appContext,
      CLEANUP_REQUEST_CODE,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
  }

  private fun buildRunId(nextRunMillis: Long): String {
    val stamp = RUN_ID_FORMATTER.format(Instant.ofEpochMilli(nextRunMillis))
    val nonce = java.lang.Long.toHexString(System.nanoTime()).takeLast(6).padStart(6, '0')
    return "cleanup-$stamp-$nonce"
  }

  private fun failure(reason: String): CleanupScheduleResult {
    Log.e(TAG, reason)
    return CleanupScheduleResult(success = false, reason = reason)
  }

  private companion object {
    const val CLEANUP_REQUEST_CODE = 4003
    val RUN_ID_FORMATTER: DateTimeFormatter =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US).withZone(java.time.ZoneOffset.UTC)
    const val TAG = "WeeklyCleanupScheduler"
  }
}

internal object WeeklyCleanupAlarmPolicy {
  fun mode(sdkInt: Int, canScheduleExactAlarms: Boolean): CleanupScheduleMode {
    return if (sdkInt < Build.VERSION_CODES.S || canScheduleExactAlarms) {
      CleanupScheduleMode.EXACT_IDLE
    } else {
      CleanupScheduleMode.APPROXIMATE_IDLE
    }
  }
}
