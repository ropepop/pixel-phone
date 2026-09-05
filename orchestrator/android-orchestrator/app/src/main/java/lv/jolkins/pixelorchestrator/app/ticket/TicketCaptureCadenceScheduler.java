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
  public static final long WAIT_UNTIL_SIGNAL_MILLIS = -1L;

  private long nextDeadlineMillis;
  private long deadlineMisses;
  private long skippedTicks;
  private long lastLatenessMillis;
  private long lastSkippedTicks;
  private boolean immediateCapturePending;
  private long immediateCaptureBlockedUntilMillis;
  private boolean ordinaryCaptureDemandGated;
  private boolean ordinaryCaptureOpportunityPending;
  private long ordinaryCaptureOpportunityValidUntilMillis;

  public TicketCaptureCadenceScheduler(long nowMillis) {
    nextDeadlineMillis = nowMillis;
  }

  public synchronized long intervalMillis() {
    return INTERVAL_MILLIS;
  }

  public synchronized long waitMillis(long nowMillis) {
    return waitMillis(nowMillis, false);
  }

  /**
   * Returns the time to the next permitted capture, or {@link #WAIT_UNTIL_SIGNAL_MILLIS} when
   * ordinary capture is intentionally parked. Proof can run without demand; when demand is already
   * pending, that same emitted picture coalesces and consumes the grant.
   */
  public synchronized long waitMillis(long nowMillis, boolean proofBypass) {
    expireOrdinaryCaptureOpportunity(nowMillis);
    if (
      ordinaryCaptureDemandGated &&
      !ordinaryCaptureOpportunityPending &&
      !proofBypass &&
      !immediateCapturePending
    ) {
      return WAIT_UNTIL_SIGNAL_MILLIS;
    }
    return Math.max(0L, nextDeadlineMillis - nowMillis);
  }

  /** Atomically enables JIT mode and latches at most one unexpired ordinary opportunity. */
  public synchronized boolean enableDemandGateAndLatchOrdinaryCapture(
    long validUntilMillis,
    long nowMillis
  ) {
    if (validUntilMillis <= 0L || nowMillis <= 0L) {
      return false;
    }
    expireOrdinaryCaptureOpportunity(nowMillis);
    ordinaryCaptureDemandGated = true;
    if (validUntilMillis < nowMillis) {
      return false;
    }
    boolean newlyPending = !ordinaryCaptureOpportunityPending;
    ordinaryCaptureOpportunityPending = true;
    ordinaryCaptureOpportunityValidUntilMillis = Math.max(
      ordinaryCaptureOpportunityValidUntilMillis,
      validUntilMillis
    );
    return newlyPending;
  }

  public synchronized boolean ordinaryCaptureDemandGated() {
    return ordinaryCaptureDemandGated;
  }

  public synchronized boolean ordinaryCaptureOpportunityPending(long nowMillis) {
    expireOrdinaryCaptureOpportunity(nowMillis);
    return ordinaryCaptureOpportunityPending;
  }

  /**
   * Coalesces a demand that arrived after capture began but before that picture reached the pipe.
   * The relay treats the next emitted binary picture as satisfying its one aggregate opportunity,
   * so the helper must not retain a second ordinary capture for the following cadence boundary.
   */
  public synchronized boolean notePictureEmitted(long nowMillis) {
    expireOrdinaryCaptureOpportunity(nowMillis);
    if (!ordinaryCaptureDemandGated || !ordinaryCaptureOpportunityPending) {
      return false;
    }
    ordinaryCaptureOpportunityPending = false;
    ordinaryCaptureOpportunityValidUntilMillis = 0L;
    return true;
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
   * Starts the steady one-second period from the first picture actually exposed to the viewer.
   * Internal encoder priming does not become part of the externally visible cadence.
   */
  public synchronized void restartPeriodFrom(long presentedAtMillis) {
    nextDeadlineMillis = presentedAtMillis + intervalMillis();
    immediateCaptureBlockedUntilMillis = nextDeadlineMillis;
    immediateCapturePending = false;
  }

  /**
   * Advances the schedule and grants one capture at or after the current deadline.
   * The returned decision is never a request for more than one capture.
   */
  public synchronized CaptureDecision beginCapture(long nowMillis) {
    return beginCapture(nowMillis, false);
  }

  public synchronized CaptureDecision beginCapture(long nowMillis, boolean proofBypass) {
    expireOrdinaryCaptureOpportunity(nowMillis);
    boolean effectiveProofBypass = proofBypass || immediateCapturePending;
    if (
      ordinaryCaptureDemandGated &&
      !ordinaryCaptureOpportunityPending &&
      !effectiveProofBypass
    ) {
      throw new IllegalStateException("ordinary capture is waiting for demand");
    }
    if (nowMillis < nextDeadlineMillis) {
      throw new IllegalStateException("capture started before its one-second deadline");
    }
    boolean ordinaryDemandOpportunityConsumed =
      ordinaryCaptureDemandGated && ordinaryCaptureOpportunityPending;
    if (ordinaryDemandOpportunityConsumed) {
      ordinaryCaptureOpportunityPending = false;
      ordinaryCaptureOpportunityValidUntilMillis = 0L;
    }
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
    return new CaptureDecision(
      lateness,
      expiredTicks,
      immediate,
      ordinaryDemandOpportunityConsumed
    );
  }

  private void expireOrdinaryCaptureOpportunity(long nowMillis) {
    if (
      ordinaryCaptureOpportunityPending &&
      nowMillis > ordinaryCaptureOpportunityValidUntilMillis
    ) {
      ordinaryCaptureOpportunityPending = false;
      ordinaryCaptureOpportunityValidUntilMillis = 0L;
    }
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
    public final boolean ordinaryDemandOpportunityConsumed;

    CaptureDecision(
      long latenessMillis,
      long skippedTicks,
      boolean immediate,
      boolean ordinaryDemandOpportunityConsumed
    ) {
      this.latenessMillis = latenessMillis;
      this.skippedTicks = skippedTicks;
      this.immediate = immediate;
      this.ordinaryDemandOpportunityConsumed = ordinaryDemandOpportunityConsumed;
    }
  }
}
