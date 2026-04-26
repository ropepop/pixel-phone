package lv.jolkins.pixelorchestrator.app.phoneautomation

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAutomationPreparationTest {
  @Test
  fun speedtestOnlyModeSucceedsWithoutCellMapperInstalled() {
    val missingRequirements = buildPreparationMissingRequirements(
      mode = PhoneAutomationMode.SPEEDTEST_ONLY,
      hasRequiredSelectors = true,
      rootAvailable = true,
      targets = PhoneAutomationResolvedTargets(
        speedtest = fakeTarget(PhoneAutomationApp.SPEEDTEST),
        cellMapper = null
      ),
      accessibilityPermissionEnabled = true,
      notificationAccessPermissionEnabled = true,
      reliability = PhoneAutomationBackgroundReliability(
        batteryUnrestricted = true,
        exactAlarmGranted = true
      )
    )

    assertTrue(missingRequirements.isEmpty())
  }

  @Test
  fun fullModeStillRequiresCellMapperInstalled() {
    val missingRequirements = buildPreparationMissingRequirements(
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      hasRequiredSelectors = true,
      rootAvailable = true,
      targets = PhoneAutomationResolvedTargets(
        speedtest = fakeTarget(PhoneAutomationApp.SPEEDTEST),
        cellMapper = null
      ),
      accessibilityPermissionEnabled = true,
      notificationAccessPermissionEnabled = true,
      reliability = PhoneAutomationBackgroundReliability(
        batteryUnrestricted = true,
        exactAlarmGranted = true
      )
    )

    assertEquals(listOf("CellMapper is not installed"), missingRequirements)
  }

  @Test
  fun automationPreparationIncludesReliabilityRequirements() {
    val missingRequirements = buildPreparationMissingRequirements(
      mode = PhoneAutomationMode.SPEEDTEST_ONLY,
      hasRequiredSelectors = true,
      rootAvailable = true,
      targets = PhoneAutomationResolvedTargets(
        speedtest = fakeTarget(PhoneAutomationApp.SPEEDTEST),
        cellMapper = null
      ),
      accessibilityPermissionEnabled = true,
      notificationAccessPermissionEnabled = true,
      reliability = PhoneAutomationBackgroundReliability(
        batteryUnrestricted = false,
        exactAlarmGranted = false
      )
    )

    assertEquals(
      listOf(
        "Unrestricted battery access is not enabled",
        "Exact alarm access is not enabled"
      ),
      missingRequirements
    )
  }

  private fun fakeTarget(app: PhoneAutomationApp): PhoneAutomationResolvedTarget {
    val profile = PhoneAutomationProfiles.profile(app)
    return PhoneAutomationResolvedTarget(
      profile = profile,
      packageName = profile.packageCandidates.first(),
      launcherLabel = profile.displayName,
      versionName = "1.0",
      launchIntent = Intent(Intent.ACTION_MAIN)
    )
  }
}
