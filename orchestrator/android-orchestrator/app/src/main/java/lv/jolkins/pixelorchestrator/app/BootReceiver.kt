package lv.jolkins.pixelorchestrator.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationWakeScheduler

class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    val action = intent?.action.orEmpty()
    val decision = BootReceiverActionPolicy.decisionFor(action)
    val cleanupReason = "receiver:$action"
    val scheduler = WeeklyCleanupScheduler(context)
    val schedule = scheduler.scheduleNext(reason = cleanupReason)
    if (!schedule.success) {
      Log.e(TAG, "cleanup_schedule_failed action=$action detail=${schedule.reason}")
    }
    if (decision.shouldRescheduleWake) {
      PhoneAutomationWakeScheduler.rescheduleFromStore(
        context = context,
        reason = cleanupReason,
        force = true
      )
    }

    when (decision.supervisorAction) {
      SupervisorService.ACTION_BOOT_RECOVER -> {
        SupervisorService.start(
          context = context,
          action = decision.supervisorAction,
          bootEventToken = bootEventToken(context, action)
        )
      }

      SupervisorService.ACTION_REFRESH_PHONE_AUTOMATION -> {
        SupervisorService.start(
          context = context,
          action = decision.supervisorAction
        )
      }

      null -> Unit
    }
  }

  private fun bootEventToken(context: Context, action: String): String {
    val bootCount = runCatching {
      Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    }.getOrElse { -1 }
    return if (bootCount >= 0) {
      "$action:$bootCount"
    } else {
      action
    }
  }

  companion object {
    private const val TAG = "BootReceiver"
    internal const val ACTION_TIME_SET = "android.intent.action.TIME_SET"
  }
}

internal data class BootReceiverActionDecision(
  val supervisorAction: String?,
  val shouldRescheduleWake: Boolean
)

internal object BootReceiverActionPolicy {
  fun decisionFor(action: String): BootReceiverActionDecision {
    return BootReceiverActionDecision(
      shouldRescheduleWake = action == Intent.ACTION_BOOT_COMPLETED ||
        action == Intent.ACTION_MY_PACKAGE_REPLACED ||
        action == BootReceiver.ACTION_TIME_SET ||
        action == Intent.ACTION_TIMEZONE_CHANGED,
      supervisorAction = when (action) {
        Intent.ACTION_BOOT_COMPLETED -> SupervisorService.ACTION_BOOT_RECOVER
        Intent.ACTION_MY_PACKAGE_REPLACED -> SupervisorService.ACTION_REFRESH_PHONE_AUTOMATION
        BootReceiver.ACTION_TIME_SET,
        Intent.ACTION_TIMEZONE_CHANGED -> null
        else -> null
      }
    )
  }
}
