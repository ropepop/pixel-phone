package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal interface RigasSatiksmeVisualGateway {
  suspend fun launchApp(): Boolean
  suspend fun waitForForeground(): Boolean
  suspend fun captureFrame(reason: String): RigasSatiksmeVisualFrame?
  suspend fun tap(x: Int, y: Int, reason: String): Boolean
  suspend fun enterManualCode(cleanDigits: String, fieldX: Int, fieldY: Int): Boolean
  suspend fun pressBack(reason: String): Boolean
}

internal class RigasSatiksmeVisualDriver(
  private val gateway: RigasSatiksmeVisualGateway,
  private val maxProofAttempts: Int = DEFAULT_MAX_PROOF_ATTEMPTS
) {
  suspend fun run(cleanDigits: String): RigasSatiksmeMonthlyTicketFlowResult {
    if (!gateway.launchApp()) {
      return failure("rs_app_launch_failed")
    }
    if (!gateway.waitForForeground()) {
      return failure("rs_app_foreground_failed")
    }

    var currentFrame: RigasSatiksmeVisualFrame? = null
    var currentState = RigasSatiksmeVisualState.UNKNOWN
    for (attempt in 1..INITIAL_VISUAL_RECOGNITION_ATTEMPTS) {
      currentFrame = gateway.captureFrame("rs_visual_initial_$attempt")
      currentState = currentFrame?.let { RigasSatiksmeVisualClassifier.classify(it, cleanDigits) }
        ?: RigasSatiksmeVisualState.UNKNOWN
      if (currentState != RigasSatiksmeVisualState.UNKNOWN) {
        break
      }
      delay(INITIAL_VISUAL_RECOGNITION_DELAY_MILLIS)
    }
    val observedStates = mutableListOf(currentState.name.lowercase())
    if (currentState == RigasSatiksmeVisualState.UNKNOWN) {
      return failure("rs_monthly_ticket_unknown_state", details = "visual_initial_unrecognized")
    }
    if (currentState == RigasSatiksmeVisualState.CONTROL_MATCHING ||
      currentState == RigasSatiksmeVisualState.CONTROL_STALE
    ) {
      gateway.pressBack("rs_initial_control_reset")
      delay(INITIAL_RESET_SETTLE_MILLIS)
      currentFrame = gateway.captureFrame("rs_visual_after_initial_reset")
      currentState = currentFrame?.let { RigasSatiksmeVisualClassifier.classify(it, cleanDigits) }
        ?: RigasSatiksmeVisualState.UNKNOWN
      observedStates += currentState.name.lowercase()
    }

    var workingFrame = currentFrame
    var workingState = currentState
    var navigationActions = 0
    var registerAttempts = 0
    var manualChoiceAttempts = 0
    var controlResetAttempts = 0
    while (!workingState.isManualEntryState() &&
      navigationActions < MAX_PRE_INPUT_NAVIGATION_ACTIONS
    ) {
      when (workingState) {
        RigasSatiksmeVisualState.REGISTER_READY -> {
          val reason = if (registerAttempts == 0) "rs_register_trip" else "rs_register_trip_retry"
          val captureReason = if (registerAttempts == 0) "rs_visual_after_register" else "rs_visual_after_register_retry"
          tapTarget(workingFrame, REGISTER_TRIP, reason)
          delay(if (registerAttempts == 0) NAVIGATION_SETTLE_MILLIS else NAVIGATION_RETRY_SETTLE_MILLIS)
          workingFrame = gateway.captureFrame(captureReason)
          workingState = workingFrame?.let { RigasSatiksmeVisualClassifier.classify(it, cleanDigits) }
            ?: RigasSatiksmeVisualState.UNKNOWN
          observedStates += workingState.name.lowercase()
          registerAttempts += 1
          navigationActions += 1
        }
        RigasSatiksmeVisualState.MANUAL_CHOICE -> {
          val reason = if (manualChoiceAttempts == 0) "rs_manual_code_choice" else "rs_manual_code_choice_retry"
          val captureReason = if (manualChoiceAttempts == 0) "rs_visual_after_manual_choice" else "rs_visual_after_manual_choice_retry"
          tapTarget(workingFrame, MANUAL_CODE_CHOICE, reason)
          delay(if (manualChoiceAttempts == 0) NAVIGATION_SETTLE_MILLIS else NAVIGATION_RETRY_SETTLE_MILLIS)
          workingFrame = gateway.captureFrame(captureReason)
          workingState = workingFrame?.let { RigasSatiksmeVisualClassifier.classify(it, cleanDigits) }
            ?: RigasSatiksmeVisualState.UNKNOWN
          observedStates += workingState.name.lowercase()
          manualChoiceAttempts += 1
          navigationActions += 1
        }
        RigasSatiksmeVisualState.CONTROL_MATCHING,
        RigasSatiksmeVisualState.CONTROL_STALE -> {
          if (controlResetAttempts > 0) break
          gateway.pressBack("rs_register_landed_on_control_reset")
          delay(INITIAL_RESET_SETTLE_MILLIS)
          workingFrame = gateway.captureFrame("rs_visual_after_register_control_reset")
          workingState = workingFrame?.let { RigasSatiksmeVisualClassifier.classify(it, cleanDigits) }
            ?: RigasSatiksmeVisualState.UNKNOWN
          observedStates += workingState.name.lowercase()
          controlResetAttempts += 1
          navigationActions += 1
        }
        RigasSatiksmeVisualState.TRIP_REGISTERED_MODAL -> {
          tapTarget(workingFrame, TRIP_REGISTERED_OK, "rs_trip_registered_ok")
          delay(MODAL_SETTLE_MILLIS)
          workingFrame = gateway.captureFrame("rs_visual_after_initial_trip_registered_ok")
          workingState = workingFrame?.let { RigasSatiksmeVisualClassifier.classify(it, cleanDigits) }
            ?: RigasSatiksmeVisualState.UNKNOWN
          observedStates += workingState.name.lowercase()
          navigationActions += 1
        }
        else -> break
      }
    }
    if (!workingState.isManualEntryState()) {
      return failure("rs_manual_code_field_missing", details = "visual_input_screen_missing states=${observedStates.joinToString(",")}")
    }

    val entryProof = enterAndProveManualCode(cleanDigits, workingFrame, observedStates)
      ?: return failure(
        "rs_manual_code_entry_unverified",
        details = "visual_input_digits_missing states=${observedStates.joinToString(",")}"
      )
    if (!tapTarget(entryProof.frame, CONFIRM_CODE, "rs_confirm_code")) {
      return failure("rs_submit_not_confirmed", details = "visual_confirm_tap_failed states=${observedStates.joinToString(",")}")
    }
    delay(POST_SUBMIT_SETTLE_MILLIS)

    var staleControlObservations = 0
    var alternateConfirmUsed = false
    repeat(maxProofAttempts.coerceAtLeast(1)) {
      val frame = gateway.captureFrame("rs_visual_proof_${it + 1}")
      val state = frame?.let { captured -> RigasSatiksmeVisualClassifier.classify(captured, cleanDigits) }
        ?: RigasSatiksmeVisualState.UNKNOWN
      observedStates += state.name.lowercase()
      when (state) {
        RigasSatiksmeVisualState.CONTROL_MATCHING -> {
          return RigasSatiksmeMonthlyTicketFlowResult(
            ok = true,
            reason = "generated",
            hierarchy = "visual_control_ticket digits=$cleanDigits",
            details = "visual_driver states=${observedStates.joinToString(",")}"
          )
        }
        RigasSatiksmeVisualState.CONTROL_STALE -> {
          staleControlObservations += 1
          if (staleControlObservations > MAX_POST_SUBMIT_STALE_RECHECKS) {
            return failure(
              "rs_monthly_ticket_stale_code",
              details = "visual_final_ticket_mismatch states=${observedStates.joinToString(",")}"
            )
          }
          delay(STALE_PROOF_RECHECK_MILLIS)
        }
        RigasSatiksmeVisualState.TRIP_REGISTERED_MODAL -> {
          tapTarget(frame, TRIP_REGISTERED_OK, "rs_trip_registered_ok")
          delay(MODAL_SETTLE_MILLIS)
          val afterModalFrame = gateway.captureFrame("rs_visual_after_trip_registered_ok")
          val afterModalState = afterModalFrame?.let { captured -> RigasSatiksmeVisualClassifier.classify(captured, cleanDigits) }
            ?: RigasSatiksmeVisualState.UNKNOWN
          observedStates += afterModalState.name.lowercase()
          if (afterModalState == RigasSatiksmeVisualState.CONTROL_MATCHING) {
            return RigasSatiksmeMonthlyTicketFlowResult(
              ok = true,
              reason = "generated",
              hierarchy = "visual_control_ticket digits=$cleanDigits",
              details = "visual_driver states=${observedStates.joinToString(",")}"
            )
          }
          tapTarget(afterModalFrame ?: frame, TICKET_FOR_CONTROL, "rs_ticket_for_control")
          delay(NAVIGATION_SETTLE_MILLIS)
        }
        RigasSatiksmeVisualState.MANUAL_INPUT_REJECTED -> {
          if (!entryProof.rejectedBeforeSubmit) {
            return failure(
              "code_rejected_by_rs",
              details = "visual_wrong_code_after_confirm states=${observedStates.joinToString(",")}"
            )
          }
          if (!alternateConfirmUsed) {
            alternateConfirmUsed = true
            tapTarget(frame, CONFIRM_CODE_ALTERNATE, "rs_confirm_code_retry")
            delay(POST_SUBMIT_RETRY_SETTLE_MILLIS)
          } else {
            return failure(
              "rs_submit_not_confirmed",
              details = "visual_wrong_code_was_stale_before_confirm states=${observedStates.joinToString(",")}"
            )
          }
        }
        RigasSatiksmeVisualState.MANUAL_INPUT,
        RigasSatiksmeVisualState.MANUAL_INPUT_WITH_SUBMITTED_CODE,
        RigasSatiksmeVisualState.MANUAL_CHOICE,
        RigasSatiksmeVisualState.REGISTER_READY -> {
          if (!alternateConfirmUsed && state == RigasSatiksmeVisualState.MANUAL_INPUT_WITH_SUBMITTED_CODE) {
            alternateConfirmUsed = true
            tapTarget(frame, CONFIRM_CODE_ALTERNATE, "rs_confirm_code_retry")
            delay(POST_SUBMIT_RETRY_SETTLE_MILLIS)
          } else {
            return failure(
              "rs_submit_not_confirmed",
              details = "visual_post_submit_still_entry states=${observedStates.joinToString(",")}"
            )
          }
        }
        RigasSatiksmeVisualState.NO_MONTHLY_TICKET -> return failure("rs_monthly_ticket_missing")
        RigasSatiksmeVisualState.AUTH_BLOCKED -> return failure("rs_auth_blocked")
        RigasSatiksmeVisualState.UNKNOWN -> delay(UNKNOWN_RECHECK_MILLIS)
      }
    }

    return failure(
      "rs_monthly_ticket_unknown_state",
      details = "visual_proof_timeout states=${observedStates.joinToString(",")}"
    )
  }

  private suspend fun tapTarget(frame: RigasSatiksmeVisualFrame?, target: VisualTapTarget, reason: String): Boolean {
    val sourceWidth = frame?.sourceWidth?.takeIf { it > 0 } ?: DEFAULT_SOURCE_WIDTH
    val sourceHeight = frame?.sourceHeight?.takeIf { it > 0 } ?: DEFAULT_SOURCE_HEIGHT
    return gateway.tap(
      x = (sourceWidth * target.x).toInt().coerceIn(0, sourceWidth - 1),
      y = (sourceHeight * target.y).toInt().coerceIn(0, sourceHeight - 1),
      reason = reason
    )
  }

  private suspend fun enterAndProveManualCode(
    cleanDigits: String,
    frame: RigasSatiksmeVisualFrame?,
    observedStates: MutableList<String>
  ): ManualEntryProof? {
    var currentFrame = frame
    repeat(MAX_MANUAL_ENTRY_ATTEMPTS) { attempt ->
      val sourceWidth = currentFrame?.sourceWidth?.takeIf { it > 0 } ?: DEFAULT_SOURCE_WIDTH
      val sourceHeight = currentFrame?.sourceHeight?.takeIf { it > 0 } ?: DEFAULT_SOURCE_HEIGHT
      val entered = gateway.enterManualCode(
        cleanDigits = cleanDigits,
        fieldX = (sourceWidth * MANUAL_CODE_FIELD.x).toInt().coerceIn(0, sourceWidth - 1),
        fieldY = (sourceHeight * MANUAL_CODE_FIELD.y).toInt().coerceIn(0, sourceHeight - 1)
      )
      if (!entered) {
        return null
      }
      delay(MANUAL_ENTRY_PROOF_SETTLE_MILLIS)
      val proofFrame = gateway.captureFrame(
        if (attempt == 0) "rs_visual_after_code_entry" else "rs_visual_after_code_entry_retry"
      )
      val proofState = proofFrame?.let { RigasSatiksmeVisualClassifier.classify(it, cleanDigits) }
        ?: RigasSatiksmeVisualState.UNKNOWN
      observedStates += proofState.name.lowercase()
      if (proofState == RigasSatiksmeVisualState.MANUAL_INPUT_WITH_SUBMITTED_CODE ||
        proofState == RigasSatiksmeVisualState.MANUAL_INPUT_REJECTED
      ) {
        val acceptedProofFrame = proofFrame ?: return null
        return ManualEntryProof(
          frame = acceptedProofFrame,
          rejectedBeforeSubmit = proofState == RigasSatiksmeVisualState.MANUAL_INPUT_REJECTED
        )
      }
      currentFrame = proofFrame ?: currentFrame
    }
    return null
  }

  private fun failure(reason: String, details: String = ""): RigasSatiksmeMonthlyTicketFlowResult {
    return RigasSatiksmeMonthlyTicketFlowResult(ok = false, reason = reason, details = details)
  }

  private data class ManualEntryProof(
    val frame: RigasSatiksmeVisualFrame,
    val rejectedBeforeSubmit: Boolean
  )

  private data class VisualTapTarget(val x: Double, val y: Double)

  companion object {
    private const val DEFAULT_SOURCE_WIDTH = 1080
    private const val DEFAULT_SOURCE_HEIGHT = 2424
    private const val DEFAULT_MAX_PROOF_ATTEMPTS = 6
    private const val MAX_MANUAL_ENTRY_ATTEMPTS = 2
    private const val INITIAL_VISUAL_RECOGNITION_ATTEMPTS = 5
    private const val MAX_POST_SUBMIT_STALE_RECHECKS = 2
    private const val MAX_PRE_INPUT_NAVIGATION_ACTIONS = 6
    private const val INITIAL_VISUAL_RECOGNITION_DELAY_MILLIS = 160L
    private const val INITIAL_RESET_SETTLE_MILLIS = 260L
    private const val NAVIGATION_SETTLE_MILLIS = 180L
    private const val NAVIGATION_RETRY_SETTLE_MILLIS = 420L
    private const val MODAL_SETTLE_MILLIS = 160L
    private const val MANUAL_ENTRY_PROOF_SETTLE_MILLIS = 140L
    private const val POST_SUBMIT_SETTLE_MILLIS = 260L
    private const val POST_SUBMIT_RETRY_SETTLE_MILLIS = 260L
    private const val STALE_PROOF_RECHECK_MILLIS = 180L
    private const val UNKNOWN_RECHECK_MILLIS = 180L

    private val REGISTER_TRIP = VisualTapTarget(0.500, 555.0 / 2424.0)
    private val MANUAL_CODE_CHOICE = VisualTapTarget(0.500, 0.915)
    private val MANUAL_CODE_FIELD = VisualTapTarget(0.500, 0.495)
    private val CONFIRM_CODE = VisualTapTarget(0.500, 0.658)
    private val CONFIRM_CODE_ALTERNATE = VisualTapTarget(0.500, 0.680)
    private val TRIP_REGISTERED_OK = VisualTapTarget(0.500, 0.545)
    private val TICKET_FOR_CONTROL = VisualTapTarget(0.500, 0.245)
  }
}

internal enum class RigasSatiksmeVisualState {
  REGISTER_READY,
  MANUAL_CHOICE,
  MANUAL_INPUT,
  MANUAL_INPUT_WITH_SUBMITTED_CODE,
  MANUAL_INPUT_REJECTED,
  TRIP_REGISTERED_MODAL,
  CONTROL_MATCHING,
  CONTROL_STALE,
  NO_MONTHLY_TICKET,
  AUTH_BLOCKED,
  UNKNOWN
}

private fun RigasSatiksmeVisualState.isManualEntryState(): Boolean {
  return this == RigasSatiksmeVisualState.MANUAL_INPUT ||
    this == RigasSatiksmeVisualState.MANUAL_INPUT_WITH_SUBMITTED_CODE ||
    this == RigasSatiksmeVisualState.MANUAL_INPUT_REJECTED
}

internal object RigasSatiksmeVisualClassifier {
  fun classify(frame: RigasSatiksmeVisualFrame, cleanDigits: String): RigasSatiksmeVisualState {
    if (frame.width > frame.height) {
      return RigasSatiksmeVisualState.UNKNOWN
    }
    if (looksLikeControlTicket(frame)) {
      val visualDigits = frame.readBottomCodeDigits()
      return if (visualDigits == cleanDigits) {
        RigasSatiksmeVisualState.CONTROL_MATCHING
      } else {
        RigasSatiksmeVisualState.CONTROL_STALE
      }
    }
    if (looksLikeTripRegisteredModal(frame)) {
      return RigasSatiksmeVisualState.TRIP_REGISTERED_MODAL
    }
    if (looksLikeManualInput(frame)) {
      val fieldDigits = frame.readManualCodeFieldDigits()
      val rejected = looksLikeWrongCode(frame)
      if (fieldDigits == cleanDigits && rejected) {
        return RigasSatiksmeVisualState.MANUAL_INPUT_REJECTED
      }
      if (fieldDigits == cleanDigits) {
        return RigasSatiksmeVisualState.MANUAL_INPUT_WITH_SUBMITTED_CODE
      }
      return RigasSatiksmeVisualState.MANUAL_INPUT
    }
    if (looksLikeRegisterReady(frame)) {
      return RigasSatiksmeVisualState.REGISTER_READY
    }
    if (looksLikeManualChoice(frame)) {
      return RigasSatiksmeVisualState.MANUAL_CHOICE
    }
    return RigasSatiksmeVisualState.UNKNOWN
  }

  private fun looksLikeControlTicket(frame: RigasSatiksmeVisualFrame): Boolean {
    val background = frame.stats(0.0, 0.0, 1.0, 1.0)
    val card = frame.stats(0.10, 0.10, 0.90, 0.80)
    val qr = frame.stats(0.22, 0.16, 0.78, 0.50)
    val codeBand = frame.stats(0.16, 0.62, 0.84, 0.78)
    return background.blueRatio >= 0.20 &&
      card.lightRatio >= 0.22 &&
      qr.darkRatio >= 0.12 &&
      qr.lightRatio >= 0.18 &&
      qr.contrast >= 55.0 &&
      codeBand.lightRatio >= 0.18
  }

  private fun looksLikeTripRegisteredModal(frame: RigasSatiksmeVisualFrame): Boolean {
    val background = frame.stats(0.0, 0.0, 1.0, 1.0)
    val modal = frame.stats(0.16, 0.34, 0.84, 0.60)
    val action = frame.stats(0.34, 0.49, 0.66, 0.58)
    return background.blueRatio >= 0.18 && modal.lightRatio >= 0.48 && modal.contrast <= 105.0 && action.blueRatio >= 0.14
  }

  private fun looksLikeManualChoice(frame: RigasSatiksmeVisualFrame): Boolean {
    val legacyButton = frame.stats(0.10, 0.48, 0.90, 0.61)
    val bottomButton = frame.stats(0.10, 0.88, 0.90, 0.98)
    return (legacyButton.blueRatio >= 0.22 && legacyButton.contrast >= 15.0) ||
      (bottomButton.lightRatio >= 0.45 && bottomButton.darkRatio >= 0.08 && bottomButton.contrast >= 55.0)
  }

  private fun looksLikeManualInput(frame: RigasSatiksmeVisualFrame): Boolean {
    val field = frame.stats(0.12, 0.43, 0.88, 0.53)
    val confirm = frame.stats(0.18, 0.60, 0.86, 0.70)
    return field.lightRatio >= 0.35 && field.darkRatio >= 0.01 && confirm.greenRatio >= 0.14
  }

  private fun looksLikeWrongCode(frame: RigasSatiksmeVisualFrame): Boolean {
    val error = frame.stats(0.14, 0.52, 0.86, 0.59)
    return error.redRatio >= 0.035 && error.contrast >= 20.0
  }

  private fun looksLikeRegisterReady(frame: RigasSatiksmeVisualFrame): Boolean {
    val registerButton = frame.stats(0.14, 0.17, 0.86, 0.30)
    return registerButton.greenRatio >= 0.18
  }
}

internal class RigasSatiksmeVisualFrame(
  val width: Int,
  val height: Int,
  private val pixels: IntArray,
  val sourceWidth: Int = width,
  val sourceHeight: Int = height
) {
  fun pixel(x: Int, y: Int): Int {
    return pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
  }

  fun fillRect(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
    val l = left.coerceIn(0, width)
    val t = top.coerceIn(0, height)
    val r = right.coerceIn(0, width)
    val b = bottom.coerceIn(0, height)
    for (y in t until b) {
      val offset = y * width
      for (x in l until r) {
        pixels[offset + x] = color
      }
    }
  }

  fun stats(left: Double, top: Double, right: Double, bottom: Double): VisualRegionStats {
    val l = (width * left).toInt().coerceIn(0, width)
    val t = (height * top).toInt().coerceIn(0, height)
    val r = (width * right).toInt().coerceIn(0, width)
    val b = (height * bottom).toInt().coerceIn(0, height)
    val regionPixels = ((r - l).coerceAtLeast(1)).toLong() * ((b - t).coerceAtLeast(1)).toLong()
    val step = kotlin.math.sqrt(regionPixels / STATS_TARGET_SAMPLES.toDouble()).toInt().coerceAtLeast(1)
    var sampled = 0
    var dark = 0
    var light = 0
    var blue = 0
    var green = 0
    var redPixels = 0
    var sum = 0L
    var sumSquares = 0L
    var y = t
    while (y < b) {
      var x = l
      while (x < r) {
        val pixel = pixel(x, y)
        val redChannel = red(pixel)
        val greenChannel = green(pixel)
        val blueChannel = blue(pixel)
        val luma = luminance(redChannel, greenChannel, blueChannel)
        if (luma <= 85) dark += 1
        if (luma >= 175) light += 1
        if (blueChannel >= 115 && blueChannel - redChannel >= 35 && blueChannel - greenChannel >= 15) blue += 1
        if (greenChannel >= 105 && greenChannel - redChannel >= 25 && greenChannel - blueChannel >= 10) green += 1
        if (redChannel >= 145 && redChannel - greenChannel >= 45 && redChannel - blueChannel >= 45) redPixels += 1
        sum += luma
        sumSquares += luma.toLong() * luma.toLong()
        sampled += 1
        x += step
      }
      y += step
    }
    if (sampled == 0) {
      return VisualRegionStats()
    }
    val mean = sum / sampled.toDouble()
    val variance = sumSquares / sampled.toDouble() - mean * mean
    return VisualRegionStats(
      darkRatio = dark / sampled.toDouble(),
      lightRatio = light / sampled.toDouble(),
      blueRatio = blue / sampled.toDouble(),
      greenRatio = green / sampled.toDouble(),
      redRatio = redPixels / sampled.toDouble(),
      contrast = kotlin.math.sqrt(max(0.0, variance))
    )
  }

  fun readBottomCodeDigits(): String? {
    return readDigitsInRegion(0.18, 0.62, 0.84, 0.78)
  }

  fun readManualCodeFieldDigits(): String? {
    return readDigitsInRegion(0.24, 0.445, 0.76, 0.515)
  }

  private fun readDigitsInRegion(leftRatio: Double, topRatio: Double, rightRatio: Double, bottomRatio: Double): String? {
    val left = (width * leftRatio).toInt()
    val top = (height * topRatio).toInt()
    val right = (width * rightRatio).toInt()
    val bottom = (height * bottomRatio).toInt()
    val columns = IntArray((right - left).coerceAtLeast(0))
    var minX = right
    var maxX = left
    var minY = bottom
    var maxY = top
    for (y in top until bottom) {
      for (x in left until right) {
        if (isDark(pixel(x, y))) {
          columns[x - left] += 1
          minX = min(minX, x)
          maxX = max(maxX, x)
          minY = min(minY, y)
          maxY = max(maxY, y)
        }
      }
    }
    if (maxX <= minX || maxY <= minY) return null
    val ranges = digitRanges(columns, left, minX, maxX, minY, maxY)
    if (ranges.size != 5) return null
    return buildString {
      for ((rangeLeft, rangeRight) in ranges) {
        append(matchDigit(rangeLeft, minY, rangeRight, maxY) ?: return null)
      }
    }
  }

  private fun digitRanges(
    columns: IntArray,
    baseLeft: Int,
    minX: Int,
    maxX: Int,
    minY: Int,
    maxY: Int
  ): List<Pair<Int, Int>> {
    val threshold = max(1, ((maxY - minY + 1) * 0.08).toInt())
    val groups = mutableListOf<Pair<Int, Int>>()
    var start = -1
    var lastDark = -1
    columns.forEachIndexed { index, count ->
      val x = baseLeft + index
      if (x < minX || x > maxX) return@forEachIndexed
      if (count >= threshold) {
        if (start < 0) start = x
        lastDark = x
      } else if (start >= 0 && x - lastDark > 2) {
        groups += start to lastDark
        start = -1
      }
    }
    if (start >= 0) groups += start to lastDark
    val digitGroups = groups.filter { (l, r) -> r - l >= 2 }
    if (digitGroups.size == 5) {
      val centers = digitGroups.map { (l, r) -> (l + r) / 2.0 }
      val pitch = centers.zipWithNext { leftCenter, rightCenter -> rightCenter - leftCenter }
        .filter { it >= 4.0 }
        .sorted()
        .let { deltas ->
          when {
            deltas.isEmpty() -> null
            deltas.size % 2 == 1 -> deltas[deltas.size / 2]
            else -> (deltas[deltas.size / 2 - 1] + deltas[deltas.size / 2]) / 2.0
          }
        }
      if (pitch != null) {
        val halfWidth = max(4.0, pitch * 0.46)
        return centers.map { center ->
          max(minX, (center - halfWidth).toInt()) to
            min(maxX, (center + halfWidth).toInt())
        }
      }
    }

    val boxWidth = (maxX - minX + 1).coerceAtLeast(5)
    return (0 until 5).map { index ->
      val l = minX + (boxWidth * index) / 5
      val r = minX + (boxWidth * (index + 1)) / 5
      l to r
    }
  }

  private fun matchDigit(left: Int, top: Int, right: Int, bottom: Int): Char? {
    var bestDigit: Char? = null
    var bestScore = Double.NEGATIVE_INFINITY
    DIGIT_PATTERNS.forEach { (digit, pattern) ->
      var score = 0
      var cells = 0
      for (row in 0 until DIGIT_ROWS) {
        for (col in 0 until DIGIT_COLUMNS) {
          val expected = pattern[row][col] == '1'
          val actual = cellDarkRatio(left, top, right, bottom, col, row) >= 0.18
          if (actual == expected) score += 1 else score -= 1
          cells += 1
        }
      }
      val normalized = score / cells.toDouble()
      if (normalized > bestScore) {
        bestScore = normalized
        bestDigit = digit
      }
    }
    return bestDigit.takeIf { bestScore >= 0.34 }
  }

  private fun cellDarkRatio(
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    col: Int,
    row: Int
  ): Double {
    val l = left + ((right - left + 1) * col) / DIGIT_COLUMNS
    val r = left + ((right - left + 1) * (col + 1)) / DIGIT_COLUMNS
    val t = top + ((bottom - top + 1) * row) / DIGIT_ROWS
    val b = top + ((bottom - top + 1) * (row + 1)) / DIGIT_ROWS
    var sampled = 0
    var dark = 0
    for (y in t..b) {
      for (x in l..r) {
        if (x in 0 until width && y in 0 until height) {
          if (isDark(pixel(x, y))) dark += 1
          sampled += 1
        }
      }
    }
    return if (sampled == 0) 0.0 else dark / sampled.toDouble()
  }

  companion object {
    private const val STATS_TARGET_SAMPLES = 4_500

    fun solid(width: Int, height: Int, color: Int): RigasSatiksmeVisualFrame {
      return RigasSatiksmeVisualFrame(width, height, IntArray(width * height) { color })
    }
  }
}

internal data class VisualRegionStats(
  val darkRatio: Double = 0.0,
  val lightRatio: Double = 0.0,
  val blueRatio: Double = 0.0,
  val greenRatio: Double = 0.0,
  val redRatio: Double = 0.0,
  val contrast: Double = 0.0
)

internal fun RigasSatiksmeVisualFrame.drawDigitText(
  digits: String,
  left: Int,
  top: Int,
  right: Int,
  bottom: Int,
  color: Int
) {
  if (digits.isBlank()) return
  val totalWidth = (right - left).coerceAtLeast(digits.length * DIGIT_COLUMNS)
  val charWidth = totalWidth / digits.length
  digits.forEachIndexed { index, digit ->
    val pattern = DIGIT_PATTERNS[digit] ?: return@forEachIndexed
    val charLeft = left + index * charWidth
    val charRight = left + (index + 1) * charWidth - max(1, charWidth / 8)
    val cellWidth = ((charRight - charLeft) / DIGIT_COLUMNS).coerceAtLeast(1)
    val cellHeight = ((bottom - top) / DIGIT_ROWS).coerceAtLeast(1)
    for (row in 0 until DIGIT_ROWS) {
      for (col in 0 until DIGIT_COLUMNS) {
        if (pattern[row][col] == '1') {
          fillRect(
            charLeft + col * cellWidth,
            top + row * cellHeight,
            charLeft + (col + 1) * cellWidth,
            top + (row + 1) * cellHeight,
            color
          )
        }
      }
    }
  }
}

private const val DIGIT_COLUMNS = 5
private const val DIGIT_ROWS = 7

private val DIGIT_PATTERNS = mapOf(
  '0' to listOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
  '1' to listOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
  '2' to listOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
  '3' to listOf("11110", "00001", "00001", "01110", "00001", "00001", "11110"),
  '4' to listOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
  '5' to listOf("11111", "10000", "10000", "11110", "00001", "00001", "11110"),
  '6' to listOf("01110", "10000", "10000", "11110", "10001", "10001", "01110"),
  '7' to listOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
  '8' to listOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
  '9' to listOf("01110", "10001", "10001", "01111", "00001", "00001", "01110")
)

private fun isDark(pixel: Int): Boolean {
  return luminance(red(pixel), green(pixel), blue(pixel)) <= 95
}

private fun red(pixel: Int): Int = (pixel shr 16) and 0xff
private fun green(pixel: Int): Int = (pixel shr 8) and 0xff
private fun blue(pixel: Int): Int = pixel and 0xff
private fun luminance(red: Int, green: Int, blue: Int): Int = (red * 299 + green * 587 + blue * 114) / 1000
