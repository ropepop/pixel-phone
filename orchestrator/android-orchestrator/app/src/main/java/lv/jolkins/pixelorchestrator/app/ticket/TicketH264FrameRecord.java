package lv.jolkins.pixelorchestrator.app.ticket;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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
    validate(record);
    ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
    header.putInt(MAGIC);
    header.put((byte) (record.keyFrame ? FLAG_KEY_FRAME : 0));
    header.putLong(record.captureAttemptId);
    header.putLong(record.codecGeneration);
    header.putLong(record.captureStartUs);
    header.putLong(record.captureCompleteUs);
    header.putLong(record.codecInputUs);
    header.putLong(record.codecOutputUs);
    header.putLong(record.recordEmissionUs);
    header.putInt(record.payload.length);
    output.write(header.array());
    output.write(record.payload);
  }

  static TicketH264FrameRecord read(InputStream input) throws IOException {
    byte[] header = new byte[HEADER_BYTES];
    int first = input.read();
    if (first < 0) return null;
    header[0] = (byte) first;
    readFully(input, header, 1, HEADER_BYTES - 1, "truncated THF1 header");
    ByteBuffer parsed = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
    if (parsed.getInt() != MAGIC) {
      throw new IOException("invalid THF1 magic");
    }
    int flags = parsed.get() & 0xff;
    if ((flags & ~FLAG_KEY_FRAME) != 0) {
      throw new IOException("unsupported THF1 flags");
    }
    long captureAttemptId = parsed.getLong();
    long codecGeneration = parsed.getLong();
    long captureStartUs = parsed.getLong();
    long captureCompleteUs = parsed.getLong();
    long codecInputUs = parsed.getLong();
    long codecOutputUs = parsed.getLong();
    long recordEmissionUs = parsed.getLong();
    int payloadBytes = parsed.getInt();
    if (payloadBytes <= 0 || payloadBytes > MAX_PAYLOAD_BYTES) {
      throw new IOException("invalid THF1 payload length");
    }
    byte[] payload = new byte[payloadBytes];
    readFully(input, payload, 0, payloadBytes, "truncated THF1 payload");
    TicketH264FrameRecord record = new TicketH264FrameRecord(
      (flags & FLAG_KEY_FRAME) != 0,
      captureAttemptId,
      codecGeneration,
      captureStartUs,
      captureCompleteUs,
      codecInputUs,
      codecOutputUs,
      recordEmissionUs,
      payload
    );
    validate(record);
    return record;
  }

  private static void validate(TicketH264FrameRecord record) throws IOException {
    if (record == null || record.payload == null) {
      throw new IOException("missing THF1 record");
    }
    if (record.payload.length <= 0 || record.payload.length > MAX_PAYLOAD_BYTES) {
      throw new IOException("invalid THF1 payload length");
    }
    if (
      record.captureAttemptId <= 0L ||
      record.codecGeneration <= 0L ||
      record.captureStartUs <= 0L ||
      record.captureCompleteUs < record.captureStartUs ||
      record.codecInputUs < record.captureCompleteUs ||
      record.codecOutputUs < record.codecInputUs ||
      record.recordEmissionUs < record.codecOutputUs
    ) {
      throw new IOException("invalid THF1 stage timestamps");
    }
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
