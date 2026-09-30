package lv.jolkins.pixelorchestrator.app.ticket;

import android.os.Debug;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.util.Arrays;

/** Synthetic bytes only; no capture, network, input, state file or Android UI. */
public final class TicketRootMediaFixtureMain {
  public static void main(String[] args) throws Exception {
    byte[] config = {0, 0, 0, 1, 0x67, 0x42, 0x11, 0, 0, 0, 1, 0x68, 0x33};
    byte[] idr = {0, 0, 0, 1, 0x65, 0x55, 0x66};
    byte[] expected = {0, 0, 0, 1, 0x67, 0x42, 0x11, 0, 0, 0, 1, 0x68, 0x33,
      0, 0, 0, 1, 0x65, 0x55, 0x66, 0, 0, 0, 1, 9, 16};
    try (TicketH264EncoderOutputAssembler assembler = new TicketH264EncoderOutputAssembler()) {
      check(assembler.accept(config, false, true, false) != null, "config");
      check(assembler.accept(Arrays.copyOfRange(idr, 0, 3), true, false, true) == null, "partial");
      TicketH264EncoderOutputAssembler.EmittedAccessUnit unit =
        assembler.accept(Arrays.copyOfRange(idr, 3, idr.length), false, false, false);
      check(Arrays.equals(expected, unit.payload) && unit.keyFrame && unit.idrKeyFrame && unit.containsVcl, "assembly");
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      TicketH264FrameRecord.write(output, new TicketH264FrameRecord(true, 1, 2, 3, 4, 5, 6, 7, unit.payload));
      TicketH264FrameRecord record = TicketH264FrameRecord.read(new ByteArrayInputStream(output.toByteArray()));
      check(record.keyFrame && record.captureAttemptId == 1 && record.recordEmissionUs == 7
        && Arrays.equals(expected, record.payload), "THF1");
      byte[] packet = TicketTsf3FrameEnvelope.encode(true, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, record.payload);
      check(packet.length == 93 + expected.length && packet[0] == 'T' && packet[3] == '3'
        && Arrays.equals(expected, Arrays.copyOfRange(packet, 93, packet.length)), "TSF3");
      try {
        TicketH264FrameRecord.read(new ByteArrayInputStream(Arrays.copyOf(output.toByteArray(), output.size() - 1)));
        throw new IllegalStateException("truncated payload accepted");
      } catch (EOFException expectedEof) { check("truncated THF1 payload".equals(expectedEof.getMessage()), "EOF"); }
      assembler.accept(new byte[2 * 1024 * 1024], true, false, false);
      assembler.accept(new byte[] {1}, true, false, false);
      check(assembler.consumeOverflowed() && !assembler.consumeOverflowed(), "overflow");
      assembler.reset();
      check(assembler.accept(idr, false, false, true) == null, "reset");
    }
    byte[] partial = new byte[512 * 1024];
    for (int i = 0; i < 16; i++) cycle(partial);
    long before = Debug.getNativeHeapAllocatedSize();
    for (int i = 0; i < 256; i++) cycle(partial);
    long growth = Debug.getNativeHeapAllocatedSize() - before;
    check(growth < 8 * 1024 * 1024, "native session allocations retained");
    System.out.println("NATIVE_MEDIA_OK rounds=256 heap_growth_bytes=" + growth);
  }

  private static void cycle(byte[] partial) {
    TicketH264EncoderOutputAssembler owner = new TicketH264EncoderOutputAssembler();
    try { check(owner.accept(partial, true, false, false) == null, "pending"); }
    finally { owner.close(); }
    owner.close();
    try { owner.reset(); throw new IllegalStateException("closed owner accepted reset"); }
    catch (IllegalStateException closed) { check("assembler is closed".equals(closed.getMessage()), "close"); }
  }

  private static void check(boolean value, String operation) {
    if (!value) throw new IllegalStateException("native media fixture failed: " + operation);
  }
}
