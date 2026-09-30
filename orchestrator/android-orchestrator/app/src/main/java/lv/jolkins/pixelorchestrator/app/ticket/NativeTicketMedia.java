package lv.jolkins.pixelorchestrator.app.ticket;

import java.io.IOException;

/** One packaged library; the root process names the same APK used for its classes. */
final class NativeTicketMedia {
  static {
    String library = System.getProperty("pixel.media.native.library");
    if (library == null) System.loadLibrary("pixel_health");
    else System.load(library);
  }

  static native TicketVisualActionClassifier.Result classifyAction(int[] pixels, boolean resolveList, long today);
  static native String selectedNavigation(int[] pixels);
  static native boolean captureLooksVisible(int[] pixels);
  static native long[] encoderStartup(int operation, long[] state, long now, boolean vcl, boolean key, boolean validFrame);
  static native int validateCodecInput(long[] stage, long[] tail, int count);
  static native long nextEncoderGeneration(long current, long now);
  static native long[] captureCadence(int operation, long[] state, long now, long validUntil, boolean proof);
  static native long[] recognizeDates(int[] pixels, int width, int height);

  static native String controlClassify(int[] pixels);
  static native String controlClassifyForActivatedTicket(int[] pixels);
  static native String controlClassifyForCleanup(int[] pixels);
  static native String controlClassifyForCleanupHighResolution(int[] pixels);
  static native String controlGeneratedResultCloseBounds(int[] pixels);
  static native String controlGeneratedResultCloseBoundsHighResolution(int[] pixels);
  static native String controlRegistrationSliderBounds(int[] pixels);
  static native String controlClassifySubmitLayout(int[] pixels);
  static native String controlSubmitInputBounds(int[] pixels);
  static native String controlSubmitButtonBounds(int[] pixels);
  static native byte[] controlCodeSignaturePixels(int[] pixels);
  static native byte[] controlHighResCodeSignaturePixels(int[] pixels);
  static native byte[] controlStaticSignaturePixels(int[] pixels, int width, int height);

  static native long assemblerCreate();
  static native void assemblerClose(long handle);
  static native void assemblerReset(long handle);
  static native boolean assemblerOverflowed(long handle);
  static native TicketH264EncoderOutputAssembler.EmittedAccessUnit assemblerAccept(
    long handle, byte[] data, boolean partial, boolean config, boolean key
  );
  static native byte[] thfHeader(boolean key, long[] stages, int payloadLength) throws IOException;
  static native int thfPayloadLength(byte[] header) throws IOException;
  static native TicketH264FrameRecord thfRecord(byte[] header, byte[] payload) throws IOException;
  static native byte[] tsfHeader(boolean key, long[] stages, int payloadLength);
}
