package lv.jolkins.pixelorchestrator.app.ticket;

/**
 * Classifies the fixed-size, sanitized visual probe produced by the rooted ticket capture helper.
 *
 * <p>This class intentionally has no Android dependencies so its production thresholds can be
 * exercised by ordinary JVM unit tests.</p>
 */
public final class TicketControlCodeVisualClassifier {
  public static final int SAMPLE_WIDTH = 48;
  public static final int SAMPLE_HEIGHT = 72;
  public static final int SUBMIT_SAMPLE_WIDTH = 96;
  public static final int SUBMIT_SAMPLE_HEIGHT = 144;

  public static final String CONTROL_POPUP = "control_popup";
  public static final String CONTROL_POPUP_STATIC_READY = "control_popup_static_ready";
  public static final String CONTROL_POPUP_VALUE_READY = "control_popup_value_ready";
  public static final String CONTROL_POPUP_KEYBOARD_READY = "control_popup_keyboard_ready";
  public static final String GENERATED = "generated";
  public static final String RAW_TICKET = "raw_ticket";
  public static final String TICKET_LIST_WITH_REGISTRATION_BUTTON =
    "ticket_list_with_registration_button";
  public static final String UNKNOWN = "unknown";

  // The result strip sits immediately below the Aztec graphic. ViVi has moved it by a few
  // pixels across recent app releases, so keep the probe tied to the lower ticket body rather
  // than one absolute four-row band. These are sanitized-probe coordinates, not device pixels.
  // The current ViVi release places the strip one to two probe rows higher than the previous
  // layout. Start above both positions so the phone can publish a marker for the fresh H.264
  // frame instead of cleaning the generated surface while the browser is still waiting.
  private static final int RESULT_CHIP_SCAN_START_TOP = 20;
  private static final int RESULT_CHIP_SCAN_END_TOP = 42;
  private static final int RESULT_CHIP_HEIGHT = 5;
  // The current strip is three sampled rows high; its middle row is softened by the display
  // scale and lands at roughly 25 dark cells instead of the old 29-cell full row.
  private static final int RESULT_CHIP_MIN_DARK_ROW_PIXELS = 22;
  private static final int RESULT_CHIP_MIN_LONG_DARK_RUN_PIXELS = 10;
  private static final int RESULT_CHIP_MIN_LONG_RUN_ROWS = 2;
  private static final int RESULT_CHIP_DARK_LUMINANCE = 100;
  private static final int RESULT_CHIP_LIGHT_LUMINANCE = 145;

  private TicketControlCodeVisualClassifier() {}

  public static String classify(int[] pixels) {
    if (pixels == null || pixels.length != SAMPLE_WIDTH * SAMPLE_HEIGHT) {
      return UNKNOWN;
    }
    if (frameHasControlCodeInputPopup(pixels)) {
      return CONTROL_POPUP;
    }
    if (frameHasGeneratedControlCodeResultChip(pixels)) {
      return GENERATED;
    }
    if (frameHasRawTicketCodeGraphic(pixels)) {
      return RAW_TICKET;
    }
    return UNKNOWN;
  }

  /**
   * Classifies the surface after the generated control-code row has been closed.
   *
   * <p>The ordinary ticket and the generated-code view share the same top header, so only the
   * inline dark result row that owns the small close X may prove generated state. This dedicated
   * cleanup entry point keeps that recovery contract explicit.</p>
   */
  public static String classifyForCleanup(int[] pixels) {
    if (frameHasTicketListWithRegistrationButton(pixels)) {
      return TICKET_LIST_WITH_REGISTRATION_BUTTON;
    }
    // The generated result is already on the cleanup lane. ViVi can leave its dark result row
    // under the same geometry that the popup heuristic samples while the row is being cleared;
    // preserve the generated-result proof here so the post-ACK verifier waits for the actual
    // transition instead of treating that still-visible result as a popup.
    if (frameHasGeneratedControlCodeResultChip(pixels) &&
        !frameHasStrongDarkControlCodeInputPopup(pixels)) {
      return GENERATED;
    }
    if (frameHasRegisteredTicketDetailSurface(pixels)) {
      return RAW_TICKET;
    }
    // The ordinary popup heuristic intentionally stays out of this lane. Its broad white-card
    // checks also match the registered-detail surface after ViVi clears the result row, so an
    // ambiguous cleanup frame must remain unknown and use the bounded safe fallback.
    return UNKNOWN;
  }

  /**
   * Proves the post-cleanup ticket list from the existing fixed-size rooted H.264 probe.
   *
   * <p>The current ViVi ticket list has a wide yellow "Reģistrēt biļeti" band immediately
   * below the card body. The registered-ticket detail places its yellow confirmation slider
   * lower, so the narrow band window distinguishes the two without OCR or exported pixels.</p>
   */
  private static boolean frameHasTicketListWithRegistrationButton(int[] pixels) {
    if (pixels == null || pixels.length != SAMPLE_WIDTH * SAMPLE_HEIGHT) {
      return false;
    }
    int qualifyingRows = 0;
    int widestYellowRow = 0;
    for (int y = 28; y <= 39; y++) {
      int yellowPixels = 0;
      for (int x = 2; x < SAMPLE_WIDTH - 2; x++) {
        int pixel = pixelAt(pixels, x, y);
        int red = (pixel >> 16) & 0xff;
        int green = (pixel >> 8) & 0xff;
        int blue = pixel & 0xff;
        if (red >= 180 && green >= 110 && green <= 235 && blue <= 90 && red - green >= 25) {
          yellowPixels += 1;
        }
      }
      widestYellowRow = Math.max(widestYellowRow, yellowPixels);
      if (yellowPixels >= 24) {
        qualifyingRows += 1;
      }
    }
    return qualifyingRows >= 3 && widestYellowRow >= 30;
  }

  /**
   * Proves the registered-ticket surface needed for the single detail-to-list return tap.
   *
   * <p>The current layout keeps the Aztec graphic in the upper ticket body and the yellow
   * confirmation slider below it. The slider is deliberately sampled outside the list proof
   * band, so this cannot authorize the resting list or press its registration button.</p>
   */
  private static boolean frameHasRegisteredTicketDetailSurface(int[] pixels) {
    if (!frameHasTicketDetailBase(pixels)) {
      return false;
    }
    int qualifyingRows = 0;
    int widestYellowRow = 0;
    for (int y = 41; y <= 49; y++) {
      int yellowPixels = 0;
      for (int x = 2; x < SAMPLE_WIDTH - 2; x++) {
        int pixel = pixelAt(pixels, x, y);
        int red = (pixel >> 16) & 0xff;
        int green = (pixel >> 8) & 0xff;
        int blue = pixel & 0xff;
        if (red >= 180 && green >= 110 && green <= 235 && blue <= 90 && red - green >= 25) {
          yellowPixels += 1;
        }
      }
      widestYellowRow = Math.max(widestYellowRow, yellowPixels);
      if (yellowPixels >= 24) {
        qualifyingRows += 1;
      }
    }
    return qualifyingRows >= 3 && widestYellowRow >= 30;
  }

  /**
   * Proves which stable popup layout and orange submit button are visible. The normal request
   * path uses the static layout so the keyboard never needs to open; the shifted result remains
   * distinguishable for recovery and diagnostics.
   */
  public static String classifySubmitLayout(int[] pixels) {
    if (pixels == null || pixels.length != SUBMIT_SAMPLE_WIDTH * SUBMIT_SAMPLE_HEIGHT) {
      return UNKNOWN;
    }
    if (submitFrameHasDarkReadyControlCodePopupWithValue(pixels)) {
      return CONTROL_POPUP_VALUE_READY;
    }
    if (submitFrameHasStaticReadyControlCodePopupWithValue(pixels)) {
      return CONTROL_POPUP_VALUE_READY;
    }
    if (submitFrameHasDarkReadyControlCodePopup(pixels)) {
      return CONTROL_POPUP_STATIC_READY;
    }
    if (submitFrameHasStaticReadyControlCodePopup(pixels)) {
      return CONTROL_POPUP_STATIC_READY;
    }
    if (submitFrameHasDarkKeyboardReadyControlCodePopup(pixels)) {
      return CONTROL_POPUP_KEYBOARD_READY;
    }
    if (submitFrameHasKeyboardReadyControlCodePopup(pixels)) {
      return CONTROL_POPUP_KEYBOARD_READY;
    }
    return UNKNOWN;
  }

  /**
   * The current ViVi control-code sheet is dark: a blue action spans the lower part of the
   * fixed popup while the entered digits are bright. The old light/orange layout remains below
   * for devices that have not received that visual theme yet. Both layouts are deliberately
   * recognized only in their fixed unshifted geometry.
   */
  private static boolean submitFrameHasDarkReadyControlCodePopup(int[] pixels) {
    VisualStats dialog = submitVisualStats(pixels, 8, 52, 88, 86);
    boolean darkDialogVisible = dialog.mean <= 85.0 &&
      dialog.darkRatio >= 0.75 &&
      dialog.lightRatio <= 0.12 &&
      dialog.contrast <= 90.0;
    return darkDialogVisible && submitFrameHasBlueControlCodePopupButton(pixels, 68, 78);
  }

  private static boolean submitFrameHasDarkReadyControlCodePopupWithValue(int[] pixels) {
    if (!submitFrameHasDarkReadyControlCodePopup(pixels)) {
      return false;
    }
    // This central interior excludes the title, field chrome and button. Dark-theme ViVi renders
    // entered digits as bright strokes; its empty placeholder is muted gray. Two columns plus
    // three pixels reject a single bright caret without inspecting or retaining the value.
    int brightPixels = submitBrightPixelCount(pixels, 30, 60, 66, 68, 190);
    int brightColumns = submitBrightColumnCount(pixels, 30, 60, 66, 68, 190);
    return brightPixels >= 3 && brightColumns >= 2;
  }

  private static boolean submitFrameHasDarkKeyboardReadyControlCodePopup(int[] pixels) {
    VisualStats shiftedDialog = submitVisualStats(pixels, 8, 24, 88, 62);
    boolean shiftedDarkDialogVisible = shiftedDialog.mean <= 85.0 &&
      shiftedDialog.darkRatio >= 0.75 &&
      shiftedDialog.lightRatio <= 0.12 &&
      shiftedDialog.contrast <= 90.0;
    return shiftedDarkDialogVisible && submitFrameHasBlueControlCodePopupButton(pixels, 40, 56);
  }

  private static boolean submitFrameHasStaticReadyControlCodePopup(int[] pixels) {
    VisualStats dialog = submitVisualStats(pixels, 16, 60, 80, 90);
    boolean dialogVisible = dialog.mean >= 125.0 &&
      dialog.lightRatio >= 0.46 &&
      dialog.darkRatio <= 0.28 &&
      dialog.contrast <= 95.0;
    return dialogVisible && submitFrameHasEnabledControlCodePopupButton(pixels, 73, 82);
  }

  private static boolean submitFrameHasStaticReadyControlCodePopupWithValue(int[] pixels) {
    if (!submitFrameHasStaticReadyControlCodePopup(pixels)) {
      return false;
    }
    // The dedicated 96x144 submit probe preserves the shortest two-digit value. ViVi's empty
    // placeholder remains above this threshold at this resolution, while the live thin digit
    // strokes land at luminance 60 and 75. Two separated columns plus two pixels preserve those
    // strokes while rejecting the placeholder, underline and a one- or two-column caret without
    // OCR or retaining the value.
    int veryDarkPixels = submitDarkPixelCount(pixels, 28, 64, 68, 72, 90);
    int veryDarkColumns = submitDarkColumnCount(pixels, 28, 64, 68, 72, 90);
    int veryDarkColumnSpan = submitDarkColumnSpan(pixels, 28, 64, 68, 72, 90);
    // At the native 1080px Pixel resolution the shortest accepted value can be reduced by the
    // 96x144 probe to two adjacent dark samples on one row. Keep the wider-span rule for normal
    // glyphs, but accept that compact shape only when it is horizontal: a caret is one vertical
    // column and must remain a blank value.
    int maximumDarkPixelsInOneRow = submitMaximumDarkPixelsInOneRow(
      pixels, 28, 64, 68, 72, 90
    );
    boolean compactTwoSampleValue = veryDarkColumnSpan >= 1 && maximumDarkPixelsInOneRow >= 2;
    return veryDarkPixels >= 2 && veryDarkColumns >= 2 &&
      (veryDarkColumnSpan >= 2 || compactTwoSampleValue);
  }

  private static int submitMaximumDarkPixelsInOneRow(
    int[] pixels,
    int left,
    int top,
    int right,
    int bottom,
    int maxLuminance
  ) {
    int maximum = 0;
    for (int y = Math.max(0, top); y < Math.min(SUBMIT_SAMPLE_HEIGHT, bottom); y++) {
      int rowDark = 0;
      for (int x = Math.max(0, left); x < Math.min(SUBMIT_SAMPLE_WIDTH, right); x++) {
        if (luminance(submitPixelAt(pixels, x, y)) <= maxLuminance) {
          rowDark += 1;
        }
      }
      maximum = Math.max(maximum, rowDark);
    }
    return maximum;
  }

  private static int submitDarkPixelCount(
    int[] pixels,
    int left,
    int top,
    int right,
    int bottom,
    int maxLuminance
  ) {
    int darkPixels = 0;
    for (int x = Math.max(0, left); x < Math.min(SUBMIT_SAMPLE_WIDTH, right); x++) {
      for (int y = Math.max(0, top); y < Math.min(SUBMIT_SAMPLE_HEIGHT, bottom); y++) {
        if (luminance(submitPixelAt(pixels, x, y)) <= maxLuminance) {
          darkPixels += 1;
        }
      }
    }
    return darkPixels;
  }

  private static int submitDarkColumnCount(
    int[] pixels,
    int left,
    int top,
    int right,
    int bottom,
    int maxLuminance
  ) {
    int darkColumns = 0;
    for (int x = Math.max(0, left); x < Math.min(SUBMIT_SAMPLE_WIDTH, right); x++) {
      boolean dark = false;
      for (int y = Math.max(0, top); y < Math.min(SUBMIT_SAMPLE_HEIGHT, bottom); y++) {
        if (luminance(submitPixelAt(pixels, x, y)) <= maxLuminance) {
          dark = true;
          break;
        }
      }
      if (dark) {
        darkColumns += 1;
      }
    }
    return darkColumns;
  }

  private static int submitDarkColumnSpan(
    int[] pixels,
    int left,
    int top,
    int right,
    int bottom,
    int maxLuminance
  ) {
    int firstDarkColumn = -1;
    int lastDarkColumn = -1;
    for (int x = Math.max(0, left); x < Math.min(SUBMIT_SAMPLE_WIDTH, right); x++) {
      for (int y = Math.max(0, top); y < Math.min(SUBMIT_SAMPLE_HEIGHT, bottom); y++) {
        if (luminance(submitPixelAt(pixels, x, y)) <= maxLuminance) {
          if (firstDarkColumn < 0) {
            firstDarkColumn = x;
          }
          lastDarkColumn = x;
          break;
        }
      }
    }
    return firstDarkColumn < 0 ? 0 : lastDarkColumn - firstDarkColumn;
  }

  private static int submitBrightPixelCount(
    int[] pixels,
    int left,
    int top,
    int right,
    int bottom,
    int minLuminance
  ) {
    int brightPixels = 0;
    for (int x = Math.max(0, left); x < Math.min(SUBMIT_SAMPLE_WIDTH, right); x++) {
      for (int y = Math.max(0, top); y < Math.min(SUBMIT_SAMPLE_HEIGHT, bottom); y++) {
        if (luminance(submitPixelAt(pixels, x, y)) >= minLuminance) {
          brightPixels += 1;
        }
      }
    }
    return brightPixels;
  }

  private static int submitBrightColumnCount(
    int[] pixels,
    int left,
    int top,
    int right,
    int bottom,
    int minLuminance
  ) {
    int brightColumns = 0;
    for (int x = Math.max(0, left); x < Math.min(SUBMIT_SAMPLE_WIDTH, right); x++) {
      boolean bright = false;
      for (int y = Math.max(0, top); y < Math.min(SUBMIT_SAMPLE_HEIGHT, bottom); y++) {
        if (luminance(submitPixelAt(pixels, x, y)) >= minLuminance) {
          bright = true;
          break;
        }
      }
      if (bright) {
        brightColumns += 1;
      }
    }
    return brightColumns;
  }

  private static boolean submitFrameHasKeyboardReadyControlCodePopup(int[] pixels) {
    VisualStats shiftedDialog = submitVisualStats(pixels, 16, 32, 80, 60);
    boolean shiftedDialogVisible = shiftedDialog.mean >= 125.0 &&
      shiftedDialog.lightRatio >= 0.46 &&
      shiftedDialog.darkRatio <= 0.28 &&
      shiftedDialog.contrast <= 95.0;
    return shiftedDialogVisible && submitFrameHasEnabledControlCodePopupButton(pixels, 48, 58);
  }

  private static boolean submitFrameHasEnabledControlCodePopupButton(int[] pixels, int top, int bottom) {
    int sampled = 0;
    int chromatic = 0;
    for (int y = top; y < bottom; y++) {
      for (int x = 62; x < 84; x++) {
        int pixel = submitPixelAt(pixels, x, y);
        int red = (pixel >> 16) & 0xff;
        int green = (pixel >> 8) & 0xff;
        int blue = pixel & 0xff;
        int maximum = Math.max(red, Math.max(green, blue));
        int minimum = Math.min(red, Math.min(green, blue));
        if (maximum >= 155 && minimum <= 95 && maximum - minimum >= 100) {
          chromatic += 1;
        }
        sampled += 1;
      }
    }
    return sampled > 0 && chromatic / (double) sampled >= 0.18;
  }

  private static boolean submitFrameHasBlueControlCodePopupButton(int[] pixels, int top, int bottom) {
    int sampled = 0;
    int blue = 0;
    for (int y = top; y < bottom; y++) {
      for (int x = 8; x < 88; x++) {
        int pixel = submitPixelAt(pixels, x, y);
        int red = (pixel >> 16) & 0xff;
        int green = (pixel >> 8) & 0xff;
        int blueChannel = pixel & 0xff;
        // The live dark sheet can be dimmed by the modal scrim, so this intentionally measures
        // the blue channel's separation instead of relying on an absolute bright Material color.
        if (blueChannel >= 80 && blueChannel - red >= 35 && blueChannel - green >= 20) {
          blue += 1;
        }
        sampled += 1;
      }
    }
    return sampled > 0 && blue / (double) sampled >= 0.18;
  }

  private static boolean frameHasControlCodeInputPopup(int[] pixels) {
    VisualStats dialog = visualStats(pixels, 8, 30, 40, 45);
    VisualStats shiftedDialog = visualStats(pixels, 8, 16, 40, 30);
    boolean dialogVisible = dialog.mean >= 125.0 &&
      dialog.lightRatio >= 0.46 &&
      dialog.darkRatio <= 0.28 &&
      dialog.contrast <= 95.0;
    boolean shiftedDialogVisible = shiftedDialog.mean >= 125.0 &&
      shiftedDialog.lightRatio >= 0.46 &&
      shiftedDialog.darkRatio <= 0.28 &&
      shiftedDialog.contrast <= 95.0;
    VisualStats darkDialog = visualStats(pixels, 4, 26, 44, 43);
    VisualStats shiftedDarkDialog = visualStats(pixels, 4, 12, 44, 31);
    boolean darkDialogVisible = darkDialog.mean <= 85.0 &&
      darkDialog.darkRatio >= 0.65 &&
      darkDialog.lightRatio <= 0.12 &&
      darkDialog.contrast <= 90.0;
    boolean shiftedDarkDialogVisible = shiftedDarkDialog.mean <= 85.0 &&
      shiftedDarkDialog.darkRatio >= 0.65 &&
      shiftedDarkDialog.lightRatio <= 0.12 &&
      shiftedDarkDialog.contrast <= 90.0;
    // A dark input line is not sufficient proof: the Aztec ticket detail also contains dark
    // text and separators in this area. Require the popup's colored action band for light
    // sheets, just as the dark-sheet path already requires its blue action band.
    return (dialogVisible && frameHasControlCodePopupOrangeOkButton(pixels)) ||
      (shiftedDialogVisible && frameHasShiftedControlCodePopupOrangeOkButton(pixels)) ||
      (darkDialogVisible && frameHasControlCodePopupBlueButton(pixels, 34, 40)) ||
      (shiftedDarkDialogVisible && frameHasControlCodePopupBlueButton(pixels, 20, 29));
  }

  private static boolean frameHasStrongDarkControlCodeInputPopup(int[] pixels) {
    VisualStats darkDialog = visualStats(pixels, 4, 26, 44, 43);
    VisualStats shiftedDarkDialog = visualStats(pixels, 4, 12, 44, 31);
    boolean darkDialogVisible = darkDialog.mean <= 85.0 &&
      darkDialog.darkRatio >= 0.65 &&
      darkDialog.lightRatio <= 0.12 &&
      darkDialog.contrast <= 90.0;
    boolean shiftedDarkDialogVisible = shiftedDarkDialog.mean <= 85.0 &&
      shiftedDarkDialog.darkRatio >= 0.65 &&
      shiftedDarkDialog.lightRatio <= 0.12 &&
      shiftedDarkDialog.contrast <= 90.0;
    return (darkDialogVisible && frameHasControlCodePopupBlueButton(pixels, 34, 40)) ||
      (shiftedDarkDialogVisible && frameHasControlCodePopupBlueButton(pixels, 20, 29));
  }

  private static boolean frameHasControlCodePopupOrangeOkButton(int[] pixels) {
    return frameHasOrangeControlCodePopupButton(pixels, 39, 44);
  }

  private static boolean frameHasShiftedControlCodePopupOrangeOkButton(int[] pixels) {
    return frameHasOrangeControlCodePopupButton(pixels, 24, 29);
  }

  private static boolean frameHasOrangeControlCodePopupButton(int[] pixels, int top, int bottom) {
    int sampled = 0;
    int orange = 0;
    int qualifyingRows = 0;
    for (int y = top; y < bottom; y++) {
      int rowOrange = 0;
      for (int x = 31; x < 42; x++) {
        int pixel = pixelAt(pixels, x, y);
        int red = (pixel >> 16) & 0xff;
        int green = (pixel >> 8) & 0xff;
        int blue = pixel & 0xff;
        if (red >= 155 && green >= 80 && green <= 190 && blue <= 95 && red - green >= 20 && green - blue >= 25) {
          orange += 1;
          rowOrange += 1;
        }
        sampled += 1;
      }
      if (rowOrange >= 6) {
        qualifyingRows += 1;
      }
    }
    return sampled > 0 && qualifyingRows >= 3 && orange / (double) sampled >= 0.08;
  }

  private static boolean frameHasControlCodePopupBlueButton(int[] pixels, int top, int bottom) {
    int sampled = 0;
    int blue = 0;
    for (int y = top; y < bottom; y++) {
      for (int x = 4; x < 44; x++) {
        int pixel = pixelAt(pixels, x, y);
        int red = (pixel >> 16) & 0xff;
        int green = (pixel >> 8) & 0xff;
        int blueChannel = pixel & 0xff;
        if (blueChannel >= 80 && blueChannel - red >= 35 && blueChannel - green >= 20) {
          blue += 1;
        }
        sampled += 1;
      }
    }
    return sampled > 0 && blue / (double) sampled >= 0.18;
  }

  private static boolean frameHasGeneratedControlCodeResultChip(int[] pixels) {
    if (!frameHasTicketDetailBase(pixels)) return false;
    int chipTop = findGeneratedControlCodeResultChipTop(pixels);
    if (chipTop < 0) return false;
    VisualStats chip = visualStats(
      pixels,
      SAMPLE_WIDTH,
      SAMPLE_HEIGHT,
      7,
      chipTop,
      SAMPLE_WIDTH - 7,
      chipTop + RESULT_CHIP_HEIGHT,
      RESULT_CHIP_DARK_LUMINANCE,
      RESULT_CHIP_LIGHT_LUMINANCE
    );
    int chipRows = generatedChipDarkRows(pixels, chipTop);
    int longRunRows = generatedChipLongRunRows(pixels, chipTop);
    return chip.darkRatio >= 0.50 && chip.lightRatio >= 0.01 &&
      chip.lightRatio <= 0.48 && chip.contrast >= 20.0 && chipRows >= 3 &&
      longRunRows >= RESULT_CHIP_MIN_LONG_RUN_ROWS;
  }

  private static int findGeneratedControlCodeResultChipTop(int[] pixels) {
    int bestTop = -1;
    int bestScore = Integer.MIN_VALUE;
    for (int top = RESULT_CHIP_SCAN_START_TOP;
         top <= RESULT_CHIP_SCAN_END_TOP;
         top++) {
      int darkRows = generatedChipDarkRows(pixels, top);
      if (darkRows < 3) continue;
      VisualStats chip = visualStats(
        pixels,
        SAMPLE_WIDTH,
        SAMPLE_HEIGHT,
        7,
        top,
        SAMPLE_WIDTH - 7,
        top + RESULT_CHIP_HEIGHT,
        RESULT_CHIP_DARK_LUMINANCE,
        RESULT_CHIP_LIGHT_LUMINANCE
      );
      if (chip.darkRatio < 0.50 || chip.lightRatio < 0.01 || chip.lightRatio > 0.48 ||
          chip.contrast < 20.0) {
        continue;
      }
      int score = darkRows * 100 + (int) Math.round(chip.darkRatio * 50.0) +
        (int) Math.round(chip.contrast);
      if (score > bestScore) {
        bestScore = score;
        bestTop = top;
      }
    }
    return bestTop;
  }

  private static int generatedChipDarkRows(int[] pixels, int top) {
    int darkRows = 0;
    for (int y = top; y < top + RESULT_CHIP_HEIGHT && y < SAMPLE_HEIGHT; y++) {
      int rowDark = 0;
      for (int x = 7; x < SAMPLE_WIDTH - 7; x++) {
        if (luminance(pixelAt(pixels, x, y)) <= RESULT_CHIP_DARK_LUMINANCE) rowDark++;
      }
      if (rowDark >= RESULT_CHIP_MIN_DARK_ROW_PIXELS) darkRows++;
    }
    return darkRows;
  }

  private static int generatedChipLongRunRows(int[] pixels, int top) {
    int qualifyingRows = 0;
    for (int y = top; y < top + RESULT_CHIP_HEIGHT && y < SAMPLE_HEIGHT; y++) {
      int longestRun = 0;
      int currentRun = 0;
      for (int x = 7; x < SAMPLE_WIDTH - 7; x++) {
        if (luminance(pixelAt(pixels, x, y)) <= RESULT_CHIP_DARK_LUMINANCE) {
          currentRun += 1;
          longestRun = Math.max(longestRun, currentRun);
        } else {
          currentRun = 0;
        }
      }
      if (longestRun >= RESULT_CHIP_MIN_LONG_DARK_RUN_PIXELS) {
        qualifyingRows += 1;
      }
    }
    return qualifyingRows;
  }

  private static boolean frameHasRawTicketCodeGraphic(int[] pixels) {
    VisualStats separator = visualStats(pixels, 7, 36, 41, 40);
    return frameHasTicketDetailBase(pixels) &&
      separator.lightRatio >= 0.50 &&
      separator.darkRatio >= 0.04 &&
      separator.darkRatio <= 0.45 &&
      separator.contrast >= 35.0;
  }

  private static boolean frameHasTicketDetailBase(int[] pixels) {
    VisualStats code = visualStats(pixels, 8, 14, 40, 34);
    return frameHasTicketDetailHeader(pixels) &&
      // The current ViVi Aztec is antialiased into a little under 14% dark cells at the
      // sanitized probe size; retain contrast/light-area checks while allowing that edge.
      code.darkRatio >= 0.10 &&
      code.lightRatio >= 0.18 &&
      code.contrast >= 45.0;
  }

  private static boolean frameHasTicketDetailHeader(int[] pixels) {
    VisualStats label = visualStats(pixels, 2, 2, 19, 7);
    VisualStats topBand = visualStats(pixels, 0, 0, SAMPLE_WIDTH, 10);
    int currentRedSamples = 0;
    int currentRedPixels = 0;
    int legacyRedSamples = 0;
    int legacyRedPixels = 0;
    for (int y = 0; y < 10; y++) {
      for (int x = 1; x < SAMPLE_WIDTH - 1; x++) {
        int pixel = pixelAt(pixels, x, y);
        int red = (pixel >> 16) & 0xff;
        int green = (pixel >> 8) & 0xff;
        int blue = pixel & 0xff;
        if (red >= 135 && red - green >= 25 && red - blue >= 35 && green <= 110 && blue <= 115) {
          currentRedPixels += 1;
        }
        currentRedSamples += 1;
      }
    }
    for (int y = 8; y < 15; y++) {
      for (int x = 1; x < SAMPLE_WIDTH - 1; x++) {
        int pixel = pixelAt(pixels, x, y);
        int red = (pixel >> 16) & 0xff;
        int green = (pixel >> 8) & 0xff;
        int blue = pixel & 0xff;
        if (red >= 135 && red - green >= 25 && red - blue >= 35 && green <= 110 && blue <= 115) {
          legacyRedPixels += 1;
        }
        legacyRedSamples += 1;
      }
    }
    double currentRedRatio = currentRedSamples == 0 ? 0.0 : currentRedPixels / (double) currentRedSamples;
    double legacyRedRatio = legacyRedSamples == 0 ? 0.0 : legacyRedPixels / (double) legacyRedSamples;
    boolean labelPillVisible = label.mean >= 150.0 &&
      label.lightRatio >= 0.48 &&
      label.darkRatio <= 0.34 &&
      label.contrast <= 115.0;
    boolean ticketHeaderShape = topBand.lightRatio >= 0.10 &&
      topBand.darkRatio >= 0.10 &&
      legacyRedRatio >= 0.24;
    // Current ViVi renders the orange ticket header immediately after the stream's top crop;
    // the old probe expected that band eight rows lower and required a light label pill. Keep
    // that legacy form, but accept the current red header when the shared ticket code graphic
    // is present. Popup detection runs first, so this cannot authorize a dialog frame.
    boolean currentTicketHeaderShape = currentRedRatio >= 0.24;
    return (labelPillVisible && ticketHeaderShape) || currentTicketHeaderShape;
  }

  private static VisualStats visualStats(int[] pixels, int left, int top, int right, int bottom) {
    return visualStats(pixels, SAMPLE_WIDTH, SAMPLE_HEIGHT, left, top, right, bottom);
  }

  private static VisualStats submitVisualStats(int[] pixels, int left, int top, int right, int bottom) {
    return visualStats(pixels, SUBMIT_SAMPLE_WIDTH, SUBMIT_SAMPLE_HEIGHT, left, top, right, bottom);
  }

  private static VisualStats visualStats(
    int[] pixels,
    int width,
    int height,
    int left,
    int top,
    int right,
    int bottom
  ) {
    return visualStats(pixels, width, height, left, top, right, bottom, 80, 175);
  }

  private static VisualStats visualStats(
    int[] pixels,
    int width,
    int height,
    int left,
    int top,
    int right,
    int bottom,
    int darkLuminance,
    int lightLuminance
  ) {
    int sampled = 0;
    int dark = 0;
    int light = 0;
    long sum = 0L;
    long sumSquares = 0L;
    for (int y = Math.max(0, top); y < Math.min(height, bottom); y++) {
      for (int x = Math.max(0, left); x < Math.min(width, right); x++) {
        int luminance = luminance(pixels[y * width + x]);
        sum += luminance;
        sumSquares += (long) luminance * luminance;
        if (luminance <= darkLuminance) {
          dark += 1;
        }
        if (luminance >= lightLuminance) {
          light += 1;
        }
        sampled += 1;
      }
    }
    if (sampled == 0) {
      return new VisualStats(0.0, 0.0, 0.0, 0.0);
    }
    double mean = sum / (double) sampled;
    double variance = (sumSquares / (double) sampled) - (mean * mean);
    return new VisualStats(
      mean,
      Math.sqrt(Math.max(0.0, variance)),
      dark / (double) sampled,
      light / (double) sampled
    );
  }

  private static int pixelAt(int[] pixels, int x, int y) {
    return pixels[y * SAMPLE_WIDTH + x];
  }

  private static int submitPixelAt(int[] pixels, int x, int y) {
    return pixels[y * SUBMIT_SAMPLE_WIDTH + x];
  }

  private static int luminance(int pixel) {
    int red = (pixel >> 16) & 0xff;
    int green = (pixel >> 8) & 0xff;
    int blue = pixel & 0xff;
    return (red * 299 + green * 587 + blue * 114) / 1000;
  }

  private static final class VisualStats {
    final double mean;
    final double contrast;
    final double darkRatio;
    final double lightRatio;

    VisualStats(double mean, double contrast, double darkRatio, double lightRatio) {
      this.mean = mean;
      this.contrast = contrast;
      this.darkRatio = darkRatio;
      this.lightRatio = lightRatio;
    }
  }
}
