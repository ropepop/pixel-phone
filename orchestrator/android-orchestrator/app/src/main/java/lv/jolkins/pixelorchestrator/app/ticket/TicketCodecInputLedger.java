package lv.jolkins.pixelorchestrator.app.ticket;

import java.util.ArrayDeque;

/** Pairs ordered Baseline/all-intra codec output with the screen capture that supplied its input. */
final class TicketCodecInputLedger {
  static final int MAX_PENDING_INPUTS = 8;

  private final ArrayDeque<InputStage> pending = new ArrayDeque<>();

  void add(InputStage stage) {
    int error = NativeTicketMedia.validateCodecInput(metadata(stage), metadata(pending.peekLast()), pending.size());
    if (error == 1) throw new IllegalArgumentException("invalid codec input stage");
    if (error == 2) throw new IllegalArgumentException("invalid codec input timestamps");
    if (error == 3) throw new IllegalArgumentException("codec input timestamps moved backwards");
    if (error == 4) throw new IllegalStateException("too many codec inputs await output");
    pending.addLast(stage);
  }

  private static long[] metadata(InputStage stage) {
    return stage == null ? new long[0] : new long[] {stage.captureAttemptId, stage.codecGeneration,
      stage.captureStartUs, stage.captureCompleteUs, stage.codecInputUs};
  }

  InputStage take() {
    InputStage stage = pending.pollFirst();
    if (stage == null) {
      throw new IllegalStateException("codec output has no matching input");
    }
    return stage;
  }

  boolean contains(InputStage stage) {
    return stage != null && pending.contains(stage);
  }

  void clear() {
    pending.clear();
  }

  int size() {
    return pending.size();
  }

  static final class InputStage {
    final long captureAttemptId;
    final long codecGeneration;
    final long captureStartUs;
    final long captureCompleteUs;
    final long codecInputUs;

    InputStage(
      long captureAttemptId,
      long codecGeneration,
      long captureStartUs,
      long captureCompleteUs,
      long codecInputUs
    ) {
      this.captureAttemptId = captureAttemptId;
      this.codecGeneration = codecGeneration;
      this.captureStartUs = captureStartUs;
      this.captureCompleteUs = captureCompleteUs;
      this.codecInputUs = codecInputUs;
    }
  }
}
