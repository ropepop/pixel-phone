package lv.jolkins.pixelorchestrator.app.ticket;

import java.util.Arrays;

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
    byte[] header = NativeTicketMedia.tsfHeader(keyFrame, new long[] {
      epoch, sequence, captureAttemptId, codecGeneration, captureStartUs,
      captureCompleteUs, codecInputUs, codecOutputUs, recordEmissionUs, calibrationGeneration, uncertaintyUs
    }, payload == null ? 0 : payload.length);
    byte[] result = Arrays.copyOf(header, header.length + payload.length);
    System.arraycopy(payload, 0, result, header.length, payload.length);
    return result;
  }
}
