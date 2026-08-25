package lv.jolkins.pixelorchestrator.app.ticket;

/**
 * Monotonic, single-capture cadence scheduler for the rooted capture helper.
 *
 * <p>Deadlines stay on the original monotonic timeline. When capture work runs
 * long, expired ticks are counted and skipped; the caller receives at most one
 * capture decision and never enters a catch-up loop.</p>
 */
public final class TicketCaptureCadenceScheduler {
  public static final int STATIC_FPS = 1;
  public static final int MODERATE_FPS = 5;
  public static final int ACTIVE_FPS = 10;

  private int targetFps;
  private long nextDeadlineMillis;
  private long cadenceChanges;
  private long deadlineMisses;
  private long skippedTicks;
  private long lastLatenessMillis;
  private long lastSkippedTicks;
  private boolean immediateCapturePending;

  public TicketCaptureCadenceScheduler(int initialFps, long nowMillis) {
    if (!isSupportedFps(initialFps)) {
      throw new IllegalArgumentException("unsupported cadence: " + initialFps);
    }
    targetFps = initialFps;
    nextDeadlineMillis = nowMillis;
  }

  public static boolean isSupportedFps(int fps) {
    return fps == STATIC_FPS || fps == MODERATE_FPS || fps == ACTIVE_FPS;
  }

  public static long intervalMillisForFps(int fps) {
    if (!isSupportedFps(fps)) {
      throw new IllegalArgumentException("unsupported cadence: " + fps);
    }
    return Math.max(1L, Math.round(1000.0 / fps));
  }

  public synchronized boolean setTargetFps(int fps, long nowMillis) {
    if (!isSupportedFps(fps)) {
      return false;
    }
    if (targetFps != fps) {
      targetFps = fps;
      cadenceChanges += 1L;
    }
    // A cadence transition is immediate and starts a fresh absolute schedule.
    nextDeadlineMillis = nowMillis;
    lastLatenessMillis = 0L;
    lastSkippedTicks = 0L;
    return true;
  }

  public synchronized int targetFps() {
    return targetFps;
  }

  public synchronized long intervalMillis() {
    return intervalMillisForFps(targetFps);
  }

  public synchronized long waitMillis(long nowMillis) {
    return Math.max(0L, nextDeadlineMillis - nowMillis);
  }

  /**
   * Makes one capture immediately due without changing the selected cadence.
   * Repeated requests before that capture are coalesced into the same pending
   * grant; a request after the grant is consumed schedules the next capture.
   */
  public synchronized boolean requestImmediateCapture(long nowMillis) {
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
    // A partially late capture still owns the next future tick. At an exact
    // deadline, advance one interval; otherwise advance past the expired
    // ticks but leave the first future deadline intact.
    long intervalsToAdvance = (lateness / intervalMillis()) + 1L;
    nextDeadlineMillis += intervalsToAdvance * intervalMillis();
    immediateCapturePending = false;
    return new CaptureDecision(lateness, expiredTicks, immediate);
  }

  public synchronized long cadenceChanges() {
    return cadenceChanges;
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
