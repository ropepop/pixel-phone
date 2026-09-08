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
  private boolean ordinaryCaptureDemandGated;
  private boolean ordinaryCaptureOpportunityPending;
  private long ordinaryCaptureOpportunityValidUntilMillis;

  public TicketCaptureCadenceScheduler(long nowMillis) {
    nextDeadlineMillis = nowMillis;
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

  /** Warm helpers begin parked; an absent viewer must not trigger continuous encoding. */
  public synchronized void parkOrdinaryCapture() {
    ordinaryCaptureDemandGated = true;
    ordinaryCaptureOpportunityPending = false;
    ordinaryCaptureOpportunityValidUntilMillis = 0L;
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
    nextDeadlineMillis = Math.max(nextDeadlineMillis, presentedAtMillis + INTERVAL_MILLIS);
  }

  /**
   * Advances the schedule and grants one capture at or after the current deadline.
   */
  public synchronized void beginCapture(long nowMillis, boolean proofBypass) {
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
    nextDeadlineMillis = nowMillis + INTERVAL_MILLIS;
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

}
