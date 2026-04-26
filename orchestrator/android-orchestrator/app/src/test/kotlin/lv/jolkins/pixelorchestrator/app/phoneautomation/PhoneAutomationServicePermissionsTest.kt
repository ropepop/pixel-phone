package lv.jolkins.pixelorchestrator.app.phoneautomation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAutomationServicePermissionsTest {
  @Test
  fun mergeEnabledServicesAddsMissingComponent() {
    val merged = PhoneAutomationServicePermissions.mergeEnabledServices(
      currentValue = "com.example/.ReaderService:com.example/.WriterService",
      componentName = "lv.jolkins.pixelorchestrator/.PhoneAutomationAccessibilityService"
    )

    assertEquals(
      "com.example/.ReaderService:com.example/.WriterService:lv.jolkins.pixelorchestrator/.PhoneAutomationAccessibilityService",
      merged
    )
  }

  @Test
  fun mergeEnabledServicesIgnoresNullAndWhitespace() {
    val merged = PhoneAutomationServicePermissions.mergeEnabledServices(
      currentValue = " null :  ",
      componentName = "lv.jolkins.pixelorchestrator/.PhoneAutomationAccessibilityService"
    )

    assertEquals("lv.jolkins.pixelorchestrator/.PhoneAutomationAccessibilityService", merged)
  }

  @Test
  fun mergeEnabledServicesDoesNotDuplicateExistingComponent() {
    val merged = PhoneAutomationServicePermissions.mergeEnabledServices(
      currentValue = "lv.jolkins.pixelorchestrator/.PhoneAutomationAccessibilityService:com.example/.ReaderService",
      componentName = "lv.jolkins.pixelorchestrator/.PhoneAutomationAccessibilityService"
    )

    assertEquals(
      "lv.jolkins.pixelorchestrator/.PhoneAutomationAccessibilityService:com.example/.ReaderService",
      merged
    )
  }

  @Test
  fun removeEnabledServiceOnlyRemovesTheRequestedAccessibilityEntry() {
    val component = PhoneAutomationServiceComponent(
      fullName = "lv.jolkins.pixelorchestrator/lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationAccessibilityService",
      shortName = "lv.jolkins.pixelorchestrator/.PhoneAutomationAccessibilityService"
    )

    val removed = PhoneAutomationServicePermissions.removeEnabledService(
      currentValue = "com.example/.ReaderService:lv.jolkins.pixelorchestrator/.PhoneAutomationAccessibilityService:com.example/.WriterService",
      componentName = component
    )

    assertEquals("com.example/.ReaderService:com.example/.WriterService", removed)
  }

  @Test
  fun removeAndReAddPreservesOtherEnabledServices() {
    val component = PhoneAutomationServiceComponent(
      fullName = "lv.jolkins.pixelorchestrator/lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationAccessibilityService",
      shortName = "lv.jolkins.pixelorchestrator/.PhoneAutomationAccessibilityService"
    )

    val removed = PhoneAutomationServicePermissions.removeEnabledService(
      currentValue = "com.example/.ReaderService:lv.jolkins.pixelorchestrator/.PhoneAutomationAccessibilityService",
      componentName = component
    )
    val rebound = PhoneAutomationServicePermissions.mergeEnabledServices(
      currentValue = removed,
      componentName = component
    )

    assertTrue(
      PhoneAutomationServicePermissions.containsEnabledService(
        currentValue = rebound,
        componentName = component
      )
    )
    assertEquals(
      "com.example/.ReaderService:lv.jolkins.pixelorchestrator/lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationAccessibilityService",
      rebound
    )
  }
}
