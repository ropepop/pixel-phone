package lv.jolkins.pixelorchestrator.app.ticket;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reassembles MediaCodec output buffers before they are put on the Ticket Annex-B pipe.
 *
 * <p>Some encoders split one access unit over buffers and mark all but the last buffer with
 * {@code BUFFER_FLAG_PARTIAL_FRAME}. Treating those fragments as independent frames corrupts
 * length prefixes, injects multiple AUDs, and can make a fragmentary IDR look like a keyframe.
 * This class deliberately has no Android dependencies so the framing contract can be tested on
 * the JVM.</p>
 */
final class TicketH264EncoderOutputAssembler {
  static final int MAX_ASSEMBLY_BYTES = TicketH264FrameRecord.MAX_PAYLOAD_BYTES;

  private static final byte[] START_CODE = new byte[] { 0, 0, 0, 1 };
  private static final byte[] ACCESS_UNIT_DELIMITER = new byte[] { 0, 0, 0, 1, 9, 16 };

  private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
  private boolean hasPending;
  private boolean pendingCodecConfig;
  private boolean pendingKeyFrame;
  private boolean discardUntilFinal;
  private boolean overflowed;
  private byte[] sps;
  private byte[] pps;
  private FramingMode framingMode = FramingMode.UNKNOWN;

  EmittedAccessUnit accept(byte[] data, boolean partialFrame, boolean codecConfig, boolean keyFrame) {
    byte[] safeData = data == null ? new byte[0] : data;
    if (safeData.length == 0 && !partialFrame) {
      // A zero-byte dequeue (especially an EOS marker) is not a completed access-unit
      // fragment. The caller must reset the assembler at EOF so an unfinished partial is dropped.
      return null;
    }
    if (discardUntilFinal) {
      if (!partialFrame) {
        clearPendingAssembly();
      }
      return null;
    }

    if (hasPending || partialFrame) {
      if (!appendBounded(safeData)) {
        clearPendingAssembly();
        discardUntilFinal = partialFrame;
        overflowed = true;
        return null;
      }
      hasPending = true;
      pendingCodecConfig |= codecConfig;
      pendingKeyFrame |= keyFrame;
      if (partialFrame) {
        return null;
      }
      return emitPending();
    }

    if (safeData.length > MAX_ASSEMBLY_BYTES) {
      overflowed = true;
      return null;
    }
    return emit(safeData, codecConfig, keyFrame);
  }

  /** Drops an unfinished partial access unit and all associated flags. */
  void reset() {
    clearPendingAssembly();
    overflowed = false;
    sps = null;
    pps = null;
    framingMode = FramingMode.UNKNOWN;
  }

  private void clearPendingAssembly() {
    pending.reset();
    hasPending = false;
    pendingCodecConfig = false;
    pendingKeyFrame = false;
    discardUntilFinal = false;
  }

  /** Explicit EOF/flush operation; incomplete output is never emitted. */
  void flush() {
    reset();
  }

  boolean hasPending() {
    return hasPending || discardUntilFinal;
  }

  boolean consumeOverflowed() {
    boolean value = overflowed;
    overflowed = false;
    return value;
  }

  private boolean appendBounded(byte[] data) {
    if (data.length > MAX_ASSEMBLY_BYTES - pending.size()) {
      return false;
    }
    pending.write(data, 0, data.length);
    return true;
  }

  private EmittedAccessUnit emitPending() {
    byte[] data = pending.toByteArray();
    boolean codecConfig = pendingCodecConfig;
    boolean keyFrame = pendingKeyFrame;
    clearPendingAssembly();
    return emit(data, codecConfig, keyFrame);
  }

  private EmittedAccessUnit emit(byte[] data, boolean codecConfig, boolean keyFrame) {
    if (framingMode == FramingMode.UNKNOWN) {
      if (!codecConfig) {
        return null;
      }
      framingMode = detectCodecConfigFraming(data);
      if (framingMode == FramingMode.UNKNOWN) {
        return null;
      }
    }
    byte[] annexB = toAnnexB(data, framingMode);
    if (annexB.length == 0) {
      return null;
    }
    rememberParameterSets(annexB);
    boolean containsVcl = containsNalTypeInRange(annexB, 1, 5);
    boolean idrKeyFrame = containsNalTypeInRange(annexB, 5, 5);
    if (idrKeyFrame) {
      annexB = makeIdrSelfContained(annexB);
      if (annexB.length == 0) {
        return null;
      }
    }
    byte[] payload = endsWith(annexB, ACCESS_UNIT_DELIMITER)
      ? annexB
      : append(annexB, ACCESS_UNIT_DELIMITER);
    if (payload.length > TicketH264FrameRecord.MAX_PAYLOAD_BYTES) {
      overflowed = true;
      return null;
    }
    return new EmittedAccessUnit(
      payload,
      codecConfig,
      keyFrame,
      containsVcl,
      idrKeyFrame
    );
  }

  private void rememberParameterSets(byte[] annexB) {
    for (byte[] nal : splitAnnexB(annexB)) {
      int type = nalType(nal);
      if (type == 7) {
        sps = nal;
      } else if (type == 8) {
        pps = nal;
      }
    }
  }

  private byte[] makeIdrSelfContained(byte[] annexB) {
    List<byte[]> nals = splitAnnexB(annexB);
    boolean hasSps = nals.stream().anyMatch(nal -> nalType(nal) == 7);
    boolean hasPps = nals.stream().anyMatch(nal -> nalType(nal) == 8);
    if ((!hasSps && sps == null) || (!hasPps && pps == null)) {
      return new byte[0];
    }
    if (!hasSps) {
      nals.add(0, sps);
    }
    if (!hasPps) {
      int insertAt = 0;
      for (int index = 0; index < nals.size(); index += 1) {
        if (nalType(nals.get(index)) == 7) insertAt = index + 1;
      }
      nals.add(insertAt, pps);
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream(annexB.length + 128);
    for (byte[] nal : nals) {
      output.write(nal, 0, nal.length);
    }
    return output.toByteArray();
  }

  private static List<byte[]> splitAnnexB(byte[] annexB) {
    List<byte[]> nals = new ArrayList<>();
    int offset = 0;
    while (offset < annexB.length) {
      int startCodeLength = startCodeLengthAt(annexB, offset);
      if (startCodeLength == 0 || offset + startCodeLength >= annexB.length) {
        return new ArrayList<>();
      }
      int next = findStartCode(annexB, offset + startCodeLength + 1);
      int end = next < 0 ? annexB.length : next;
      nals.add(Arrays.copyOfRange(annexB, offset, end));
      offset = end;
    }
    return nals;
  }

  private static int nalType(byte[] nalWithStartCode) {
    int startCodeLength = startCodeLengthAt(nalWithStartCode, 0);
    if (startCodeLength == 0 || startCodeLength >= nalWithStartCode.length) return 0;
    return nalWithStartCode[startCodeLength] & 0x1f;
  }

  private static FramingMode detectCodecConfigFraming(byte[] data) {
    byte[] converted = tryConvertLengthPrefixed(data);
    boolean lengthPrefixedConfig = converted != null && containsParameterSet(converted);
    boolean annexBConfig = startsWithStartCode(data) && containsParameterSet(data);
    if (lengthPrefixedConfig == annexBConfig) {
      return FramingMode.UNKNOWN;
    }
    return lengthPrefixedConfig ? FramingMode.LENGTH_PREFIXED : FramingMode.ANNEX_B;
  }

  private static byte[] toAnnexB(byte[] data, FramingMode mode) {
    if (mode == FramingMode.LENGTH_PREFIXED) {
      byte[] converted = tryConvertLengthPrefixed(data);
      return converted == null ? new byte[0] : converted;
    }
    return startsWithStartCode(data) ? Arrays.copyOf(data, data.length) : new byte[0];
  }

  private static byte[] tryConvertLengthPrefixed(byte[] data) {
    ByteArrayOutputStream converted = new ByteArrayOutputStream(data.length + START_CODE.length);
    int offset = 0;
    int nalCount = 0;
    while (offset + 4 <= data.length) {
      int length = readInt(data, offset);
      if (length <= 0 || length > data.length - offset - 4) {
        return null;
      }
      offset += 4;
      converted.write(START_CODE, 0, START_CODE.length);
      converted.write(data, offset, length);
      offset += length;
      nalCount += 1;
    }
    if (nalCount == 0 || offset != data.length) {
      return null;
    }
    return converted.toByteArray();
  }

  private static int readInt(byte[] data, int offset) {
    return ((data[offset] & 0xff) << 24) |
      ((data[offset + 1] & 0xff) << 16) |
      ((data[offset + 2] & 0xff) << 8) |
      (data[offset + 3] & 0xff);
  }

  private static boolean startsWithStartCode(byte[] data) {
    return startCodeLengthAt(data, 0) > 0;
  }

  private static boolean containsParameterSet(byte[] annexB) {
    int offset = 0;
    boolean found = false;
    while (offset < annexB.length) {
      int startCodeLength = startCodeLengthAt(annexB, offset);
      if (startCodeLength == 0) {
        return false;
      }
      int nalOffset = offset + startCodeLength;
      if (nalOffset >= annexB.length) {
        return false;
      }
      int header = annexB[nalOffset] & 0xff;
      int nalType = header & 0x1f;
      if ((header & 0x80) != 0 || nalType == 0) {
        return false;
      }
      found |= nalType == 7 || nalType == 8;
      int next = findStartCode(annexB, nalOffset + 1);
      if (next < 0) {
        return found;
      }
      offset = next;
    }
    return found;
  }

  private static boolean containsNalTypeInRange(byte[] annexB, int minimumNalType, int maximumNalType) {
    int offset = 0;
    while (offset < annexB.length) {
      int startCodeLength = startCodeLengthAt(annexB, offset);
      if (startCodeLength == 0) {
        return false;
      }
      int nalOffset = offset + startCodeLength;
      if (nalOffset >= annexB.length) {
        return false;
      }
      int nalType = annexB[nalOffset] & 0x1f;
      if (nalType >= minimumNalType && nalType <= maximumNalType) {
        return true;
      }
      int next = findStartCode(annexB, nalOffset + 1);
      if (next < 0) {
        return false;
      }
      offset = next;
    }
    return false;
  }

  private static int findStartCode(byte[] data, int from) {
    for (int offset = Math.max(0, from); offset <= data.length - 3; offset += 1) {
      if (startCodeLengthAt(data, offset) > 0) {
        return offset;
      }
    }
    return -1;
  }

  private static int startCodeLengthAt(byte[] data, int offset) {
    if (
      offset + 3 <= data.length &&
      data[offset] == 0 &&
      data[offset + 1] == 0 &&
      data[offset + 2] == 1
    ) {
      return 3;
    }
    if (
      offset + 4 <= data.length &&
      data[offset] == 0 &&
      data[offset + 1] == 0 &&
      data[offset + 2] == 0 &&
      data[offset + 3] == 1
    ) {
      return 4;
    }
    return 0;
  }

  private static boolean endsWith(byte[] data, byte[] suffix) {
    if (data.length < suffix.length) {
      return false;
    }
    int start = data.length - suffix.length;
    for (int i = 0; i < suffix.length; i++) {
      if (data[start + i] != suffix[i]) {
        return false;
      }
    }
    return true;
  }

  private static byte[] append(byte[] first, byte[] second) {
    byte[] result = Arrays.copyOf(first, first.length + second.length);
    System.arraycopy(second, 0, result, first.length, second.length);
    return result;
  }

  private enum FramingMode {
    UNKNOWN,
    ANNEX_B,
    LENGTH_PREFIXED
  }

  static final class EmittedAccessUnit {
    final byte[] payload;
    final boolean codecConfig;
    final boolean keyFrame;
    final boolean containsVcl;
    final boolean idrKeyFrame;

    EmittedAccessUnit(
      byte[] payload,
      boolean codecConfig,
      boolean keyFrame,
      boolean containsVcl,
      boolean idrKeyFrame
    ) {
      this.payload = payload;
      this.codecConfig = codecConfig;
      this.keyFrame = keyFrame;
      this.containsVcl = containsVcl;
      this.idrKeyFrame = idrKeyFrame;
    }
  }
}
