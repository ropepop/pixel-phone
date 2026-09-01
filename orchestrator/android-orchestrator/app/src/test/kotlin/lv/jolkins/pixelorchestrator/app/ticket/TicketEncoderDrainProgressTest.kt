package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketEncoderDrainProgressTest {
  @Test
  fun codecConfigurationIsForwardedButDoesNotCountAsAnEncodedFrame() {
    assertFalse(TicketEncoderDrainProgress.isEncodedFrameOutput(128, true, false))
  }

  @Test
  fun keyFrameWithConfigurationCountsAsAnEncodedFrame() {
    assertTrue(TicketEncoderDrainProgress.isEncodedFrameOutput(128, true, true))
  }

  @Test
  fun emptyOutputDoesNotCountAsAnEncodedFrame() {
    assertFalse(TicketEncoderDrainProgress.isEncodedFrameOutput(0, false, false))
  }

  @Test
  fun nonConfigurationMediaOutputCountsAsAnEncodedFrame() {
    assertTrue(TicketEncoderDrainProgress.isEncodedFrameOutput(128, false, false))
  }

  @Test
  fun partialAndCodecConfigurationBuffersAreProgressWithoutCompleteMedia() {
    val partial = TicketEncoderDrainProgress.fromDequeuedOutput(128, 0, false, false)
    val config = TicketEncoderDrainProgress.fromDequeuedOutput(96, 96, true, false)
    val combined = partial.plus(config)

    assertTrue(partial.madeCodecProgress)
    assertEquals(0, partial.encodedFrameOutputs)
    assertTrue(config.madeCodecProgress)
    assertEquals(0, config.encodedFrameOutputs)
    assertTrue(combined.madeCodecProgress)
    assertEquals(0, combined.encodedFrameOutputs)
  }

  @Test
  fun completedMediaAccumulatesWithEarlierFragmentProgress() {
    val partial = TicketEncoderDrainProgress.fromDequeuedOutput(64, 0, false, false)
    val media = TicketEncoderDrainProgress.fromDequeuedOutput(128, 128, false, false)
    val combined = partial.plus(media)

    assertTrue(combined.madeCodecProgress)
    assertEquals(1, combined.encodedFrameOutputs)
  }

  @Test
  fun multipleCompletedMediaOutputsAccumulate() {
    val first = TicketEncoderDrainProgress.fromDequeuedOutput(40_000, 40_000, false, true)
    val second = TicketEncoderDrainProgress.fromDequeuedOutput(20_000, 20_000, false, true)

    assertEquals(2, first.plus(second).encodedFrameOutputs)
  }

  @Test
  fun assembledNonVclMetadataIsProgressButNotAPicture() {
    val progress = TicketEncoderDrainProgress.fromDequeuedAccessUnit(128, 128, false)

    assertTrue(progress.madeCodecProgress)
    assertEquals(0, progress.encodedFrameOutputs)
  }

  @Test
  fun assembledVclCountsAsPictureRegardlessOfCodecFlags() {
    val progress = TicketEncoderDrainProgress.fromDequeuedAccessUnit(128, 128, true)

    assertTrue(progress.madeCodecProgress)
    assertEquals(1, progress.encodedFrameOutputs)
  }
}
