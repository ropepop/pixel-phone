package lv.jolkins.pixelorchestrator.app.ticket;

/** Synchronized capture clock state; the packaged Rust library owns cadence and demand policy. */
public final class TicketCaptureCadenceScheduler {
  public static final int FIXED_FPS = 1;
  public static final long INTERVAL_MILLIS = 1_000L;
  public static final long WAIT_UNTIL_SIGNAL_MILLIS = -1L;
  private final long[] state;

  public TicketCaptureCadenceScheduler(long nowMillis) {
    state = new long[] {nowMillis, 0L, 0L, 0L};
  }

  private long decide(int operation, long nowMillis, long validUntilMillis, boolean proofBypass) {
    long[] result = NativeTicketMedia.captureCadence(operation, state, nowMillis, validUntilMillis, proofBypass);
    System.arraycopy(result, 0, state, 0, state.length);
    if (result[5] == 1L) throw new IllegalStateException("ordinary capture is waiting for demand");
    if (result[5] == 2L) throw new IllegalStateException("capture started before its one-second deadline");
    return result[4];
  }

  public synchronized long waitMillis(long nowMillis, boolean proofBypass) { return decide(0, nowMillis, 0L, proofBypass); }
  public synchronized boolean enableDemandGateAndLatchOrdinaryCapture(long validUntilMillis, long nowMillis) {
    return decide(1, nowMillis, validUntilMillis, false) != 0L;
  }
  public synchronized void parkOrdinaryCapture() { decide(2, 0L, 0L, false); }
  public synchronized boolean notePictureEmitted(long nowMillis) { return decide(3, nowMillis, 0L, false) != 0L; }
  public synchronized void restartPeriodFrom(long presentedAtMillis) { decide(4, presentedAtMillis, 0L, false); }
  public synchronized void beginCapture(long nowMillis, boolean proofBypass) { decide(5, nowMillis, 0L, proofBypass); }
}
