package lv.jolkins.pixelorchestrator.app.ticket;

import java.time.LocalDate;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Native date recognition; this platform adapter owns the existing private anchor identity. */
public final class TicketVisualDateGlyphRecognizer {
  private static final char[] HEX = "0123456789abcdef".toCharArray();
  private static final byte[] ANCHOR_SALT = loadOrCreateAnchorSalt();

  /** Retained font-adapter value contract. Pixels are always copied. */
  public static final class RenderedGlyphTemplate {
    public final char value;
    public final boolean[] pixels;
    public final int width;
    public final int height;
    public RenderedGlyphTemplate(char value, boolean[] pixels, int width, int height) {
      this.value = value;
      this.pixels = pixels == null ? new boolean[0] : pixels.clone();
      this.width = width;
      this.height = height;
    }
  }

  public static final class DateRange {
    public final LocalDate from;
    public final LocalDate until;
    public final String anchor;
    public final int centerY;
    DateRange(LocalDate from, LocalDate until, int centerY) {
      this.from = from;
      this.until = until;
      this.centerY = centerY;
      this.anchor = opaqueAnchor(from, until);
    }
  }

  private TicketVisualDateGlyphRecognizer() {}

  public static List<DateRange> recognize(int[] pixels, int width, int height) {
    long[] values = NativeTicketMedia.recognizeDates(pixels, width, height);
    List<DateRange> ranges = new ArrayList<>(values.length / 3);
    for (int index = 0; index < values.length; index += 3) {
      ranges.add(new DateRange(LocalDate.ofEpochDay(values[index]),
        LocalDate.ofEpochDay(values[index + 1]), (int) values[index + 2]));
    }
    return ranges;
  }

  // Native card results reuse the same platform-owned salt and epoch-day encoding.
  static String anchorForEpochDays(long from, long until) {
    return opaqueAnchor(LocalDate.ofEpochDay(from), LocalDate.ofEpochDay(until));
  }

  private static String opaqueAnchor(LocalDate from, LocalDate until) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(ANCHOR_SALT);
      digest.update(ByteBuffer.allocate(16)
        .putLong(from.toEpochDay())
        .putLong(until.toEpochDay())
        .array());
      byte[] value = digest.digest();
      StringBuilder anchor = new StringBuilder(24);
      for (int index = 0; index < 12; index++) {
        int octet = value[index] & 0xff;
        anchor.append(HEX[octet >>> 4]);
        anchor.append(HEX[octet & 0x0f]);
      }
      return anchor.toString();
    } catch (Exception ignored) {
      // SHA-256 is mandatory on Android. Fail closed if the runtime is unexpectedly broken.
      return "";
    }
  }

  private static byte[] loadOrCreateAnchorSalt() {
    Path path = Paths.get("/data/local/pixel-stack/state/ticket-visual-anchor-salt.bin");
    try {
      byte[] existing = Files.readAllBytes(path);
      if (existing.length == 32) return existing;
    } catch (Exception ignored) {
      // First use or host-side unit test.
    }
    byte[] created = new byte[32];
    new SecureRandom().nextBytes(created);
    try {
      Files.createDirectories(path.getParent());
      Path temporary = Paths.get(path.toString() + ".tmp");
      Files.write(temporary, created);
      try {
        Files.setPosixFilePermissions(temporary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
      } catch (Exception ignored) {
        // Android's filesystem permission is also constrained by the private root directory.
      }
      Files.move(temporary, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (Exception ignored) {
      // Unit tests and restricted runtimes use a process-only salt and therefore fail closed
      // across restart instead of publishing a reversible date-derived identifier.
    }
    return created;
  }

}
