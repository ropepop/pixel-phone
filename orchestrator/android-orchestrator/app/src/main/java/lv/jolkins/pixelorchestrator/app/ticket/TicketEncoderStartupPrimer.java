package lv.jolkins.pixelorchestrator.app.ticket;

/** Rust priming state with one Java frame reference retained for the platform pipe owner. */
final class TicketEncoderStartupPrimer {
  static final int MAX_INPUTS = 3;
  static final long INPUT_SPACING_MILLIS = 100L;
  enum OutputDisposition { FORWARD, BUFFER_BOUNDARY, SUPPRESS }
  private final long[] state = new long[9];
  private TicketH264FrameRecord boundaryAccessUnit;
  TicketEncoderStartupPrimer(int requestedFrames) { decide(0, requestedFrames, false, false, false); }
  private long decide(int operation, long now, boolean vcl, boolean key, boolean validFrame) {
    long[] result = NativeTicketMedia.encoderStartup(operation, state, now, vcl, key, validFrame);
    System.arraycopy(result, 0, state, 0, state.length);
    if (result[10] == 1L) throw new IllegalStateException("startup primer input is not due");
    if (result[10] == 2L) throw new IllegalStateException("startup primer boundary drain is not available");
    if (result[10] == 3L) throw new IllegalStateException("startup primer boundary access unit is not valid");
    return result[9];
  }
  boolean canPostInput(long now) { return decide(1, now, false, false, false) != 0L; }
  boolean canPostAnotherInput() { return decide(2, 0L, false, false, false) != 0L; }
  long millisUntilNextInput(long now) { return decide(3, now, false, false, false); }
  void noteInputPosted(long now) { decide(4, now, false, false, false); }
  OutputDisposition classifyCompleteAccessUnit(boolean vcl, boolean key, long now) {
    return OutputDisposition.values()[(int) decide(5, now, vcl, key, false)];
  }
  OutputDisposition classifyCompleteAccessUnit(TicketH264FrameRecord frame, boolean vcl, boolean key, long now) {
    OutputDisposition disposition = classifyCompleteAccessUnit(vcl, key, now);
    if (disposition == OutputDisposition.BUFFER_BOUNDARY) bufferBoundaryAccessUnit(frame);
    return disposition;
  }
  void beginBoundaryDrain() { decide(6, 0L, false, false, false); boundaryAccessUnit = null; }
  void bufferBoundaryAccessUnit(TicketH264FrameRecord frame) {
    decide(7, 0L, false, false, frame != null && frame.payload.length != 0);
    boundaryAccessUnit = frame;
  }
  TicketH264FrameRecord completeBoundaryDrain() {
    if (decide(8, 0L, false, false, false) == 0L) return null;
    TicketH264FrameRecord selected = boundaryAccessUnit;
    boundaryAccessUnit = null;
    return selected;
  }
  void noteBoundaryAccessUnitForwarded() { decide(9, 0L, false, false, false); }
  void finish() { decide(10, 0L, false, false, false); boundaryAccessUnit = null; }
  void beginFallbackWait() { decide(11, 0L, false, false, false); }
  boolean firstKeyFrameForwarded() { return state[4] != 0L; }
  boolean fallbackWaitingForFirstKeyFrame() { return state[5] != 0L; }
  boolean boundaryDrainActive() { return state[6] != 0L; }
  boolean boundaryAccessUnitForwarded() { return state[7] != 0L; }
  boolean finished() { return state[8] != 0L; }
  int inputLimit() { return (int) state[0]; }
  int inputPosts() { return (int) state[1]; }
  int mediaOutputs() { return (int) state[2]; }
  long lastInputAtMillis() { return state[3]; }
}
