package lv.jolkins.pixelorchestrator.app.ticket;

/** Summarizes encoder progress without treating codec setup bytes as a captured frame. */
final class TicketEncoderDrainProgress {
  final int encodedFrameOutputs;
  final boolean madeCodecProgress;

  TicketEncoderDrainProgress(
    int encodedFrameOutputs,
    boolean madeCodecProgress
  ) {
    this.encodedFrameOutputs = Math.max(0, encodedFrameOutputs);
    this.madeCodecProgress = madeCodecProgress;
  }

  static TicketEncoderDrainProgress empty() {
    return new TicketEncoderDrainProgress(0, false);
  }

  static TicketEncoderDrainProgress fromDequeuedOutput(
    int dequeuedSize,
    int emittedSize,
    boolean emittedCodecConfig,
    boolean emittedKeyFrame
  ) {
    boolean encodedFrame = isEncodedFrameOutput(emittedSize, emittedCodecConfig, emittedKeyFrame);
    return new TicketEncoderDrainProgress(
      encodedFrame ? 1 : 0,
      dequeuedSize > 0
    );
  }

  static TicketEncoderDrainProgress fromDequeuedAccessUnit(
    int dequeuedSize,
    int emittedSize,
    boolean emittedContainsVcl
  ) {
    return new TicketEncoderDrainProgress(
      emittedSize > 0 && emittedContainsVcl ? 1 : 0,
      dequeuedSize > 0
    );
  }

  TicketEncoderDrainProgress plus(TicketEncoderDrainProgress other) {
    if (other == null) {
      return this;
    }
    return new TicketEncoderDrainProgress(
      encodedFrameOutputs + other.encodedFrameOutputs,
      madeCodecProgress || other.madeCodecProgress
    );
  }

  static boolean isEncodedFrameOutput(int size, boolean codecConfig, boolean keyFrame) {
    return size > 0 && (!codecConfig || keyFrame);
  }
}
