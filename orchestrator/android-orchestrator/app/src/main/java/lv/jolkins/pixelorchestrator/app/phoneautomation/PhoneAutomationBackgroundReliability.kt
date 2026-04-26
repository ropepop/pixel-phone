package lv.jolkins.pixelorchestrator.app.phoneautomation

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

internal data class PhoneAutomationBackgroundReliability(
  val batteryUnrestricted: Boolean,
  val exactAlarmGranted: Boolean
)

internal object PhoneAutomationBackgroundReliabilitySupport {
  private const val BATTERY_RESTRICTED_DETAIL = "Unrestricted battery access is not enabled"
  private const val EXACT_ALARM_MISSING_DETAIL = "Exact alarm access is not enabled"

  fun read(context: Context): PhoneAutomationBackgroundReliability {
    val appContext = context.applicationContext
    val powerManager = appContext.getSystemService(PowerManager::class.java)
    val alarmManager = appContext.getSystemService(AlarmManager::class.java)
    return PhoneAutomationBackgroundReliability(
      batteryUnrestricted = powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) ?: false,
      exactAlarmGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        alarmManager?.canScheduleExactAlarms() == true
      } else {
        true
      }
    )
  }

  fun missingAutomationRequirements(
    reliability: PhoneAutomationBackgroundReliability
  ): List<String> {
    val missing = mutableListOf<String>()
    if (!reliability.batteryUnrestricted) {
      missing += BATTERY_RESTRICTED_DETAIL
    }
    if (!reliability.exactAlarmGranted) {
      missing += EXACT_ALARM_MISSING_DETAIL
    }
    return missing
  }

  fun missingTouchBrightnessRequirements(
    reliability: PhoneAutomationBackgroundReliability
  ): List<String> {
    return if (reliability.batteryUnrestricted) {
      emptyList()
    } else {
      listOf(BATTERY_RESTRICTED_DETAIL)
    }
  }

  fun requestBatteryUnrestrictedIntent(context: Context): Intent {
    return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
      data = packageUri(context)
    }
  }

  fun batteryOptimizationSettingsIntent(context: Context): Intent {
    return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
      data = packageUri(context)
    }
  }

  fun requestExactAlarmIntent(context: Context): Intent {
    return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
      data = packageUri(context)
    }
  }

  fun appDetailsIntent(context: Context): Intent {
    return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
      data = packageUri(context)
    }
  }

  private fun packageUri(context: Context): Uri {
    return Uri.parse("package:${context.packageName}")
  }
}
