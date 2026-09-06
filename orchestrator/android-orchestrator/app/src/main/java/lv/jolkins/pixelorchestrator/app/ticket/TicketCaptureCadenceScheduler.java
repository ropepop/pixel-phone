package lv.jolkins.pixelorchestrator.app.ticket;

/**
 * Monotonic, fixed one-frame-per-second scheduler for the rooted capture helper.
 *
 * <p>Every capture starts a full new one-second interval. Slow work and proof
 * refreshes cannot compress the following interval or cause catch-up frames.</p>
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
      !proofBypass
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

  /** Warm helpers begin parked; an absent viewer must not trigger continuous encoding. */
  public synchronized void parkOrdinaryCapture() {
    ordinaryCaptureDemandGated = true;
    ordinaryCaptureOpportunityPending = false;
    ordinaryCaptureOpportunityValidUntilMillis = 0L;
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
   * Starts the steady one-second period from the first picture actually exposed to the viewer.
   * Internal encoder priming does not become part of the externally visible cadence.
   */
  public synchronized void restartPeriodFrom(long presentedAtMillis) {
    nextDeadlineMillis = Math.max(nextDeadlineMillis, presentedAtMillis + intervalMillis());
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
    if (
      ordinaryCaptureDemandGated &&
      !ordinaryCaptureOpportunityPending &&
      !proofBypass
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
    nextDeadlineMillis = nowMillis + intervalMillis();
    return new CaptureDecision(
      lateness,
      expiredTicks,
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
    public final boolean ordinaryDemandOpportunityConsumed;

    CaptureDecision(
      long latenessMillis,
      long skippedTicks,
      boolean ordinaryDemandOpportunityConsumed
    ) {
      this.latenessMillis = latenessMillis;
      this.skippedTicks = skippedTicks;
      this.ordinaryDemandOpportunityConsumed = ordinaryDemandOpportunityConsumed;
    }
  }
}
