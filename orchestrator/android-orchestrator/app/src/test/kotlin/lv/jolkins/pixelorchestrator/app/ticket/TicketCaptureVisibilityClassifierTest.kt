package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketCaptureVisibilityClassifierTest {
  @Test
  fun sparseDarkEmptyTicketsSurfaceRemainsVisible() {
    val pixels = IntArray(48 * 72) { gray(50) }
    repeat(17) { pixels[it * 197 % pixels.size] = gray(255) }

    assertTrue(TicketCaptureVisibilityClassifier.looksVisible(pixels))
  }

  @Test
  fun blackFlatAndNavigationPillFramesRemainBlocked() {
    assertFalse(TicketCaptureVisibilityClassifier.looksVisible(IntArray(48 * 72)))
    assertFalse(TicketCaptureVisibilityClassifier.looksVisible(IntArray(48 * 72) { gray(50) }))

    val navigationPillOnly = IntArray(48 * 72)
    repeat(12) { navigationPillOnly[navigationPillOnly.lastIndex - it] = gray(255) }
    assertFalse(TicketCaptureVisibilityClassifier.looksVisible(navigationPillOnly))
  }

  @Test
  fun ordinaryMixedSurfaceRemainsVisible() {
    val pixels = IntArray(48 * 72) { index ->
      when {
        index % 11 == 0 -> gray(230)
        index % 3 == 0 -> gray(30)
        else -> gray(95)
      }
    }
    assertTrue(TicketCaptureVisibilityClassifier.looksVisible(pixels))
  }

  private fun gray(value: Int): Int = (0xff shl 24) or (value shl 16) or (value shl 8) or value
}
