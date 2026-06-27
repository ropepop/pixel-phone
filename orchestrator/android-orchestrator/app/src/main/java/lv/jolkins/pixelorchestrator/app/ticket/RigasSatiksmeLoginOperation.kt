package lv.jolkins.pixelorchestrator.app.ticket

import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationVisibleNode

/**
 * Pure decision-only state machine for the RS login flow.
 *
 * The actual RS app shows phone + password on the SAME screen. The password field
 * is where the SMS code goes. The flow is:
 *   1. Type phone into the phone field
 *   2. Wait for the broker to send the SMS code
 *   3. Type the SMS code into the password field
 *   4. Tap LOG IN
 *   5. Wait for the app to transition (VIVI email / home)
 *
 * The caller tracks what has been done via [LoginStep] and passes it to [decide].
 * The classifier only identifies WHICH screen is visible; the state machine
 * drives the action sequence.
 */
internal class RigasSatiksmeLoginOperation {

  enum class LoginStep {
    /** Nothing typed yet. */
    IDLE,
    /** Phone has been typed into the phone field. */
    PHONE_TYPED,
    /** SMS code has been typed into the password field. */
    CODE_TYPED,
    /** LOG IN button has been tapped. */
    SUBMITTED,
  }

  data class Decision(
    val action: RigasSatiksmeLoginDriverAction,
    val nextState: LoginStep,
    val done: Boolean,
    val resultState: String = "",
  )

  fun decide(
    currentStep: LoginStep,
    snapshot: List<PhoneAutomationVisibleNode>,
    smsCodeAvailable: Boolean,
  ): Decision {
    val observed = RigasSatiksmeLoginClassifier.classify(snapshot)

    return when (observed) {
      RigasSatiksmeLoginState.AUTH_BLOCKED -> Decision(
        action = RigasSatiksmeLoginDriverAction.ReportAuthBlocked,
        nextState = currentStep,
        done = true,
        resultState = "failed",
      )

      RigasSatiksmeLoginState.WRONG_CODE -> Decision(
        action = RigasSatiksmeLoginDriverAction.ReportWrongCode,
        nextState = currentStep,
        done = true,
        resultState = "failed",
      )

      RigasSatiksmeLoginState.LOGIN_DONE -> Decision(
        action = RigasSatiksmeLoginDriverAction.Noop,
        nextState = LoginStep.SUBMITTED,
        done = true,
        resultState = "succeeded",
      )

      RigasSatiksmeLoginState.LOGIN_LANDING -> when (currentStep) {
        LoginStep.IDLE -> Decision(
          action = RigasSatiksmeLoginDriverAction.TapSignInToShowForm,
          nextState = LoginStep.IDLE,
          done = false,
        )
        else -> Decision(
          action = RigasSatiksmeLoginDriverAction.Noop,
          nextState = currentStep,
          done = false,
        )
      }

      RigasSatiksmeLoginState.LOGIN_FORM -> when (currentStep) {
        LoginStep.IDLE -> Decision(
          action = RigasSatiksmeLoginDriverAction.TypePhone,
          nextState = LoginStep.PHONE_TYPED,
          done = false,
        )
        LoginStep.PHONE_TYPED -> if (smsCodeAvailable) {
          Decision(
            action = RigasSatiksmeLoginDriverAction.TypeCode,
            nextState = LoginStep.CODE_TYPED,
            done = false,
          )
        } else {
          Decision(
            action = RigasSatiksmeLoginDriverAction.Noop,
            nextState = LoginStep.PHONE_TYPED,
            done = false,
          )
        }
        LoginStep.CODE_TYPED -> Decision(
          action = RigasSatiksmeLoginDriverAction.TapSignIn,
          nextState = LoginStep.SUBMITTED,
          done = false,
        )
        LoginStep.SUBMITTED -> Decision(
          // Still on the login form after tapping LOG IN — wrong password.
          action = RigasSatiksmeLoginDriverAction.ReportWrongCode,
          nextState = LoginStep.SUBMITTED,
          done = true,
          resultState = "failed",
        )
      }

      RigasSatiksmeLoginState.UNKNOWN -> when (currentStep) {
        LoginStep.SUBMITTED -> Decision(
          // After tapping LOG IN the app transitioned away from the login form
          // to something we don't recognize (could be VIVI, a loading screen,
          // or the RS home screen). Since the login form is gone and there's
          // no explicit error, the RS login succeeded.
          action = RigasSatiksmeLoginDriverAction.Noop,
          nextState = LoginStep.SUBMITTED,
          done = true,
          resultState = "succeeded",
        )
        else -> Decision(
          action = RigasSatiksmeLoginDriverAction.Noop,
          nextState = currentStep,
          done = false,
        )
      }
    }
  }

  companion object {
    fun phoneLast4(phone: String): String {
      val digits = phone.filter { it.isDigit() }
      return if (digits.length <= 4) digits else digits.substring(digits.length - 4)
    }

    fun isValidPhone(phone: String): Boolean {
      val trimmed = phone.trim()
      if (trimmed.isEmpty()) return false
      val allowed = trimmed.all { it.isDigit() || it == '+' || it == '-' || it == ' ' || it == '(' || it == ')' }
      if (!allowed) return false
      val digitCount = trimmed.count { it.isDigit() }
      return digitCount in 6..16
    }

    fun isValidSmsCode(code: String): Boolean {
      val trimmed = code.trim()
      if (trimmed.isEmpty()) return false
      return trimmed.length in 4..64
    }
  }
}

internal sealed class RigasSatiksmeLoginDriverAction {
  object Noop : RigasSatiksmeLoginDriverAction()
  object TypePhone : RigasSatiksmeLoginDriverAction()
  object TypeCode : RigasSatiksmeLoginDriverAction()
  object TapSignIn : RigasSatiksmeLoginDriverAction()
  object TapSignInToShowForm : RigasSatiksmeLoginDriverAction()
  object ReportWrongCode : RigasSatiksmeLoginDriverAction()
  object ReportAuthBlocked : RigasSatiksmeLoginDriverAction()
}
