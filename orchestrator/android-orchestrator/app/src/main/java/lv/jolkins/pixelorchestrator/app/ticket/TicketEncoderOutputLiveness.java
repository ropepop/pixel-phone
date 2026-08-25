package lv.jolkins.pixelorchestrator.app.ticket;

/**
 * Requests one bounded sync-frame recovery when the static encoder stops producing media.
 *
 * <p>At one frame per second, one empty dequeue can be ordinary encoder latency: the next
 * scheduled drain can still collect that output. Two consecutive steady-state captures without a
 * complete media access unit are a real output drought. Recovery remains armed until media output
 * resumes so the helper cannot create a keyframe or immediate-capture storm.</p>
 */
final class TicketEncoderOutputLiveness {
  private static final int STATIC_EMPTY_DRAIN_THRESHOLD = 2;
  private static final long STATIC_INTERVAL_MILLIS =
    TicketCaptureCadenceScheduler.intervalMillisForFps(TicketCaptureCadenceScheduler.STATIC_FPS);
  private static final long SCHEDULING_JITTER_ALLOWANCE_MILLIS = 250L;
  private static final long MIN_STATIC_EVIDENCE_INTERVAL_MILLIS =
    STATIC_INTERVAL_MILLIS - SCHEDULING_JITTER_ALLOWANCE_MILLIS;
  private static final long MAX_PROGRESS_WITHOUT_MEDIA_MILLIS =
    (2L * STATIC_INTERVAL_MILLIS) - SCHEDULING_JITTER_ALLOWANCE_MILLIS;

  private int consecutiveStaticEmptyDrains;
  private long lastStaticEmptyDrainAtMillis;
  private long mediaDroughtStartedAtMillis = -1L;
  private boolean recoveryArmed;

  boolean noteDrain(
    int targetFps,
    boolean steadyState,
    boolean scheduledCadenceDrain,
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
    if (!steadyState || targetFps != TicketCaptureCadenceScheduler.STATIC_FPS) {
      if (!recoveryArmed) {
        clearCandidate();
      }
      return false;
    }
    // Keyframe requests, cadence transitions, and other immediate captures can run several
    // static-target drains inside one second. They are useful recovery work, not additional
    // evidence that two scheduled 1 FPS outputs were missed.
    if (!scheduledCadenceDrain) {
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
      consecutiveStaticEmptyDrains > 0 &&
      nowMillis - lastStaticEmptyDrainAtMillis < MIN_STATIC_EVIDENCE_INTERVAL_MILLIS
    ) {
      return false;
    }
    consecutiveStaticEmptyDrains += 1;
    lastStaticEmptyDrainAtMillis = nowMillis;
    if (consecutiveStaticEmptyDrains < STATIC_EMPTY_DRAIN_THRESHOLD) {
      return false;
    }
    recoveryArmed = true;
    return true;
  }

  int consecutiveStaticEmptyDrains() {
    return consecutiveStaticEmptyDrains;
  }

  private void resetDrought() {
    clearCandidate();
    recoveryArmed = false;
  }

  private void clearCandidate() {
    consecutiveStaticEmptyDrains = 0;
    lastStaticEmptyDrainAtMillis = 0L;
  }
}
