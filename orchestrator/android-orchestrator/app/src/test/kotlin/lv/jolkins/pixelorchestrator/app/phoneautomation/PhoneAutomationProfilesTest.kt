package lv.jolkins.pixelorchestrator.app.phoneautomation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAutomationProfilesTest {
  @Test
  fun cellMapperRecordingMatcherRequiresStopRecordingAction() {
    val matcher = PhoneAutomationProfiles
      .profile(PhoneAutomationApp.CELLMAPPER)
      .notificationMatchers
      .getValue(PhoneAutomationNotificationKind.CELLMAPPER_RECORDING)

    val recording = PhoneAutomationObservedNotification(
      key = "recording",
      packageName = "cellmapper.net.cellmapper",
      channelId = "CellMapper",
      title = "CellMapper",
      text = "Collecting",
      actionTitles = listOf("Stop Recording", "Exit"),
      postedAtMillis = 1L,
      ongoing = true
    )
    val notRecording = recording.copy(
      key = "not-recording",
      actionTitles = listOf("Start Recording", "Exit")
    )

    assertTrue(matcher.matches(recording))
    assertFalse(matcher.matches(notRecording))
  }
}
