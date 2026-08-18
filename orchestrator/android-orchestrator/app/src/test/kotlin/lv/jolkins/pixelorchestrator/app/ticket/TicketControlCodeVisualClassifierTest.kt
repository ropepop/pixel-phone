package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Test

class TicketControlCodeVisualClassifierTest {
  @Test
  fun sharedTicketHeaderCannotImpersonateGeneratedResult() {
    val frame = rawTicketFrame()
    frame.fill(left = 0, top = 0, right = 48, bottom = 10, color = DARK)
    frame.fill(left = 2, top = 2, right = 19, bottom = 7, color = LIGHT)
    frame.fill(left = 1, top = 8, right = 47, bottom = 15, color = RED)

    assertEquals(TicketControlCodeVisualClassifier.RAW_TICKET, classify(frame))
    assertEquals(TicketControlCodeVisualClassifier.UNKNOWN, classifyForCleanup(frame))
  }

  @Test
  fun generatedResultWinsOverRawTicketGraphic() {
    val frame = rawTicketFrame()
    for (y in 36 until 40) {
      for (x in 7 until 41) {
        frame[x, y] = if (x < 37) DARK else LIGHT
      }
    }

    assertEquals(TicketControlCodeVisualClassifier.GENERATED, classify(frame))
    assertEquals(TicketControlCodeVisualClassifier.GENERATED, classifyForCleanup(frame))
  }

  @Test
  fun activatedTicketProofAcceptsAztecWhenStatusStripLooksLikeGeneratedResult() {
    val frame = rawTicketFrame()
    frame.fill(left = 7, top = 36, right = 41, bottom = 40, color = RESULT_DARK)
    frame[24, 37] = LIGHT
    frame[25, 37] = LIGHT

    assertEquals(TicketControlCodeVisualClassifier.GENERATED, classify(frame))
    assertEquals(
      TicketControlCodeVisualClassifier.RAW_TICKET,
      TicketControlCodeVisualClassifier.classifyForActivatedTicket(frame.pixels)
    )
  }

  @Test
  fun nearSolidGeneratedRowWithSparseWhiteDigitsIsRecognized() {
    val frame = rawTicketFrame()
    frame.fill(left = 7, top = 36, right = 41, bottom = 40, color = RESULT_DARK)
    frame[24, 37] = LIGHT
    frame[25, 37] = LIGHT
    frame[36, 38] = LIGHT
    frame[37, 38] = LIGHT

    assertEquals(TicketControlCodeVisualClassifier.GENERATED, classify(frame))
    assertEquals(TicketControlCodeVisualClassifier.GENERATED, classifyForCleanup(frame))
  }

  @Test
  fun shiftedMutedGeneratedRowUnderAztecIsRecognized() {
    val frame = rawTicketFrame()
    frame.fill(left = 7, top = 32, right = 41, bottom = 37, color = SHIFTED_RESULT_DARK)
    frame.fill(left = 23, top = 34, right = 27, bottom = 35, color = SHIFTED_RESULT_TEXT)
    frame.fill(left = 36, top = 33, right = 40, bottom = 35, color = SHIFTED_RESULT_TEXT)

    assertEquals(TicketControlCodeVisualClassifier.GENERATED, classify(frame))
    assertEquals(TicketControlCodeVisualClassifier.GENERATED, classifyForCleanup(frame))
  }

  @Test
  fun generatedResultMovedUpFromPreviousBandIsRecognized() {
    val frame = rawTicketFrame()
    frame.fill(left = 7, top = 27, right = 41, bottom = 31, color = RESULT_DARK)
    frame[24, 28] = LIGHT
    frame[25, 28] = LIGHT
    frame[36, 29] = LIGHT
    frame[37, 29] = LIGHT

    assertEquals(TicketControlCodeVisualClassifier.GENERATED, classify(frame))
    assertEquals(TicketControlCodeVisualClassifier.GENERATED, classifyForCleanup(frame))
  }

  @Test
  fun currentTicketHeaderWithMovedGeneratedRowIsRecognized() {
    val frame = SanitizedFrame()
    frame.fill(left = 0, top = 0, right = 48, bottom = 10, color = RED)
    for (y in 10 until 26) {
      for (x in 8 until 40) {
        frame[x, y] = if ((x + y) % 2 == 0) DARK else LIGHT
      }
    }
    frame.fill(left = 7, top = 27, right = 41, bottom = 31, color = RESULT_DARK)
    frame[24, 28] = LIGHT
    frame[25, 28] = LIGHT
    frame[36, 29] = LIGHT
    frame[37, 29] = LIGHT

    assertEquals(TicketControlCodeVisualClassifier.GENERATED, classify(frame))
    assertEquals(TicketControlCodeVisualClassifier.GENERATED, classifyForCleanup(frame))
  }

  @Test
  fun normalPopupWinsOverRawTicketBehindDialog() {
    val frame = rawTicketFrame()
    frame.fill(left = 8, top = 30, right = 40, bottom = 45, color = LIGHT)
    frame.fill(left = 13, top = 39, right = 36, bottom = 40, color = DARK)
    frame.fill(left = 31, top = 39, right = 42, bottom = 44, color = ORANGE)

    assertEquals(TicketControlCodeVisualClassifier.CONTROL_POPUP, classify(frame))
    assertEquals(TicketControlCodeVisualClassifier.UNKNOWN, classifyForCleanup(frame))
  }

  @Test
  fun currentDarkPopupWinsOverGeneratedResultHeuristics() {
    val frame = rawTicketFrame()
    frame.fill(left = 4, top = 26, right = 44, bottom = 43, color = DARK_DIALOG)
    frame.fill(left = 6, top = 35, right = 43, bottom = 38, color = DARK_BLUE)

    assertEquals(TicketControlCodeVisualClassifier.CONTROL_POPUP, classify(frame))
    assertEquals(TicketControlCodeVisualClassifier.UNKNOWN, classifyForCleanup(frame))
  }

  @Test
  fun keyboardShiftedPopupWinsOverRawTicketBehindDialog() {
    val frame = rawTicketFrame()
    frame.fill(left = 8, top = 16, right = 40, bottom = 30, color = LIGHT)
    frame.fill(left = 31, top = 24, right = 42, bottom = 29, color = ORANGE)
    val submitFrame = SubmitFrame()
    submitFrame.fill(left = 16, top = 32, right = 80, bottom = 60, color = LIGHT)
    submitFrame.fill(left = 62, top = 48, right = 84, bottom = 58, color = ORANGE)

    assertEquals(TicketControlCodeVisualClassifier.CONTROL_POPUP, classify(frame))
    assertEquals(
      TicketControlCodeVisualClassifier.CONTROL_POPUP_KEYBOARD_READY,
      TicketControlCodeVisualClassifier.classifySubmitLayout(submitFrame.pixels)
    )
  }

  @Test
  fun ordinaryPopupAuthorizesStaticSubmitGeometryWithoutKeyboard() {
    val frame = rawTicketFrame()
    frame.fill(left = 8, top = 30, right = 40, bottom = 45, color = LIGHT)
    frame.fill(left = 31, top = 39, right = 42, bottom = 44, color = ORANGE)

    assertEquals(TicketControlCodeVisualClassifier.CONTROL_POPUP, classify(frame))
    val submitFrame = blankSubmitFrame()
    assertEquals(
      TicketControlCodeVisualClassifier.CONTROL_POPUP_STATIC_READY,
      TicketControlCodeVisualClassifier.classifySubmitLayout(submitFrame.pixels)
    )
  }

  @Test
  fun enteredValueIsRequiredBeforeStaticPopupCanAuthorizeSubmit() {
    val blank = blankSubmitFrame()

    assertEquals(
      TicketControlCodeVisualClassifier.CONTROL_POPUP_STATIC_READY,
      TicketControlCodeVisualClassifier.classifySubmitLayout(blank.pixels)
    )

    val entered = blankSubmitFrame()
    entered.fill(left = 46, top = 66, right = 51, bottom = 69, color = DARK)

    assertEquals(
      TicketControlCodeVisualClassifier.CONTROL_POPUP_VALUE_READY,
      TicketControlCodeVisualClassifier.classifySubmitLayout(entered.pixels)
    )
  }

  @Test
  fun currentDarkPopupAuthorizesOnlyAVisiblyEnteredValue() {
    val blank = darkSubmitFrame()
    assertEquals(
      TicketControlCodeVisualClassifier.CONTROL_POPUP_STATIC_READY,
      TicketControlCodeVisualClassifier.classifySubmitLayout(blank.pixels)
    )

    val entered = darkSubmitFrame()
    entered.fill(left = 42, top = 62, right = 47, bottom = 65, color = LIGHT)
    assertEquals(
      TicketControlCodeVisualClassifier.CONTROL_POPUP_VALUE_READY,
      TicketControlCodeVisualClassifier.classifySubmitLayout(entered.pixels)
    )
  }

  @Test
  fun darkPopupPlaceholderAndSingleBrightCaretCannotAuthorizeSubmit() {
    val placeholder = darkSubmitFrame()
    placeholder.fill(left = 34, top = 61, right = 62, bottom = 65, color = DARK_PLACEHOLDER)
    assertEquals(
      TicketControlCodeVisualClassifier.CONTROL_POPUP_STATIC_READY,
      TicketControlCodeVisualClassifier.classifySubmitLayout(placeholder.pixels)
    )

    val caret = darkSubmitFrame()
    caret.fill(left = 48, top = 60, right = 49, bottom = 68, color = LIGHT)
    assertEquals(
      TicketControlCodeVisualClassifier.CONTROL_POPUP_STATIC_READY,
      TicketControlCodeVisualClassifier.classifySubmitLayout(caret.pixels)
    )
  }

  @Test
  fun darkKeyboardShiftRemainsAHardSubmitRejection() {
    val shifted = SubmitFrame()
    shifted.fill(left = 8, top = 24, right = 88, bottom = 62, color = DARK_DIALOG)
    shifted.fill(left = 8, top = 40, right = 88, bottom = 46, color = DARK_BLUE)

    assertEquals(
      TicketControlCodeVisualClassifier.CONTROL_POPUP_KEYBOARD_READY,
      TicketControlCodeVisualClassifier.classifySubmitLayout(shifted.pixels)
    )
  }

  @Test
  fun oneOrangeRowCannotAuthorizeEnteredValue() {
    val entered = SubmitFrame()
    entered.fill(left = 16, top = 60, right = 80, bottom = 90, color = LIGHT)
    entered.fill(left = 62, top = 78, right = 84, bottom = 79, color = ORANGE)
    entered.fill(left = 46, top = 66, right = 51, bottom = 69, color = DARK)

    assertEquals(
      TicketControlCodeVisualClassifier.UNKNOWN,
      TicketControlCodeVisualClassifier.classifySubmitLayout(entered.pixels)
    )
  }

  @Test
  fun enabledButtonSurvivesRedBlueChannelSwap() {
    val entered = SubmitFrame()
    entered.fill(left = 16, top = 60, right = 80, bottom = 90, color = LIGHT)
    entered.fill(left = 62, top = 74, right = 84, bottom = 80, color = SWAPPED_ORANGE)
    entered.fill(left = 46, top = 66, right = 51, bottom = 69, color = DARK)

    assertEquals(
      TicketControlCodeVisualClassifier.CONTROL_POPUP_VALUE_READY,
      TicketControlCodeVisualClassifier.classifySubmitLayout(entered.pixels)
    )
  }

  @Test
  fun oneDarkCaretColumnCannotImpersonateEnteredDigits() {
    val caret = blankSubmitFrame()
    caret.fill(left = 48, top = 64, right = 49, bottom = 72, color = DARK)

    assertEquals(
      TicketControlCodeVisualClassifier.CONTROL_POPUP_STATIC_READY,
      TicketControlCodeVisualClassifier.classifySubmitLayout(caret.pixels)
    )
  }

  @Test
  fun liveThinTwoDigitAggregateIsEnoughForTheShortestAcceptedValue() {
    val entered = blankSubmitFrame()
    // Privacy-safe aggregate reconstructed from the production 96x144 submit probe. It retains
    // only the two sampled stroke positions and luminances, never the entered value or image.
    entered[46, 68] = LIVE_DIGIT_STROKE_60
    entered[48, 67] = LIVE_DIGIT_STROKE_75

    assertEquals(
      TicketControlCodeVisualClassifier.CONTROL_POPUP_VALUE_READY,
      TicketControlCodeVisualClassifier.classifySubmitLayout(entered.pixels)
    )
  }

  @Test
  fun twoAdjacentCaretColumnsCannotImpersonateEnteredDigits() {
    val caret = blankSubmitFrame()
    caret[48, 67] = LIVE_DIGIT_STROKE_60
    caret[49, 68] = LIVE_DIGIT_STROKE_75

    assertEquals(
      TicketControlCodeVisualClassifier.CONTROL_POPUP_STATIC_READY,
      TicketControlCodeVisualClassifier.classifySubmitLayout(caret.pixels)
    )
  }

  @Test
  fun shortestValueSurvivesAdjacentProbePixelCollapse() {
    val entered = blankSubmitFrame()
    // The live two-digit minimum can collapse to two neighboring samples on the same row after
    // the rooted H.264 source is reduced to the privacy-safe submit probe.
    entered[46, 68] = LIVE_DIGIT_STROKE_60
    entered[47, 68] = LIVE_DIGIT_STROKE_75

    assertEquals(
      TicketControlCodeVisualClassifier.CONTROL_POPUP_VALUE_READY,
      TicketControlCodeVisualClassifier.classifySubmitLayout(entered.pixels)
    )
  }

  @Test
  fun rawTicketGraphicIsRecognizedWithoutPopupOrResultMarkers() {
    assertEquals(TicketControlCodeVisualClassifier.RAW_TICKET, classify(rawTicketFrame()))
  }

  @Test
  fun denseAztecRowsWithoutAContinuousResultStripRemainRawTicket() {
    val frame = rawTicketFrame()
    for (y in 20 until 27) {
      for (x in 8 until 40) {
        frame[x, y] = if (x % 7 < 5) DARK else LIGHT
      }
    }

    assertEquals(TicketControlCodeVisualClassifier.RAW_TICKET, classify(frame))
  }

  @Test
  fun registeredTicketDetailCannotBeMistakenForControlPopup() {
    val frame = rawTicketFrame()
    frame.fill(left = 4, top = 43, right = 44, bottom = 47, color = YELLOW)

    assertEquals(TicketControlCodeVisualClassifier.RAW_TICKET, classify(frame))
  }

  @Test
  fun ticketListCardCannotImpersonateOpenTicketDetail() {
    val frame = SanitizedFrame()
    frame.fill(left = 8, top = 14, right = 40, bottom = 34, color = DARK)
    frame.fill(left = 31, top = 14, right = 40, bottom = 34, color = ORANGE)
    frame.fill(left = 7, top = 36, right = 41, bottom = 40, color = LIGHT)

    assertEquals(TicketControlCodeVisualClassifier.UNKNOWN, classify(frame))
    assertEquals(TicketControlCodeVisualClassifier.UNKNOWN, classifyForCleanup(frame))
  }

  @Test
  fun ticketListRegistrationButtonIsASeparateCleanupProof() {
    val frame = SanitizedFrame()
    frame.fill(left = 0, top = 0, right = 48, bottom = 10, color = DARK)
    frame.fill(left = 1, top = 10, right = 47, bottom = 15, color = RED)
    frame.fill(left = 4, top = 31, right = 44, bottom = 36, color = YELLOW)

    assertEquals(TicketControlCodeVisualClassifier.UNKNOWN, classify(frame))
    assertEquals(
      TicketControlCodeVisualClassifier.TICKET_LIST_WITH_REGISTRATION_BUTTON,
      classifyForCleanup(frame)
    )
  }

  @Test
  fun registeredDetailSliderDoesNotImpersonateTicketListRegistrationButton() {
    val frame = SanitizedFrame()
    frame.fill(left = 0, top = 0, right = 48, bottom = 10, color = DARK)
    frame.fill(left = 1, top = 5, right = 47, bottom = 9, color = RED)
    frame.fill(left = 4, top = 43, right = 44, bottom = 47, color = YELLOW)

    assertEquals(TicketControlCodeVisualClassifier.UNKNOWN, classifyForCleanup(frame))
  }

  @Test
  fun registeredDetailWithAztecAndSliderIsASeparateCleanupProof() {
    val frame = rawTicketFrame()
    frame.fill(left = 4, top = 43, right = 44, bottom = 47, color = YELLOW)

    assertEquals(TicketControlCodeVisualClassifier.RAW_TICKET, classifyForCleanup(frame))
  }

  @Test
  fun registrationSliderBoundsUseTheWideTrackAndNotTheTextAnchor() {
    val frame = rawTicketFrame()
    frame.fill(left = 4, top = 43, right = 44, bottom = 47, color = YELLOW)
    frame.fill(left = 8, top = 43, right = 12, bottom = 47, color = DARK)

    assertEquals("3,42,45,48", TicketControlCodeVisualClassifier.registrationSliderBounds(frame.pixels))
  }

  @Test
  fun almostGeneratedDarkRowCannotFallThroughAsRawTicket() {
    val frame = rawTicketFrame()
    for (y in 36 until 40) {
      for (x in 7 until 41) {
        frame[x, y] = if (x < 25) DARK else LIGHT
      }
    }

    assertEquals(TicketControlCodeVisualClassifier.UNKNOWN, classifyForCleanup(frame))
  }

  @Test
  fun unrelatedLowContrastFrameStaysUnknown() {
    assertEquals(TicketControlCodeVisualClassifier.UNKNOWN, classify(SanitizedFrame()))
  }

  @Test
  fun malformedProbeStaysUnknown() {
    assertEquals(TicketControlCodeVisualClassifier.UNKNOWN, TicketControlCodeVisualClassifier.classify(IntArray(1)))
    assertEquals(
      TicketControlCodeVisualClassifier.UNKNOWN,
      TicketControlCodeVisualClassifier.classifySubmitLayout(IntArray(1))
    )
  }

  private fun classify(frame: SanitizedFrame): String = TicketControlCodeVisualClassifier.classify(frame.pixels)

  private fun classifyForCleanup(frame: SanitizedFrame): String =
    TicketControlCodeVisualClassifier.classifyForCleanup(frame.pixels)

  private fun rawTicketFrame(): SanitizedFrame {
    val frame = SanitizedFrame()
    frame.fill(left = 0, top = 0, right = 48, bottom = 10, color = DARK)
    frame.fill(left = 2, top = 2, right = 19, bottom = 7, color = LIGHT)
    frame.fill(left = 1, top = 8, right = 47, bottom = 15, color = RED)
    for (y in 14 until 34) {
      for (x in 8 until 40) {
        frame[x, y] = if ((x + y) % 2 == 0) DARK else LIGHT
      }
    }
    for (y in 36 until 40) {
      for (x in 7 until 41) {
        frame[x, y] = if (x % 5 == 0) DARK else LIGHT
      }
    }
    return frame
  }

  private fun blankSubmitFrame(): SubmitFrame {
    val frame = SubmitFrame()
    frame.fill(left = 16, top = 60, right = 80, bottom = 90, color = LIGHT)
    // Matches the static ViVi popup on the Pixel after the 200 px stream crop.
    frame.fill(left = 62, top = 74, right = 84, bottom = 80, color = ORANGE)
    // Approximate the wide gray ViVi placeholder; it must remain blank authority.
    frame.fill(left = 34, top = 66, right = 60, bottom = 68, color = PLACEHOLDER)
    return frame
  }

  private fun darkSubmitFrame(): SubmitFrame {
    val frame = SubmitFrame()
    frame.fill(left = 8, top = 52, right = 88, bottom = 86, color = DARK_DIALOG)
    // Current ViVi uses a dark sheet with a dimmed blue action from x=11..84 / y=70..74.
    frame.fill(left = 11, top = 70, right = 85, bottom = 75, color = DARK_BLUE)
    return frame
  }

  private class SanitizedFrame {
    val pixels = IntArray(
      TicketControlCodeVisualClassifier.SAMPLE_WIDTH * TicketControlCodeVisualClassifier.SAMPLE_HEIGHT
    ) { MID }

    operator fun set(x: Int, y: Int, color: Int) {
      pixels[y * TicketControlCodeVisualClassifier.SAMPLE_WIDTH + x] = color
    }

    fun fill(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
      for (y in top until bottom) {
        for (x in left until right) {
          this[x, y] = color
        }
      }
    }
  }

  private class SubmitFrame {
    val pixels = IntArray(
      TicketControlCodeVisualClassifier.SUBMIT_SAMPLE_WIDTH *
        TicketControlCodeVisualClassifier.SUBMIT_SAMPLE_HEIGHT
    ) { MID }

    operator fun set(x: Int, y: Int, color: Int) {
      pixels[y * TicketControlCodeVisualClassifier.SUBMIT_SAMPLE_WIDTH + x] = color
    }

    fun fill(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
      for (y in top until bottom) {
        for (x in left until right) {
          this[x, y] = color
        }
      }
    }
  }

  private companion object {
    val DARK = rgb(0, 0, 0)
    val LIGHT = rgb(240, 240, 240)
    val MID = rgb(112, 112, 112)
    val ORANGE = rgb(230, 130, 30)
    val YELLOW = rgb(255, 190, 0)
    val SWAPPED_ORANGE = rgb(30, 130, 230)
    val PLACEHOLDER = rgb(99, 99, 99)
    val DARK_DIALOG = rgb(18, 26, 37)
    val DARK_BLUE = rgb(23, 58, 114)
    val DARK_PLACEHOLDER = rgb(115, 115, 120)
    val LIVE_DIGIT_STROKE_60 = rgb(60, 60, 60)
    val LIVE_DIGIT_STROKE_75 = rgb(75, 75, 75)
    val RED = rgb(190, 45, 35)
    val RESULT_DARK = rgb(52, 52, 52)
    val SHIFTED_RESULT_DARK = rgb(72, 72, 72)
    val SHIFTED_RESULT_TEXT = rgb(190, 190, 190)

    fun rgb(red: Int, green: Int, blue: Int): Int =
      (0xff shl 24) or (red shl 16) or (green shl 8) or blue
  }
}
