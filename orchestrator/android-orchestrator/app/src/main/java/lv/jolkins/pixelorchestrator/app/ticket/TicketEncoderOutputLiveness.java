package lv.jolkins.pixelorchestrator.app.ticket;

/**
 * Requests one bounded sync-frame recovery when the fixed one-FPS encoder stops producing media.
 *
 * <p>At one frame per second, one empty dequeue can be ordinary encoder latency: the next
 * scheduled drain can still collect that output. Two consecutive steady-state captures without a
 * complete media access unit are a real output drought. Recovery remains armed until media output
 * resumes so the helper cannot create a keyframe or immediate-capture storm.</p>
 */
final class TicketEncoderOutputLiveness {
  private static final int EMPTY_DRAIN_THRESHOLD = 2;
  private static final long FIXED_INTERVAL_MILLIS = TicketCaptureCadenceScheduler.INTERVAL_MILLIS;
  private static final long SCHEDULING_JITTER_ALLOWANCE_MILLIS = 250L;
  private static final long MIN_EVIDENCE_INTERVAL_MILLIS =
    FIXED_INTERVAL_MILLIS - SCHEDULING_JITTER_ALLOWANCE_MILLIS;
  private static final long MAX_PROGRESS_WITHOUT_MEDIA_MILLIS =
    (2L * FIXED_INTERVAL_MILLIS) - SCHEDULING_JITTER_ALLOWANCE_MILLIS;

  private int consecutiveEmptyDrains;
  private long lastEmptyDrainAtMillis;
  private long mediaDroughtStartedAtMillis = -1L;
  private boolean recoveryArmed;

  boolean noteDrain(
    boolean steadyState,
    boolean scheduledPeriodicDrain,
    boolean madeCodecProgress,
    int encodedFrameOutputs,
    long nowMillis
  ) {
    if (mediaDroughtStartedAtMillis < 0L) {
      mediaDroughtStartedAtMillis = nowMillis;
    }
    if (encodedFrameOutputs > 0) {
      mediaDroughtStartedAtMillis = nowMillis;
      resetDrought();
      return false;
    }
    boolean completeMediaOverdue =
      nowMillis - mediaDroughtStartedAtMillis >= MAX_PROGRESS_WITHOUT_MEDIA_MILLIS;
    if (madeCodecProgress && !completeMediaOverdue) {
      if (!recoveryArmed) {
        clearCandidate();
      }
      return false;
    }
    if (!steadyState) {
      if (!recoveryArmed) {
        clearCandidate();
      }
      return false;
    }
    // A coalesced refresh can add one drain before the newly scheduled one-second deadline.
    // It is useful recovery work, not additional evidence that two scheduled outputs were missed.
    if (!scheduledPeriodicDrain) {
      return false;
    }
    if (recoveryArmed) {
      return false;
    }
    // New fragments are useful short-term encoder progress, but a stream of partial/config-only
    // buffers must not hide the absence of a browser-visible access unit indefinitely.
    if (completeMediaOverdue) {
      recoveryArmed = true;
      return true;
    }
    if (
      consecutiveEmptyDrains > 0 &&
      nowMillis - lastEmptyDrainAtMillis < MIN_EVIDENCE_INTERVAL_MILLIS
    ) {
      return false;
    }
    consecutiveEmptyDrains += 1;
    lastEmptyDrainAtMillis = nowMillis;
    if (consecutiveEmptyDrains < EMPTY_DRAIN_THRESHOLD) {
      return false;
    }
    recoveryArmed = true;
    return true;
  }

  int consecutiveEmptyDrains() {
    return consecutiveEmptyDrains;
  }

  private void resetDrought() {
    clearCandidate();
    recoveryArmed = false;
  }

  private void clearCandidate() {
    consecutiveEmptyDrains = 0;
    lastEmptyDrainAtMillis = 0L;
  }
}
