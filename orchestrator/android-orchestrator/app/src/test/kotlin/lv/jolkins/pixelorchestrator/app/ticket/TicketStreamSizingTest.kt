package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class TicketStreamSizingTest {
  @Test
  fun rootHardwareStreamRemovesStatusStripAndNativeDisplayEdgePixels() {
    val size = TicketStreamSizing.rootHardwareH264(sourceWidth = 1080, sourceHeight = 2424)

    assertEquals(994, size.width)
    assertEquals(2_046, size.height)
    assertEquals(4, size.sourceLeftCrop)
    assertEquals(200, size.sourceTopCrop)
    assertEquals(3, size.sourceRightCrop)
    assertEquals(3, size.sourceBottomCrop)
    assertEquals(1080, size.sourceWidth)
    assertEquals(2424, size.sourceHeight)
    assertEquals(1073, size.sourceVisibleWidth)
    assertEquals(2221, size.sourceVisibleHeight)
    assertEquals(4, size.sourceX(0))
    assertEquals(200, size.sourceY(0))
    assertEquals(1077, size.sourceX(size.width))
    assertEquals(2421, size.sourceY(size.height))
    val codedWidth = ((size.width + 15) / 16) * 16
    val codedHeight = ((size.height + 15) / 16) * 16
    val macroblocks = (codedWidth / 16) * (codedHeight / 16)
    assertEquals(1_008, codedWidth)
    assertEquals(2_048, codedHeight)
    assertEquals(8_064, macroblocks)
    assertEquals(2_064_384, codedWidth * codedHeight)
    assertTrue(codedWidth * codedHeight <= TicketScreenConfig.MAX_EQUIVALENT_PIXELS)
    assertTrue(macroblocks <= 8_192)
  }

  @Test
  fun croppedStreamStartsBelowSyntheticTopBand() {
    val size = TicketStreamSizing.rootHardwareH264(sourceWidth = 1080, sourceHeight = 2424)
    val syntheticTopBand = 0 until 200

    assertEquals(false, size.sourceY(0) in syntheticTopBand)
    assertEquals(200, size.sourceY(0))
    assertEquals(201, size.sourceY(1))
  }

  @Test
  fun ticketStreamKeepsTheFullVisibleHeightMapping() {
    val size = TicketStreamSizing.rootHardwareH264(sourceWidth = 1080, sourceHeight = 2424)

    assertEquals(1080, size.sourceWidth)
    assertEquals(2221, size.sourceVisibleHeight)
  }

  @Test
  fun saturatedOuterRingAndOnePixelSamplingFringeAreOutsideTheSharedCaptureRectangle() {
    val width = 8
    val height = 210
    val saturated = 0x00ffff00
    val fringe = 0x0000ff00
    val inner = 0x00112233
    val pixels = IntArray(width * height) { inner }
    for (y in 0 until height) {
      pixels[y * width] = saturated
      pixels[y * width + width - 1] = saturated
      for (x in 1..3) pixels[y * width + x] = fringe
      for (x in width - 3 until width - 1) pixels[y * width + x] = fringe
    }
    for (x in 0 until width) {
      pixels[(height - 1) * width + x] = saturated
      for (y in height - 3 until height - 1) pixels[y * width + x] = fringe
    }

    val crop = TicketCaptureGeometry.sourceCrop(width, height)
    val croppedOuterRing = buildList {
      for (y in crop.top until crop.bottom) {
        add(pixels[y * width + crop.left])
        add(pixels[y * width + crop.right - 1])
      }
      for (x in crop.left until crop.right) add(pixels[(crop.bottom - 1) * width + x])
    }

    assertEquals(4, crop.left)
    assertEquals(width - 3, crop.right)
    assertEquals(height - 3, crop.bottom)
    assertEquals(false, croppedOuterRing.contains(saturated))
    assertEquals(false, croppedOuterRing.contains(fringe))
  }

  @Test
  fun probeGeometryRoundTripsThroughTheFourEdgeCrop() {
    val fullProbe = TicketVisualProbeBounds(0, 0, 192, 288)
    val mapped = TicketCaptureGeometry.mapProbeBoundsToDevice(
      fullProbe,
      probeWidth = 192,
      probeHeight = 288,
      sourceWidth = 1080,
      sourceHeight = 2424
    )
    assertEquals(4, mapped.left)
    assertEquals(200, mapped.top)
    assertEquals(1077, mapped.right)
    assertEquals(2421, mapped.bottom)

    val normalized = TicketCaptureGeometry.normalizeProbeBounds(
      TicketVisualProbeBounds(19, 144, 173, 176),
      probeWidth = 192,
      probeHeight = 288
    )!!
    assertEquals(990, normalized.leftBasisPoints)
    assertEquals(5000, normalized.topBasisPoints)
    assertEquals(9010, normalized.rightBasisPoints)
    assertEquals(6111, normalized.bottomBasisPoints)
  }

  @Test
  fun everyVisualControlMapsThroughTheSameFourEdgeCrop() {
    val controls = mapOf(
      "slider" to TicketVisualProbeBounds(18, 230, 174, 250),
      "registration" to TicketVisualProbeBounds(20, 120, 172, 138),
      "activated_status" to TicketVisualProbeBounds(156, 102, 174, 120),
      "back" to TicketVisualProbeBounds(4, 4, 22, 22),
      "popup" to TicketVisualProbeBounds(28, 68, 164, 224),
      "submit" to TicketVisualProbeBounds(105, 202, 160, 222),
      "close" to TicketVisualProbeBounds(166, 6, 188, 28)
    )
    val crop = TicketCaptureGeometry.sourceCrop(1080, 2424)

    controls.forEach { (name, bounds) ->
      val mapped = TicketCaptureGeometry.mapProbeBoundsToDevice(
        bounds = bounds,
        probeWidth = 192,
        probeHeight = 288,
        sourceWidth = 1080,
        sourceHeight = 2424
      )
      assertEquals(
        "$name left",
        (crop.left + bounds.left / 192f * crop.width).toIntRounded(),
        mapped.left
      )
      assertEquals(
        "$name top",
        (crop.top + bounds.top / 288f * crop.height).toIntRounded(),
        mapped.top
      )
      assertEquals(
        "$name right",
        (crop.left + bounds.right / 192f * crop.width).toIntRounded(),
        mapped.right
      )
      assertEquals(
        "$name bottom",
        (crop.top + bounds.bottom / 288f * crop.height).toIntRounded(),
        mapped.bottom
      )
    }
  }

  private fun Float.toIntRounded(): Int = roundToInt()
}
