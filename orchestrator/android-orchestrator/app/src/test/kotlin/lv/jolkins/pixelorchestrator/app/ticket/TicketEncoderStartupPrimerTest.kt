package lv.jolkins.pixelorchestrator.app.ticket

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketEncoderStartupPrimerTest {
  @Test
  fun productionPrimerCapsInputsAtThreeWithOneHundredMillisecondSpacing() {
    val primer = TicketEncoderStartupPrimer(0)

    assertEquals(3, primer.inputLimit())
    assertTrue(primer.canPostInput(1_000L))
    primer.noteInputPosted(1_000L)
    assertEquals(100L, primer.millisUntilNextInput(1_000L))
    assertFalse(primer.canPostInput(1_099L))
    assertTrue(primer.canPostInput(1_100L))
    primer.noteInputPosted(1_100L)
    assertTrue(primer.canPostInput(1_200L))
    primer.noteInputPosted(1_200L)

    assertEquals(3, primer.inputPosts())
    assertFalse(primer.canPostAnotherInput())
    assertFalse(primer.canPostInput(2_000L))
  }

  @Test
  fun oneFrameReadinessProbeNeverReceivesExtraPrimerInputs() {
    val primer = TicketEncoderStartupPrimer(1)

    primer.noteInputPosted(50L)

    assertEquals(1, primer.inputLimit())
    assertEquals(1, primer.inputPosts())
    assertFalse(primer.canPostAnotherInput())
  }

  @Test
  fun firstCompleteKeyFrameStopsPostsAtInputOneTwoOrThree() {
    for (firstKeyFrameInput in 1..3) {
      val primer = TicketEncoderStartupPrimer(0)
      repeat(firstKeyFrameInput) { index ->
        val postedAt = 1_000L + index * TicketEncoderStartupPrimer.INPUT_SPACING_MILLIS
        primer.noteInputPosted(postedAt)
      }

      assertEquals(
        TicketEncoderStartupPrimer.OutputDisposition.FORWARD,
        primer.classifyCompleteAccessUnit(
          true,
          true,
          1_000L + firstKeyFrameInput * 100L
        )
      )

      assertEquals(firstKeyFrameInput, primer.inputPosts())
      assertTrue(primer.firstKeyFrameForwarded())
      assertFalse(primer.canPostAnotherInput())
      assertEquals(firstKeyFrameInput * 100L, primer.firstKeyFrameLatencyMillis())
    }
  }

  @Test
  fun codecConfigurationIsForwardedAndOnlyFirstPrimerPictureEscapes() {
    val primer = TicketEncoderStartupPrimer(0)
    primer.noteInputPosted(100L)
    primer.noteInputPosted(200L)
    primer.noteInputPosted(300L)

    assertEquals(
      TicketEncoderStartupPrimer.OutputDisposition.FORWARD,
      primer.classifyCompleteAccessUnit(false, false, 310L)
    )
    assertEquals(
      TicketEncoderStartupPrimer.OutputDisposition.SUPPRESS,
      primer.classifyCompleteAccessUnit(true, false, 320L)
    )
    assertEquals(
      TicketEncoderStartupPrimer.OutputDisposition.FORWARD,
      primer.classifyCompleteAccessUnit(true, true, 330L)
    )
    assertEquals(
      TicketEncoderStartupPrimer.OutputDisposition.SUPPRESS,
      primer.classifyCompleteAccessUnit(true, true, 340L)
    )

    assertEquals(3, primer.mediaOutputs())
    assertEquals(2, primer.suppressedMediaOutputs())
    assertEquals(230L, primer.firstKeyFrameLatencyMillis())
  }

  @Test
  fun combinedConfigurationAndKeyFrameIsTheFirstForwardedPicture() {
    val primer = TicketEncoderStartupPrimer(0)
    primer.noteInputPosted(700L)

    assertEquals(
      TicketEncoderStartupPrimer.OutputDisposition.FORWARD,
      primer.classifyCompleteAccessUnit(true, true, 725L)
    )
    assertTrue(primer.firstKeyFrameForwarded())
    assertEquals(1, primer.mediaOutputs())
    assertEquals(0, primer.suppressedMediaOutputs())
  }

  @Test
  fun partialAccessUnitIsClassifiedOnlyAfterAssemblerCompletesIt() {
    val assembler = TicketH264EncoderOutputAssembler()
    val config = annexB(
      byteArrayOf(0x67, 0x11, 0x22),
      byteArrayOf(0x68, 0x33)
    )
    val configOutput = assembler.accept(config, false, true, false)
    assertTrue(configOutput != null)

    val keyFrame = annexB(byteArrayOf(0x65, 0x44, 0x55, 0x66))
    val primer = TicketEncoderStartupPrimer(0)
    primer.noteInputPosted(1_000L)
    assertNull(
      assembler.accept(
        keyFrame.copyOfRange(0, keyFrame.size - 1),
        true,
        false,
        true
      )
    )
    assertFalse(primer.firstKeyFrameForwarded())

    val emitted = assembler.accept(
      keyFrame.copyOfRange(keyFrame.size - 1, keyFrame.size),
      false,
      false,
      false
    )
    assertTrue(emitted != null)
    val complete = emitted!!
    assertTrue(complete.idrKeyFrame)
    assertEquals(
      TicketEncoderStartupPrimer.OutputDisposition.FORWARD,
      primer.classifyCompleteAccessUnit(
        complete.containsVcl,
        complete.idrKeyFrame,
        1_025L
      )
    )
    assertTrue(primer.firstKeyFrameForwarded())
  }

  @Test
  fun boundedFallbackForwardsOneLateKeyFrameAndSuppressesItsQueuedSiblings() {
    val primer = TicketEncoderStartupPrimer(0)
    primer.noteInputPosted(0L)
    primer.noteInputPosted(100L)
    primer.noteInputPosted(200L)

    // The vendor encoder returns nothing during the bounded primer window, but the inputs can be
    // delayed rather than dropped. Keep the gate through the next ordinary drain.
    assertFalse(primer.firstKeyFrameForwarded())
    primer.beginFallbackWait()

    assertEquals(
      TicketEncoderStartupPrimer.OutputDisposition.FORWARD,
      primer.classifyCompleteAccessUnit(true, true, 1_200L)
    )
    // The ordinary drain has now returned TRY_AGAIN, but the gate stays active until the next
    // cadence boundary. A delayed sibling that becomes ready during that one-second period drops.
    assertFalse(primer.finished())
    assertEquals(
      TicketEncoderStartupPrimer.OutputDisposition.SUPPRESS,
      primer.classifyCompleteAccessUnit(true, true, 1_900L)
    )
    assertEquals(1, primer.suppressedMediaOutputs())

    // The caller finishes after the final boundary sweep reaches TRY_AGAIN. Future steady AUs
    // cannot be consumed by a missing/dropped primer input count.
    primer.finish()
    assertEquals(
      TicketEncoderStartupPrimer.OutputDisposition.FORWARD,
      primer.classifyCompleteAccessUnit(true, true, 2_200L)
    )
  }

  @Test
  fun boundaryDrainSuppressesOldPrimerSiblingsAndForwardsOnlyTheNewestIdr() {
    val primer = TicketEncoderStartupPrimer(0)
    primer.noteInputPosted(0L)
    primer.noteInputPosted(100L)
    primer.noteInputPosted(200L)
    assertEquals(
      TicketEncoderStartupPrimer.OutputDisposition.FORWARD,
      primer.classifyCompleteAccessUnit(true, true, 220L)
    )

    primer.beginBoundaryDrain()
    fun offerIdr(label: String, atMillis: Long) {
      val payload = label.toByteArray()
      assertEquals(
        TicketEncoderStartupPrimer.OutputDisposition.BUFFER_BOUNDARY,
        primer.classifyCompleteAccessUnit(payload, true, true, atMillis)
      )
    }

    offerIdr("old-primer-sibling-1", 1_205L)
    offerIdr("old-primer-sibling-2", 1_210L)
    offerIdr("steady-boundary-idr", 1_215L)
    val boundaryOutput = ByteArrayOutputStream()
    TicketRootHardwareH264CaptureMain.forwardSelectedBoundaryAccessUnit(boundaryOutput, primer)

    assertEquals("steady-boundary-idr", boundaryOutput.toString(Charsets.UTF_8.name()))
    assertEquals(2, primer.suppressedMediaOutputs())
    assertTrue(primer.boundaryAccessUnitForwarded())
    assertFalse(primer.boundaryDrainActive())

    primer.finish()
    assertEquals(
      TicketEncoderStartupPrimer.OutputDisposition.FORWARD,
      primer.classifyCompleteAccessUnit(true, true, 2_200L)
    )
  }

  @Test
  fun mediaCodecKeyFlagWithoutAnIdrDoesNotCompleteThePrimer() {
    val assembler = TicketH264EncoderOutputAssembler()
    val config = annexB(
      byteArrayOf(0x67, 0x11, 0x22),
      byteArrayOf(0x68, 0x33)
    )
    assertTrue(assembler.accept(config, false, true, false) != null)
    val flaggedDelta = assembler.accept(
      annexB(byteArrayOf(0x41, 0x44, 0x55)),
      false,
      false,
      true
    )
    assertTrue(flaggedDelta != null)
    assertTrue(flaggedDelta!!.keyFrame)
    assertFalse(flaggedDelta.idrKeyFrame)

    val primer = TicketEncoderStartupPrimer(0)
    primer.noteInputPosted(10L)
    assertEquals(
      TicketEncoderStartupPrimer.OutputDisposition.SUPPRESS,
      primer.classifyCompleteAccessUnit(
        flaggedDelta.containsVcl,
        flaggedDelta.idrKeyFrame,
        20L
      )
    )
    assertFalse(primer.firstKeyFrameForwarded())
  }

  @Test
  fun realIdrCompletesPrimerEvenWhenMediaCodecOmitsKeyFrameFlag() {
    val assembler = TicketH264EncoderOutputAssembler()
    val config = annexB(
      byteArrayOf(0x67, 0x11, 0x22),
      byteArrayOf(0x68, 0x33)
    )
    assertTrue(assembler.accept(config, false, true, false) != null)
    val idrWithoutFlag = assembler.accept(
      annexB(byteArrayOf(0x65, 0x44, 0x55)),
      false,
      false,
      false
    )
    assertTrue(idrWithoutFlag != null)
    val accessUnit = idrWithoutFlag!!
    assertFalse(accessUnit.keyFrame)
    assertTrue(accessUnit.idrKeyFrame)

    val primer = TicketEncoderStartupPrimer(0)
    primer.noteInputPosted(10L)
    assertEquals(
      TicketEncoderStartupPrimer.OutputDisposition.FORWARD,
      primer.classifyCompleteAccessUnit(
        accessUnit.containsVcl,
        accessUnit.idrKeyFrame,
        20L
      )
    )
    assertTrue(primer.firstKeyFrameForwarded())
    val progress = TicketEncoderDrainProgress.fromDequeuedAccessUnit(
      accessUnit.payload.size,
      accessUnit.payload.size,
      accessUnit.containsVcl
    )
    assertEquals(1, progress.encodedFrameOutputs)
  }

  @Test
  fun configurationFlagCannotHideACombinedNonIdrPicture() {
    val assembler = TicketH264EncoderOutputAssembler()
    val combined = assembler.accept(
      annexB(
        byteArrayOf(0x67, 0x11, 0x22),
        byteArrayOf(0x68, 0x33),
        byteArrayOf(0x41, 0x44, 0x55)
      ),
      false,
      true,
      true
    )
    assertTrue(combined != null)
    val accessUnit = combined!!
    assertTrue(accessUnit.codecConfig)
    assertTrue(accessUnit.containsVcl)
    assertFalse(accessUnit.idrKeyFrame)

    val primer = TicketEncoderStartupPrimer(0)
    primer.noteInputPosted(10L)
    assertEquals(
      TicketEncoderStartupPrimer.OutputDisposition.SUPPRESS,
      primer.classifyCompleteAccessUnit(
        accessUnit.containsVcl,
        accessUnit.idrKeyFrame,
        20L
      )
    )
    assertFalse(primer.firstKeyFrameForwarded())
    val progress = TicketEncoderDrainProgress.fromDequeuedAccessUnit(
      accessUnit.payload.size,
      accessUnit.payload.size,
      accessUnit.containsVcl
    )
    assertEquals(1, progress.encodedFrameOutputs)
  }

  @Test
  fun unflaggedNonVclMetadataIsForwardedAndDoesNotCountAsPicture() {
    val assembler = TicketH264EncoderOutputAssembler()
    val config = annexB(
      byteArrayOf(0x67, 0x11, 0x22),
      byteArrayOf(0x68, 0x33)
    )
    assertTrue(assembler.accept(config, false, true, false) != null)
    val sei = assembler.accept(
      annexB(byteArrayOf(0x06, 0x05, 0x01)),
      false,
      false,
      false
    )
    assertTrue(sei != null)
    val accessUnit = sei!!
    assertFalse(accessUnit.codecConfig)
    assertFalse(accessUnit.containsVcl)

    val primer = TicketEncoderStartupPrimer(0)
    primer.noteInputPosted(10L)
    assertEquals(
      TicketEncoderStartupPrimer.OutputDisposition.FORWARD,
      primer.classifyCompleteAccessUnit(accessUnit.containsVcl, accessUnit.idrKeyFrame, 20L)
    )
    assertEquals(0, primer.mediaOutputs())
    val progress = TicketEncoderDrainProgress.fromDequeuedAccessUnit(
      accessUnit.payload.size,
      accessUnit.payload.size,
      accessUnit.containsVcl
    )
    assertTrue(progress.madeCodecProgress)
    assertEquals(0, progress.encodedFrameOutputs)
  }

  @Test
  fun suppressedPrimerMediaStillCountsAsHealthyCodecProgress() {
    val primer = TicketEncoderStartupPrimer(0)
    primer.noteInputPosted(10L)
    primer.noteInputPosted(110L)
    primer.classifyCompleteAccessUnit(true, true, 120L)
    assertEquals(
      TicketEncoderStartupPrimer.OutputDisposition.SUPPRESS,
      primer.classifyCompleteAccessUnit(true, true, 130L)
    )

    val progress = TicketEncoderDrainProgress.fromDequeuedAccessUnit(40_000, 40_000, true)
    assertTrue(progress.madeCodecProgress)
    assertEquals(1, progress.encodedFrameOutputs)
  }

  private fun annexB(vararg nals: ByteArray): ByteArray = nals.fold(byteArrayOf()) { result, nal ->
    result + byteArrayOf(0, 0, 0, 1) + nal
  }
}
