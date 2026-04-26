package lv.jolkins.pixelorchestrator.app.phoneautomation

enum class PhoneAutomationApp {
  SPEEDTEST,
  CELLMAPPER
}

enum class PhoneAutomationNotificationKind {
  SPEEDTEST_COMPLETED,
  SPEEDTEST_RUNNING,
  CELLMAPPER_STATUS,
  CELLMAPPER_RECORDING
}

enum class PhoneAutomationSelectorKind {
  SPEEDTEST_START,
  SPEEDTEST_RETRY,
  SPEEDTEST_CONNECTING,
  CELLMAPPER_RECORD_TOGGLE
}

data class PhoneAutomationSelector(
  val resourceId: String? = null,
  val text: String? = null,
  val contentDescription: String? = null
) {
  fun matches(
    nodeText: String,
    nodeResourceId: String,
    nodeContentDescription: String
  ): Boolean {
    val resourceMatches = resourceId == null || nodeResourceId == resourceId
    val textMatches = text == null || nodeText == text
    val descriptionMatches = contentDescription == null || nodeContentDescription == contentDescription
    return resourceMatches && textMatches && descriptionMatches
  }
}

data class PhoneAutomationNotificationMatcher(
  val packageCandidates: List<String>,
  val channelId: String? = null,
  val titleContains: String? = null,
  val actionTitleContains: String? = null
) {
  fun matches(notification: PhoneAutomationObservedNotification): Boolean {
    val packageMatches = notification.packageName in packageCandidates
    val channelMatches = channelId == null || notification.channelId == channelId
    val titleMatches = titleContains == null || notification.title.contains(titleContains, ignoreCase = true)
    val actionMatches = actionTitleContains == null ||
      notification.actionTitles.any { actionTitle -> actionTitle.contains(actionTitleContains, ignoreCase = true) }
    return packageMatches && channelMatches && titleMatches && actionMatches
  }
}

data class PhoneAutomationTargetProfile(
  val app: PhoneAutomationApp,
  val displayName: String,
  val packageCandidates: List<String>,
  val launcherLabelKeywords: List<String>,
  val notificationMatchers: Map<PhoneAutomationNotificationKind, PhoneAutomationNotificationMatcher>,
  val selectors: Map<PhoneAutomationSelectorKind, List<PhoneAutomationSelector>>
)

object PhoneAutomationProfiles {
  // Verified on the attached Pixel 9a on 2026-04-18 using live `uiautomator dump` output.
  private val speedtestProfile = PhoneAutomationTargetProfile(
    app = PhoneAutomationApp.SPEEDTEST,
    displayName = "Speedtest by Ookla",
    packageCandidates = listOf("org.zwanoo.android.speedtest"),
    launcherLabelKeywords = listOf("speedtest", "ookla"),
    notificationMatchers = mapOf(
      PhoneAutomationNotificationKind.SPEEDTEST_COMPLETED to PhoneAutomationNotificationMatcher(
        packageCandidates = listOf("org.zwanoo.android.speedtest"),
        channelId = "EotNotificationChannel",
        titleContains = "Test Complete"
      ),
      PhoneAutomationNotificationKind.SPEEDTEST_RUNNING to PhoneAutomationNotificationMatcher(
        packageCandidates = listOf("org.zwanoo.android.speedtest"),
        channelId = "SpeedtestRunningChannel"
      )
    ),
    selectors = mapOf(
      PhoneAutomationSelectorKind.SPEEDTEST_START to listOf(
        PhoneAutomationSelector(
          resourceId = "org.zwanoo.android.speedtest:id/go_button",
          contentDescription = "Start a Speedtest"
        )
      ),
      PhoneAutomationSelectorKind.SPEEDTEST_RETRY to listOf(
        PhoneAutomationSelector(
          resourceId = "org.zwanoo.android.speedtest:id/suite_completed_feedback_assembly_test_again",
          text = "Test Again"
        )
      ),
      PhoneAutomationSelectorKind.SPEEDTEST_CONNECTING to listOf(
        PhoneAutomationSelector(
          resourceId = "org.zwanoo.android.speedtest:id/connecting_button"
        )
      )
    )
  )

  private val cellMapperProfile = PhoneAutomationTargetProfile(
    app = PhoneAutomationApp.CELLMAPPER,
    displayName = "CellMapper",
    packageCandidates = listOf("cellmapper.net.cellmapper"),
    launcherLabelKeywords = listOf("cellmapper"),
    notificationMatchers = mapOf(
      PhoneAutomationNotificationKind.CELLMAPPER_STATUS to PhoneAutomationNotificationMatcher(
        packageCandidates = listOf("cellmapper.net.cellmapper"),
        channelId = "CellMapper"
      ),
      PhoneAutomationNotificationKind.CELLMAPPER_RECORDING to PhoneAutomationNotificationMatcher(
        packageCandidates = listOf("cellmapper.net.cellmapper"),
        channelId = "CellMapper",
        actionTitleContains = "Stop Recording"
      )
    ),
    selectors = mapOf(
      PhoneAutomationSelectorKind.CELLMAPPER_RECORD_TOGGLE to listOf(
        PhoneAutomationSelector(
          resourceId = "cellmapper.net.cellmapper:id/menu_actionbar_record_toggle",
          contentDescription = "Record Data"
        )
      )
    )
  )

  private val profiles = mapOf(
    PhoneAutomationApp.SPEEDTEST to speedtestProfile,
    PhoneAutomationApp.CELLMAPPER to cellMapperProfile
  )

  fun profile(app: PhoneAutomationApp): PhoneAutomationTargetProfile = profiles.getValue(app)

  fun hasRequiredSelectors(mode: PhoneAutomationMode): Boolean {
    val requiredApps = if (mode.maintainsCellMapper) {
      profiles.keys
    } else {
      setOf(PhoneAutomationApp.SPEEDTEST)
    }
    return requiredApps.all { app ->
      val profile = profiles.getValue(app)
      profile.selectors.values.all { selectors -> selectors.isNotEmpty() }
    }
  }
}
