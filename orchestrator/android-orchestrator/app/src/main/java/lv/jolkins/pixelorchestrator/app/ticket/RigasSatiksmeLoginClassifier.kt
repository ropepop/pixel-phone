package lv.jolkins.pixelorchestrator.app.ticket

import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationVisibleNode

/**
 * Rīgas Satiksme login flow classifier.
 *
 * The actual RS app (com.flutter.rspassenger) shows a single login screen with
 * BOTH a phone field and a password field visible at the same time. The password
 * field is where the SMS code goes. After LOG IN, the app hands off to the VIVI
 * platform (com.pv.vivi) which shows an email onboarding screen.
 *
 * Flutter apps expose visible text via `contentDescription` and field placeholders
 * via `hint`, NOT via `text`. This classifier reads all three.
 */
internal enum class RigasSatiksmeLoginState {
  /** App not foreground or classifier has not matched a known state. */
  UNKNOWN,
  /** App shows the initial landing screen with LOG IN / REGISTER buttons (no form yet). */
  LOGIN_LANDING,
  /** Login screen with phone + password/code fields visible. */
  LOGIN_FORM,
  /** App has moved past the login screen (VIVI email, home, ticket list). */
  LOGIN_DONE,
  /** App shows a wrong-code / rejected state. */
  WRONG_CODE,
  /** App shows the "session expired" auth-blocked dialog. */
  AUTH_BLOCKED,
}

internal object RigasSatiksmeLoginClassifier {
  fun classify(nodes: List<PhoneAutomationVisibleNode>): RigasSatiksmeLoginState {
    if (nodes.isEmpty()) return RigasSatiksmeLoginState.UNKNOWN

    // Flutter apps put visible text in contentDescription and placeholders in hint.
    // Build a joined string from all three sources for robust matching.
    val allTexts = nodes.flatMap { node ->
      listOf(node.text, node.contentDescription, node.hint)
        .map { it.trim() }
        .filter { it.isNotBlank() }
    }
    if (allTexts.isEmpty()) return RigasSatiksmeLoginState.UNKNOWN
    val joined = allTexts.joinToString(separator = " | ").lowercase()

    // Priority 1: explicit session-expired dialog.
    if (looksLikeAuthBlocked(joined)) {
      return RigasSatiksmeLoginState.AUTH_BLOCKED
    }

    // Priority 2: wrong-code / invalid-code error on the login screen.
    if (looksLikeWrongCode(joined)) {
      return RigasSatiksmeLoginState.WRONG_CODE
    }

    // Priority 3: past the login screen (VIVI email, home, ticket list, etc.).
    if (looksLikeLoginDone(joined)) {
      return RigasSatiksmeLoginState.LOGIN_DONE
    }

    // Priority 4: the initial landing screen (LOG IN + REGISTER buttons, no form fields).
    if (looksLikeLoginLanding(nodes, joined)) {
      return RigasSatiksmeLoginState.LOGIN_LANDING
    }

    // Priority 5: the login form itself (phone + password/code + LOG IN button).
    if (looksLikeLoginForm(nodes, joined)) {
      return RigasSatiksmeLoginState.LOGIN_FORM
    }

    return RigasSatiksmeLoginState.UNKNOWN
  }

  internal fun looksLikeAuthBlocked(joined: String): Boolean {
    return hasAny(
      joined,
      "session expired",
      "sesija beigusies",
      "pieslēgties, lai turpinātu",
      "pierakstīties, lai turpinātu",
      "авторизуйтесь, чтобы продолжить",
      "сессия истекла",
      "ваша сессия истекла",
    )
  }

  internal fun looksLikeWrongCode(joined: String): Boolean {
    return hasAny(
      joined,
      "wrong code",
      "incorrect code",
      "invalid code",
      "nepareizs kods",
      "nederīgs kods",
      "nederigs kods",
      "неверный код",
      "wrong password",
      "incorrect password",
      "nepareiza parole",
      "nederīga parole",
    )
  }

  internal fun looksLikeLoginDone(joined: String): Boolean {
    // RS app home / ticket list markers only. Do NOT use VIVI markers here —
    // VIVI (com.pv.vivi) is a separate app. The RS login is done when the RS
    // app itself shows its home screen.
    val homeMarkers = listOf(
      "esat pieslēdzies",
      "you are logged in",
      "sesija atjaunota",
      "you are now signed in",
      "register a trip",
      "reģistrēt braucienu",
      "registret braucienu",
      "30 dienu biļete",
      "30 dienu bilete",
      "30 day ticket",
      "30-day ticket",
      "monthly ticket",
      "manas biļetes",
      "manas bileses",
      "tickets",
      "biļetes",
      "bileses",
      "public transport",
      "sabiedriskais transports",
    )
    if (hasAny(joined, *homeMarkers.toTypedArray())) {
      // Make sure we're not still on the login form.
      val loginFormMarkers = listOf(
        "phone number", "tālruņa numurs", "talnuma numurs",
        "password", "parole",
        "log in", "ielogoties", "pieslēgties", "pierakstīties",
      )
      if (hasAny(joined, *loginFormMarkers.toTypedArray())) {
        return false
      }
      return true
    }
    return false
  }

  internal fun looksLikeLoginForm(nodes: List<PhoneAutomationVisibleNode>, joined: String): Boolean {
    val hasEditable = nodes.any { it.editable }
    if (!hasEditable) return false

    // The RS login screen has: PHONE NUMBER (hint), PASSWORD (hint), LOG IN (content-desc).
    val phoneMarkers = listOf(
      "phone number", "tālruņa numurs", "talnuma numurs",
      "telefona numurs", "phone", "numurs",
    )
    val passwordMarkers = listOf(
      "password", "parole", "пароль",
    )
    val signInMarkers = listOf(
      "log in", "sign in", "ielogoties", "pieslēgties",
      "pierakstīties", "turpināt", "turpinat", "continue",
      "войти",
    )

    val hasPhone = hasAny(joined, *phoneMarkers.toTypedArray())
    val hasPassword = hasAny(joined, *passwordMarkers.toTypedArray())
    val hasSignIn = hasAny(joined, *signInMarkers.toTypedArray())

    // The actual RS app shows "PHONE NUMBER" (hint), "PASSWORD" (hint), "LOG IN" (content-desc).
    // Require at least a phone or password marker + a sign-in button.
    return (hasPhone || hasPassword) && hasSignIn
  }

  internal fun looksLikeLoginLanding(nodes: List<PhoneAutomationVisibleNode>, joined: String): Boolean {
    // The initial RS app screen has LOG IN and REGISTER buttons but no editable fields.
    // Tapping LOG IN reveals the phone + password form.
    val hasEditable = nodes.any { it.editable }
    if (hasEditable) return false

    val landingMarkers = listOf(
      "log in", "ielogoties", "pieslēgties", "pierakstīties",
      "register", "reģistrēties", "registreties", "регистрация",
    )
    val hasBoth = hasAny(joined, "log in", "ielogoties", "pieslēgties", "pierakstīties") &&
      hasAny(joined, "register", "reģistrēties", "registreties", "регистрация")

    return hasBoth
  }

  private fun hasAny(haystack: String, vararg needles: String): Boolean {
    for (needle in needles) {
      if (needle.isNotBlank() && haystack.contains(needle)) return true
    }
    return false
  }
}
