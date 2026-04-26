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

object NotificationHelper {
  private const val CHANNEL_ID = "stack_supervision"

  fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return
    }

    val manager = context.getSystemService(NotificationManager::class.java)
    val existing = manager.getNotificationChannel(CHANNEL_ID)
    if (existing != null) {
      return
    }

    val channel = NotificationChannel(
      CHANNEL_ID,
      context.getString(R.string.notif_channel_name),
      NotificationManager.IMPORTANCE_LOW
    ).apply {
      description = context.getString(R.string.notif_channel_description)
    }

    manager.createNotificationChannel(channel)
  }

  fun buildForegroundNotification(context: Context): Notification {
    val intent = Intent(context, MainActivity::class.java)
    val pending = PendingIntent.getActivity(
      context,
      1001,
      intent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    return NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_notify_sync)
      .setContentTitle(context.getString(R.string.notif_title))
      .setContentText(context.getString(R.string.notif_content))
      .setContentIntent(pending)
      .setOngoing(true)
      .build()
  }
}
