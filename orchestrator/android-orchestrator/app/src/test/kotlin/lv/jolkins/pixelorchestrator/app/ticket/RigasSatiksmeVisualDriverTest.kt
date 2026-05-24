package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class RigasSatiksmeVisualDriverTest {
  @Test
  fun successfulFlowUsesRootTapsAndPixelProof() = runTest {
    val gateway = FakeRigasSatiksmeVisualGateway(
      frames = ArrayDeque(
        listOf(
          registerReadyFrame(),
          manualChoiceFrame(),
          manualInputFrame(),
          manualInputWithCodeFrame("58011"),
          tripRegisteredFrame(),
          manualChoiceFrame(),
          controlTicketFrame("58011")
        )
      )
    )

    val result = RigasSatiksmeVisualDriver(gateway).run("58011")

    assertTrue("reason=${result.reason} actions=${gateway.actions}", result.ok)
    assertEquals("generated", result.reason)
    assertEquals(
      listOf(
        "rs_register_trip",
        "rs_manual_code_choice",
        "rs_confirm_code",
        "rs_trip_registered_ok",
        "rs_ticket_for_control"
      ),
      gateway.tapReasons
    )
    assertEquals(listOf("58011"), gateway.typedText)
    val confirmTap = gateway.actions.first { it.startsWith("tap:rs_confirm_code:") }.split(":")
    val confirmY = confirmTap[3].toInt()
    assertTrue("confirm tap should stay inside the green button with keyboard open: $confirmY", confirmY > TEST_HEIGHT * 0.64)
    assertFalse(gateway.actions.any { it.contains("accessibility", ignoreCase = true) })
  }

  @Test
  fun initialMatchingControlTicketIsBackedOutAndFreshlyRegistered() = runTest {
    val gateway = FakeRigasSatiksmeVisualGateway(
      frames = ArrayDeque(
        listOf(
          controlTicketFrame("58011"),
          registerReadyFrame(),
          manualChoiceFrame(),
          manualInputFrame(),
          manualInputWithCodeFrame("58011"),
          tripRegisteredFrame(),
          manualChoiceFrame(),
          controlTicketFrame("58011")
        )
      )
    )

    val result = RigasSatiksmeVisualDriver(gateway).run("58011")

    assertTrue("reason=${result.reason} actions=${gateway.actions}", result.ok)
    assertEquals("generated", result.reason)
    assertTrue(gateway.actions.contains("back:rs_initial_control_reset"))
    assertEquals(1, gateway.typedText.count { it == "58011" })
  }

  @Test
  fun postSubmitStaleTicketIsBoundedBeforeSpecificFailure() = runTest {
    val gateway = FakeRigasSatiksmeVisualGateway(
      frames = ArrayDeque(
        listOf(
          registerReadyFrame(),
          manualChoiceFrame(),
          manualInputFrame(),
          manualInputWithCodeFrame("58011"),
          tripRegisteredFrame(),
          manualChoiceFrame(),
          controlTicketFrame("11111"),
          controlTicketFrame("11111"),
          controlTicketFrame("11111")
        )
      )
    )

    val result = RigasSatiksmeVisualDriver(gateway).run("58011")

    assertFalse(result.ok)
    assertEquals("rs_monthly_ticket_stale_code", result.reason)
    assertEquals(1, gateway.typedText.count { it == "58011" })
    assertFalse(gateway.actions.drop(1).any { it.startsWith("back:") })
  }

  @Test
  fun scanQrManualChoiceUsesBottomButtonAndThenManualInput() = runTest {
    val gateway = FakeRigasSatiksmeVisualGateway(
      frames = ArrayDeque(
        listOf(
          registerReadyFrame(),
          scanQrManualChoiceFrame(),
          manualInputFrame(),
          manualInputWithCodeFrame("68803"),
          manualChoiceFrame()
        )
      )
    )

    val result = RigasSatiksmeVisualDriver(gateway).run("68803")

    assertFalse(result.ok)
    assertEquals("rs_submit_not_confirmed", result.reason)
    val manualChoiceTap = gateway.actions.first { it.startsWith("tap:rs_manual_code_choice:") }
    val y = manualChoiceTap.substringAfterLast(":").toInt()
    assertTrue("manual choice tap should hit the bottom scan-screen button: $manualChoiceTap", y > TEST_HEIGHT * 0.85)
    assertEquals(1, gateway.typedText.count { it == "68803" })
  }

  @Test
  fun manualCodeReturnAfterSubmitBecomesRejectedCode() = runTest {
    val gateway = FakeRigasSatiksmeVisualGateway(
      frames = ArrayDeque(
        listOf(
          registerReadyFrame(),
          manualChoiceFrame(),
          manualInputFrame(),
          manualInputWithCodeFrame("27515"),
          manualInputWrongCodeFrame("27515")
        )
      )
    )

    val result = RigasSatiksmeVisualDriver(gateway).run("27515")

    assertFalse(result.ok)
    assertEquals("code_rejected_by_rs", result.reason)
    assertEquals(1, gateway.typedText.count { it == "27515" })
  }

  @Test
  fun missedConfirmDoesNotBecomeRejectedCode() = runTest {
    val gateway = FakeRigasSatiksmeVisualGateway(
      frames = ArrayDeque(
        listOf(
          registerReadyFrame(),
          manualChoiceFrame(),
          manualInputFrame(),
          manualInputWithCodeFrame("27515"),
          manualInputWithCodeFrame("27515"),
          manualInputWithCodeFrame("27515")
        )
      )
    )

    val result = RigasSatiksmeVisualDriver(gateway).run("27515")

    assertFalse(result.ok)
    assertEquals("rs_submit_not_confirmed", result.reason)
    assertEquals(1, gateway.typedText.count { it == "27515" })
  }

  @Test
  fun manualCodeEntryMustBeProvenBeforeConfirm() = runTest {
    val gateway = FakeRigasSatiksmeVisualGateway(
      frames = ArrayDeque(
        listOf(
          registerReadyFrame(),
          manualChoiceFrame(),
          manualInputFrame(),
          manualInputFrame(),
          manualInputFrame()
        )
      )
    )

    val result = RigasSatiksmeVisualDriver(gateway).run("27515")

    assertFalse(result.ok)
    assertEquals("rs_manual_code_entry_unverified", result.reason)
    assertFalse(gateway.tapReasons.contains("rs_confirm_code"))
    assertEquals(2, gateway.typedText.count { it == "27515" })
  }

  @Test
  fun registerTripIsRetriedOnceWhenHomeDoesNotAdvance() = runTest {
    val gateway = FakeRigasSatiksmeVisualGateway(
      frames = ArrayDeque(
        listOf(
          registerReadyFrame(),
          registerReadyFrame(),
          manualChoiceFrame(),
          manualInputFrame(),
          manualInputWithCodeFrame("68803"),
          tripRegisteredFrame(),
          manualChoiceFrame(),
          controlTicketFrame("68803")
        )
      )
    )

    val result = RigasSatiksmeVisualDriver(gateway).run("68803")

    assertTrue("reason=${result.reason} actions=${gateway.actions}", result.ok)
    assertTrue(gateway.tapReasons.contains("rs_register_trip_retry"))
  }

  @Test
  fun manualChoiceIsRetriedOnceWhenScanScreenDoesNotAdvance() = runTest {
    val gateway = FakeRigasSatiksmeVisualGateway(
      frames = ArrayDeque(
        listOf(
          registerReadyFrame(),
          manualChoiceFrame(),
          manualChoiceFrame(),
          manualInputFrame(),
          manualInputWithCodeFrame("68803"),
          tripRegisteredFrame(),
          manualChoiceFrame(),
          controlTicketFrame("68803")
        )
      )
    )

    val result = RigasSatiksmeVisualDriver(gateway).run("68803")

    assertTrue("reason=${result.reason} actions=${gateway.actions}", result.ok)
    assertTrue(gateway.tapReasons.contains("rs_manual_code_choice_retry"))
  }

  @Test
  fun unknownVisualScreenFailsSpecificallyWithoutAccessibility() = runTest {
    val gateway = FakeRigasSatiksmeVisualGateway(
      frames = ArrayDeque(List(10) { unknownFrame() })
    )

    val result = RigasSatiksmeVisualDriver(gateway, maxProofAttempts = 3).run("68803")

    assertFalse(result.ok)
    assertEquals("rs_monthly_ticket_unknown_state", result.reason)
    assertFalse(gateway.actions.any { it.contains("accessibility", ignoreCase = true) })
  }

  @Test
  fun visualDigitReaderRequiresSubmittedDigits() {
    assertEquals(
      RigasSatiksmeVisualState.CONTROL_MATCHING,
      RigasSatiksmeVisualClassifier.classify(controlTicketFrame("68803"), "68803")
    )
    assertEquals(
      RigasSatiksmeVisualState.CONTROL_STALE,
      RigasSatiksmeVisualClassifier.classify(controlTicketFrame("58011"), "68803")
    )
  }

  @Test
  fun manualInputClassifierDistinguishesTypedAndRejectedStates() {
    assertEquals(
      RigasSatiksmeVisualState.MANUAL_INPUT_WITH_SUBMITTED_CODE,
      RigasSatiksmeVisualClassifier.classify(manualInputWithCodeFrame("68803"), "68803")
    )
    assertEquals(
      RigasSatiksmeVisualState.MANUAL_INPUT_REJECTED,
      RigasSatiksmeVisualClassifier.classify(manualInputWrongCodeFrame("68803"), "68803")
    )
    assertEquals(
      RigasSatiksmeVisualState.MANUAL_INPUT,
      RigasSatiksmeVisualClassifier.classify(manualInputWithCodeFrame("58011"), "68803")
    )
  }

  @Test
  fun landscapeStartupFramesAreIgnoredUntilPortraitSettles() {
    assertEquals(
      RigasSatiksmeVisualState.UNKNOWN,
      RigasSatiksmeVisualClassifier.classify(landscapeRegisterLikeFrame(), "68803")
    )
  }

  @Test
  fun homeScreenWithBottomRouteCardStillClassifiesAsRegisterReady() {
    assertEquals(
      RigasSatiksmeVisualState.REGISTER_READY,
      RigasSatiksmeVisualClassifier.classify(registerReadyWithBottomRouteCardFrame(), "68803")
    )
  }

  @Test
  fun productionRsFlowUsesDirectTapDriverAndAllowsOnlyIsolatedRsCoordinateTaps() {
    val service = readFirstExisting(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketStreamService.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketStreamService.kt")
    )
    val flow = service.substringBetween(
      "private suspend fun runRigasSatiksmeMonthlyTicketFlow",
      "private suspend fun captureRigasSatiksmeMonthlyTicketImageBytes"
    )

    assertTrue(flow.contains("RigasSatiksmeDirectTapDriver("))
    assertFalse(flow.contains("ensureRigasSatiksmeSemanticAutomationReady"))
    assertFalse(flow.contains("PhoneAutomationServiceBridge.snapshotVisibleNodes"))
    assertTrue(service.contains("inputRootExecutor"))

    val directDriver = readFirstExisting(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/RigasSatiksmeDirectTapDriver.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/RigasSatiksmeDirectTapDriver.kt")
    )
    assertTrue(directDriver.contains("rs_direct_register_trip"))
    assertTrue(directDriver.contains("RigasSatiksmeSemanticDriver.classify"))
  }

  private class FakeRigasSatiksmeVisualGateway(
    private val launchReady: Boolean = true,
    private val foregroundReady: Boolean = true,
    private val frames: ArrayDeque<RigasSatiksmeVisualFrame> = ArrayDeque()
  ) : RigasSatiksmeVisualGateway {
    val actions = mutableListOf<String>()
    val tapReasons = mutableListOf<String>()
    val typedText = mutableListOf<String>()
    private var lastFrame: RigasSatiksmeVisualFrame? = null

    override suspend fun launchApp(): Boolean {
      actions += "launch"
      return launchReady
    }

    override suspend fun waitForForeground(): Boolean {
      actions += "foreground"
      return foregroundReady
    }

    override suspend fun captureFrame(reason: String): RigasSatiksmeVisualFrame? {
      actions += "capture:$reason"
      lastFrame = frames.removeFirstOrNull() ?: lastFrame
      return lastFrame
    }

    override suspend fun tap(x: Int, y: Int, reason: String): Boolean {
      actions += "tap:$reason:$x:$y"
      tapReasons += reason
      return true
    }

    override suspend fun enterManualCode(
      cleanDigits: String,
      fieldX: Int,
      fieldY: Int
    ): Boolean {
      actions += "enter:$fieldX:$fieldY"
      typedText += cleanDigits
      return true
    }

    override suspend fun pressBack(reason: String): Boolean {
      actions += "back:$reason"
      return true
    }
  }

  private fun registerReadyFrame(): RigasSatiksmeVisualFrame {
    return RigasSatiksmeVisualFrame.solid(TEST_WIDTH, TEST_HEIGHT, rgb(246, 247, 250)).also { frame ->
      frame.fillNormalized(0.18, 0.20, 0.82, 0.27, rgb(30, 150, 95))
    }
  }

  private fun registerReadyWithBottomRouteCardFrame(): RigasSatiksmeVisualFrame {
    return registerReadyFrame().also { frame ->
      frame.fillNormalized(0.0, 0.72, 1.0, 0.98, rgb(250, 250, 250))
      frame.fillNormalized(0.20, 0.81, 0.45, 0.87, rgb(18, 18, 18))
    }
  }

  private fun manualChoiceFrame(): RigasSatiksmeVisualFrame {
    return RigasSatiksmeVisualFrame.solid(TEST_WIDTH, TEST_HEIGHT, rgb(246, 247, 250)).also { frame ->
      frame.fillNormalized(0.13, 0.50, 0.87, 0.58, rgb(35, 70, 190))
    }
  }

  private fun scanQrManualChoiceFrame(): RigasSatiksmeVisualFrame {
    return RigasSatiksmeVisualFrame.solid(TEST_WIDTH, TEST_HEIGHT, rgb(4, 5, 5)).also { frame ->
      frame.fillNormalized(0.12, 0.89, 0.88, 0.97, rgb(250, 250, 250))
      frame.fillNormalized(0.34, 0.91, 0.66, 0.95, rgb(15, 15, 15))
    }
  }

  private fun manualInputFrame(): RigasSatiksmeVisualFrame {
    return RigasSatiksmeVisualFrame.solid(TEST_WIDTH, TEST_HEIGHT, rgb(18, 16, 160)).also { frame ->
      frame.fillNormalized(0.13, 0.44, 0.87, 0.52, rgb(250, 250, 250))
      frame.fillNormalized(0.17, 0.47, 0.32, 0.50, rgb(100, 106, 112))
      frame.fillNormalized(0.18, 0.61, 0.86, 0.69, rgb(174, 218, 80))
    }
  }

  private fun manualInputWithCodeFrame(digits: String): RigasSatiksmeVisualFrame {
    return manualInputFrame().also { frame ->
      frame.drawDigitsNormalized(digits, 0.36, 0.455, 0.64, 0.505)
    }
  }

  private fun manualInputWrongCodeFrame(digits: String): RigasSatiksmeVisualFrame {
    return manualInputWithCodeFrame(digits).also { frame ->
      frame.fillNormalized(0.18, 0.535, 0.52, 0.565, rgb(210, 45, 45))
    }
  }

  private fun tripRegisteredFrame(): RigasSatiksmeVisualFrame {
    return RigasSatiksmeVisualFrame.solid(TEST_WIDTH, TEST_HEIGHT, rgb(22, 19, 160)).also { frame ->
      frame.fillNormalized(0.18, 0.36, 0.82, 0.58, rgb(250, 250, 250))
      frame.fillNormalized(0.38, 0.51, 0.62, 0.56, rgb(32, 105, 210))
    }
  }

  private fun controlTicketFrame(digits: String): RigasSatiksmeVisualFrame {
    return RigasSatiksmeVisualFrame.solid(TEST_WIDTH, TEST_HEIGHT, rgb(17, 10, 170)).also { frame ->
      frame.fillNormalized(0.12, 0.11, 0.88, 0.78, rgb(252, 252, 252))
      frame.drawQrLikeNormalized(0.26, 0.18, 0.74, 0.46)
      frame.fillNormalized(0.20, 0.66, 0.80, 0.74, rgb(226, 229, 238))
      frame.drawDigitsNormalized(digits, 0.31, 0.675, 0.69, 0.725)
    }
  }

  private fun unknownFrame(): RigasSatiksmeVisualFrame {
    return RigasSatiksmeVisualFrame.solid(TEST_WIDTH, TEST_HEIGHT, rgb(12, 12, 12))
  }

  private fun landscapeRegisterLikeFrame(): RigasSatiksmeVisualFrame {
    return RigasSatiksmeVisualFrame.solid(TEST_HEIGHT, TEST_WIDTH, rgb(246, 247, 250)).also { frame ->
      frame.fillNormalized(0.18, 0.20, 0.82, 0.27, rgb(30, 150, 95))
    }
  }

  private fun RigasSatiksmeVisualFrame.fillNormalized(
    left: Double,
    top: Double,
    right: Double,
    bottom: Double,
    color: Int
  ) {
    fillRect(
      (width * left).toInt(),
      (height * top).toInt(),
      (width * right).toInt(),
      (height * bottom).toInt(),
      color
    )
  }

  private fun RigasSatiksmeVisualFrame.drawQrLikeNormalized(
    left: Double,
    top: Double,
    right: Double,
    bottom: Double
  ) {
    val l = (width * left).toInt()
    val t = (height * top).toInt()
    val r = (width * right).toInt()
    val b = (height * bottom).toInt()
    fillRect(l, t, r, b, rgb(255, 255, 255))
    val cell = ((r - l) / 17).coerceAtLeast(1)
    for (y in 0 until 17) {
      for (x in 0 until 17) {
        if ((x * 31 + y * 17 + x * y) % 5 <= 1) {
          fillRect(l + x * cell, t + y * cell, l + (x + 1) * cell, t + (y + 1) * cell, rgb(0, 0, 0))
        }
      }
    }
  }

  private fun RigasSatiksmeVisualFrame.drawDigitsNormalized(
    digits: String,
    left: Double,
    top: Double,
    right: Double,
    bottom: Double
  ) {
    val l = (width * left).toInt()
    val t = (height * top).toInt()
    val r = (width * right).toInt()
    val b = (height * bottom).toInt()
    drawDigitText(digits, l, t, r, b, rgb(20, 20, 20))
  }

  private fun rgb(red: Int, green: Int, blue: Int): Int {
    return (255 shl 24) or (red shl 16) or (green shl 8) or blue
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

  private companion object {
    private const val TEST_WIDTH = 360
    private const val TEST_HEIGHT = 808
  }
}
