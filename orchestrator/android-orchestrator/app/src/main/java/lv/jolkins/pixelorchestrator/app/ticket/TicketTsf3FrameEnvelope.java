package lv.jolkins.pixelorchestrator.app.ticket;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Exact phone-produced TSF3 envelope shared with the relay and browser. */
final class TicketTsf3FrameEnvelope {
  static final String VERSION = "tsf3";
  static final int MAGIC = 0x54534633;
  static final int HEADER_BYTES = 93;
  static final byte FLAG_KEY_FRAME = 1;
  static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

  private TicketTsf3FrameEnvelope() {}

  static byte[] encode(
    boolean keyFrame,
    long epoch,
    long sequence,
    long captureAttemptId,
    long codecGeneration,
    long captureStartUs,
    long captureCompleteUs,
    long codecInputUs,
    long codecOutputUs,
    long recordEmissionUs,
    long calibrationGeneration,
    long uncertaintyUs,
    byte[] payload
  ) {
    if (
      epoch <= 0L || epoch > MAX_SAFE_INTEGER ||
      sequence <= 0L || sequence > MAX_SAFE_INTEGER
    ) {
      throw new IllegalArgumentException("TSF3 epoch and sequence must be positive");
    }
    if (
      captureAttemptId <= 0L || captureAttemptId > MAX_SAFE_INTEGER ||
      codecGeneration <= 0L || codecGeneration > MAX_SAFE_INTEGER ||
      captureStartUs <= 0L || captureStartUs > MAX_SAFE_INTEGER ||
      captureCompleteUs < captureStartUs ||
      captureCompleteUs > MAX_SAFE_INTEGER ||
      codecInputUs < captureCompleteUs ||
      codecInputUs > MAX_SAFE_INTEGER ||
      codecOutputUs < codecInputUs ||
      codecOutputUs > MAX_SAFE_INTEGER ||
      recordEmissionUs < codecOutputUs ||
      recordEmissionUs > MAX_SAFE_INTEGER ||
      calibrationGeneration < 0L || calibrationGeneration > MAX_SAFE_INTEGER ||
      uncertaintyUs < 0L || uncertaintyUs > MAX_SAFE_INTEGER
    ) {
      throw new IllegalArgumentException("invalid TSF3 stage metadata");
    }
    if (payload == null || payload.length <= 0 || payload.length > TicketH264FrameRecord.MAX_PAYLOAD_BYTES) {
      throw new IllegalArgumentException("invalid TSF3 payload length");
    }
    ByteBuffer buffer = ByteBuffer
      .allocate(HEADER_BYTES + payload.length)
      .order(ByteOrder.BIG_ENDIAN);
    buffer.putInt(MAGIC);
    buffer.put(keyFrame ? FLAG_KEY_FRAME : 0);
    buffer.putLong(epoch);
    buffer.putLong(sequence);
    buffer.putLong(captureAttemptId);
    buffer.putLong(codecGeneration);
    buffer.putLong(captureStartUs);
    buffer.putLong(captureCompleteUs);
    buffer.putLong(codecInputUs);
    buffer.putLong(codecOutputUs);
    buffer.putLong(recordEmissionUs);
    buffer.putLong(calibrationGeneration);
    buffer.putLong(uncertaintyUs);
    buffer.put(payload);
    return buffer.array();
  }
}
