package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.test.runTest
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationVisibleNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RigasSatiksmeDirectTapDriverTest {
  @Test
  fun postSubmitReturnToRegisterScreenIsARejectedCode() = runTest {
    val gateway = FakeDirectTapGateway(
      snapshots = ArrayDeque(
        listOf(
          nodes("REGISTER A TRIP", "1 month"),
          nodes("ENTER THE CODE MANUALLY"),
          nodes("Control code", "Cancel", "OK"),
          nodes("Control code", "58011", "Cancel", "OK"),
          nodes("REGISTER A TRIP", "1 month")
        )
      )
    )

    val result = RigasSatiksmeDirectTapDriver(gateway).run("58011")

    assertFalse(result.ok)
    assertEquals("code_rejected_by_rs", result.reason)
    assertFalse(gateway.snapshotReasons.contains("rs_direct_proof_unknown_recheck"))
  }

  @Test
  fun postSubmitReturnToManualEntryScreenIsRejectedWithoutReEnteringCode() = runTest {
    val gateway = FakeDirectTapGateway(
      snapshots = ArrayDeque(
        listOf(
          nodes("REGISTER A TRIP", "1 month"),
          nodes("ENTER THE CODE MANUALLY"),
          listOf(
            PhoneAutomationVisibleNode(
              text = "Control code",
              resourceId = "",
              contentDescription = "Control code",
              editable = true
            )
          ),
          listOf(
            PhoneAutomationVisibleNode(
              text = "58011",
              resourceId = "",
              contentDescription = "Control code 58011",
              editable = true
            )
          ),
          listOf(
            PhoneAutomationVisibleNode(
              text = "Control code",
              resourceId = "",
              contentDescription = "Control code",
              editable = true
            )
          ),
        )
      )
    )

    val result = RigasSatiksmeDirectTapDriver(gateway).run("58011")

    assertFalse(result.ok)
    assertEquals("code_rejected_by_rs", result.reason)
    assertEquals(listOf("58011"), gateway.enteredCodes)
    assertFalse(gateway.snapshotReasons.contains("rs_direct_proof_unknown_recheck"))
  }

  @Test
  fun initialUnknownStateIsRecoveredBeforeAnyCodeEntry() = runTest {
    val gateway = FakeDirectTapGateway(
      snapshots = ArrayDeque(
        listOf(
          emptyList(),
          nodes("REGISTER A TRIP", "1 month"),
          nodes("ENTER THE CODE MANUALLY"),
          nodes("Control code", "Cancel", "OK"),
          nodes("Control code", "58011", "Cancel", "OK"),
          nodes("TICKET FOR CONTROL", "qr code", "1 month", "58011")
        )
      )
    )

    val result = RigasSatiksmeDirectTapDriver(gateway).run("58011")

    assertEquals(true, result.ok)
    assertEquals("generated", result.reason)
    val resetIndex = gateway.actions.indexOf("reset:rs_direct_initial_unknown_reset")
    val firstTapIndex = gateway.actions.indexOfFirst { it.startsWith("tap:") }
    val entryIndex = gateway.actions.indexOfFirst { it.startsWith("enter:") }
    assertEquals(0, resetIndex)
    assertEquals(true, firstTapIndex > resetIndex)
    assertEquals(true, entryIndex > resetIndex)
  }

  @Test
  fun persistentInitialUnknownStateRequiresAttentionWithoutTypingCode() = runTest {
    val gateway = FakeDirectTapGateway(
      snapshots = ArrayDeque(
        listOf(
          emptyList(),
          emptyList()
        )
      )
    )

    val result = RigasSatiksmeDirectTapDriver(gateway).run("58011")

    assertFalse(result.ok)
    assertEquals("rs_app_attention_required", result.reason)
    assertEquals(listOf("reset:rs_direct_initial_unknown_reset"), gateway.actions)
    assertEquals(emptyList<String>(), gateway.enteredCodes)
  }

  @Test
  fun postSubmitPersistentUnknownStateRequiresAttention() = runTest {
    val gateway = FakeDirectTapGateway(
      snapshots = ArrayDeque(
        listOf(
          nodes("REGISTER A TRIP", "1 month"),
          nodes("ENTER THE CODE MANUALLY"),
          nodes("Control code", "Cancel", "OK"),
          nodes("Control code", "58011", "Cancel", "OK"),
          emptyList(),
          emptyList()
        )
      )
    )

    val result = RigasSatiksmeDirectTapDriver(gateway).run("58011")

    assertFalse(result.ok)
    assertEquals("rs_app_attention_required", result.reason)
    assertEquals(listOf("58011"), gateway.enteredCodes)
    assertEquals(
      listOf(
        "rs_direct_initial",
        "rs_direct_register_trip_opened",
        "rs_direct_manual_code_choice_opened",
        "rs_direct_manual_code_entered",
        "rs_direct_proof_1",
        "rs_direct_proof_unknown_recheck"
      ),
      gateway.snapshotReasons
    )
  }

  @Test
  fun initialWrongCodeStateBacksOutBeforeFreshEntry() = runTest {
    val gateway = FakeDirectTapGateway(
      snapshots = ArrayDeque(
        listOf(
          nodes("wrong code", "OK"),
          nodes("REGISTER A TRIP", "1 month"),
          nodes("ENTER THE CODE MANUALLY"),
          nodes("Control code", "Cancel", "OK"),
          nodes("Control code", "58011", "Cancel", "OK"),
          nodes("TICKET FOR CONTROL", "qr code", "1 month", "58011")
        )
      )
    )

    val result = RigasSatiksmeDirectTapDriver(gateway).run("58011")

    assertEquals(true, result.ok)
    assertEquals("generated", result.reason)
    val backIndex = gateway.actions.indexOf("back:rs_direct_initial_wrong_code_back")
    val firstTapIndex = gateway.actions.indexOfFirst { it.startsWith("tap:") }
    assertEquals(0, backIndex)
    assertEquals(true, firstTapIndex > backIndex)
  }

  @Test
  fun initialLatvianStaleControlMustBeProvenClearedBeforeFreshEntry() = runTest {
    val gateway = FakeDirectTapGateway(
      snapshots = ArrayDeque(
        listOf(
          staleControlNodes("55555"),
          staleControlNodes("55555"),
          staleControlNodes("55555"),
          staleControlNodes("55555")
        )
      )
    )

    val result = RigasSatiksmeDirectTapDriver(gateway).run("68803")

    assertFalse(result.ok)
    assertEquals("rs_app_attention_required", result.reason)
    assertEquals(emptyList<String>(), gateway.enteredCodes)
    assertEquals(false, gateway.actions.any { it == "tap:rs_direct_register_trip" })
  }

  @Test
  fun staleControlVisibleCloseClearsBeforeBackOrRelaunch() = runTest {
    val gateway = FakeDirectTapGateway(
      snapshots = ArrayDeque(
        listOf(
          staleControlNodes("55555"),
          nodes("30 dienu biļete", "Reģistrēt braucienu"),
          nodes("Ievadīt kodu manuāli"),
          nodes("Ievadi kontroles kodu", "Atcelt", "OK"),
          nodes("Ievadi kontroles kodu", "68803", "Atcelt", "OK"),
          nodes("KONTROLES KODS", "qr code", "30 dienu biļete", "68803")
        )
      )
    )

    val result = RigasSatiksmeDirectTapDriver(gateway).run("68803")

    assertEquals(true, result.ok)
    assertEquals("generated", result.reason)
    val closeIndex = gateway.actions.indexOf("tap:rs_direct_initial_control_exit:node")
    val backIndex = gateway.actions.indexOf("back:rs_direct_initial_control_back")
    val resetIndex = gateway.actions.indexOf("reset:rs_direct_initial_control_reset")
    val entryIndex = gateway.actions.indexOf("enter:68803")
    assertEquals(0, closeIndex)
    assertEquals(-1, backIndex)
    assertEquals(-1, resetIndex)
    assertEquals(true, entryIndex > closeIndex)
  }

  @Test
  fun staleControlCanCloseAfterBackAndRelaunchBeforeEntry() = runTest {
    val gateway = FakeDirectTapGateway(
      snapshots = ArrayDeque(
        listOf(
          staleControlNodes("55555"),
          staleControlNodes("55555"),
          staleControlNodes("55555"),
          staleControlNodes("55555"),
          nodes("30 dienu biļete", "Reģistrēt braucienu"),
          nodes("Ievadīt kodu manuāli"),
          nodes("Ievadi kontroles kodu", "Atcelt", "OK"),
          nodes("Ievadi kontroles kodu", "68803", "Atcelt", "OK"),
          nodes("KONTROLES KODS", "qr code", "30 dienu biļete", "68803")
        )
      )
    )

    val result = RigasSatiksmeDirectTapDriver(gateway).run("68803")

    assertEquals(true, result.ok)
    assertEquals("generated", result.reason)
    val firstCloseIndex = gateway.actions.indexOf("tap:rs_direct_initial_control_exit:node")
    val backIndex = gateway.actions.indexOf("back:rs_direct_initial_control_back")
    val resetIndex = gateway.actions.indexOf("reset:rs_direct_initial_control_reset")
    val secondCloseIndex = gateway.actions.indexOfLast { it == "tap:rs_direct_initial_control_exit:node" }
    val entryIndex = gateway.actions.indexOf("enter:68803")
    assertEquals(0, firstCloseIndex)
    assertEquals(true, backIndex > firstCloseIndex)
    assertEquals(true, resetIndex > backIndex)
    assertEquals(true, secondCloseIndex > resetIndex)
    assertEquals(true, entryIndex > secondCloseIndex)
  }

  @Test
  fun staleControlBackFailureCanRecoverWithNoDataClearRelaunchBeforeEntry() = runTest {
    val gateway = FakeDirectTapGateway(
      snapshots = ArrayDeque(
        listOf(
          nodes("KONTROLES KODS", "qr code", "30 dienu biļete", "55555"),
          nodes("KONTROLES KODS", "qr code", "30 dienu biļete", "55555"),
          nodes("30 dienu biļete", "Reģistrēt braucienu"),
          nodes("Ievadīt kodu manuāli"),
          nodes("Ievadi kontroles kodu", "Atcelt", "OK"),
          nodes("Ievadi kontroles kodu", "68803", "Atcelt", "OK"),
          nodes("KONTROLES KODS", "qr code", "30 dienu biļete", "68803")
        )
      )
    )

    val result = RigasSatiksmeDirectTapDriver(gateway).run("68803")

    assertEquals(true, result.ok)
    assertEquals("generated", result.reason)
    val closeIndex = gateway.actions.indexOf("tap:rs_direct_initial_control_exit:node")
    val backIndex = gateway.actions.indexOf("back:rs_direct_initial_control_back")
    val resetIndex = gateway.actions.indexOf("reset:rs_direct_initial_control_reset")
    val entryIndex = gateway.actions.indexOf("enter:68803")
    assertEquals(-1, closeIndex)
    assertEquals(true, backIndex >= 0)
    assertEquals(true, resetIndex > backIndex)
    assertEquals(true, entryIndex > resetIndex)
  }

  @Test
  fun registerTapMustBeProvenBeforeManualChoiceAndDigitEntry() = runTest {
    val gateway = FakeDirectTapGateway(
      snapshots = ArrayDeque(
        listOf(
          nodes("30 dienu biļete", "Reģistrēt braucienu"),
          nodes("30 dienu biļete", "Reģistrēt braucienu")
        )
      )
    )

    val result = RigasSatiksmeDirectTapDriver(gateway).run("68803")

    assertFalse(result.ok)
    assertEquals("rs_app_attention_required", result.reason)
    assertEquals(emptyList<String>(), gateway.enteredCodes)
    assertEquals(false, gateway.actions.any { it == "tap:rs_direct_manual_code_choice" })
  }

  @Test
  fun manualCodeEntryMustShowRequestedDigitsBeforeConfirmTap() = runTest {
    val gateway = FakeDirectTapGateway(
      snapshots = ArrayDeque(
        listOf(
          nodes("30 dienu biļete", "Reģistrēt braucienu"),
          nodes("Ievadīt kodu manuāli"),
          nodes("Ievadi kontroles kodu", "Atcelt", "OK"),
          nodes("Ievadi kontroles kodu", "Atcelt", "OK"),
          nodes("Ievadi kontroles kodu", "Atcelt", "OK")
        )
      )
    )

    val result = RigasSatiksmeDirectTapDriver(gateway).run("68803")

    assertFalse(result.ok)
    assertEquals("rs_manual_code_entry_unverified", result.reason)
    assertEquals(listOf("68803", "68803"), gateway.enteredCodes)
    assertEquals(false, gateway.actions.any { it == "tap:rs_direct_confirm_code" })
  }

  @Test
  fun missingDigitProofRetriesOnceBeforeConfirmingCode() = runTest {
    val gateway = FakeDirectTapGateway(
      snapshots = ArrayDeque(
        listOf(
          nodes("30 dienu biļete", "Reģistrēt braucienu"),
          nodes("Ievadīt kodu manuāli"),
          nodes("Ievadi kontroles kodu", "Atcelt", "OK"),
          nodes("Ievadi kontroles kodu", "Atcelt", "OK"),
          nodes("Ievadi kontroles kodu", "68803", "Atcelt", "OK"),
          nodes("KONTROLES KODS", "qr code", "30 dienu biļete", "68803")
        )
      )
    )

    val result = RigasSatiksmeDirectTapDriver(gateway).run("68803")

    assertEquals(true, result.ok)
    assertEquals("generated", result.reason)
    assertEquals(listOf("68803", "68803"), gateway.enteredCodes)
    assertEquals(true, gateway.actions.contains("tap:rs_direct_confirm_code"))
  }

  @Test
  fun manualPopupDisappearingBeforeDigitProofRequiresAttentionWithoutConfirmTap() = runTest {
    val gateway = FakeDirectTapGateway(
      snapshots = ArrayDeque(
        listOf(
          nodes("30 dienu biļete", "Reģistrēt braucienu"),
          nodes("Ievadīt kodu manuāli"),
          nodes("Ievadi kontroles kodu", "Atcelt", "OK"),
          nodes("30 dienu biļete", "Reģistrēt braucienu")
        )
      )
    )

    val result = RigasSatiksmeDirectTapDriver(gateway).run("68803")

    assertFalse(result.ok)
    assertEquals("rs_app_attention_required", result.reason)
    assertEquals(listOf("68803"), gateway.enteredCodes)
    assertEquals(false, gateway.actions.any { it == "tap:rs_direct_confirm_code" })
  }

  @Test
  fun matchingLatvianGeneratedTicketAfterVerifiedSubmitSucceeds() = runTest {
    val gateway = FakeDirectTapGateway(
      snapshots = ArrayDeque(
        listOf(
          nodes("30 dienu biļete", "Reģistrēt braucienu"),
          nodes("Ievadīt kodu manuāli"),
          nodes("Ievadi kontroles kodu", "Atcelt", "OK"),
          nodes("Ievadi kontroles kodu", "68803", "Atcelt", "OK"),
          nodes("KONTROLES KODS", "qr code", "30 dienu biļete", "68803")
        )
      )
    )

    val result = RigasSatiksmeDirectTapDriver(gateway).run("68803")

    assertEquals(true, result.ok)
    assertEquals("generated", result.reason)
    assertEquals(listOf("68803"), gateway.enteredCodes)
    assertEquals(true, gateway.actions.contains("tap:rs_direct_confirm_code"))
  }

  private class FakeDirectTapGateway(
    private val snapshots: ArrayDeque<List<PhoneAutomationVisibleNode>>
  ) : RigasSatiksmeDirectTapGateway {
    val snapshotReasons = mutableListOf<String>()
    val enteredCodes = mutableListOf<String>()
    val actions = mutableListOf<String>()

    override suspend fun launchApp(): Boolean = true

    override suspend fun waitForForeground(): Boolean = true

    override suspend fun snapshot(reason: String): List<PhoneAutomationVisibleNode> {
      snapshotReasons += reason
      return snapshots.removeFirstOrNull().orEmpty()
    }

    override suspend fun resetApp(reason: String): Boolean {
      actions += "reset:$reason"
      return true
    }

    override suspend fun tapNodeCenter(node: PhoneAutomationVisibleNode, reason: String): Boolean {
      actions += "tap:$reason:node"
      return true
    }

    override suspend fun tapRatio(x: Double, y: Double, reason: String): Boolean {
      actions += "tap:$reason"
      return true
    }

    override suspend fun enterManualCode(cleanDigits: String, fieldXRatio: Double, fieldYRatio: Double): Boolean {
      actions += "enter:$cleanDigits"
      enteredCodes += cleanDigits
      return true
    }

    override suspend fun pressBack(reason: String): Boolean {
      actions += "back:$reason"
      return true
    }
  }

  private fun nodes(vararg labels: String): List<PhoneAutomationVisibleNode> {
    return labels.map { label -> PhoneAutomationVisibleNode(text = label, resourceId = "", contentDescription = label) }
  }

  private fun staleControlNodes(code: String): List<PhoneAutomationVisibleNode> {
    return listOf(
      PhoneAutomationVisibleNode(text = "KONTROLES KODS", resourceId = "", contentDescription = "KONTROLES KODS"),
      PhoneAutomationVisibleNode(text = "qr code", resourceId = "", contentDescription = "qr code"),
      PhoneAutomationVisibleNode(text = "30 dienu biļete", resourceId = "", contentDescription = "30 dienu biļete"),
      PhoneAutomationVisibleNode(text = code, resourceId = "", contentDescription = code),
      PhoneAutomationVisibleNode(
        text = "",
        resourceId = "",
        contentDescription = "Close",
        className = "android.widget.ImageView",
        bounds = "[814,184][940,310]",
        clickable = true,
        enabled = true
      )
    )
  }
}
