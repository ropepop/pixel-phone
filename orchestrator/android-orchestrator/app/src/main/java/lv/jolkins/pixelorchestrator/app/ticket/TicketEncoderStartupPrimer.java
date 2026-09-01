package lv.jolkins.pixelorchestrator.app.ticket;

/**
 * Bounds cold-start encoder priming without changing the public one-FPS stream contract.
 *
 * <p>The caller owns capture and codec I/O. This state machine only schedules at most three
 * input-surface posts and decides which fully assembled access units may reach the Annex-B pipe.
 * Codec configuration remains visible, the first complete keyframe is exposed, and every other
 * media access unit produced while priming is suppressed. At the first steady-cadence boundary,
 * the caller may buffer all IDRs dequeued after that Surface post and expose only the newest one.
 * This keeps delayed priming siblings off the pipe without creating a two-period public gap.</p>
 */
final class TicketEncoderStartupPrimer {
  static final int MAX_INPUTS = 3;
  static final long INPUT_SPACING_MILLIS = 100L;

  enum OutputDisposition {
    FORWARD,
    BUFFER_BOUNDARY,
    SUPPRESS
  }

  private final int inputLimit;
  private int inputPosts;
  private int mediaOutputs;
  private int suppressedMediaOutputs;
  private long firstInputAtMillis = -1L;
  private long lastInputAtMillis = -1L;
  private long firstKeyFrameAtMillis = -1L;
  private boolean firstKeyFrameForwarded;
  private boolean fallbackWaitingForFirstKeyFrame;
  private boolean boundaryDrainActive;
  private byte[] boundaryAccessUnit;
  private boolean boundaryAccessUnitForwarded;
  private boolean finished;

  TicketEncoderStartupPrimer(int requestedFrames) {
    inputLimit = requestedFrames > 0
      ? Math.min(MAX_INPUTS, requestedFrames)
      : MAX_INPUTS;
  }

  boolean canPostInput(long nowMillis) {
    return !finished &&
      !firstKeyFrameForwarded &&
      inputPosts < inputLimit &&
      millisUntilNextInput(nowMillis) == 0L;
  }

  boolean canPostAnotherInput() {
    return !finished && !firstKeyFrameForwarded && inputPosts < inputLimit;
  }

  long millisUntilNextInput(long nowMillis) {
    if (finished || firstKeyFrameForwarded || inputPosts >= inputLimit || lastInputAtMillis < 0L) {
      return 0L;
    }
    return Math.max(0L, lastInputAtMillis + INPUT_SPACING_MILLIS - nowMillis);
  }

  void noteInputPosted(long nowMillis) {
    if (!canPostInput(nowMillis)) {
      throw new IllegalStateException("startup primer input is not due");
    }
    inputPosts += 1;
    if (firstInputAtMillis < 0L) {
      firstInputAtMillis = nowMillis;
    }
    lastInputAtMillis = nowMillis;
  }

  OutputDisposition classifyCompleteAccessUnit(
    boolean containsVcl,
    boolean idrKeyFrame,
    long nowMillis
  ) {
    if (finished) {
      return OutputDisposition.FORWARD;
    }

    // SPS/PPS, SEI, and AUD output are not pictures. They must reach the pipe even when an encoder
    // omits or misapplies BUFFER_FLAG_CODEC_CONFIG.
    if (!containsVcl) {
      return OutputDisposition.FORWARD;
    }

    mediaOutputs += 1;
    if (idrKeyFrame && !firstKeyFrameForwarded) {
      firstKeyFrameForwarded = true;
      firstKeyFrameAtMillis = nowMillis;
      return OutputDisposition.FORWARD;
    }

    if (boundaryDrainActive && idrKeyFrame) {
      return OutputDisposition.BUFFER_BOUNDARY;
    }

    suppressedMediaOutputs += 1;
    return OutputDisposition.SUPPRESS;
  }

  OutputDisposition classifyCompleteAccessUnit(
    byte[] payload,
    boolean containsVcl,
    boolean idrKeyFrame,
    long nowMillis
  ) {
    OutputDisposition disposition = classifyCompleteAccessUnit(
      containsVcl,
      idrKeyFrame,
      nowMillis
    );
    if (disposition == OutputDisposition.BUFFER_BOUNDARY) {
      bufferBoundaryAccessUnit(payload);
    }
    return disposition;
  }

  void beginBoundaryDrain() {
    if (finished || !firstKeyFrameForwarded || boundaryDrainActive) {
      throw new IllegalStateException("startup primer boundary drain is not available");
    }
    boundaryDrainActive = true;
    boundaryAccessUnit = null;
    boundaryAccessUnitForwarded = false;
  }

  void bufferBoundaryAccessUnit(byte[] payload) {
    if (!boundaryDrainActive || payload == null || payload.length == 0) {
      throw new IllegalStateException("startup primer boundary access unit is not valid");
    }
    if (boundaryAccessUnit != null) {
      // Output order is input order for the baseline all-intra encoder. Retain the newest IDR so
      // delayed primer siblings cannot burst ahead of the picture posted at this boundary.
      suppressedMediaOutputs += 1;
    }
    boundaryAccessUnit = payload;
  }

  byte[] completeBoundaryDrain() {
    if (!boundaryDrainActive) {
      return null;
    }
    boundaryDrainActive = false;
    byte[] selected = boundaryAccessUnit;
    boundaryAccessUnit = null;
    return selected;
  }

  void noteBoundaryAccessUnitForwarded() {
    boundaryAccessUnitForwarded = true;
  }

  boolean boundaryDrainActive() {
    return boundaryDrainActive;
  }

  boolean boundaryAccessUnitForwarded() {
    return boundaryAccessUnitForwarded;
  }

  void finish() {
    boundaryDrainActive = false;
    boundaryAccessUnit = null;
    finished = true;
  }

  void beginFallbackWait() {
    fallbackWaitingForFirstKeyFrame = true;
  }

  boolean firstKeyFrameForwarded() {
    return firstKeyFrameForwarded;
  }

  boolean finished() {
    return finished;
  }

  boolean fallbackWaitingForFirstKeyFrame() {
    return fallbackWaitingForFirstKeyFrame;
  }

  int inputLimit() {
    return inputLimit;
  }

  int inputPosts() {
    return inputPosts;
  }

  int mediaOutputs() {
    return mediaOutputs;
  }

  int suppressedMediaOutputs() {
    return suppressedMediaOutputs;
  }

  long lastInputAtMillis() {
    return lastInputAtMillis;
  }

  long firstKeyFrameAtMillis() {
    return firstKeyFrameAtMillis;
  }

  long firstInputAtMillis() {
    return firstInputAtMillis;
  }

  long firstKeyFrameLatencyMillis() {
    if (firstInputAtMillis < 0L || firstKeyFrameAtMillis < 0L) {
      return -1L;
    }
    return Math.max(0L, firstKeyFrameAtMillis - firstInputAtMillis);
  }
}
