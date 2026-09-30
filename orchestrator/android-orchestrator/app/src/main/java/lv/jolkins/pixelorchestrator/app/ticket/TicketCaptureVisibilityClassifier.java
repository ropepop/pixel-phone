package lv.jolkins.pixelorchestrator.app.ticket;

/** The fixed sparse-page versus black-capture classifier is owned by Rust. */
final class TicketCaptureVisibilityClassifier {
  private TicketCaptureVisibilityClassifier() {}
  static boolean looksVisible(int[] pixels) { return NativeTicketMedia.captureLooksVisible(pixels); }
}
