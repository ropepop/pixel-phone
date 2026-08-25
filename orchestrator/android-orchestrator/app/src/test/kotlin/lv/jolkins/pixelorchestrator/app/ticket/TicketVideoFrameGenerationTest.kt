package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketVideoFrameGenerationTest {
  @Test
  fun callbackAcceptedInOldEpochCannotBeRelabeledIntoNewEpoch() {
    val accepted = TicketVideoFrameGeneration(epoch = 7L, width = 720, height = 1482)

    assertTrue(accepted.matches(currentEpoch = 7L, currentWidth = 720, currentHeight = 1482))
    assertFalse(accepted.matches(currentEpoch = 8L, currentWidth = 720, currentHeight = 1482))
  }

  @Test
  fun callbackFromOldCaptureDimensionsCannotEnterCurrentGeneration() {
    val accepted = TicketVideoFrameGeneration(epoch = 8L, width = 720, height = 1482)

    assertFalse(accepted.matches(currentEpoch = 8L, currentWidth = 720, currentHeight = 1440))
    assertFalse(accepted.matches(currentEpoch = 8L, currentWidth = null, currentHeight = null))
  }
}
