package lv.jolkins.pixelorchestrator.app.phoneautomation

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class PhoneAutomationNotificationListenerService : NotificationListenerService() {
  override fun onListenerConnected() {
    super.onListenerConnected()
    PhoneAutomationServiceBridge.setNotificationListenerConnected(true)
    PhoneAutomationServiceBridge.replaceActiveNotifications(
      activeNotifications.orEmpty().map(::toObservedNotification)
    )
  }

  override fun onListenerDisconnected() {
    PhoneAutomationServiceBridge.setNotificationListenerConnected(false)
    super.onListenerDisconnected()
  }

  override fun onNotificationPosted(sbn: StatusBarNotification) {
    PhoneAutomationServiceBridge.recordPosted(toObservedNotification(sbn))
  }

  override fun onNotificationRemoved(sbn: StatusBarNotification) {
    PhoneAutomationServiceBridge.recordRemoved(toObservedNotification(sbn))
  }

  private fun toObservedNotification(sbn: StatusBarNotification): PhoneAutomationObservedNotification {
    val extras = sbn.notification.extras
    return PhoneAutomationObservedNotification(
      key = sbn.key,
      packageName = sbn.packageName.orEmpty(),
      channelId = sbn.notification.channelId,
      title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
      text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
      actionTitles = sbn.notification.actions
        ?.mapNotNull { action -> action.title?.toString()?.takeIf(String::isNotBlank) }
        .orEmpty(),
      postedAtMillis = sbn.postTime,
      ongoing = sbn.isOngoing
    )
  }
}
