package lv.jolkins.pixelorchestrator.app.ticket;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Privacy-safe recognizer for the two numeric dates rendered on ViVi ticket cards.
 *
 * <p>Only the ten digits and the date separator are recognized. Text, pixels and recognized
 * dates never leave this process; callers receive an opaque stable anchor and comparable epoch
 * days. Unknown or ambiguous glyph runs fail closed.</p>
 */
public final class TicketVisualDateGlyphRecognizer {
  private static final int TEMPLATE_WIDTH = 5;
  private static final int TEMPLATE_HEIGHT = 7;
  private static final int NORMALIZED_GLYPH_WIDTH = 12;
  private static final int NORMALIZED_GLYPH_HEIGHT = 18;
  private static final int NORMALIZED_GLYPH_WORDS =
    (NORMALIZED_GLYPH_WIDTH * NORMALIZED_GLYPH_HEIGHT + Long.SIZE - 1) / Long.SIZE;
  private static final int[] DARK_GLYPH_THRESHOLDS = { 115, 150, 185 };
  private static final int[] BRIGHT_GLYPH_THRESHOLDS = { 185, 150, 115 };
  private static final int RANGE_ROW_TOLERANCE = 5;
  private static final char[] HEX = "0123456789abcdef".toCharArray();
  private static final Map<Character, List<String[]>> DIGITS = digitTemplates();
  private static volatile List<RenderedGlyphTemplate> RUNTIME_DIGITS = embeddedFontDigitTemplates();
  private static final byte[] ANCHOR_SALT = loadOrCreateAnchorSalt();

  /**
   * A phone-local digit template rendered from a font shipped by the installed ViVi app.
   * Templates are binary glyph masks only; no captured pixels or recognized values are stored.
   */
  public static final class RenderedGlyphTemplate {
    public final char value;
    public final boolean[] pixels;
    public final int width;
    public final int height;
    private final long[] normalizedWords;
    private final double logAspect;

    public RenderedGlyphTemplate(char value, boolean[] pixels, int width, int height) {
      this.value = value;
      this.pixels = pixels == null ? new boolean[0] : pixels.clone();
      this.width = width;
      this.height = height;
      this.normalizedWords = normalizedWords(this.pixels, width, height);
      this.logAspect = width > 0 && height > 0
        ? Math.log(width / (double) height)
        : Double.NaN;
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

  private static final class Component {
    final int left;
    final int top;
    final int right;
    final int bottom;
    final char value;

    Component(int left, int top, int right, int bottom, char value) {
      this.left = left;
      this.top = top;
      this.right = right;
      this.bottom = bottom;
      this.value = value;
    }

    int centerY() { return (top + bottom) / 2; }
  }

  private TicketVisualDateGlyphRecognizer() {}

  public static void installRuntimeDigitTemplates(List<RenderedGlyphTemplate> templates) {
    if (templates == null || templates.isEmpty()) {
      RUNTIME_DIGITS = Collections.emptyList();
      return;
    }
    List<RenderedGlyphTemplate> accepted = new ArrayList<>();
    for (RenderedGlyphTemplate template : templates) {
      if (template != null && template.value >= '0' && template.value <= '9' &&
        template.width > 0 && template.height > 0 &&
        template.pixels.length == template.width * template.height
      ) {
        accepted.add(template);
      }
    }
    RUNTIME_DIGITS = Collections.unmodifiableList(accepted);
  }

  public static List<DateRange> recognize(int[] pixels, int width, int height) {
    if (pixels == null || width <= 0 || height <= 0 || pixels.length != width * height) {
      return Collections.emptyList();
    }
    int[] luminances = new int[pixels.length];
    for (int i = 0; i < pixels.length; i++) {
      int pixel = pixels[i];
      int red = (pixel >> 16) & 0xff;
      int green = (pixel >> 8) & 0xff;
      int blue = pixel & 0xff;
      luminances[i] = (red * 54 + green * 183 + blue * 19) >> 8;
    }
    List<DateRange> candidates = new ArrayList<>();
    for (int thresholdIndex = 0; thresholdIndex < DARK_GLYPH_THRESHOLDS.length; thresholdIndex++) {
      int darkThreshold = DARK_GLYPH_THRESHOLDS[thresholdIndex];
      int brightThreshold = BRIGHT_GLYPH_THRESHOLDS[thresholdIndex];
      boolean[] dark = new boolean[pixels.length];
      boolean[] bright = new boolean[pixels.length];
      for (int i = 0; i < pixels.length; i++) {
        dark[i] = luminances[i] <= darkThreshold;
        bright[i] = luminances[i] >= brightThreshold;
      }
      candidates.addAll(recognizePolarity(dark, width, height));
      candidates.addAll(recognizePolarity(bright, width, height));
    }
    return agreeingRanges(candidates);
  }

  private static List<DateRange> agreeingRanges(List<DateRange> candidates) {
    List<DateRange> accepted = new ArrayList<>();
    List<Integer> conflictedRows = new ArrayList<>();
    for (DateRange candidate : candidates) {
      if (conflictedRows.stream().anyMatch(center ->
        Math.abs(center - candidate.centerY) <= RANGE_ROW_TOLERANCE
      )) continue;
      int matchingIndex = -1;
      for (int index = 0; index < accepted.size(); index++) {
        if (Math.abs(accepted.get(index).centerY - candidate.centerY) <= RANGE_ROW_TOLERANCE) {
          matchingIndex = index;
          break;
        }
      }
      if (matchingIndex < 0) {
        accepted.add(candidate);
      } else if (!accepted.get(matchingIndex).anchor.equals(candidate.anchor)) {
        int center = (accepted.get(matchingIndex).centerY + candidate.centerY) / 2;
        accepted.remove(matchingIndex);
        conflictedRows.add(center);
      }
    }
    return accepted;
  }

  /** Phone-local aggregate diagnostics; never includes recognized glyphs, dates or geometry. */
  public static String safeDiagnostic(int[] pixels, int width, int height) {
    if (pixels == null || width <= 0 || height <= 0 || pixels.length != width * height) {
      return "date_probe_invalid";
    }
    int[] luminances = new int[pixels.length];
    for (int i = 0; i < pixels.length; i++) {
      int pixel = pixels[i];
      int red = (pixel >> 16) & 0xff;
      int green = (pixel >> 8) & 0xff;
      int blue = pixel & 0xff;
      luminances[i] = (red * 54 + green * 183 + blue * 19) >> 8;
    }
    int darkDigits = 0;
    int brightDigits = 0;
    int darkRow = 0;
    int brightRow = 0;
    for (int thresholdIndex = 0; thresholdIndex < DARK_GLYPH_THRESHOLDS.length; thresholdIndex++) {
      int darkThreshold = DARK_GLYPH_THRESHOLDS[thresholdIndex];
      int brightThreshold = BRIGHT_GLYPH_THRESHOLDS[thresholdIndex];
      boolean[] dark = new boolean[pixels.length];
      boolean[] bright = new boolean[pixels.length];
      for (int i = 0; i < pixels.length; i++) {
        dark[i] = luminances[i] <= darkThreshold;
        bright[i] = luminances[i] >= brightThreshold;
      }
      List<Component> darkComponents = components(dark, width, height);
      List<Component> brightComponents = components(bright, width, height);
      darkDigits = Math.max(darkDigits,
        (int) darkComponents.stream().filter(value -> value.value != '.').count());
      brightDigits = Math.max(brightDigits,
        (int) brightComponents.stream().filter(value -> value.value != '.').count());
      darkRow = Math.max(darkRow, maxDigitsInOneRow(darkComponents));
      brightRow = Math.max(brightRow, maxDigitsInOneRow(brightComponents));
    }
    int rangeCount = recognize(pixels, width, height).size();
    return "date_ranges_" + Math.min(rangeCount, 9) +
      "_dark_digits_" + Math.min(darkDigits, 99) +
      "_dark_row_" + Math.min(darkRow, 99) +
      "_bright_digits_" + Math.min(brightDigits, 99) +
      "_bright_row_" + Math.min(brightRow, 99);
  }

  private static int maxDigitsInOneRow(List<Component> components) {
    int maximum = 0;
    for (Component seed : components) {
      int count = 0;
      for (Component component : components) {
        if (component.value != '.' &&
          Math.abs(component.centerY() - seed.centerY()) <= Math.max(3, seed.bottom - seed.top)
        ) count += 1;
      }
      maximum = Math.max(maximum, count);
    }
    return maximum;
  }

  private static List<DateRange> recognizePolarity(boolean[] foreground, int width, int height) {
    List<Component> components = components(foreground, width, height);
    components.sort(Comparator.comparingInt((Component value) -> value.centerY())
      .thenComparingInt(value -> value.left));
    List<String> rowTexts = new ArrayList<>();
    List<Integer> rowCenters = new ArrayList<>();
    Set<String> rowKeys = new HashSet<>();
    List<DateRange> ranges = new ArrayList<>();
    for (int start = 0; start < components.size(); start++) {
      List<Component> row = new ArrayList<>();
      Component seed = components.get(start);
      for (Component component : components) {
        if (Math.abs(component.centerY() - seed.centerY()) <= Math.max(3, seed.bottom - seed.top)) {
          row.add(component);
        }
      }
      row.sort(Comparator.comparingInt(value -> value.left));
      StringBuilder text = new StringBuilder();
      int previousRight = -1;
      for (Component component : row) {
        if (previousRight >= 0 && component.left - previousRight > Math.max(8, width / 20)) {
          text.append(' ');
        }
        text.append(component.value);
        previousRight = component.right;
      }
      String rowText = text.toString();
      String rowKey = rowText + "@" + (seed.centerY() / 3);
      if (rowKeys.add(rowKey)) {
        rowTexts.add(rowText);
        rowCenters.add(seed.centerY());
        DateRange range = parseDateRange(rowText, seed.centerY());
        if (range != null) ranges.add(range);
      }
    }
    // Some ViVi card layouts wrap the validity endpoints onto two close visual rows. Associate
    // only adjacent rows that each contain exactly one complete numeric date; anything less
    // specific remains ambiguous and fails closed.
    for (int first = 0; first < rowTexts.size(); first++) {
      String firstDigits = digitsOnly(rowTexts.get(first));
      if (firstDigits.length() != 8) continue;
      for (int second = first + 1; second < rowTexts.size(); second++) {
        if (Math.abs(rowCenters.get(first) - rowCenters.get(second)) > Math.max(24, height / 7)) continue;
        String secondDigits = digitsOnly(rowTexts.get(second));
        if (secondDigits.length() != 8) continue;
        DateRange range = parseDateRange(firstDigits + secondDigits,
          (rowCenters.get(first) + rowCenters.get(second)) / 2);
        if (range != null) {
          ranges.add(range);
        }
      }
    }
    return ranges;
  }

  /** Testable glyph entry point used by synthetic visual fixtures. */
  static char recognizeGlyph(boolean[] dark, int width, int height) {
    return recognizeGlyph(dark, width, height, true);
  }

  /** Test-only view of the unchanged rendered/fixed template confidence gate. */
  static char recognizeGlyphWithoutTopologyForTest(boolean[] dark, int width, int height) {
    return recognizeGlyph(dark, width, height, false);
  }

  /** Test-only entry point for fail-closed zero topology fixtures. */
  static boolean looksLikeTopologyZeroForTest(boolean[] dark, int width, int height) {
    return looksLikeTopologyZero(dark, width, height);
  }

  private static char recognizeGlyph(boolean[] dark, int width, int height, boolean allowTopology) {
    if (dark == null || width <= 0 || height <= 0 || dark.length != width * height) return '?';
    char runtimeMatch = recognizeRenderedGlyph(dark, width, height);
    if (runtimeMatch != '?') return runtimeMatch;
    char best = '?';
    double bestError = Double.MAX_VALUE;
    double runnerUpError = Double.MAX_VALUE;
    double zeroError = Double.MAX_VALUE;
    for (Map.Entry<Character, List<String[]>> entry : DIGITS.entrySet()) {
      double error = entry.getValue().stream()
        .mapToDouble(template -> templateError(dark, width, height, template))
        .min()
        .orElse(Double.MAX_VALUE);
      if (entry.getKey() == '0') zeroError = error;
      if (error < bestError) {
        runnerUpError = bestError;
        bestError = error;
        best = entry.getKey();
      } else if (error < runnerUpError) {
        runnerUpError = error;
      }
    }
    if (bestError > 0.34) return '?';
    if (runnerUpError - bestError >= 0.06) return best;
    if (!allowTopology) return '?';
    // The exact Android bilinear probe can leave a zero and a nine within two normalized pixels.
    // A zero may be recovered only when its bounded template score is itself within the existing
    // ambiguity margin of the best digit and the full-resolution component retains balanced side
    // strokes with no middle crossbar. This distinguishes the current phase-shifted zero from the
    // same font's nine without reducing the general template margin.
    if (zeroError <= 0.34 && zeroError - bestError <= 0.06 &&
      looksLikeTopologyZero(dark, width, height)
    ) return '0';
    // Bilinear reduction can soften the current thin-font eight enough that its bitmap-template
    // margin becomes inconclusive even though its two closed counters remain exact. Topology may
    // only corroborate the same bounded best template candidate; it never invents a digit from
    // unrelated letter-like components after an unrestricted rejection.
    if (best == '8' && looksLikeTopologyEight(dark, width, height)) return '8';
    return best == '9' && looksLikeTopologyNine(dark, width, height) ? '9' : '?';
  }

  private static boolean looksLikeTopologyZero(boolean[] foreground, int width, int height) {
    if (foreground == null || width < 5 || height < 7 ||
      foreground.length != width * height ||
      width * 100 < height * 45 || width * 100 > height * 140
    ) return false;
    if (!hasSingleCenteredEnclosedCounter(foreground, width, height)) return false;
    int foregroundCount = 0;
    int strongestTopRow = 0;
    int strongestMiddleRow = 0;
    int strongestBottomRow = 0;
    int middleLeftInk = 0;
    int middleRightInk = 0;
    int lowerLeftInk = 0;
    int lowerRightInk = 0;
    for (int y = 0; y < height; y++) {
      int rowCount = 0;
      for (int x = 0; x < width; x++) {
        if (!foreground[y * width + x]) continue;
        foregroundCount += 1;
        rowCount += 1;
        if (y >= height / 3 && y <= height * 2 / 3) {
          if (x < width / 2) middleLeftInk += 1;
          else middleRightInk += 1;
        }
        if (y >= height * 2 / 3) {
          if (x < width / 2) lowerLeftInk += 1;
          else lowerRightInk += 1;
        }
      }
      if (y < Math.max(1, height / 3)) strongestTopRow = Math.max(strongestTopRow, rowCount);
      if (y >= height / 3 && y <= height * 2 / 3) {
        strongestMiddleRow = Math.max(strongestMiddleRow, rowCount);
      }
      if (y >= height * 2 / 3) strongestBottomRow = Math.max(strongestBottomRow, rowCount);
    }
    int area = width * height;
    return foregroundCount * 100 >= area * 25 && foregroundCount * 100 <= area * 65 &&
      strongestTopRow * 100 >= width * 35 && strongestBottomRow * 100 >= width * 35 &&
      strongestMiddleRow * 100 <= width * 60 &&
      middleLeftInk >= 2 && middleRightInk >= 2 &&
      lowerLeftInk >= 2 && lowerRightInk >= 2 &&
      lowerLeftInk <= lowerRightInk * 2 && lowerRightInk <= lowerLeftInk * 2;
  }

  private static boolean hasSingleCenteredEnclosedCounter(
    boolean[] foreground,
    int width,
    int height
  ) {
    if (foreground == null || width < 3 || height < 5 ||
      foreground.length != width * height
    ) return false;
    int counterCenterY = singleEnclosedCounterCenterY(foreground, width, height);
    return counterCenterY >= 0 && counterCenterY * 100 >= height * 25 &&
      counterCenterY * 100 <= height * 75;
  }

  /**
   * Returns the vertical center of one adequately sized, four-connected enclosed background
   * counter. Any open outline, tiny enclosed speck, or second counter fails closed.
   */
  private static int singleEnclosedCounterCenterY(boolean[] foreground, int width, int height) {
    int area = width * height;
    boolean[] exterior = new boolean[area];
    int[] queue = new int[area];
    int head = 0;
    int tail = 0;
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        if (x != 0 && x != width - 1 && y != 0 && y != height - 1) continue;
        int index = y * width + x;
        if (!foreground[index] && !exterior[index]) {
          exterior[index] = true;
          queue[tail++] = index;
        }
      }
    }
    while (head < tail) {
      int current = queue[head++];
      int x = current % width;
      int y = current / width;
      int[] neighbors = {
        x > 0 ? current - 1 : -1,
        x + 1 < width ? current + 1 : -1,
        y > 0 ? current - width : -1,
        y + 1 < height ? current + width : -1,
      };
      for (int neighbor : neighbors) {
        if (neighbor >= 0 && !foreground[neighbor] && !exterior[neighbor]) {
          exterior[neighbor] = true;
          queue[tail++] = neighbor;
        }
      }
    }

    boolean[] visited = new boolean[area];
    int counterCount = 0;
    int counterCenterY = -1;
    int minimumCounterArea = Math.max(4, area / 40);
    for (int start = 0; start < area; start++) {
      if (foreground[start] || exterior[start] || visited[start]) continue;
      head = 0;
      tail = 0;
      queue[tail++] = start;
      visited[start] = true;
      int count = 0;
      int left = width;
      int right = -1;
      int top = height;
      int bottom = -1;
      int yTotal = 0;
      while (head < tail) {
        int current = queue[head++];
        int x = current % width;
        int y = current / width;
        count += 1;
        yTotal += y;
        left = Math.min(left, x);
        right = Math.max(right, x);
        top = Math.min(top, y);
        bottom = Math.max(bottom, y);
        int[] neighbors = {
          x > 0 ? current - 1 : -1,
          x + 1 < width ? current + 1 : -1,
          y > 0 ? current - width : -1,
          y + 1 < height ? current + width : -1,
        };
        for (int neighbor : neighbors) {
          if (neighbor >= 0 && !foreground[neighbor] && !exterior[neighbor] &&
            !visited[neighbor]
          ) {
            visited[neighbor] = true;
            queue[tail++] = neighbor;
          }
        }
      }
      int counterWidth = right - left + 1;
      int counterHeight = bottom - top + 1;
      if (count < minimumCounterArea || counterWidth < Math.max(2, (width + 3) / 4) ||
        counterHeight < Math.max(3, (height + 3) / 4)
      ) return -1;
      counterCount += 1;
      if (counterCount > 1) return -1;
      counterCenterY = yTotal / Math.max(1, count);
    }
    return counterCount == 1 ? counterCenterY : -1;
  }

  private static boolean looksLikeTopologyEight(boolean[] foreground, int width, int height) {
    if (foreground == null || width < 5 || height < 7 ||
      foreground.length != width * height ||
      width * 100 < height * 45 || width * 100 > height * 140
    ) return false;
    int foregroundCount = 0;
    for (boolean value : foreground) if (value) foregroundCount += 1;
    int area = width * height;
    if (foregroundCount * 100 < area * 25 || foregroundCount * 100 > area * 75) return false;

    int strongestWaistRow = 0;
    int strongestTopRow = 0;
    int strongestBottomRow = 0;
    for (int y = 0; y < height; y++) {
      int rowCount = 0;
      for (int x = 0; x < width; x++) if (foreground[y * width + x]) rowCount += 1;
      if (y >= height / 3 && y <= height * 2 / 3) {
        strongestWaistRow = Math.max(strongestWaistRow, rowCount);
      }
      if (y < Math.max(1, height / 3)) strongestTopRow = Math.max(strongestTopRow, rowCount);
      if (y >= height * 2 / 3) strongestBottomRow = Math.max(strongestBottomRow, rowCount);
    }
    if (strongestWaistRow * 100 < width * 55 ||
      strongestTopRow * 100 < width * 45 ||
      strongestBottomRow * 100 < width * 45
    ) return false;

    boolean[] exterior = new boolean[area];
    int[] queue = new int[area];
    int head = 0;
    int tail = 0;
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        if (x != 0 && x != width - 1 && y != 0 && y != height - 1) continue;
        int index = y * width + x;
        if (!foreground[index] && !exterior[index]) {
          exterior[index] = true;
          queue[tail++] = index;
        }
      }
    }
    while (head < tail) {
      int current = queue[head++];
      int x = current % width;
      int y = current / width;
      int[] neighbors = {
        x > 0 ? current - 1 : -1,
        x + 1 < width ? current + 1 : -1,
        y > 0 ? current - width : -1,
        y + 1 < height ? current + width : -1,
      };
      for (int neighbor : neighbors) {
        if (neighbor >= 0 && !foreground[neighbor] && !exterior[neighbor]) {
          exterior[neighbor] = true;
          queue[tail++] = neighbor;
        }
      }
    }

    boolean[] visitedHole = new boolean[area];
    int[] holeCenters = new int[2];
    int holeCount = 0;
    int minimumHoleArea = Math.max(2, area / 40);
    for (int start = 0; start < area; start++) {
      if (foreground[start] || exterior[start] || visitedHole[start]) continue;
      head = 0;
      tail = 0;
      queue[tail++] = start;
      visitedHole[start] = true;
      int count = 0;
      int left = width;
      int right = -1;
      int top = height;
      int bottom = -1;
      int yTotal = 0;
      while (head < tail) {
        int current = queue[head++];
        int x = current % width;
        int y = current / width;
        count += 1;
        yTotal += y;
        left = Math.min(left, x);
        right = Math.max(right, x);
        top = Math.min(top, y);
        bottom = Math.max(bottom, y);
        int[] neighbors = {
          x > 0 ? current - 1 : -1,
          x + 1 < width ? current + 1 : -1,
          y > 0 ? current - width : -1,
          y + 1 < height ? current + width : -1,
        };
        for (int neighbor : neighbors) {
          if (neighbor >= 0 && !foreground[neighbor] && !exterior[neighbor] &&
            !visitedHole[neighbor]
          ) {
            visitedHole[neighbor] = true;
            queue[tail++] = neighbor;
          }
        }
      }
      int holeWidth = right - left + 1;
      int holeHeight = bottom - top + 1;
      if (count < minimumHoleArea || holeWidth < Math.max(1, width / 4) ||
        holeHeight < Math.max(1, height / 7)
      ) return false;
      if (holeCount >= holeCenters.length) return false;
      holeCenters[holeCount++] = yTotal / Math.max(1, count);
    }
    if (holeCount != 2) return false;
    Arrays.sort(holeCenters);
    return holeCenters[0] < height / 2 && holeCenters[1] >= height / 2 &&
      holeCenters[1] - holeCenters[0] >= Math.max(2, height / 4);
  }

  private static boolean looksLikeTopologyNine(boolean[] foreground, int width, int height) {
    if (foreground == null || width < 5 || height < 7 ||
      foreground.length != width * height ||
      width * 100 < height * 45 || width * 100 > height * 140
    ) return false;
    int area = width * height;
    int foregroundCount = 0;
    int strongestTopRow = 0;
    int strongestMiddleRow = 0;
    int upperLeftInk = 0;
    int upperRightInk = 0;
    int lowerLeftInk = 0;
    int lowerRightInk = 0;
    for (int y = 0; y < height; y++) {
      int rowCount = 0;
      for (int x = 0; x < width; x++) {
        if (!foreground[y * width + x]) continue;
        foregroundCount += 1;
        rowCount += 1;
        if (y < height * 2 / 3) {
          if (x < width / 2) upperLeftInk += 1;
          else upperRightInk += 1;
        } else {
          if (x < width / 2) lowerLeftInk += 1;
          else lowerRightInk += 1;
        }
      }
      if (y < Math.max(1, height / 3)) strongestTopRow = Math.max(strongestTopRow, rowCount);
      if (y >= height / 3 && y <= height * 2 / 3) {
        strongestMiddleRow = Math.max(strongestMiddleRow, rowCount);
      }
    }
    if (foregroundCount * 100 < area * 25 || foregroundCount * 100 > area * 75 ||
      strongestTopRow * 100 < width * 45 || strongestMiddleRow * 100 < width * 55 ||
      upperLeftInk < 3 || upperRightInk < 3 ||
      lowerRightInk < 3 || lowerRightInk < lowerLeftInk * 2
    ) return false;

    boolean[] exterior = new boolean[area];
    boolean[] visitedHole = new boolean[area];
    int[] queue = new int[area];
    int head = 0;
    int tail = 0;
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        if (x != 0 && x != width - 1 && y != 0 && y != height - 1) continue;
        int index = y * width + x;
        if (!foreground[index] && !exterior[index]) {
          exterior[index] = true;
          queue[tail++] = index;
        }
      }
    }
    while (head < tail) {
      int current = queue[head++];
      int x = current % width;
      int y = current / width;
      int[] neighbors = {
        x > 0 ? current - 1 : -1,
        x + 1 < width ? current + 1 : -1,
        y > 0 ? current - width : -1,
        y + 1 < height ? current + width : -1,
      };
      for (int neighbor : neighbors) {
        if (neighbor >= 0 && !foreground[neighbor] && !exterior[neighbor]) {
          exterior[neighbor] = true;
          queue[tail++] = neighbor;
        }
      }
    }

    int holeCount = 0;
    int holeCenterY = -1;
    int minimumHoleArea = Math.max(2, area / 40);
    for (int start = 0; start < area; start++) {
      if (foreground[start] || exterior[start] || visitedHole[start]) continue;
      head = 0;
      tail = 0;
      queue[tail++] = start;
      visitedHole[start] = true;
      int count = 0;
      int left = width;
      int right = -1;
      int top = height;
      int bottom = -1;
      int yTotal = 0;
      while (head < tail) {
        int current = queue[head++];
        int x = current % width;
        int y = current / width;
        count += 1;
        yTotal += y;
        left = Math.min(left, x);
        right = Math.max(right, x);
        top = Math.min(top, y);
        bottom = Math.max(bottom, y);
        int[] neighbors = {
          x > 0 ? current - 1 : -1,
          x + 1 < width ? current + 1 : -1,
          y > 0 ? current - width : -1,
          y + 1 < height ? current + width : -1,
        };
        for (int neighbor : neighbors) {
          if (neighbor >= 0 && !foreground[neighbor] && !exterior[neighbor] &&
            !visitedHole[neighbor]
          ) {
            visitedHole[neighbor] = true;
            queue[tail++] = neighbor;
          }
        }
      }
      int holeWidth = right - left + 1;
      int holeHeight = bottom - top + 1;
      if (count < minimumHoleArea || holeWidth < Math.max(1, width / 4) ||
        holeHeight < Math.max(1, height / 7)
      ) return false;
      holeCount += 1;
      if (holeCount > 1) return false;
      holeCenterY = yTotal / Math.max(1, count);
    }
    return holeCount == 1 && holeCenterY >= 0 && holeCenterY * 100 < height * 55;
  }

  private static char recognizeRenderedGlyph(boolean[] source, int width, int height) {
    List<RenderedGlyphTemplate> templates = RUNTIME_DIGITS;
    if (templates.isEmpty()) return '?';
    long[] sourceWords = normalizedWords(source, width, height);
    double sourceLogAspect = Math.log(width / (double) height);
    double[] digitErrors = new double[10];
    Arrays.fill(digitErrors, Double.MAX_VALUE);
    for (RenderedGlyphTemplate template : templates) {
      int digit = template.value - '0';
      digitErrors[digit] = Math.min(
        digitErrors[digit],
        renderedTemplateError(sourceWords, sourceLogAspect, template)
      );
    }
    int bestDigit = -1;
    double bestError = Double.MAX_VALUE;
    double runnerUpError = Double.MAX_VALUE;
    for (int digit = 0; digit < digitErrors.length; digit++) {
      double error = digitErrors[digit];
      if (error < bestError) {
        runnerUpError = bestError;
        bestError = error;
        bestDigit = digit;
      } else if (error < runnerUpError) {
        runnerUpError = error;
      }
    }
    double margin = runnerUpError - bestError;
    boolean confident = (bestError <= 0.34 && margin >= 0.02) ||
      (bestError <= 0.42 && margin >= 0.10);
    return bestDigit >= 0 && confident
      ? (char) ('0' + bestDigit)
      : '?';
  }

  private static double renderedTemplateError(
    long[] sourceWords,
    double sourceLogAspect,
    RenderedGlyphTemplate template
  ) {
    int mismatches = 0;
    int union = 0;
    for (int index = 0; index < NORMALIZED_GLYPH_WORDS; index++) {
      long sourceWord = sourceWords[index];
      long templateWord = template.normalizedWords[index];
      mismatches += Long.bitCount(sourceWord ^ templateWord);
      union += Long.bitCount(sourceWord | templateWord);
    }
    double pixelError = mismatches / (double) Math.max(1, union);
    double aspectError = Math.min(1.0, Math.abs(sourceLogAspect - template.logAspect));
    return pixelError * 0.90 + aspectError * 0.10;
  }

  private static long[] normalizedWords(boolean[] pixels, int width, int height) {
    long[] words = new long[NORMALIZED_GLYPH_WORDS];
    if (pixels == null || width <= 0 || height <= 0 || pixels.length != width * height) {
      return words;
    }
    for (int y = 0; y < NORMALIZED_GLYPH_HEIGHT; y++) {
      for (int x = 0; x < NORMALIZED_GLYPH_WIDTH; x++) {
        if (!normalizedCell(
          pixels,
          width,
          height,
          x,
          y,
          NORMALIZED_GLYPH_WIDTH,
          NORMALIZED_GLYPH_HEIGHT
        )) continue;
        int bit = y * NORMALIZED_GLYPH_WIDTH + x;
        words[bit / Long.SIZE] |= 1L << (bit % Long.SIZE);
      }
    }
    return words;
  }

  private static boolean normalizedCell(
    boolean[] pixels,
    int width,
    int height,
    int normalizedX,
    int normalizedY,
    int normalizedWidth,
    int normalizedHeight
  ) {
    int left = normalizedX * width / normalizedWidth;
    int right = Math.max(left + 1, (normalizedX + 1) * width / normalizedWidth);
    int top = normalizedY * height / normalizedHeight;
    int bottom = Math.max(top + 1, (normalizedY + 1) * height / normalizedHeight);
    right = Math.min(width, right);
    bottom = Math.min(height, bottom);
    int foreground = 0;
    int samples = 0;
    for (int y = top; y < bottom; y++) {
      for (int x = left; x < right; x++) {
        if (pixels[y * width + x]) foreground += 1;
        samples += 1;
      }
    }
    return foreground * 2 >= Math.max(1, samples);
  }

  private static List<Component> components(boolean[] dark, int width, int height) {
    boolean[] seen = new boolean[dark.length];
    List<Component> values = new ArrayList<>();
    int[] queue = new int[dark.length];
    for (int index = 0; index < dark.length; index++) {
      if (!dark[index] || seen[index]) continue;
      int head = 0;
      int tail = 0;
      queue[tail++] = index;
      seen[index] = true;
      int left = width;
      int top = height;
      int right = -1;
      int bottom = -1;
      int count = 0;
      while (head < tail) {
        int current = queue[head++];
        int x = current % width;
        int y = current / width;
        left = Math.min(left, x);
        top = Math.min(top, y);
        right = Math.max(right, x);
        bottom = Math.max(bottom, y);
        count += 1;
        for (int dy = -1; dy <= 1; dy++) {
          for (int dx = -1; dx <= 1; dx++) {
            if (dx == 0 && dy == 0) continue;
            int nextX = x + dx;
            int nextY = y + dy;
            if (nextX < 0 || nextX >= width || nextY < 0 || nextY >= height) continue;
            int next = nextY * width + nextX;
            if (dark[next] && !seen[next]) {
              seen[next] = true;
              queue[tail++] = next;
            }
          }
        }
      }
      int componentWidth = right - left + 1;
      int componentHeight = bottom - top + 1;
      if (count <= 3 && componentWidth <= 3 && componentHeight <= 3) {
        values.add(new Component(left, top, right + 1, bottom + 1, '.'));
        continue;
      }
      if (componentHeight < 4 || componentWidth < 2 || componentWidth > componentHeight * 2) continue;
      boolean[] crop = new boolean[componentWidth * componentHeight];
      for (int y = top; y <= bottom; y++) {
        for (int x = left; x <= right; x++) {
          crop[(y - top) * componentWidth + (x - left)] = dark[y * width + x];
        }
      }
      char digit = recognizeGlyph(crop, componentWidth, componentHeight);
      if (digit != '?') {
        values.add(new Component(left, top, right + 1, bottom + 1, digit));
      }
    }
    return values;
  }

  private static DateRange parseDateRange(String value, int centerY) {
    String digits = digitsOnly(value);
    for (int start = 0; start + 16 <= digits.length(); start++) {
      try {
        LocalDate from = LocalDate.of(
          Integer.parseInt(digits.substring(start + 4, start + 8)),
          Integer.parseInt(digits.substring(start + 2, start + 4)),
          Integer.parseInt(digits.substring(start, start + 2))
        );
        LocalDate until = LocalDate.of(
          Integer.parseInt(digits.substring(start + 12, start + 16)),
          Integer.parseInt(digits.substring(start + 10, start + 12)),
          Integer.parseInt(digits.substring(start + 8, start + 10))
        );
        if (until.isBefore(from) || until.isAfter(from.plusYears(2))) continue;
        return new DateRange(from, until, centerY);
      } catch (DateTimeException | NumberFormatException ignored) {
        // Continue scanning this visual row. Invalid dates are never guessed.
      }
    }
    return null;
  }

  private static String digitsOnly(String value) {
    StringBuilder digits = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character >= '0' && character <= '9') digits.append(character);
    }
    return digits.toString();
  }

  private static double templateError(boolean[] source, int width, int height, String[] template) {
    int mismatches = 0;
    int samples = 0;
    for (int y = 0; y < TEMPLATE_HEIGHT; y++) {
      for (int x = 0; x < TEMPLATE_WIDTH; x++) {
        int sourceX = Math.min(width - 1, Math.max(0, (x * width + width / 2) / TEMPLATE_WIDTH));
        int sourceY = Math.min(height - 1, Math.max(0, (y * height + height / 2) / TEMPLATE_HEIGHT));
        boolean expected = template[y].charAt(x) == '#';
        if (source[sourceY * width + sourceX] != expected) mismatches += 1;
        samples += 1;
      }
    }
    return mismatches / (double) samples;
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

  private static Map<Character, List<String[]>> digitTemplates() {
    Map<Character, List<String[]>> values = new HashMap<>();
    values.put('0', variants(
      rows(".###.", "##.##", "##.##", "##.##", "##.##", "##.##", ".###."),
      rows(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###.")
    ));
    values.put('1', variants(
      rows("..##.", ".###.", "..##.", "..##.", "..##.", "..##.", ".####"),
      rows("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###.")
    ));
    values.put('2', variants(
      rows(".###.", "##.##", "...##", "..##.", ".##..", "##...", "#####"),
      rows(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####")
    ));
    values.put('3', variants(
      rows("####.", "...##", "...##", ".###.", "...##", "...##", "####."),
      rows("####.", "....#", "....#", ".###.", "....#", "....#", "####.")
    ));
    values.put('4', variants(
      rows("...##", "..###", ".#.##", "##.##", "#####", "...##", "...##"),
      rows("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#.")
    ));
    values.put('5', variants(
      rows("#####", "##...", "##...", "####.", "...##", "...##", "####."),
      rows("#####", "#....", "#....", "####.", "....#", "....#", "####.")
    ));
    values.put('6', variants(
      rows(".###.", "##...", "##...", "####.", "##.##", "##.##", ".###."),
      rows(".###.", "#....", "#....", "####.", "#...#", "#...#", ".###.")
    ));
    values.put('7', variants(
      rows("#####", "...##", "..##.", "..##.", ".##..", ".##..", ".##.."),
      rows("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#...")
    ));
    values.put('8', variants(
      rows(".###.", "##.##", "##.##", ".###.", "##.##", "##.##", ".###."),
      rows(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
      rows("..##.", ".####", ".#..#", ".###.", ".#..#", ".#..#", ".####")
    ));
    values.put('9', variants(
      rows(".###.", "##.##", "##.##", ".####", "...##", "...##", ".###."),
      rows(".###.", "#...#", "#...#", ".####", "....#", "....#", ".###."),
      rows(".##..", ".###.", "#...#", "#...#", ".####", "...#.", ".###.")
    ));
    return values;
  }

  private static List<RenderedGlyphTemplate> embeddedFontDigitTemplates() {
    List<RenderedGlyphTemplate> values = new ArrayList<>();
    values.add(encoded('0', "f070c030cf909fc0bfc03fc03fc03fc03fc03fc03fc03fc03fc0bfc09fc0cf90c030f070"));
    values.add(encoded('1', "00000000fc00fc00fc00fc00fc00fc00fc00fc00fc00fc00fc00fc00fc00fc00fc00fc00"));
    values.add(encoded('2', "f8f0e030cf90cf90dfd0ffd0ff90ff90ff30fc30fc70fcf0f9f0e3f0c3f0c7f080000000"));
    values.add(encoded('3', "80108030fe70fe70fcf0f1f0f0f0e030fe30ff90ffd0ffc0ffc0ffd07f903f308070e0f0"));
    values.add(encoded('4', "fc70fcf0fcf0f9f0f9f0f3f0f3f0e3f0e7f0cfc0cfc0cfc09fc00000ffc0ffc0ffc0ffc0"));
    values.add(encoded('5', "c010c010cff0cff0cff0c0f0c030c030ff90ffc0ffc0ffc0ffc0ffc03fd09f90c030e0f0"));
    values.add(encoded('6', "fcf0fcf0f1f0f3f0f3f0e0f0c03086308f10bfd03fc03fc03fc0bfd09f908f30e070f0f0"));
    values.add(encoded('7', "00000010ffb0ff30ff30fe30fe70fe70fcf0f9f0f9f0f9f0f1f0f3f0f7f0e7f0cff0cff0"));
    values.add(encoded('8', "f0f0e030cf30cfb0dfb0cf30c630e030c0309f90bfc03fc03fc03fc09fc08f90c030f0f0"));
    values.add(encoded('9', "f070c0308f909fc0bfc03fc03fc03fc09fc0c710c030f030fe30fc70fcf0f9f0f3f0e3f0"));
    values.add(encoded('0', "e070c0309f901f801f801f801f801f801f801f801f801f801f801f801f809f90c030e070"));
    values.add(encoded('1', "ffc0fc0000000e00fe00fe00fe00fe00fe00fe00fe00fe00fe00fe00fe00fe00fe00fe00"));
    values.add(encoded('2', "e0f0c0301f303f903f903f90ffb0ff30fe30fc70fcf0f3f0e3f0c7f0cff08ff000000000"));
    values.add(encoded('3', "e070c0301f901f903f80ff80ff90e030e070e030ff10ff80ff803f801f901f10c070e070"));
    values.add(encoded('4', "fe30fe30fc30f830f030f230f230e630ce308e308e301e3000000000fe30fe30fe30fe30"));
    values.add(encoded('5', "801080109ff09ff09ff09ff0807000309f10ff90ff80ff80ff803f801f901f10c070e070"));
    values.add(encoded('6', "fc70f070e7f0cff09ff09ff0907007101f801f801f801fc01fc01f809f808f80e030f070"));
    values.add(encoded('7', "00000000ff90ff30ff30ff30fe30fe70fef0fcf0fcf0f9f0f1f0f1f0f3f0f3f0e7f0cff0"));
    values.add(encoded('8', "e070c0309f901f801f801f809f90c030e070c0309f901f801f801f801f801f80c030e070"));
    values.add(encoded('9', "e0f0c0701f101f901f903f803f801f801f808f008000ff90ff90ff90ff30fe30e0f0e3f0"));
    // Low-resolution antialias variants for the ViVi regular-font six. This digit closes both
    // four-digit years and is the most sensitive to the card's current thin-stroke scaling.
    values.add(encoded('6', "f030e070e0708ff08ff08ff0003002001f801f801f801f801f801f808f8080308030e070"));
    values.add(encoded('6', "f830e030c1f0cff08ff09030801087001f801f801fc01fc01fc09fc08fc08f80c010e030"));
    values.add(encoded('6', "f830f030c3f08ff00ff038f000100f103fc03fc03fc03fc03fc03fc03fc08f108010f0f0"));
    values.add(encoded('6', "f8f0f1f0f1f0f7f0e7f0e7f080f00e100e101f107f807f807f801f101f108010e0f0e0f0"));
    values.add(encoded('6', "fc30f030c7f08ff0bff038f000100f103fc03fc03fc03fc03fc03fc03fc08f10c010f0f0"));
    values.add(encoded('6', "fc70e1f0e1f08ff09ff09ff000300e301f801f801fc01fc01fc01fc09f8082308230e070"));
    values.add(encoded('6', "fc70e1f0e1f08ff09ff09ff090700e301f801f801fc01fc01fc01fc09f808e308e30f070"));
    values.add(encoded('6', "fc70f070c3f0cff09ff09ff0907000300f101f903f903f803f801f909f908f10c030e070"));
    values.add(encoded('6', "fc70f1f0f1f0f3f0e3f0e3f080708e008e001fc07fc07fc07fc01fc01fc08e30e070e070"));
    values.add(encoded('6', "fc70fdf0fdf0f3f0eff0eff080708fb08fb07fc07fc07fc07fc01fc01fc08e30e070e070"));
    values.add(encoded('6', "fcf0f8f0f1f0f3f0e7f0c0f0c0308f109f909f903f803fc03fc03f801f908f10c030e0f0"));
    values.add(encoded('6', "fcf0f9f0f9f0f3f0e7f0e0f0c0308f109f909f903fd03fc03fc03fd01f908f30c030e0f0"));
    values.add(encoded('6', "fcf0fcf0f1f0f1f0f3f0e0f0c03080308f10bfc03fc03fc03fc09fc09f908f10c030f0f0"));
    values.add(encoded('6', "fcf0fdf0f9f0f3f0f3f0e1f0c070ce308f30bfd0bfc03fc03fc0bfd09f908f30e070f1f0"));
    values.add(encoded('6', "fe70f070e7f0cff0dff09ff0987007101f901f801f801fc01fc09f809f808f90e030f070"));
    values.add(encoded('6', "fe70f8f0f9f0f1f0e7f0e070c0309f90bfc0bfc03fe03fe03fe03fe09fc09f90c030f070"));
    return Collections.unmodifiableList(values);
  }

  private static RenderedGlyphTemplate encoded(char value, String rows) {
    final int width = 12;
    final int height = 18;
    if (rows == null || rows.length() != height * 4) {
      return new RenderedGlyphTemplate(value, new boolean[0], 0, 0);
    }
    boolean[] pixels = new boolean[width * height];
    for (int y = 0; y < height; y++) {
      int row = Integer.parseInt(rows.substring(y * 4, y * 4 + 4), 16);
      for (int x = 0; x < width; x++) {
        pixels[y * width + x] = ((row >> (15 - x)) & 1) == 0;
      }
    }
    return new RenderedGlyphTemplate(value, pixels, width, height);
  }

  private static String[] rows(String... values) { return values; }
  private static List<String[]> variants(String[]... values) { return Arrays.asList(values); }
}
