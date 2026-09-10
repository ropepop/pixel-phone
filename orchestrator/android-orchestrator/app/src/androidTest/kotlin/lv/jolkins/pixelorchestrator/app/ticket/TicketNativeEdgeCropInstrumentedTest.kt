package lv.jolkins.pixelorchestrator.app.ticket

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class TicketNativeEdgeCropInstrumentedTest {
  @Test
  fun actualScaledDrawKeepsNativeDisplayRingOutOfTheOutputOuterRing() {
    val sourceWidth = 1080
    val sourceHeight = 2424
    val nativeEdge = Color.rgb(255, 255, 0)
    val samplingFringe = Color.rgb(0, 255, 0)
    val croppedTopBand = Color.rgb(255, 0, 255)
    val cleanContent = Color.rgb(35, 74, 111)
    val source = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888)
    source.eraseColor(cleanContent)

    for (y in 0 until TicketScreenConfig.TICKET_MEDIA_TOP_CROP_SOURCE_PIXELS) {
      for (x in 0 until sourceWidth) source.setPixel(x, y, croppedTopBand)
    }
    for (y in 0 until sourceHeight) {
      source.setPixel(0, y, nativeEdge)
      source.setPixel(sourceWidth - 1, y, nativeEdge)
      for (x in 1..3) source.setPixel(x, y, samplingFringe)
      for (x in sourceWidth - 3 until sourceWidth - 1) {
        source.setPixel(x, y, samplingFringe)
      }
    }
    for (x in 0 until sourceWidth) {
      source.setPixel(x, sourceHeight - 1, nativeEdge)
      for (y in sourceHeight - 3 until sourceHeight - 1) {
        source.setPixel(x, y, samplingFringe)
      }
    }

    val crop = TicketCaptureGeometry.sourceCrop(sourceWidth, sourceHeight)
    val size = TicketStreamSizing.rootHardwareH264(sourceWidth, sourceHeight)
    val output = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    val outputBounds = Rect(0, 0, output.width, output.height)
    TicketRootHardwareH264CaptureMain.drawScaledCrop(
      Canvas(output),
      source,
      Rect(crop.left, crop.top, crop.right, crop.bottom),
      outputBounds,
      Paint(Paint.FILTER_BITMAP_FLAG).apply { isFilterBitmap = true }
    )

    for (x in 0 until output.width) {
      assertColorsNear(output.getPixel(x, 0), output.getPixel(x, 1), "top outer ring at x=$x")
      assertColorsNear(
        output.getPixel(x, output.height - 1),
        output.getPixel(x, output.height - 2),
        "bottom outer ring at x=$x"
      )
    }
    for (y in 0 until output.height) {
      assertColorsNear(output.getPixel(0, y), output.getPixel(1, y), "left outer ring at y=$y")
      assertColorsNear(
        output.getPixel(output.width - 1, y),
        output.getPixel(output.width - 2, y),
        "right outer ring at y=$y"
      )
    }

    val outputPixels = IntArray(output.width * output.height)
    output.getPixels(outputPixels, 0, output.width, 0, 0, output.width, output.height)
    assertTrue("native yellow edge reached output", outputPixels.none { colorsNear(it, nativeEdge) })
    assertTrue("native edge sampling fringe reached output", outputPixels.none { colorsNear(it, samplingFringe) })
    assertTrue("cropped top band reached output", outputPixels.none { colorsNear(it, croppedTopBand) })

    source.recycle()
    output.recycle()
  }

  private fun assertColorsNear(actual: Int, adjacent: Int, label: String) {
    assertTrue(
      "$label differs from its adjacent inner ring: actual=$actual adjacent=$adjacent",
      colorsNear(actual, adjacent)
    )
  }

  private fun colorsNear(first: Int, second: Int): Boolean =
    abs(Color.red(first) - Color.red(second)) <= CHANNEL_TOLERANCE &&
      abs(Color.green(first) - Color.green(second)) <= CHANNEL_TOLERANCE &&
      abs(Color.blue(first) - Color.blue(second)) <= CHANNEL_TOLERANCE &&
      abs(Color.alpha(first) - Color.alpha(second)) <= CHANNEL_TOLERANCE

  companion object {
    private const val CHANNEL_TOLERANCE = 2
  }
}
