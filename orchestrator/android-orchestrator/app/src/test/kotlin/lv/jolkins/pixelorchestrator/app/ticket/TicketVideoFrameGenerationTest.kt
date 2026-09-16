package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketVideoFrameGenerationTest {
  @Test
  fun earlyColdPictureCannotAcquireTheLaterConfiguration() {
    val early = TicketVideoFrameGeneration(0L, 994, 2046)
    assertFalse(early.matches(0L, 994, 2046))

    val configured = TicketVideoFrameGeneration(42L, 994, 2046)
    assertFalse(early.matches(configured.epoch, 994, 2046))
    assertTrue(configured.matches(42L, 994, 2046))
  }

  @Test
  fun retainedPictureCannotUseReplacementEpochOrDimensions() {
    val picture = TicketVideoFrameGeneration(42L, 994, 2046)
    assertFalse(picture.matches(43L, 994, 2046))
    assertFalse(picture.matches(42L, null, null))
    assertFalse(picture.matches(42L, 1080, 2424))
    assertTrue(picture.matches(42L, 994, 2046))
  }
}
