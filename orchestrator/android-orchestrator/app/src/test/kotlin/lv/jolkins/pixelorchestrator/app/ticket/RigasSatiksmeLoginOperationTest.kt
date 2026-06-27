package lv.jolkins.pixelorchestrator.app.ticket

import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationVisibleNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RigasSatiksmeLoginOperationTest {

  @Test
  fun freshSnapshotOnLoginFormTypesPhone() {
    val op = RigasSatiksmeLoginOperation()
    val snapshot = loginFormNodes()
    val decision = op.decide(RigasSatiksmeLoginOperation.LoginStep.IDLE, snapshot, smsCodeAvailable = false)
    assertEquals(RigasSatiksmeLoginDriverAction.TypePhone, decision.action)
    assertEquals(RigasSatiksmeLoginOperation.LoginStep.PHONE_TYPED, decision.nextState)
    assertFalse(decision.done)
  }

  @Test
  fun phoneTypedWithoutCodeWaits() {
    val op = RigasSatiksmeLoginOperation()
    val snapshot = loginFormNodes()
    val decision = op.decide(RigasSatiksmeLoginOperation.LoginStep.PHONE_TYPED, snapshot, smsCodeAvailable = false)
    assertEquals(RigasSatiksmeLoginDriverAction.Noop, decision.action)
    assertEquals(RigasSatiksmeLoginOperation.LoginStep.PHONE_TYPED, decision.nextState)
    assertFalse(decision.done)
  }

  @Test
  fun phoneTypedWithCodeTypesCode() {
    val op = RigasSatiksmeLoginOperation()
    val snapshot = loginFormNodes()
    val decision = op.decide(RigasSatiksmeLoginOperation.LoginStep.PHONE_TYPED, snapshot, smsCodeAvailable = true)
    assertEquals(RigasSatiksmeLoginDriverAction.TypeCode, decision.action)
    assertEquals(RigasSatiksmeLoginOperation.LoginStep.CODE_TYPED, decision.nextState)
    assertFalse(decision.done)
  }

  @Test
  fun codeTypedTapsSignIn() {
    val op = RigasSatiksmeLoginOperation()
    val snapshot = loginFormNodes()
    val decision = op.decide(RigasSatiksmeLoginOperation.LoginStep.CODE_TYPED, snapshot, smsCodeAvailable = true)
    assertEquals(RigasSatiksmeLoginDriverAction.TapSignIn, decision.action)
    assertEquals(RigasSatiksmeLoginOperation.LoginStep.SUBMITTED, decision.nextState)
    assertFalse(decision.done)
  }

  @Test
  fun loginDoneReportsSuccess() {
    val op = RigasSatiksmeLoginOperation()
    val snapshot = rsHomeNodes()
    val decision = op.decide(RigasSatiksmeLoginOperation.LoginStep.SUBMITTED, snapshot, smsCodeAvailable = true)
    assertEquals(RigasSatiksmeLoginDriverAction.Noop, decision.action)
    assertTrue(decision.done)
    assertEquals("succeeded", decision.resultState)
  }

  @Test
  fun submittedWithUnknownReportsSuccess() {
    // After tapping LOG IN, if the login form is gone (UNKNOWN screen),
    // the RS login succeeded — the app transitioned away from the login form.
    val op = RigasSatiksmeLoginOperation()
    val snapshot = emptyList<PhoneAutomationVisibleNode>()
    val decision = op.decide(RigasSatiksmeLoginOperation.LoginStep.SUBMITTED, snapshot, smsCodeAvailable = true)
    assertEquals(RigasSatiksmeLoginDriverAction.Noop, decision.action)
    assertTrue(decision.done)
    assertEquals("succeeded", decision.resultState)
  }

  @Test
  fun submittedWithLoginFormReportsWrongCode() {
    // After tapping LOG IN, if we're still on the login form,
    // the password was wrong.
    val op = RigasSatiksmeLoginOperation()
    val snapshot = loginFormNodes()
    val decision = op.decide(RigasSatiksmeLoginOperation.LoginStep.SUBMITTED, snapshot, smsCodeAvailable = true)
    assertEquals(RigasSatiksmeLoginDriverAction.ReportWrongCode, decision.action)
    assertTrue(decision.done)
    assertEquals("failed", decision.resultState)
  }

  @Test
  fun authBlockedReportsFailure() {
    val op = RigasSatiksmeLoginOperation()
    val snapshot = authBlockedNodes()
    val decision = op.decide(RigasSatiksmeLoginOperation.LoginStep.IDLE, snapshot, smsCodeAvailable = false)
    assertEquals(RigasSatiksmeLoginDriverAction.ReportAuthBlocked, decision.action)
    assertTrue(decision.done)
    assertEquals("failed", decision.resultState)
  }

  @Test
  fun wrongCodeReportsFailure() {
    val op = RigasSatiksmeLoginOperation()
    val snapshot = wrongCodeNodes()
    val decision = op.decide(RigasSatiksmeLoginOperation.LoginStep.SUBMITTED, snapshot, smsCodeAvailable = true)
    assertEquals(RigasSatiksmeLoginDriverAction.ReportWrongCode, decision.action)
    assertTrue(decision.done)
    assertEquals("failed", decision.resultState)
  }

  @Test
  fun emptySnapshotIsUnknownAndWaits() {
    val op = RigasSatiksmeLoginOperation()
    val decision = op.decide(RigasSatiksmeLoginOperation.LoginStep.IDLE, emptyList(), smsCodeAvailable = false)
    assertEquals(RigasSatiksmeLoginDriverAction.Noop, decision.action)
    assertFalse(decision.done)
  }

  @Test
  fun phoneValidationRejectsShortOrLongOrNonDigits() {
    assertFalse(RigasSatiksmeLoginOperation.isValidPhone(""))
    assertFalse(RigasSatiksmeLoginOperation.isValidPhone("12345"))
    assertFalse(RigasSatiksmeLoginOperation.isValidPhone("abcdefg"))
    assertFalse(RigasSatiksmeLoginOperation.isValidPhone("+12345678901234567"))
    assertFalse(RigasSatiksmeLoginOperation.isValidPhone("+-()"))
    assertTrue(RigasSatiksmeLoginOperation.isValidPhone("+371 27079944"))
    assertTrue(RigasSatiksmeLoginOperation.isValidPhone("27079944"))
    assertTrue(RigasSatiksmeLoginOperation.isValidPhone("+1 (415) 555-9999"))
    assertTrue(RigasSatiksmeLoginOperation.isValidPhone("123456"))
    assertTrue(RigasSatiksmeLoginOperation.isValidPhone("1234567890123456"))
  }

  @Test
  fun smsCodeValidationRejectsShortOrLongOrEmpty() {
    assertFalse(RigasSatiksmeLoginOperation.isValidSmsCode(""))
    assertFalse(RigasSatiksmeLoginOperation.isValidSmsCode("ab"))
    assertFalse(RigasSatiksmeLoginOperation.isValidSmsCode("abc"))
    assertTrue(RigasSatiksmeLoginOperation.isValidSmsCode("1234"))
    assertTrue(RigasSatiksmeLoginOperation.isValidSmsCode("123456"))
    assertTrue(RigasSatiksmeLoginOperation.isValidSmsCode("Rajpud-qigjon-sehxo9"))
    assertTrue(RigasSatiksmeLoginOperation.isValidSmsCode("27079944"))
    assertTrue(RigasSatiksmeLoginOperation.isValidSmsCode("abcdefghijklmnopabcdefghijklmnopabcdefghijklmnopabcdefghijklmnop"))
  }

  @Test
  fun phoneLast4RedactsCorrectly() {
    assertEquals("9944", RigasSatiksmeLoginOperation.phoneLast4("+371 27079944"))
    assertEquals("9944", RigasSatiksmeLoginOperation.phoneLast4("27079944"))
    assertEquals("123", RigasSatiksmeLoginOperation.phoneLast4("123"))
    assertEquals("1234", RigasSatiksmeLoginOperation.phoneLast4("abcd1234efgh"))
    assertEquals("9999", RigasSatiksmeLoginOperation.phoneLast4("+1 (415) 555-9999"))
  }

  // --- Node helpers that match the actual RS app's uiautomator structure ---

  private fun loginFormNodes(): List<PhoneAutomationVisibleNode> {
    return listOf(
      node(contentDescription = "LOG IN"),
      node(contentDescription = "+371"),
      node(hint = "PHONE NUMBER", editable = true),
      node(hint = "PASSWORD", editable = true),
      node(contentDescription = "LOG IN", className = "android.widget.Button"),
      node(contentDescription = "Reset password", className = "android.widget.Button"),
      node(contentDescription = "Informative phone"),
      node(contentDescription = "20361862"),
      node(contentDescription = "Version 2.1.0")
    )
  }

  private fun rsHomeNodes(): List<PhoneAutomationVisibleNode> {
    return listOf(
      node(text = "30 dienu biļete"),
      node(text = "Reģistrēt braucienu"),
      node(text = "Sabiedriskais transports")
    )
  }

  private fun authBlockedNodes(): List<PhoneAutomationVisibleNode> {
    return listOf(
      node(text = "Session expired. Please log in again.")
    )
  }

  private fun wrongCodeNodes(): List<PhoneAutomationVisibleNode> {
    return listOf(
      node(contentDescription = "LOG IN"),
      node(hint = "PHONE NUMBER", editable = true),
      node(hint = "PASSWORD", editable = true),
      node(text = "Wrong code")
    )
  }

  private fun node(
    text: String = "",
    contentDescription: String = "",
    hint: String = "",
    editable: Boolean = false,
    className: String = "android.view.View",
  ): PhoneAutomationVisibleNode {
    return PhoneAutomationVisibleNode(
      text = text,
      resourceId = "",
      contentDescription = contentDescription,
      className = className,
      bounds = "",
      clickable = false,
      enabled = true,
      focused = false,
      editable = editable,
      focusable = false,
      hint = hint
    )
  }

  @Test
  fun `landing screen triggers TapSignInToShowForm in IDLE state`() {
    val op = RigasSatiksmeLoginOperation()
    val nodes = listOf(
      node(contentDescription = "LOG IN"),
      node(contentDescription = "REGISTER"),
    )
    val decision = op.decide(
      currentStep = RigasSatiksmeLoginOperation.LoginStep.IDLE,
      snapshot = nodes,
      smsCodeAvailable = false,
    )
    assertEquals(RigasSatiksmeLoginDriverAction.TapSignInToShowForm, decision.action)
    assertFalse(decision.done)
  }

  @Test
  fun `landing screen in non-IDLE state does nothing`() {
    val op = RigasSatiksmeLoginOperation()
    val nodes = listOf(
      node(contentDescription = "LOG IN"),
      node(contentDescription = "REGISTER"),
    )
    val decision = op.decide(
      currentStep = RigasSatiksmeLoginOperation.LoginStep.PHONE_TYPED,
      snapshot = nodes,
      smsCodeAvailable = false,
    )
    assertEquals(RigasSatiksmeLoginDriverAction.Noop, decision.action)
    assertFalse(decision.done)
  }
}
