package lv.jolkins.pixelorchestrator.app.ticket;

/**
 * Monotonic, fixed one-frame-per-second scheduler for the rooted capture helper.
 *
 * <p>Deadlines stay on the original monotonic timeline. When capture work runs
 * long, expired ticks are counted and skipped; the caller receives at most one
 * capture decision and never enters a catch-up loop.</p>
 */
public final class TicketCaptureCadenceScheduler {
  public static final int FIXED_FPS = 1;
  public static final long INTERVAL_MILLIS = 1_000L;

  private long nextDeadlineMillis;
  private long deadlineMisses;
  private long skippedTicks;
  private long lastLatenessMillis;
  private long lastSkippedTicks;
  private boolean immediateCapturePending;
  private long immediateCaptureBlockedUntilMillis;

  public TicketCaptureCadenceScheduler(long nowMillis) {
    nextDeadlineMillis = nowMillis;
  }

  public synchronized long intervalMillis() {
    return INTERVAL_MILLIS;
  }

  public synchronized long waitMillis(long nowMillis) {
    return Math.max(0L, nextDeadlineMillis - nowMillis);
  }

  /**
   * Makes one capture immediately due without changing the fixed cadence.
   * Repeated requests before that capture share the same pending grant. Once
   * consumed, the refresh starts a new one-second period; requests inside that
   * period coalesce onto the already-produced refresh instead of adding frames.
   */
  public synchronized boolean requestImmediateCapture(long nowMillis) {
    if (!immediateCapturePending && nowMillis < immediateCaptureBlockedUntilMillis) {
      return false;
    }
    boolean newlyPending = !immediateCapturePending;
    immediateCapturePending = true;
    nextDeadlineMillis = Math.min(nextDeadlineMillis, nowMillis);
    return newlyPending;
  }

  public synchronized boolean hasImmediateCapturePending() {
    return immediateCapturePending;
  }

  /**
   * Advances the schedule and grants one capture at or after the current deadline.
   * The returned decision is never a request for more than one capture.
   */
  public synchronized CaptureDecision beginCapture(long nowMillis) {
    boolean immediate = immediateCapturePending;
    long lateness = Math.max(0L, nowMillis - nextDeadlineMillis);
    long expiredTicks = lateness == 0L
      ? 0L
      : (lateness + intervalMillis() - 1L) / intervalMillis();
    if (lateness > 0L) {
      deadlineMisses += 1L;
    }
    skippedTicks += expiredTicks;
    lastLatenessMillis = lateness;
    lastSkippedTicks = expiredTicks;
    if (immediate) {
      // An event refresh starts a fresh one-second period. Requests in that period
      // coalesce onto the already-produced frame rather than exceeding 1 FPS.
      nextDeadlineMillis = nowMillis + intervalMillis();
      immediateCaptureBlockedUntilMillis = nextDeadlineMillis;
    } else {
      // A partially late capture still owns the next future tick. At an exact
      // deadline, advance one interval; otherwise advance past the expired
      // ticks but leave the first future deadline intact.
      long intervalsToAdvance = (lateness / intervalMillis()) + 1L;
      nextDeadlineMillis += intervalsToAdvance * intervalMillis();
    }
    immediateCapturePending = false;
    return new CaptureDecision(lateness, expiredTicks, immediate);
  }

  public synchronized long deadlineMisses() {
    return deadlineMisses;
  }

  public synchronized long skippedTicks() {
    return skippedTicks;
  }

  public synchronized long lastLatenessMillis() {
    return lastLatenessMillis;
  }

  public synchronized long lastSkippedTicks() {
    return lastSkippedTicks;
  }

  public static final class CaptureDecision {
    public final long latenessMillis;
    public final long skippedTicks;
    public final boolean immediate;

    CaptureDecision(long latenessMillis, long skippedTicks, boolean immediate) {
      this.latenessMillis = latenessMillis;
      this.skippedTicks = skippedTicks;
      this.immediate = immediate;
    }
  }
}
