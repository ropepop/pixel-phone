package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.test.runTest
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationSelector
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationVisibleNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class RigasSatiksmeSemanticDriverTest {
  @Test
  fun successfulFlowUsesSemanticClicksAndTextEntry() = runTest {
    val gateway = FakeRigasSatiksmeGateway(
      snapshots = ArrayDeque(
        listOf(
          nodes("REGISTER A TRIP", "Monthly ticket 1 month"),
          nodes("ENTER THE CODE MANUALLY"),
          nodes("Control code", editable = true),
          nodes("Trip is registered", "OK"),
          nodes("TICKET FOR CONTROL", "qr code", "58011", "1 month")
        )
      )
    )

    val result = RigasSatiksmeSemanticDriver(gateway).run("58011")

    assertTrue(result.ok)
    assertEquals("generated", result.reason)
    assertEquals(
      listOf("REGISTER A TRIP", "ENTER THE CODE MANUALLY", "CONFIRM", "OK", "TICKET FOR CONTROL"),
      gateway.clickedTextContains
    )
    assertEquals(listOf("58011"), gateway.enteredText)
    assertFalse(gateway.actions.any { it.contains("input tap") })
  }

  @Test
  fun initialMatchingControlTicketIsResetAndFreshlyRegistered() = runTest {
    val gateway = FakeRigasSatiksmeGateway(
      snapshots = ArrayDeque(
        listOf(
          nodes("TICKET FOR CONTROL", "qr code", "58011", "1 month"),
          nodes("REGISTER A TRIP", "Monthly ticket 1 month"),
          nodes("ENTER THE CODE MANUALLY"),
          nodes("Control code", editable = true),
          nodes("Trip is registered", "OK"),
          nodes("TICKET FOR CONTROL", "qr code", "58011", "1 month")
        )
      )
    )

    val result = RigasSatiksmeSemanticDriver(gateway).run("58011")

    assertTrue(result.ok)
    assertEquals("generated", result.reason)
    assertEquals("back", gateway.actions.first())
    assertEquals(listOf("58011"), gateway.enteredText)
    assertEquals(1, gateway.clickedTextContains.count { it == "REGISTER A TRIP" })
  }

  @Test
  fun unavailableAutomationFailsBeforeLaunchingRs() = runTest {
    val gateway = FakeRigasSatiksmeGateway(automationReady = false)

    val result = RigasSatiksmeSemanticDriver(gateway).run("58011")

    assertFalse(result.ok)
    assertEquals("rs_phone_automation_unavailable", result.reason)
    assertFalse(gateway.launched)
  }

  @Test
  fun wrongCodeIsPreservedAsSpecificFailure() = runTest {
    val gateway = FakeRigasSatiksmeGateway(
      snapshots = ArrayDeque(
        listOf(
          nodes("Control code", editable = true),
          nodes("Wrong code")
        )
      )
    )

    val result = RigasSatiksmeSemanticDriver(gateway).run("12345")

    assertFalse(result.ok)
    assertEquals("code_rejected_by_rs", result.reason)
  }

  @Test
  fun missingMonthlyTicketIsSpecificFailureOnlyFromExplicitEmptyTicketList() = runTest {
    val gateway = FakeRigasSatiksmeGateway(
      snapshots = ArrayDeque(
        listOf(nodes("REGISTER A TRIP", "No active tickets"))
      )
    )

    val result = RigasSatiksmeSemanticDriver(gateway).run("58011")

    assertFalse(result.ok)
    assertEquals("rs_monthly_ticket_missing", result.reason)
  }

  @Test
  fun unknownScreenFailsWithUnknownStateInsteadOfGenericTimeout() = runTest {
    val gateway = FakeRigasSatiksmeGateway(
      snapshots = ArrayDeque(List(8) { nodes("Loading") })
    )

    val result = RigasSatiksmeSemanticDriver(gateway, maxStateAttempts = 4).run("58011")

    assertFalse(result.ok)
    assertEquals("rs_monthly_ticket_unknown_state", result.reason)
    assertEquals(1, gateway.actions.count { it == "back" })
  }

  @Test
  fun staleControlTicketIsResetBeforeEnteringNextCode() = runTest {
    val gateway = FakeRigasSatiksmeGateway(
      snapshots = ArrayDeque(
        listOf(
          nodes("TICKET FOR CONTROL", "qr code", "11111", "1 month"),
          nodes("REGISTER A TRIP", "Monthly ticket 1 month"),
          nodes("ENTER THE CODE MANUALLY"),
          nodes("Control code", editable = true),
          nodes("Trip is registered", "OK"),
          nodes("TICKET FOR CONTROL", "qr code", "58011", "1 month")
        )
      )
    )

    val result = RigasSatiksmeSemanticDriver(gateway).run("58011")

    assertTrue(result.ok)
    assertEquals("generated", result.reason)
    assertTrue("stale control screen should be backed out before reuse", gateway.actions.first() == "back")
    assertEquals(listOf("58011"), gateway.enteredText)
  }

  @Test
  fun staleControlTicketResetWaitsInsteadOfDoubleBackingDuringSlowTransition() = runTest {
    val gateway = FakeRigasSatiksmeGateway(
      snapshots = ArrayDeque(
        listOf(
          nodes("TICKET FOR CONTROL", "qr code", "11111", "1 month"),
          nodes("TICKET FOR CONTROL", "qr code", "11111", "1 month"),
          nodes("REGISTER A TRIP", "Monthly ticket 1 month"),
          nodes("ENTER THE CODE MANUALLY"),
          nodes("Control code", editable = true),
          nodes("Trip is registered", "OK"),
          nodes("TICKET FOR CONTROL", "qr code", "58011", "1 month")
        )
      )
    )

    val result = RigasSatiksmeSemanticDriver(gateway).run("58011")

    assertTrue(result.ok)
    assertEquals("generated", result.reason)
    assertEquals(1, gateway.actions.count { it == "back" })
    assertEquals(listOf("58011"), gateway.enteredText)
  }

  @Test
  fun emptyRsHierarchyRelaunchesAppInsteadOfBackingAgain() = runTest {
    val gateway = FakeRigasSatiksmeGateway(
      snapshots = ArrayDeque(
        listOf(
          emptyList(),
          nodes("REGISTER A TRIP", "Monthly ticket 1 month"),
          nodes("ENTER THE CODE MANUALLY"),
          nodes("Control code", editable = true),
          nodes("Trip is registered", "OK"),
          nodes("TICKET FOR CONTROL", "qr code", "58011", "1 month")
        )
      )
    )

    val result = RigasSatiksmeSemanticDriver(gateway).run("58011")

    assertTrue(result.ok)
    assertEquals("generated", result.reason)
    assertEquals(2, gateway.launchCount)
    assertFalse(gateway.actions.contains("back"))
  }

  @Test
  fun emptyRsHierarchyAfterSubmitIsTreatedAsTransientInsteadOfRelaunching() = runTest {
    val gateway = FakeRigasSatiksmeGateway(
      snapshots = ArrayDeque(
        listOf(
          nodes("REGISTER A TRIP", "Monthly ticket 1 month"),
          nodes("ENTER THE CODE MANUALLY"),
          nodes("Control code", editable = true),
          emptyList(),
          nodes("ENTER THE CODE MANUALLY")
        )
      )
    )

    val result = RigasSatiksmeSemanticDriver(gateway, maxStateAttempts = 6).run("58011")

    assertFalse(result.ok)
    assertEquals("code_rejected_by_rs", result.reason)
    assertEquals(1, gateway.launchCount)
    assertEquals(1, gateway.enteredText.count { it == "58011" })
  }

  @Test
  fun staleControlTicketAfterSubmittedCodeIsRecoveredBeforeFinalMismatch() = runTest {
    val gateway = FakeRigasSatiksmeGateway(
      snapshots = ArrayDeque(
        listOf(
          nodes("REGISTER A TRIP", "Monthly ticket 1 month"),
          nodes("ENTER THE CODE MANUALLY"),
          nodes("Control code", editable = true),
          nodes("Trip is registered", "OK"),
          nodes("TICKET FOR CONTROL", "qr code", "11111", "1 month"),
          nodes("TICKET FOR CONTROL", "qr code", "11111", "1 month"),
          nodes("TICKET FOR CONTROL", "qr code", "58011", "1 month")
        )
      )
    )

    val result = RigasSatiksmeSemanticDriver(gateway).run("58011")

    assertTrue(result.ok)
    assertEquals("generated", result.reason)
    assertEquals(listOf("58011"), gateway.enteredText)
    assertFalse("post-submit stale proof should not reset the whole app flow", gateway.actions.contains("back"))
  }

  @Test
  fun ticketListWithoutRegisterTripUsesSemanticNavigationBeforeFailing() = runTest {
    val gateway = FakeRigasSatiksmeGateway(
      snapshots = ArrayDeque(
        listOf(
          nodes("Tickets", "1 month", "Monthly ticket"),
          nodes("REGISTER A TRIP", "Monthly ticket 1 month"),
          nodes("ENTER THE CODE MANUALLY"),
          nodes("Control code", editable = true),
          nodes("Trip is registered", "OK"),
          nodes("TICKET FOR CONTROL", "qr code", "58011", "1 month")
        )
      )
    )

    val result = RigasSatiksmeSemanticDriver(gateway).run("58011")

    assertTrue(result.ok)
    assertEquals("generated", result.reason)
    assertEquals("Tickets", gateway.clickedTextContains.first())
    assertEquals(listOf("58011"), gateway.enteredText)
  }

  @Test
  fun transientRegisterTripActivationFailureRetriesBoundedSemanticClick() = runTest {
    val gateway = FakeRigasSatiksmeGateway(
      snapshots = ArrayDeque(
        listOf(
          nodes("REGISTER A TRIP", "Monthly ticket 1 month"),
          nodes("REGISTER A TRIP", "Monthly ticket 1 month"),
          nodes("ENTER THE CODE MANUALLY"),
          nodes("Control code", editable = true),
          nodes("Trip is registered", "OK"),
          nodes("TICKET FOR CONTROL", "qr code", "58011", "1 month")
        )
      ),
      clickResults = ArrayDeque(listOf(false, true, true, true, true, true))
    )

    val result = RigasSatiksmeSemanticDriver(gateway).run("58011")

    assertTrue(result.ok)
    assertEquals("generated", result.reason)
    assertEquals(2, gateway.clickedTextContains.count { it == "REGISTER A TRIP" })
  }

  @Test
  fun repeatedTripRegisteredModalUsesBackEscapeBeforeTimingOut() = runTest {
    val gateway = FakeRigasSatiksmeGateway(
      snapshots = ArrayDeque(
        listOf(
          nodes("Trip is registered", "Ok"),
          nodes("Trip is registered", "Ok"),
          nodes("TICKET FOR CONTROL", "qr code", "58011", "1 month")
        )
      )
    )

    val result = RigasSatiksmeSemanticDriver(gateway).run("58011")

    assertTrue(result.ok)
    assertEquals("generated", result.reason)
    assertTrue(gateway.actions.contains("back"))
  }

  @Test
  fun repeatedRegisterTripScreenFailsQuicklyWithSpecificReason() = runTest {
    val gateway = FakeRigasSatiksmeGateway(
      snapshots = ArrayDeque(List(8) { nodes("REGISTER A TRIP", "Monthly ticket 1 month") })
    )

    val result = RigasSatiksmeSemanticDriver(gateway).run("58011")

    assertFalse(result.ok)
    assertEquals("rs_register_trip_missing", result.reason)
    assertEquals(4, gateway.clickedTextContains.count { it == "REGISTER A TRIP" })
  }

  @Test
  fun repeatedManualCodeChoiceAfterSubmitFailsAsCodeRejectedWithoutRetryingEntry() = runTest {
    val gateway = FakeRigasSatiksmeGateway(
      snapshots = ArrayDeque(
        listOf(
          nodes("ENTER THE CODE MANUALLY"),
          nodes("Control code", editable = true),
          nodes("ENTER THE CODE MANUALLY"),
          nodes("Control code", editable = true),
          nodes("ENTER THE CODE MANUALLY"),
          nodes("ENTER THE CODE MANUALLY")
        )
      )
    )

    val result = RigasSatiksmeSemanticDriver(gateway).run("58011")

    assertFalse(result.ok)
    assertEquals("code_rejected_by_rs", result.reason)
    assertEquals(1, gateway.clickedTextContains.count { it == "ENTER THE CODE MANUALLY" })
  }

  @Test
  fun singleManualCodeChoiceAfterSubmitFailsAsCodeRejectedWithoutWaitingForSecondEcho() = runTest {
    val gateway = FakeRigasSatiksmeGateway(
      snapshots = ArrayDeque(
        listOf(
          nodes("REGISTER A TRIP", "Monthly ticket 1 month"),
          nodes("ENTER THE CODE MANUALLY"),
          nodes("Control code", editable = true),
          nodes("ENTER THE CODE MANUALLY")
        )
      )
    )

    val result = RigasSatiksmeSemanticDriver(gateway, maxStateAttempts = 5).run("58011")

    assertFalse(result.ok)
    assertEquals("code_rejected_by_rs", result.reason)
    assertEquals(1, gateway.enteredText.count { it == "58011" })
    assertEquals(1, gateway.clickedTextContains.count { it == "ENTER THE CODE MANUALLY" })
  }

  @Test
  fun productionGatewayUsesAccessibilityClickBeforeSemanticGestureFallback() {
    val source = readFirstExisting(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/RigasSatiksmeSemanticDriver.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/RigasSatiksmeSemanticDriver.kt")
    )
    val click = source.substringBetween("override suspend fun click(", "override suspend fun openFirstEditableInput")

    assertTrue(click.indexOf("clickSelectors(") in 0 until click.indexOf("tapSelectorCenter("))
    assertTrue(click.contains("if (clicked)"))
    assertTrue(click.contains("return true"))
    assertTrue(click.contains("if (tapped)"))
    assertTrue(click.contains("return tapped"))
    assertFalse(source.contains("input tap"))
  }

  @Test
  fun productionGatewayTrustsSuccessfulAccessibilityTextBeforeShellTypingFallback() {
    val source = readFirstExisting(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/RigasSatiksmeSemanticDriver.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/RigasSatiksmeSemanticDriver.kt")
    )
    val setText = source.substringBetween("override suspend fun setTextInFirstEditableInput(", "override suspend fun performBack")

    assertTrue(setText.contains("setTextInFirstEditableInput("))
    assertTrue(setText.contains("if (accessibilityUpdated)"))
    assertTrue(setText.indexOf("return true") in 0 until setText.indexOf("typeDigitsWithInput?.invoke(text)"))
    assertTrue(setText.contains("return typeDigitsWithInput?.invoke(text) ?: false"))
  }

  @Test
  fun productionDriverKeepsPostSubmitPollingTightForFifteenSecondTarget() {
    val source = readFirstExisting(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/RigasSatiksmeSemanticDriver.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/RigasSatiksmeSemanticDriver.kt")
    )

    assertTrue(source.contains("private const val DEFAULT_STATE_SETTLE_MILLIS = 120L"))
    assertTrue(source.contains("private const val CLICK_TIMEOUT_MILLIS = 550L"))
    assertTrue(source.contains("private const val INPUT_TIMEOUT_MILLIS = 650L"))
    assertTrue(source.contains("private const val POST_STALE_CONTROL_BACK_SETTLE_MILLIS = 450L"))
    assertTrue(source.contains("private const val POST_SUBMIT_STALE_CONTROL_RECHECK_MILLIS = 150L"))
    assertTrue(source.contains("private const val POST_NAVIGATION_SETTLE_MILLIS = 120L"))
    assertTrue(source.contains("private const val POST_SUBMIT_SETTLE_MILLIS = 120L"))
    assertTrue(source.contains("private const val MAX_POST_SUBMIT_STALE_CONTROL_RECHECKS = 2"))
  }

  private fun readFirstExisting(vararg paths: Path): String {
    val path = paths.firstOrNull { Files.exists(it) } ?: error("missing source file: ${paths.joinToString()}")
    return String(Files.readAllBytes(path), Charsets.UTF_8)
  }

  private fun String.substringBetween(startNeedle: String, endNeedle: String): String {
    val start = indexOf(startNeedle)
    assertTrue("missing start needle: $startNeedle", start >= 0)
    val end = indexOf(endNeedle, start + startNeedle.length)
    assertTrue("missing end needle: $endNeedle", end >= 0)
    return substring(start, end)
  }

  private class FakeRigasSatiksmeGateway(
    private val automationReady: Boolean = true,
    private val launchReady: Boolean = true,
    private val foregroundReady: Boolean = true,
    private val snapshots: ArrayDeque<List<PhoneAutomationVisibleNode>> = ArrayDeque(),
    private val clickResults: ArrayDeque<Boolean> = ArrayDeque()
  ) : RigasSatiksmeSemanticGateway {
    val actions = mutableListOf<String>()
    val clickedTextContains = mutableListOf<String>()
    val enteredText = mutableListOf<String>()
    var launched = false
    var launchCount = 0

    override suspend fun prepareAutomation(): Boolean = automationReady

    override suspend fun launchApp(): Boolean {
      launched = true
      launchCount += 1
      return launchReady
    }

    override suspend fun waitForForeground(): Boolean = foregroundReady

    override suspend fun snapshot(): List<PhoneAutomationVisibleNode> {
      return snapshots.removeFirstOrNull().orEmpty()
    }

    override suspend fun click(selectors: List<PhoneAutomationSelector>, timeoutMillis: Long): Boolean {
      val text = selectors.firstNotNullOfOrNull { it.textContains ?: it.text }
      if (text != null) {
        clickedTextContains += text
      }
      actions += "click:${text.orEmpty()}"
      return clickResults.removeFirstOrNull() ?: true
    }

    override suspend fun openFirstEditableInput(timeoutMillis: Long): Boolean {
      actions += "open_editable"
      return true
    }

    override suspend fun setTextInFirstEditableInput(text: String, timeoutMillis: Long): Boolean {
      enteredText += text
      actions += "set_text"
      return true
    }

    override suspend fun performBack(): Boolean {
      actions += "back"
      return true
    }
  }

  private fun nodes(
    vararg texts: String,
    editable: Boolean = false
  ): List<PhoneAutomationVisibleNode> {
    return texts.mapIndexed { index, text ->
      PhoneAutomationVisibleNode(
        text = text,
        resourceId = "",
        contentDescription = if (text == "qr code") "qr code" else "",
        editable = editable && index == 0,
        clickable = true,
        enabled = true
      )
    }
  }
}
