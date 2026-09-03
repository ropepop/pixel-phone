package lv.jolkins.pixelorchestrator.app.phoneautomation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneAutomationViviLoginContractTest {
  @Test
  fun selectsExactlyOneEmailAboveExactlyOnePassword() {
    val nodes = listOf(
      node(hint = "E-pasts", bounds = "[40,300][900,380]", editable = true),
      node(hint = "Parole", bounds = "[40,410][900,490]", editable = true, password = true),
      node(text = "IEIET", bounds = "[40,520][900,600]", clickable = true)
    )

    assertEquals(PhoneAutomationViviLoginFields(0, 1), PhoneAutomationViviLoginContract.fields(nodes))
    assertEquals(PhoneAutomationViviAuthSurface.LOGIN, PhoneAutomationViviLoginContract.authSurface(nodes))
  }

  @Test
  fun acceptsCurrentViviEditTextShapeWithoutEditableFlag() {
    val nodes = listOf(
      node(
        bounds = "[40,300][900,380]",
        className = "android.widget.EditText",
        editable = false,
        clickable = true,
        focusable = true
      ),
      node(
        bounds = "[40,410][900,490]",
        className = "android.widget.EditText",
        editable = false,
        clickable = true,
        focusable = true,
        password = true
      ),
      node(text = "IEIET", bounds = "[40,520][900,600]", enabled = false)
    )

    assertEquals(PhoneAutomationViviLoginFields(0, 1), PhoneAutomationViviLoginContract.fields(nodes))
    assertEquals(PhoneAutomationViviAuthSurface.LOGIN, PhoneAutomationViviLoginContract.authSurface(nodes))
    assertEquals(true, PhoneAutomationViviLoginContract.isLoginSubmit(nodes.last()))
  }

  @Test
  fun visibleFieldsWinOverHiddenFallbackDuplicates() {
    val visible = listOf(
      node(
        bounds = "[40,300][900,380]",
        className = "android.widget.EditText",
        clickable = true,
        focusable = true
      ),
      node(
        bounds = "[40,410][900,490]",
        className = "android.widget.EditText",
        clickable = true,
        focusable = true,
        password = true
      )
    )
    val fallbackWithHiddenDuplicates = visible + visible

    assertEquals(
      PhoneAutomationViviLoginFieldSelection(
        PhoneAutomationViviLoginFields(0, 1),
        usesFallbackNodes = false
      ),
      PhoneAutomationViviLoginContract.fieldsPreferringVisible(
        visible,
        fallbackWithHiddenDuplicates
      )
    )
  }

  @Test
  fun nonEditableCompatibilityRemainsFailClosed() {
    val current = listOf(
      node(
        bounds = "[40,300][900,380]",
        className = "android.widget.EditText",
        clickable = true,
        focusable = true
      ),
      node(
        bounds = "[40,410][900,490]",
        className = "android.widget.EditText",
        clickable = true,
        focusable = true,
        password = true
      )
    )
    val extraField = current + node(
      bounds = "[40,520][900,600]",
      className = "android.widget.EditText",
      clickable = true,
      focusable = true
    )
    val notClickable = current.toMutableList().also { nodes ->
      nodes[0] = nodes[0].copy(clickable = false)
    }
    val notFocusable = current.toMutableList().also { nodes ->
      nodes[0] = nodes[0].copy(focusable = false)
    }
    val notEditText = current.toMutableList().also { nodes ->
      nodes[0] = nodes[0].copy(className = "android.view.View")
    }
    val editTextLookalike = current.toMutableList().also { nodes ->
      nodes[0] = nodes[0].copy(className = "android.view.NotAnEditText")
    }
    val disabled = current.toMutableList().also { nodes ->
      nodes[0] = nodes[0].copy(enabled = false)
    }
    val noPasswordDistinction = current.map { it.copy(password = false) }

    assertNull(PhoneAutomationViviLoginContract.fields(extraField))
    assertNull(PhoneAutomationViviLoginContract.fields(notClickable))
    assertNull(PhoneAutomationViviLoginContract.fields(notFocusable))
    assertNull(PhoneAutomationViviLoginContract.fields(notEditText))
    assertNull(PhoneAutomationViviLoginContract.fields(editTextLookalike))
    assertNull(PhoneAutomationViviLoginContract.fields(disabled))
    assertNull(PhoneAutomationViviLoginContract.fields(noPasswordDistinction))
  }

  @Test
  fun rejectsAmbiguousOrReversedEditableFields() {
    val ambiguous = listOf(
      node(hint = "E-pasts", bounds = "[40,200][900,280]", editable = true),
      node(hint = "Lietotajs", bounds = "[40,300][900,380]", editable = true),
      node(hint = "Parole", bounds = "[40,410][900,490]", editable = true, password = true)
    )
    val reversed = listOf(
      node(hint = "Parole", bounds = "[40,200][900,280]", editable = true, password = true),
      node(hint = "E-pasts", bounds = "[40,410][900,490]", editable = true)
    )

    assertNull(PhoneAutomationViviLoginContract.fields(ambiguous))
    assertNull(PhoneAutomationViviLoginContract.fields(reversed))
  }

  @Test
  fun classifiesOnlyBoundedAttentionSurfaces() {
    assertEquals(
      PhoneAutomationViviAuthSurface.ADDITIONAL_VERIFICATION,
      PhoneAutomationViviLoginContract.authSurface(listOf(node(text = "Verification code")))
    )
    assertEquals(
      PhoneAutomationViviAuthSurface.DEVICE_LINK,
      PhoneAutomationViviLoginContract.authSurface(listOf(node(text = "Apstipriniet ierices saisti")))
    )
    assertEquals(
      PhoneAutomationViviAuthSurface.PROFILE_SELECTION,
      PhoneAutomationViviLoginContract.authSurface(
        listOf(node(text = "TURPINAT"), node(bounds = "[1,2][3,4]", editable = true))
      )
    )
    assertEquals(
      PhoneAutomationViviAuthSurface.CAPTCHA,
      PhoneAutomationViviLoginContract.authSurface(listOf(node(text = "I am not a robot CAPTCHA")))
    )
    assertEquals(
      PhoneAutomationViviAuthSurface.ONBOARDING,
      PhoneAutomationViviLoginContract.authSurface(listOf(node(text = "Laipni ludzam")))
    )
    assertEquals(
      PhoneAutomationViviAuthSurface.UNKNOWN,
      PhoneAutomationViviLoginContract.authSurface(listOf(node(text = "Unexpected page")))
    )
  }

  private fun node(
    text: String = "",
    hint: String = "",
    bounds: String = "",
    className: String = "",
    editable: Boolean = false,
    clickable: Boolean = false,
    focusable: Boolean = false,
    password: Boolean = false,
    enabled: Boolean = true
  ) = PhoneAutomationVisibleNode(
    text = text,
    resourceId = "",
    contentDescription = "",
    className = className,
    bounds = bounds,
    clickable = clickable,
    enabled = enabled,
    editable = editable,
    focusable = focusable,
    hint = hint,
    password = password
  )
}
