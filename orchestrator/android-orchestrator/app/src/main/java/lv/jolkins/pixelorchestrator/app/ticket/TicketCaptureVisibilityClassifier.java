package lv.jolkins.pixelorchestrator.app.ticket;

/** Lightweight fixed-probe check that distinguishes a visible sparse ViVi page from black capture. */
final class TicketCaptureVisibilityClassifier {
  private TicketCaptureVisibilityClassifier() {}

  static boolean looksVisible(int[] pixels) {
    if (pixels == null || pixels.length == 0) return false;
    int bright = 0;
    int dark = 0;
    int min = 255;
    int max = 0;
    long sum = 0L;
    for (int pixel : pixels) {
      int red = (pixel >> 16) & 0xff;
      int green = (pixel >> 8) & 0xff;
      int blue = pixel & 0xff;
      int luminance = (red * 299 + green * 587 + blue * 114) / 1000;
      min = Math.min(min, luminance);
      max = Math.max(max, luminance);
      sum += luminance;
      if (luminance >= 180) bright += 1;
      if (luminance <= 60) dark += 1;
    }
    double mean = sum / (double) pixels.length;
    double darkRatio = dark / (double) pixels.length;
    int range = max - min;
    // The current dark empty-Tickets surface contains only 17 bright samples out of 3,456.
    // Eight independent highlights plus substantial mean/range authority prove sparse UI while
    // keeping uniform black, a lone navigation pill, and flat placeholder frames rejected.
    return range >= 35 && bright >= 8 && darkRatio >= 0.02 && mean >= 35.0;
  }
}
