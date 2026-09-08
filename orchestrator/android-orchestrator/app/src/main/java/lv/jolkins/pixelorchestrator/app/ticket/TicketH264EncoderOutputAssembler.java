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
    NalUnits units;
    if (framingMode == FramingMode.UNKNOWN) {
      if (!codecConfig) return null;
      NalUnits annex = parse(data, FramingMode.ANNEX_B);
      NalUnits lengths = parse(data, FramingMode.LENGTH_PREFIXED);
      boolean annexConfig = annex != null && annex.hasParameterSet();
      boolean lengthConfig = lengths != null && lengths.hasParameterSet();
      if (annexConfig == lengthConfig) return null;
      framingMode = annexConfig ? FramingMode.ANNEX_B : FramingMode.LENGTH_PREFIXED;
      units = annexConfig ? annex : lengths;
    } else {
      units = parse(data, framingMode);
    }
    if (units == null) return null;
    if (units.sps != null) sps = units.sps;
    if (units.pps != null) pps = units.pps;
    if (units.idr) {
      if (sps == null || pps == null) return null;
      if (units.sps == null) units.nals.add(0, sps);
      if (units.pps == null) {
        int afterSps = 0;
        for (int i = 0; i < units.nals.size(); i++) {
          byte[] nal = units.nals.get(i);
          if ((nal[startCodeLengthAt(nal, 0)] & 0x1f) == 7) afterSps = i + 1;
        }
        units.nals.add(afterSps, pps);
      }
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream(data.length);
    for (byte[] nal : units.nals) output.write(nal, 0, nal.length);
    byte[] assembled = output.toByteArray();
    if (!endsWith(assembled, ACCESS_UNIT_DELIMITER)) {
      output.write(ACCESS_UNIT_DELIMITER, 0, ACCESS_UNIT_DELIMITER.length);
    }
    if (output.size() > MAX_ASSEMBLY_BYTES) {
      overflowed = true;
      return null;
    }
    return new EmittedAccessUnit(output.toByteArray(), codecConfig, keyFrame, units.vcl, units.idr);
  }

  /** Parse and validate once; configuration detection rejects ambiguous framing. */
  private static NalUnits parse(byte[] data, FramingMode mode) {
    NalUnits units = new NalUnits();
    int offset = 0;
    while (offset < data.length) {
      int headerAt;
      int end;
      byte[] nal;
      if (mode == FramingMode.LENGTH_PREFIXED) {
        if (data.length - offset < 4) return null;
        int length = readInt(data, offset);
        if (length <= 0 || length > data.length - offset - 4) return null;
        headerAt = offset + 4;
        end = headerAt + length;
        nal = Arrays.copyOfRange(data, offset, end);
        System.arraycopy(START_CODE, 0, nal, 0, START_CODE.length);
      } else {
        int prefix = startCodeLengthAt(data, offset);
        if (prefix == 0 || offset + prefix >= data.length) return null;
        headerAt = offset + prefix;
        int next = findStartCode(data, headerAt + 1);
        end = next < 0 ? data.length : next;
        nal = Arrays.copyOfRange(data, offset, end);
      }
      int header = data[headerAt] & 0xff;
      int type = header & 0x1f;
      if ((header & 0x80) != 0 || type == 0) return null;
      units.nals.add(nal);
      if (type == 7) units.sps = nal;
      if (type == 8) units.pps = nal;
      units.vcl |= type >= 1 && type <= 5;
      units.idr |= type == 5;
      offset = end;
    }
    return units.nals.isEmpty() ? null : units;
  }

  private static final class NalUnits {
    final List<byte[]> nals = new ArrayList<>();
    byte[] sps;
    byte[] pps;
    boolean vcl;
    boolean idr;

    boolean hasParameterSet() {
      return sps != null || pps != null;
    }
  }

  private static int readInt(byte[] data, int offset) {
    return ((data[offset] & 0xff) << 24) |
      ((data[offset + 1] & 0xff) << 16) |
      ((data[offset + 2] & 0xff) << 8) |
      (data[offset + 3] & 0xff);
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
