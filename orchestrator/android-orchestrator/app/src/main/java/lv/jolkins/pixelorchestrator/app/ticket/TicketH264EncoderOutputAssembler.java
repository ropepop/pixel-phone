package lv.jolkins.pixelorchestrator.app.ticket;

/** Android-facing ownership adapter for bounded native access-unit assembly. */
final class TicketH264EncoderOutputAssembler implements AutoCloseable {
  static final int MAX_ASSEMBLY_BYTES = TicketH264FrameRecord.MAX_PAYLOAD_BYTES;
  private long handle = NativeTicketMedia.assemblerCreate();

  synchronized EmittedAccessUnit accept(byte[] data, boolean partialFrame, boolean codecConfig, boolean keyFrame) {
    return NativeTicketMedia.assemblerAccept(openHandle(), data, partialFrame, codecConfig, keyFrame);
  }

  synchronized void reset() { NativeTicketMedia.assemblerReset(openHandle()); }
  synchronized boolean consumeOverflowed() { return NativeTicketMedia.assemblerOverflowed(openHandle()); }

  @Override public synchronized void close() {
    long owned = handle;
    handle = 0;
    if (owned != 0) NativeTicketMedia.assemblerClose(owned);
  }

  private long openHandle() {
    if (handle == 0) throw new IllegalStateException("assembler is closed");
    return handle;
  }

  static final class EmittedAccessUnit {
    final byte[] payload;
    final boolean codecConfig;
    final boolean keyFrame;
    final boolean containsVcl;
    final boolean idrKeyFrame;

    EmittedAccessUnit(byte[] payload, boolean codecConfig, boolean keyFrame, boolean containsVcl, boolean idrKeyFrame) {
      this.payload = payload;
      this.codecConfig = codecConfig;
      this.keyFrame = keyFrame;
      this.containsVcl = containsVcl;
      this.idrKeyFrame = idrKeyFrame;
    }
  }
}
