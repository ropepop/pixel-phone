package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Test

class TicketStreamSizingTest {
  @Test
  fun rootHardwareStreamRemovesTheVisibleStatusStripSourcePixels() {
    val size = TicketStreamSizing.rootHardwareH264(sourceWidth = 1080, sourceHeight = 2424)

    assertEquals(720, size.width)
    assertEquals(1482, size.height)
    assertEquals(200, size.sourceTopCrop)
    assertEquals(1080, size.sourceWidth)
    assertEquals(2424, size.sourceHeight)
    assertEquals(2224, size.sourceVisibleHeight)
    assertEquals(0, size.sourceX(0))
    assertEquals(200, size.sourceY(0))
    assertEquals(2424, size.sourceY(size.height))
  }

  @Test
  fun croppedStreamStartsBelowSyntheticTopBand() {
    val size = TicketStreamSizing.rootHardwareH264(sourceWidth = 1080, sourceHeight = 2424)
    val syntheticTopBand = 0 until 200

    assertEquals(false, size.sourceY(0) in syntheticTopBand)
    assertEquals(200, size.sourceY(0))
    assertEquals(202, size.sourceY(1))
  }

  @Test
  fun ticketStreamKeepsTheFullVisibleHeightMapping() {
    val size = TicketStreamSizing.rootHardwareH264(sourceWidth = 1080, sourceHeight = 2424)

    assertEquals(1080, size.sourceWidth)
    assertEquals(2224, size.sourceVisibleHeight)
  }
}
