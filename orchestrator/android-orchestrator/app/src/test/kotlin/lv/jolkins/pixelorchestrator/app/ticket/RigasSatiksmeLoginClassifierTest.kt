package lv.jolkins.pixelorchestrator.app.ticket

import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationVisibleNode
import org.junit.Assert.assertEquals
import org.junit.Test

class RigasSatiksmeLoginClassifierTest {

  @Test
  fun emptySnapshotIsUnknown() {
    assertEquals(
      RigasSatiksmeLoginState.UNKNOWN,
      RigasSatiksmeLoginClassifier.classify(emptyList())
    )
  }

  @Test
  fun actualRsLoginFormDetectedViaContentDescAndHint() {
    val snapshot = listOf(
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
    assertEquals(
      RigasSatiksmeLoginState.LOGIN_FORM,
      RigasSatiksmeLoginClassifier.classify(snapshot)
    )
  }

  @Test
  fun viviEmailScreenIsNotLoginDone() {
    // VIVI (com.pv.vivi) is a separate app. The RS login screen's "E-pasts"
    // / "TURPINĀT" markers must NOT be classified as RS LOGIN_DONE.
    val snapshot = listOf(
      node(contentDescription = "E-pasts"),
      node(contentDescription = "Ievadi e-pastu"),
      node(contentDescription = "TURPINĀT", className = "android.widget.Button"),
      node(text = "27079944")
    )
    assertEquals(
      RigasSatiksmeLoginState.UNKNOWN,
      RigasSatiksmeLoginClassifier.classify(snapshot)
    )
  }

  @Test
  fun rsHomeScreenDetectedAsLoginDone() {
    val snapshot = listOf(
      node(text = "30 dienu biļete"),
      node(text = "Reģistrēt braucienu")
    )
    assertEquals(
      RigasSatiksmeLoginState.LOGIN_DONE,
      RigasSatiksmeLoginClassifier.classify(snapshot)
    )
  }

  @Test
  fun authBlockedDialogDetected() {
    val snapshot = listOf(
      node(text = "Session expired. Please log in again.")
    )
    assertEquals(
      RigasSatiksmeLoginState.AUTH_BLOCKED,
      RigasSatiksmeLoginClassifier.classify(snapshot)
    )
  }

  @Test
  fun lvAuthBlockedDialog() {
    val snapshot = listOf(
      node(text = "Sesija beigusies. Lūdzu, pieslēdzieties vēlreiz.")
    )
    assertEquals(
      RigasSatiksmeLoginState.AUTH_BLOCKED,
      RigasSatiksmeLoginClassifier.classify(snapshot)
    )
  }

  @Test
  fun wrongCodeDetected() {
    val snapshot = listOf(
      node(contentDescription = "LOG IN"),
      node(hint = "PHONE NUMBER", editable = true),
      node(hint = "PASSWORD", editable = true),
      node(text = "Wrong code")
    )
    assertEquals(
      RigasSatiksmeLoginState.WRONG_CODE,
      RigasSatiksmeLoginClassifier.classify(snapshot)
    )
  }

  @Test
  fun lvWrongCodeDetected() {
    val snapshot = listOf(
      node(contentDescription = "LOG IN"),
      node(hint = "PHONE NUMBER", editable = true),
      node(hint = "PASSWORD", editable = true),
      node(text = "Nepareizs kods")
    )
    assertEquals(
      RigasSatiksmeLoginState.WRONG_CODE,
      RigasSatiksmeLoginClassifier.classify(snapshot)
    )
  }

  @Test
  fun loginFormWithOnlyTextFieldsIsLoginForm() {
    val snapshot = listOf(
      node(text = "Phone number", editable = true),
      node(text = "Password", editable = true),
      node(text = "Log in")
    )
    assertEquals(
      RigasSatiksmeLoginState.LOGIN_FORM,
      RigasSatiksmeLoginClassifier.classify(snapshot)
    )
  }

  @Test
  fun viviEmailWithLoginFormMarkersStaysLoginForm() {
    // If "turpināt" appears alongside "password" + "log in", it's still the login form.
    val snapshot = listOf(
      node(contentDescription = "LOG IN"),
      node(hint = "PHONE NUMBER", editable = true),
      node(hint = "PASSWORD", editable = true),
      node(contentDescription = "Turpināt")
    )
    assertEquals(
      RigasSatiksmeLoginState.LOGIN_FORM,
      RigasSatiksmeLoginClassifier.classify(snapshot)
    )
  }

  @Test
  fun viviEmailWithoutLoginFormMarkersIsUnknown() {
    // VIVI markers without RS login markers are UNKNOWN, not LOGIN_DONE.
    val snapshot = listOf(
      node(contentDescription = "Turpināt")
    )
    assertEquals(
      RigasSatiksmeLoginState.UNKNOWN,
      RigasSatiksmeLoginClassifier.classify(snapshot)
    )
  }

  @Test
  fun unknownScreenWithoutLoginMarkersIsUnknown() {
    val snapshot = listOf(
      node(text = "Loading..."),
      node(text = "Please wait")
    )
    assertEquals(
      RigasSatiksmeLoginState.UNKNOWN,
      RigasSatiksmeLoginClassifier.classify(snapshot)
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
  fun `landing screen with LOG IN and REGISTER buttons classifies as LOGIN_LANDING`() {
    val nodes = listOf(
      node(contentDescription = "LOG IN"),
      node(contentDescription = "REGISTER"),
      node(contentDescription = "Informative phone"),
    )
    val state = RigasSatiksmeLoginClassifier.classify(nodes)
    assertEquals(RigasSatiksmeLoginState.LOGIN_LANDING, state)
  }

  @Test
  fun `landing screen in Latvian classifies as LOGIN_LANDING`() {
    val nodes = listOf(
      node(contentDescription = "Ielogoties"),
      node(contentDescription = "Reģistrēties"),
    )
    val state = RigasSatiksmeLoginClassifier.classify(nodes)
    assertEquals(RigasSatiksmeLoginState.LOGIN_LANDING, state)
  }

  @Test
  fun `login form with phone and password fields is not LOGIN_LANDING`() {
    val nodes = listOf(
      node(contentDescription = "LOG IN"),
      node(hint = "PHONE NUMBER", editable = true),
      node(hint = "PASSWORD", editable = true),
    )
    val state = RigasSatiksmeLoginClassifier.classify(nodes)
    assertEquals(RigasSatiksmeLoginState.LOGIN_FORM, state)
  }
}
