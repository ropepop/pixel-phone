package lv.jolkins.pixelorchestrator.app.ticket;

import java.util.ArrayDeque;

/** Pairs ordered Baseline/all-intra codec output with the screen capture that supplied its input. */
final class TicketCodecInputLedger {
  static final int MAX_PENDING_INPUTS = 8;

  private final ArrayDeque<InputStage> pending = new ArrayDeque<>();

  void add(InputStage stage) {
    if (stage == null || stage.captureAttemptId <= 0L || stage.codecGeneration <= 0L) {
      throw new IllegalArgumentException("invalid codec input stage");
    }
    if (
      stage.captureStartUs <= 0L ||
      stage.captureCompleteUs < stage.captureStartUs ||
      stage.codecInputUs < stage.captureCompleteUs
    ) {
      throw new IllegalArgumentException("invalid codec input timestamps");
    }
    InputStage tail = pending.peekLast();
    if (tail != null && stage.codecInputUs < tail.codecInputUs) {
      throw new IllegalArgumentException("codec input timestamps moved backwards");
    }
    if (pending.size() >= MAX_PENDING_INPUTS) {
      throw new IllegalStateException("too many codec inputs await output");
    }
    pending.addLast(stage);
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
