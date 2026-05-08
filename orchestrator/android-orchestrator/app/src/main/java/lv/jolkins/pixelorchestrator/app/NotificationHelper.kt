package lv.jolkins.pixelorchestrator.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import lv.jolkins.pixelorchestrator.R
import lv.jolkins.pixelorchestrator.app.cpufrequency.CpuFrequencySettingsSnapshot
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationSettingsSnapshot
import lv.jolkins.pixelorchestrator.app.ticket.TicketServiceSettingsSnapshot

object NotificationHelper {
  private const val LEGACY_CHANNEL_ID = "stack_supervision"
  private const val CHANNEL_ID = "stack_supervision_quiet"

  fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return
    }

    val manager = context.getSystemService(NotificationManager::class.java)
    val existing = manager.getNotificationChannel(CHANNEL_ID)
    if (existing == null) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        context.getString(R.string.notif_channel_name),
        NotificationManager.IMPORTANCE_MIN
      ).apply {
        description = context.getString(R.string.notif_channel_description)
        lockscreenVisibility = Notification.VISIBILITY_SECRET
        setShowBadge(false)
        setSound(null, null)
        enableLights(false)
        enableVibration(false)
      }

      manager.createNotificationChannel(channel)
    }

    if (manager.getNotificationChannel(LEGACY_CHANNEL_ID) != null) {
      manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
    }
  }

  fun buildForegroundNotification(
    context: Context,
    automationSnapshot: PhoneAutomationSettingsSnapshot,
    cpuFrequencySnapshot: CpuFrequencySettingsSnapshot,
    ticketServiceSnapshot: TicketServiceSettingsSnapshot
  ): Notification {
    val intent = Intent(context, MainActivity::class.java)
    val pending = PendingIntent.getActivity(
      context,
      1001,
      intent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val automationSummary = automationSnapshot.notificationSummary()
    val touchBrightnessSummary = automationSnapshot.touchBrightnessNotificationSummary()
    val cpuFrequencySummary = cpuFrequencySnapshot.notificationSummary()
    val ticketServiceSummary = ticketServiceSnapshot.notificationSummary()
    val contentText = context.getString(
      R.string.notif_content,
      automationSummary,
      touchBrightnessSummary,
      cpuFrequencySummary,
      ticketServiceSummary
    )
    val bigText = context.getString(
      R.string.notif_big_text,
      automationSummary,
      touchBrightnessSummary,
      cpuFrequencySummary,
      ticketServiceSummary
    )

    return NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_notify_sync)
      .setContentTitle(context.getString(R.string.notif_title))
      .setContentText(contentText)
      .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
      .setContentIntent(pending)
      .setOngoing(true)
      .setLocalOnly(true)
      .setSilent(true)
      .setOnlyAlertOnce(true)
      .setPriority(NotificationCompat.PRIORITY_MIN)
      .setVisibility(NotificationCompat.VISIBILITY_SECRET)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .build()
  }
}
