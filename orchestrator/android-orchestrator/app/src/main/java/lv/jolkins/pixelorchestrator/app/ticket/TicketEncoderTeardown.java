package lv.jolkins.pixelorchestrator.app.ticket;

/** Attempts every independent release; a source remains pinned until codec release succeeds. */
final class TicketEncoderTeardown {
  private TicketEncoderTeardown() {}

  static void release(Runnable surface, Runnable stop, Runnable codec, Runnable source) {
    boolean surfaceReleased = attempt(surface);
    attempt(stop);
    boolean codecReleased = attempt(codec);
    boolean sourceReleased = codecReleased && attempt(source);
    if (!surfaceReleased || !codecReleased || !sourceReleased) throw new UnsafeReleaseException();
  }

  private static boolean attempt(Runnable release) {
    try { release.run(); return true; }
    catch (Throwable failure) { return false; }
  }

  static final class UnsafeReleaseException extends RuntimeException {
    UnsafeReleaseException() { super("encoder_resources_not_released"); }
  }
}
