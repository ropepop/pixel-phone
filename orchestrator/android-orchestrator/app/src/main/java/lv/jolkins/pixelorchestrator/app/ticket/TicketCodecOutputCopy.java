package lv.jolkins.pixelorchestrator.app.ticket;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

/** No codec-owned buffer may survive into assembly, pacing or pipe I/O. */
final class TicketCodecOutputCopy {
  private TicketCodecOutputCopy() {}

  static byte[] copyAndRelease(Supplier<ByteBuffer> getBuffer, int offset, int size, Runnable release) {
    try {
      ByteBuffer buffer = getBuffer.get();
      if (buffer == null || size <= 0) return new byte[0];
      buffer.position(offset);
      buffer.limit(offset + size);
      byte[] data = new byte[size];
      buffer.get(data);
      return data;
    } finally {
      release.run();
    }
  }
}
