package lv.jolkins.pixelorchestrator.app.ticket;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Length-delimited, versioned IPC record between the root encoder helper and Android service. */
final class TicketH264FrameRecord {
  static final int MAGIC = 0x54484631; // THF1
  static final int HEADER_BYTES = 65;
  static final int MAX_PAYLOAD_BYTES = 2 * 1024 * 1024;
  static final int FLAG_KEY_FRAME = 1;

  final boolean keyFrame;
  final long captureAttemptId;
  final long codecGeneration;
  final long captureStartUs;
  final long captureCompleteUs;
  final long codecInputUs;
  final long codecOutputUs;
  final long recordEmissionUs;
  final byte[] payload;

  TicketH264FrameRecord(
    boolean keyFrame,
    long captureAttemptId,
    long codecGeneration,
    long captureStartUs,
    long captureCompleteUs,
    long codecInputUs,
    long codecOutputUs,
    long recordEmissionUs,
    byte[] payload
  ) {
    this.keyFrame = keyFrame;
    this.captureAttemptId = captureAttemptId;
    this.codecGeneration = codecGeneration;
    this.captureStartUs = captureStartUs;
    this.captureCompleteUs = captureCompleteUs;
    this.codecInputUs = codecInputUs;
    this.codecOutputUs = codecOutputUs;
    this.recordEmissionUs = recordEmissionUs;
    this.payload = payload;
  }

  TicketH264FrameRecord withRecordEmissionUs(long value) {
    return new TicketH264FrameRecord(
      keyFrame,
      captureAttemptId,
      codecGeneration,
      captureStartUs,
      captureCompleteUs,
      codecInputUs,
      codecOutputUs,
      value,
      payload
    );
  }

  static void write(OutputStream output, TicketH264FrameRecord record) throws IOException {
    if (record == null || record.payload == null) throw new IOException("missing THF1 record");
    byte[] header = NativeTicketMedia.thfHeader(record.keyFrame, new long[] {
      record.captureAttemptId, record.codecGeneration, record.captureStartUs,
      record.captureCompleteUs, record.codecInputUs, record.codecOutputUs, record.recordEmissionUs
    }, record.payload.length);
    output.write(header);
    output.write(record.payload);
  }

  static TicketH264FrameRecord read(InputStream input) throws IOException {
    byte[] header = new byte[HEADER_BYTES];
    int first = input.read();
    if (first < 0) return null;
    header[0] = (byte) first;
    readFully(input, header, 1, HEADER_BYTES - 1, "truncated THF1 header");
    int payloadBytes = NativeTicketMedia.thfPayloadLength(header);
    byte[] payload = new byte[payloadBytes];
    readFully(input, payload, 0, payloadBytes, "truncated THF1 payload");
    return NativeTicketMedia.thfRecord(header, payload);
  }

  private static void readFully(
    InputStream input,
    byte[] destination,
    int offset,
    int length,
    String message
  ) throws IOException {
    int readTotal = 0;
    while (readTotal < length) {
      int read = input.read(destination, offset + readTotal, length - readTotal);
      if (read < 0) throw new EOFException(message);
      if (read == 0) continue;
      readTotal += read;
    }
  }
}
