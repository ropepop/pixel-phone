package lv.jolkins.pixelorchestrator.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    val action = intent?.action.orEmpty()
    val scheduler = NightlyCleanupScheduler(context)
    val schedule = scheduler.scheduleNext(reason = "receiver:$action")
    if (!schedule.success) {
      Log.e(TAG, "cleanup_schedule_failed action=$action detail=${schedule.reason}")
    }

    when (intent?.action) {
      Intent.ACTION_BOOT_COMPLETED -> {
        SupervisorService.start(
          context = context,
          action = SupervisorService.ACTION_BOOT_RECOVER,
          bootEventToken = bootEventToken(context, intent.action.orEmpty())
        )
      }
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
  }
}
