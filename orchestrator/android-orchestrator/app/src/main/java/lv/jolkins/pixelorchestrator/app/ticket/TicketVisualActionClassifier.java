package lv.jolkins.pixelorchestrator.app.ticket;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Fixed-size, phone-local visual state and action-anchor classifier for Ticket action v3. */
public final class TicketVisualActionClassifier {
  public static final int PROBE_WIDTH = 384;
  public static final int PROBE_HEIGHT = 576;
  public static final int SAMPLE_WIDTH = 192;
  public static final int SAMPLE_HEIGHT = 288;

  public static final class Bounds {
    public final int left;
    public final int top;
    public final int right;
    public final int bottom;

    Bounds(int left, int top, int right, int bottom) {
      this.left = left;
      this.top = top;
      this.right = right;
      this.bottom = bottom;
    }

    String wire() { return left + "," + top + "," + right + "," + bottom; }
  }

  private static final class EmptyTicketsDetection {
    final Bounds singleUseTabTarget;
    final Bounds timeTabTarget;
    final Bounds timeLabelGlyph;
    final boolean timeTicketsSelected;

    EmptyTicketsDetection(
      Bounds singleUseTabTarget,
      Bounds timeTabTarget,
      Bounds timeLabelGlyph,
      boolean timeTicketsSelected
    ) {
      this.singleUseTabTarget = singleUseTabTarget;
      this.timeTabTarget = timeTabTarget;
      this.timeLabelGlyph = timeLabelGlyph;
      this.timeTicketsSelected = timeTicketsSelected;
    }
  }

  public static final class Card {
    public final Bounds bounds;
    public final Bounds registrationBounds;
    public final Bounds activatedDetailBounds;
    public final String anchor;
    public final boolean latest;

    Card(
      Bounds bounds,
      Bounds registrationBounds,
      Bounds activatedDetailBounds,
      String anchor,
      boolean latest
    ) {
      this.bounds = bounds;
      this.registrationBounds = registrationBounds;
      this.activatedDetailBounds = activatedDetailBounds;
      this.anchor = anchor;
      this.latest = latest;
    }
  }

  public static final class Result {
    public final String state;
    public final String currentAnchor;
    public final Bounds sliderBounds;
    public final Bounds controlCodeBounds;
    public final Bounds backBounds;
    public final Bounds ticketsTabBounds;
    public final Bounds timeTicketsTabBounds;
    public final List<Card> cards;

    Result(String state, String currentAnchor, Bounds sliderBounds, Bounds controlCodeBounds, Bounds backBounds, List<Card> cards) {
      this(state, currentAnchor, sliderBounds, controlCodeBounds, backBounds, null, null, cards);
    }

    Result(String state, String currentAnchor, Bounds sliderBounds, Bounds controlCodeBounds, Bounds backBounds, Bounds ticketsTabBounds, List<Card> cards) {
      this(state, currentAnchor, sliderBounds, controlCodeBounds, backBounds, ticketsTabBounds, null, cards);
    }

    Result(String state, String currentAnchor, Bounds sliderBounds, Bounds controlCodeBounds, Bounds backBounds, Bounds ticketsTabBounds, Bounds timeTicketsTabBounds, List<Card> cards) {
      this.state = state;
      this.currentAnchor = currentAnchor;
      this.sliderBounds = sliderBounds;
      this.controlCodeBounds = controlCodeBounds;
      this.backBounds = backBounds;
      this.ticketsTabBounds = ticketsTabBounds;
      this.timeTicketsTabBounds = timeTicketsTabBounds;
      this.cards = cards;
    }

    public String wire() {
      StringBuilder value = new StringBuilder();
      value.append("state=").append(state);
      if (!currentAnchor.isEmpty()) value.append(" anchor=").append(currentAnchor);
      if (sliderBounds != null) value.append(" slider=").append(sliderBounds.wire());
      if (controlCodeBounds != null) value.append(" control=").append(controlCodeBounds.wire());
      if (backBounds != null) value.append(" back=").append(backBounds.wire());
      if (ticketsTabBounds != null) value.append(" tickets=").append(ticketsTabBounds.wire());
      if (timeTicketsTabBounds != null) value.append(" time=").append(timeTicketsTabBounds.wire());
      if (!cards.isEmpty()) {
        value.append(" cards=");
        for (int index = 0; index < cards.size(); index++) {
          if (index > 0) value.append(';');
          Card card = cards.get(index);
          value.append(card.anchor).append('@').append(card.bounds.wire()).append('@');
          value.append(card.registrationBounds == null ? "-" : card.registrationBounds.wire());
          value.append('@');
          value.append(card.activatedDetailBounds == null ? "-" : card.activatedDetailBounds.wire());
          value.append('@').append(card.latest ? '1' : '0');
        }
      }
      return value.toString();
    }
  }

  private static final class ViviHomeDetection {
    final Bounds ticketsTabBounds;
    final String diagnostic;

    ViviHomeDetection(Bounds ticketsTabBounds, String diagnostic) {
      this.ticketsTabBounds = ticketsTabBounds;
      this.diagnostic = diagnostic;
    }
  }

  private static final class ViviProfileDetection {
    final boolean proved;
    final String diagnostic;

    ViviProfileDetection(boolean proved, String diagnostic) {
      this.proved = proved;
      this.diagnostic = diagnostic;
    }
  }

  private static final class ViviOtherTabDetection {
    final boolean proved;

    ViviOtherTabDetection(boolean proved) {
      this.proved = proved;
    }
  }

  private static final class NavigationGlyphStats {
    int pixels = 0;
    int left = SAMPLE_WIDTH;
    int top = SAMPLE_HEIGHT;
    int right = -1;
    int bottom = -1;

    void add(int x, int y) {
      pixels += 1;
      left = Math.min(left, x);
      top = Math.min(top, y);
      right = Math.max(right, x + 1);
      bottom = Math.max(bottom, y + 1);
    }

    int width() { return right - left; }
    int height() { return bottom - top; }
    int area() { return width() * height(); }
    boolean present() { return right > left && bottom > top; }
  }

  private TicketVisualActionClassifier() {}

  public static Result classify(int[] pixels) {
    return classify(pixels, true);
  }

  /** Detail-only proof path: deliberately skips ticket-list glyph/date recognition. */
  public static Result classifyCurrent(int[] pixels) {
    return classify(pixels, false);
  }

  /**
   * Returns the one proved selected ViVi bottom tab without consulting any page-body pixels.
   *
   * <p>Exactly one lower glyph must be orange and the other three must independently retain
   * their neutral silhouettes. This route-only result is carried beside the richer Ticket view
   * classification so changing news, cards, headers, and account content cannot redirect the
   * non-destructive account flow.</p>
   */
  public static String selectedBottomNavigationTab(int[] pixels) {
    int[] geometryPixels = geometryPixels(pixels);
    if (geometryPixels == null) return "";
    if (detectViviHome(geometryPixels).ticketsTabBounds != null) return "home";
    if (detectViviProfile(geometryPixels).proved) return "profile";
    if (detectViviOtherBottomTab(geometryPixels, false).proved) return "menu";
    if (detectViviOtherBottomTab(geometryPixels, true).proved) return "tickets";
    return "";
  }

  private static Result classify(int[] pixels, boolean resolveTicketList) {
    if (pixels == null) {
      return unknown();
    }
    int[] geometryPixels;
    if (pixels.length == PROBE_WIDTH * PROBE_HEIGHT) {
      geometryPixels = downsample(pixels, PROBE_WIDTH, PROBE_HEIGHT, SAMPLE_WIDTH, SAMPLE_HEIGHT);
    } else if (pixels.length == SAMPLE_WIDTH * SAMPLE_HEIGHT) {
      geometryPixels = pixels;
    } else {
      return unknown();
    }
    int[] headerGeometryPixels = pixels.length == PROBE_WIDTH * PROBE_HEIGHT
      ? preserveProbeHeaderContrast(pixels, geometryPixels)
      : geometryPixels;
    int[] compact = downsample(geometryPixels, SAMPLE_WIDTH, SAMPLE_HEIGHT,
      TicketControlCodeVisualClassifier.SAMPLE_WIDTH,
      TicketControlCodeVisualClassifier.SAMPLE_HEIGHT);
    String ordinary = TicketControlCodeVisualClassifier.classify(compact);
    String activated = TicketControlCodeVisualClassifier.classifyForActivatedTicket(compact);
    String generatedClose = TicketControlCodeVisualClassifier.generatedResultCloseBounds(compact);
    String slider = TicketControlCodeVisualClassifier.registrationSliderBounds(compact);
    String detailAnchor = detailVisualAnchor(geometryPixels);
    Bounds back = detectDetailCloseBounds(headerGeometryPixels);
    List<Bounds> registrationBands = yellowBands(geometryPixels);
    boolean strongListChrome = hasStrongTicketListChrome(geometryPixels);
    boolean hasTicketDetailBase = activated.equals(TicketControlCodeVisualClassifier.RAW_TICKET);
    boolean hasStrongDetailChrome = hasTicketDetailBase || back != null;

    // Preserve the primary classifier's blocker-sensitive precedence for every existing Ticket
    // action. The body-independent route authority is exposed separately by
    // selectedBottomNavigationTab() and must never turn a popup, login, or detail view into a
    // generic navigation state.
    ViviHomeDetection home = detectViviHome(geometryPixels);
    boolean homeBlocked = home.ticketsTabBounds != null && blocksViviHomeProof(
      ordinary,
      activated,
      slider,
      geometryPixels
    );
    if (home.ticketsTabBounds != null && !homeBlocked) {
      return new Result(
        "vivi_home",
        "",
        null,
        null,
        null,
        home.ticketsTabBounds,
        new ArrayList<>()
      );
    }

    // The current list can contain a large ticket card whose orange action button and nearby dark
    // marker resemble the detail slider after reduction. Prove the separated tab underline and
    // card-header bands first, then keep all list-shaped frames out of the detail fallback.
    if (strongListChrome && !resolveTicketList) {
      return new Result("ticket_list", "", null, null, back, new ArrayList<>());
    }
    if (strongListChrome) {
      List<TicketVisualDateGlyphRecognizer.DateRange> dates = registrationBands.isEmpty()
        ? Collections.emptyList()
        : (pixels.length == PROBE_WIDTH * PROBE_HEIGHT
          ? scaleDateRanges(
            TicketVisualDateGlyphRecognizer.recognize(pixels, PROBE_WIDTH, PROBE_HEIGHT),
            PROBE_HEIGHT,
            SAMPLE_HEIGHT
          )
          : TicketVisualDateGlyphRecognizer.recognize(pixels, SAMPLE_WIDTH, SAMPLE_HEIGHT));
      List<Card> cards = detectCards(geometryPixels, dates, registrationBands);
      boolean hasListRegistrationGeometry = cards.stream()
        .anyMatch(card -> card.registrationBounds != null);
      if (!cards.isEmpty() && hasListRegistrationGeometry) {
        return new Result("ticket_list", "", null, null, back, cards);
      }
      return unknown();
    }

    EmptyTicketsDetection emptyTickets = detectEmptyTicketsTabs(geometryPixels);
    Bounds timeTicketsTab = emptyTickets == null
      ? null
      : emptyTickets.timeTabTarget;
    // The right Time-tickets label can resemble the compact detail X after the high-resolution
    // header contrast pass. Suppress that conflict only when the candidate's centre stays inside
    // the unpadded label glyph and no native sample phase proves a real X. A genuine X at the same
    // location or elsewhere remains a blocker, as do popup, result, login, slider, and every shell
    // conflict.
    boolean closeIsAbsentOrTimeLabelAlias = back == null ||
      isTimeTicketsLabelCloseAlias(pixels, back, emptyTickets);
    if (emptyTickets != null && slider.isEmpty() && !hasTicketDetailBase &&
      closeIsAbsentOrTimeLabelAlias &&
      !looksLikeLogin(geometryPixels) &&
      !ordinary.equals(TicketControlCodeVisualClassifier.CONTROL_POPUP) &&
      !ordinary.equals(TicketControlCodeVisualClassifier.GENERATED)
    ) {
      return new Result(
        emptyTickets.timeTicketsSelected ? "tickets_time_empty" : "tickets_single_use_empty",
        "",
        null,
        null,
        null,
        emptyTickets.timeTicketsSelected ? emptyTickets.singleUseTabTarget : null,
        emptyTickets.timeTicketsSelected ? null : timeTicketsTab,
        new ArrayList<>()
      );
    }

    // A wide orange band and nearby dark patch are not detail authority on their own. The same
    // fresh frame must also contain the ticket-detail graphic or its unique top-right close;
    // otherwise an incomplete/redesigned list remains unknown and cannot publish authorization.
    // The close alternative preserves proof while ViVi rotates the dynamic code graphic.
    if (!slider.isEmpty() && hasStrongDetailChrome) {
      return new Result(
        "unactivated_detail",
        detailAnchor,
        refineRegistrationSliderBounds(geometryPixels, scaleBounds(slider), registrationBands),
        detectControlCodeButton(headerGeometryPixels),
        back,
        new ArrayList<>()
      );
    }
    if (!slider.isEmpty()) {
      return unknown();
    }
    // The compatibility activated-detail classifier deliberately tolerates the old dark status
    // strip. A proved close glyph inside that strip is instead an already-open generated result
    // and must block a new popup transaction.
    if (ordinary.equals(TicketControlCodeVisualClassifier.GENERATED) && !generatedClose.isEmpty()) {
      return new Result("blocked", "", null, null, null, new ArrayList<>());
    }
    if (activated.equals(TicketControlCodeVisualClassifier.RAW_TICKET)) {
      return new Result("activated_detail", detailAnchor, null, detectControlCodeButton(headerGeometryPixels), back, new ArrayList<>());
    }
    if (looksLikeLogin(geometryPixels)) {
      return new Result("login_required", "", null, null, null, new ArrayList<>());
    }
    if (ordinary.equals(TicketControlCodeVisualClassifier.CONTROL_POPUP) ||
      ordinary.equals(TicketControlCodeVisualClassifier.GENERATED)) {
      return new Result("blocked", "", null, null, null, new ArrayList<>());
    }
    if (detectViviProfile(geometryPixels).proved) {
      return new Result("vivi_profile", "", null, null, null, new ArrayList<>());
    }
    if (detectViviOtherBottomTab(geometryPixels, false).proved) {
      return new Result("vivi_other_tab", "", null, null, null, new ArrayList<>());
    }
    return unknown();
  }

  /** Content-free phone-local explanation for an unrecognized registration surface. */
  public static String registrationDiagnostic(int[] pixels) {
    int[] geometryPixels = geometryPixels(pixels);
    if (geometryPixels == null) {
      return "invalid_action_probe";
    }
    int[] compact = downsample(
      geometryPixels,
      SAMPLE_WIDTH,
      SAMPLE_HEIGHT,
      TicketControlCodeVisualClassifier.SAMPLE_WIDTH,
      TicketControlCodeVisualClassifier.SAMPLE_HEIGHT
    );
    return TicketControlCodeVisualClassifier.registrationSliderDiagnostic(compact);
  }

  /** Aggregate phone-local diagnostics with no ticket content or coordinates. */
  public static String visualDiagnostic(int[] pixels) {
    int[] geometryPixels = geometryPixels(pixels);
    if (geometryPixels == null) {
      return "invalid_action_probe";
    }
    int[] compact = downsample(
      geometryPixels,
      SAMPLE_WIDTH,
      SAMPLE_HEIGHT,
      TicketControlCodeVisualClassifier.SAMPLE_WIDTH,
      TicketControlCodeVisualClassifier.SAMPLE_HEIGHT
    );
    int registrationBands = yellowBands(geometryPixels).size();
    String dateDiagnostic = "date_probe_not_list";
    if (registrationBands > 0) {
      int dateWidth = pixels.length == PROBE_WIDTH * PROBE_HEIGHT ? PROBE_WIDTH : SAMPLE_WIDTH;
      int dateHeight = pixels.length == PROBE_WIDTH * PROBE_HEIGHT ? PROBE_HEIGHT : SAMPLE_HEIGHT;
      dateDiagnostic = TicketVisualDateGlyphRecognizer.safeDiagnostic(pixels, dateWidth, dateHeight);
    }
    return TicketControlCodeVisualClassifier.registrationSliderDiagnostic(compact) + "_" +
      dateDiagnostic + "_registration_bands_" + Math.min(registrationBands, 9);
  }

  /** Content-free diagnostic for prove_current; never invokes the date glyph recognizer. */
  public static String currentVisualDiagnostic(int[] pixels) {
    int[] geometryPixels = geometryPixels(pixels);
    if (geometryPixels == null) {
      return "invalid_action_probe";
    }
    int[] compact = downsample(
      geometryPixels,
      SAMPLE_WIDTH,
      SAMPLE_HEIGHT,
      TicketControlCodeVisualClassifier.SAMPLE_WIDTH,
      TicketControlCodeVisualClassifier.SAMPLE_HEIGHT
    );
    ViviHomeDetection home = detectViviHome(geometryPixels);
    String homeDiagnostic = home.ticketsTabBounds != null && blocksViviHomeProof(
      TicketControlCodeVisualClassifier.classify(compact),
      TicketControlCodeVisualClassifier.classifyForActivatedTicket(compact),
      TicketControlCodeVisualClassifier.registrationSliderBounds(compact),
      geometryPixels
    ) ? "reject_blocked_surface" : home.diagnostic;
    return TicketControlCodeVisualClassifier.registrationSliderDiagnostic(compact) +
      "_date_probe_disabled_registration_bands_" + Math.min(yellowBands(geometryPixels).size(), 9) +
      "_vivi_home_gate_" + homeDiagnostic;
  }

  private static int[] geometryPixels(int[] pixels) {
    if (pixels == null) return null;
    if (pixels.length == SAMPLE_WIDTH * SAMPLE_HEIGHT) return pixels;
    if (pixels.length == PROBE_WIDTH * PROBE_HEIGHT) {
      return downsample(pixels, PROBE_WIDTH, PROBE_HEIGHT, SAMPLE_WIDTH, SAMPLE_HEIGHT);
    }
    return null;
  }

  private static List<TicketVisualDateGlyphRecognizer.DateRange> scaleDateRanges(
    List<TicketVisualDateGlyphRecognizer.DateRange> ranges,
    int sourceHeight,
    int targetHeight
  ) {
    List<TicketVisualDateGlyphRecognizer.DateRange> scaled = new ArrayList<>();
    for (TicketVisualDateGlyphRecognizer.DateRange range : ranges) {
      scaled.add(new TicketVisualDateGlyphRecognizer.DateRange(
        range.from,
        range.until,
        Math.max(0, Math.min(targetHeight - 1, range.centerY * targetHeight / sourceHeight))
      ));
    }
    return scaled;
  }

  private static Result unknown() {
    return new Result("unknown", "", null, null, null, new ArrayList<>());
  }

  /**
   * Keeps detail identity phone-local and content-free. The salted signature is stable only for
   * this rooted capture-helper process, so a helper restart or a visually different open ticket
   * invalidates an idle registration proof instead of silently rebinding it.
   */
  private static String detailVisualAnchor(int[] geometryPixels) {
    String signature = TicketControlCodeVisualClassifier.ticketDetailStaticVisualSignature(
      geometryPixels,
      SAMPLE_WIDTH,
      SAMPLE_HEIGHT
    );
    String epoch = TicketControlCodeVisualClassifier.ticketCodeVisualSignatureEpoch();
    return signature.length() < 16 || epoch.length() < 12
      ? ""
      : "d_" + signature.substring(0, 16) + epoch.substring(0, 12);
  }

  private static Bounds detectControlCodeButton(int[] pixels) {
    Bounds legacyButton = detectLegacyControlCodeButton(pixels);
    return legacyButton != null ? legacyButton : detectViviLogoButton(pixels);
  }

  private static Bounds detectLegacyControlCodeButton(int[] pixels) {
    int start = -1;
    int left = SAMPLE_WIDTH;
    int right = -1;
    for (int y = 8; y < 60; y++) {
      int rowLeft = SAMPLE_WIDTH;
      int rowRight = -1;
      int colored = 0;
      for (int x = 4; x < SAMPLE_WIDTH / 2; x++) {
        if (isRegistrationColor(pixels[y * SAMPLE_WIDTH + x])) {
          colored += 1;
          rowLeft = Math.min(rowLeft, x);
          rowRight = Math.max(rowRight, x);
        }
      }
      if (colored >= 12) {
        if (start < 0) start = y;
        left = Math.min(left, rowLeft);
        right = Math.max(right, rowRight);
      } else if (start >= 0) {
        if (y - start >= 3 && right - left >= 12) return new Bounds(left, start, right + 1, y);
        start = -1;
        left = SAMPLE_WIDTH;
        right = -1;
      }
    }
    return null;
  }

  /**
   * Current ViVi uses its top-left wordmark as the control-code entry. Detect the neutral,
   * sparse multi-letter mark against either a dark or light header and return only its bounded
   * visual geometry. This is evaluated only after the surrounding frame has already been typed
   * as a ticket detail, so a similarly shaped mark on another page cannot authorize a tap.
   */
  private static Bounds detectViviLogoButton(int[] pixels) {
    final int searchLeft = 4;
    final int searchTop = 2;
    final int searchRight = SAMPLE_WIDTH / 2;
    // The current wordmark is entirely above the colored route strip. Keeping the search in
    // this narrow header band prevents route text/icons below it from merging into the mark.
    final int searchBottom = 22;
    int[] luminances = new int[(searchRight - searchLeft) * (searchBottom - searchTop)];
    int luminanceIndex = 0;
    for (int y = searchTop; y < searchBottom; y++) {
      for (int x = searchLeft; x < searchRight; x++) {
        luminances[luminanceIndex++] = luminance(pixels[y * SAMPLE_WIDTH + x]);
      }
    }
    Arrays.sort(luminances);
    int background = luminances[luminances.length / 2];
    boolean[] foreground = new boolean[SAMPLE_WIDTH * SAMPLE_HEIGHT];
    for (int y = searchTop; y < searchBottom; y++) {
      for (int x = searchLeft; x < searchRight; x++) {
        int pixel = pixels[y * SAMPLE_WIDTH + x];
        int red = (pixel >> 16) & 0xff;
        int green = (pixel >> 8) & 0xff;
        int blue = pixel & 0xff;
        int saturation = Math.max(red, Math.max(green, blue)) -
          Math.min(red, Math.min(green, blue));
        foreground[y * SAMPLE_WIDTH + x] = saturation <= 92 &&
          Math.abs(luminance(pixel) - background) >= 28;
      }
    }

    // A thin neutral edge from the colored route strip can appear in this bounded header band.
    // Build the wordmark from glyph-height connected components so that separator never stretches
    // the proved tap geometry. Multiple components are required by some antialiased/theme variants,
    // while the current production wordmark can rasterize as one connected component.
    boolean[] visited = new boolean[foreground.length];
    boolean[] glyphForeground = new boolean[foreground.length];
    int[] component = new int[(searchRight - searchLeft) * (searchBottom - searchTop)];
    int candidateComponents = 0;
    int foregroundCount = 0;
    int left = searchRight;
    int top = searchBottom;
    int right = -1;
    int bottom = -1;
    for (int y = searchTop; y < searchBottom; y++) {
      for (int x = searchLeft; x < searchRight; x++) {
        int start = y * SAMPLE_WIDTH + x;
        if (!foreground[start] || visited[start]) continue;
        int head = 0;
        int tail = 0;
        int componentLeft = x;
        int componentTop = y;
        int componentRight = x + 1;
        int componentBottom = y + 1;
        component[tail++] = start;
        visited[start] = true;
        while (head < tail) {
          int current = component[head++];
          int currentX = current % SAMPLE_WIDTH;
          int currentY = current / SAMPLE_WIDTH;
          componentLeft = Math.min(componentLeft, currentX);
          componentTop = Math.min(componentTop, currentY);
          componentRight = Math.max(componentRight, currentX + 1);
          componentBottom = Math.max(componentBottom, currentY + 1);
          for (int offsetY = -1; offsetY <= 1; offsetY++) {
            for (int offsetX = -1; offsetX <= 1; offsetX++) {
              if (offsetX == 0 && offsetY == 0) continue;
              int adjacentX = currentX + offsetX;
              int adjacentY = currentY + offsetY;
              if (adjacentX < searchLeft || adjacentX >= searchRight ||
                adjacentY < searchTop || adjacentY >= searchBottom) continue;
              int adjacent = adjacentY * SAMPLE_WIDTH + adjacentX;
              if (!foreground[adjacent] || visited[adjacent]) continue;
              visited[adjacent] = true;
              component[tail++] = adjacent;
            }
          }
        }
        int componentWidth = componentRight - componentLeft;
        int componentHeight = componentBottom - componentTop;
        if (componentWidth < 2 || componentHeight < 4 || tail < 6) continue;
        candidateComponents += 1;
        foregroundCount += tail;
        left = Math.min(left, componentLeft);
        top = Math.min(top, componentTop);
        right = Math.max(right, componentRight);
        bottom = Math.max(bottom, componentBottom);
        for (int index = 0; index < tail; index++) glyphForeground[component[index]] = true;
      }
    }
    if (candidateComponents == 0 || candidateComponents > 8) return null;

    int[] rowCounts = new int[searchBottom - searchTop];
    int[] columnCounts = new int[searchRight - searchLeft];
    for (int y = searchTop; y < searchBottom; y++) {
      for (int x = searchLeft; x < searchRight; x++) {
        if (!glyphForeground[y * SAMPLE_WIDTH + x]) continue;
        rowCounts[y - searchTop] += 1;
        columnCounts[x - searchLeft] += 1;
      }
    }
    if (right <= left || bottom <= top) return null;
    int width = right - left;
    int height = bottom - top;
    int area = width * height;
    if (width < 14 || width > 72 || height < 4 || height > 18 ||
      left > 30 || top > 14 || right > 90 || bottom > 22 ||
      width * 100 < height * 170 || width * 100 > height * 800 ||
      foregroundCount < 14 || foregroundCount * 100 < area * 7 ||
      foregroundCount * 100 > area * 75
    ) return null;

    int activeRows = 0;
    int activeColumns = 0;
    for (int count : rowCounts) if (count > 0) activeRows += 1;
    for (int count : columnCounts) if (count > 0) activeColumns += 1;
    if (activeRows * 3 < height * 2 || activeColumns * 2 < width) return null;

    int[] quartiles = new int[4];
    for (int x = left; x < right; x++) {
      int count = columnCounts[x - searchLeft];
      int quartile = Math.min(3, (x - left) * 4 / Math.max(1, width));
      quartiles[quartile] += count;
    }
    int minimumQuartileInk = Math.max(3, foregroundCount / 24);
    for (int count : quartiles) if (count < minimumQuartileInk) return null;

    return new Bounds(
      Math.max(searchLeft, left - 4),
      Math.max(searchTop, top - 4),
      Math.min(searchRight, right + 4),
      Math.min(searchBottom, bottom + 4)
    );
  }

  private static List<Card> detectCards(
    int[] pixels,
    List<TicketVisualDateGlyphRecognizer.DateRange> dates,
    List<Bounds> registrationBands
  ) {
    List<Card> cards = new ArrayList<>();
    for (TicketVisualDateGlyphRecognizer.DateRange date : dates) {
      int top = Math.max(38, date.centerY - 34);
      int bottom = Math.min(SAMPLE_HEIGHT - 24, date.centerY + 58);
      Bounds cardBounds = new Bounds(8, top, SAMPLE_WIDTH - 8, bottom);
      Bounds registration = registrationBands.stream()
        .filter(value -> value.top >= top && value.bottom <= bottom + 12)
        .min(Comparator.comparingInt(value -> Math.abs(value.top - (date.centerY + 20))))
        .orElse(null);
      Bounds activatedDetail = detectActivatedDetailStatusBounds(
        pixels,
        date.centerY,
        cardBounds,
        registration
      );
      cards.add(new Card(
        cardBounds,
        registration,
        activatedDetail,
        date.anchor,
        false
      ));
    }
    LocalDate latestEligibleDate = null;
    int latestEligibleIndex = -1;
    boolean latestAmbiguous = false;
    for (int index = 0; index < cards.size(); index++) {
      Card card = cards.get(index);
      TicketVisualDateGlyphRecognizer.DateRange date = dates.get(index);
      if (card.registrationBounds == null || date.until.isBefore(LocalDate.now())) continue;
      int comparison = latestEligibleDate == null ? 1 : date.from.compareTo(latestEligibleDate);
      if (comparison > 0) {
        latestEligibleDate = date.from;
        latestEligibleIndex = index;
        latestAmbiguous = false;
      } else if (comparison == 0) {
        latestAmbiguous = true;
      }
    }
    if (latestEligibleIndex >= 0 && !latestAmbiguous) {
      Card selected = cards.get(latestEligibleIndex);
      cards.set(latestEligibleIndex, new Card(
        selected.bounds,
        selected.registrationBounds,
        selected.activatedDetailBounds,
        selected.anchor,
        true
      ));
    }
    return cards;
  }

  /**
   * Detects the compact orange confirmation marker inside the registered-status row.
   *
   * <p>The marker is intentionally separate from both the generic card body and the wide
   * registration control below it. A unique compact component in the far-right quarter of the
   * card authorizes a tap only inside that proved status target. Theme background colors are not
   * authority, so the same geometry works for ViVi's light and dark ticket-list themes.</p>
   */
  private static Bounds detectActivatedDetailStatusBounds(
    int[] pixels,
    int dateCenterY,
    Bounds cardBounds,
    Bounds registrationBounds
  ) {
    int searchLeft = Math.max(cardBounds.left + (cardBounds.right - cardBounds.left) * 3 / 4, 120);
    int searchRight = Math.max(searchLeft, cardBounds.right - 2);
    int searchTop = Math.max(cardBounds.top + 2, dateCenterY + 1);
    int searchBottom = Math.min(
      cardBounds.bottom - 2,
      registrationBounds == null ? dateCenterY + 26 : registrationBounds.top
    );
    if (searchRight - searchLeft < 6 || searchBottom - searchTop < 4) return null;

    boolean[] visited = new boolean[SAMPLE_WIDTH * SAMPLE_HEIGHT];
    int[] queue = new int[(searchRight - searchLeft) * (searchBottom - searchTop)];
    Bounds candidate = null;
    for (int y = searchTop; y < searchBottom; y++) {
      for (int x = searchLeft; x < searchRight; x++) {
        int start = y * SAMPLE_WIDTH + x;
        if (visited[start] || !isRegistrationColor(pixels[start])) continue;
        int head = 0;
        int tail = 0;
        int left = x;
        int top = y;
        int right = x + 1;
        int bottom = y + 1;
        queue[tail++] = start;
        visited[start] = true;
        while (head < tail) {
          int current = queue[head++];
          int currentX = current % SAMPLE_WIDTH;
          int currentY = current / SAMPLE_WIDTH;
          left = Math.min(left, currentX);
          top = Math.min(top, currentY);
          right = Math.max(right, currentX + 1);
          bottom = Math.max(bottom, currentY + 1);
          for (int offsetY = -1; offsetY <= 1; offsetY++) {
            for (int offsetX = -1; offsetX <= 1; offsetX++) {
              if (offsetX == 0 && offsetY == 0) continue;
              int adjacentX = currentX + offsetX;
              int adjacentY = currentY + offsetY;
              if (adjacentX < searchLeft || adjacentX >= searchRight ||
                adjacentY < searchTop || adjacentY >= searchBottom) continue;
              int adjacent = adjacentY * SAMPLE_WIDTH + adjacentX;
              if (visited[adjacent] || !isRegistrationColor(pixels[adjacent])) continue;
              visited[adjacent] = true;
              queue[tail++] = adjacent;
            }
          }
        }
        int width = right - left;
        int height = bottom - top;
        int area = width * height;
        if (width < 5 || width > 28 || height < 4 || height > 22 || tail < 12 ||
          tail * 100 < area * 20 || (left + right) / 2 < cardBounds.right - 44
        ) continue;
        if (candidate != null) return null;
        candidate = new Bounds(
          Math.max(cardBounds.left + 2, left - 4),
          Math.max(searchTop, top - 3),
          Math.min(cardBounds.right - 2, right + 4),
          Math.min(searchBottom, bottom + 3)
        );
      }
    }
    return candidate;
  }

  /** Compact detection grants shape authority; this sample locates its actual unpadded edges. */
  private static Bounds refineRegistrationSliderBounds(int[] pixels, Bounds coarse, List<Bounds> bands) {
    if (coarse == null) return null;
    Bounds track = null;
    for (Bounds band : bands) {
      int center = (band.top + band.bottom) / 2;
      if (center < coarse.top || center >= coarse.bottom || band.right <= coarse.left || band.left >= coarse.right) continue;
      if (track != null) return null;
      track = band;
    }
    if (track == null) return null;
    int height = track.bottom - track.top;
    int left = Math.max(0, coarse.left - 4);
    int right = Math.min(SAMPLE_WIDTH, Math.max(track.left + height * 2, coarse.left + (coarse.right - coarse.left) / 3));
    int top = Math.max(0, coarse.top - 4);
    int bottom = Math.min(SAMPLE_HEIGHT, coarse.bottom + 4);
    boolean[] seen = new boolean[SAMPLE_WIDTH * SAMPLE_HEIGHT];
    int[] queue = new int[(right - left) * (bottom - top)];
    Bounds thumb = null;
    int bestArea = 0;
    for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) {
      int seed = y * SAMPLE_WIDTH + x;
      if (seen[seed] || luminance(pixels[seed]) > 90 || isRegistrationColor(pixels[seed])) continue;
      int head = 0, tail = 0;
      queue[tail++] = seed;
      seen[seed] = true;
      int minX = x, maxX = x, minY = y, maxY = y;
      boolean edge = false;
      while (head < tail) {
        int point = queue[head++], px = point % SAMPLE_WIDTH, py = point / SAMPLE_WIDTH;
        minX = Math.min(minX, px); maxX = Math.max(maxX, px);
        minY = Math.min(minY, py); maxY = Math.max(maxY, py);
        if (px == left || px == right - 1 || py == top || py == bottom - 1) edge = true;
        int[] neighbors = {point - 1, point + 1, point - SAMPLE_WIDTH, point + SAMPLE_WIDTH};
        for (int next : neighbors) {
          int nx = next % SAMPLE_WIDTH, ny = next / SAMPLE_WIDTH;
          if (nx < left || nx >= right || ny < top || ny >= bottom || seen[next]) continue;
          seen[next] = true;
          if (luminance(pixels[next]) <= 90 && !isRegistrationColor(pixels[next])) queue[tail++] = next;
        }
      }
      // Page chrome touches the search boundary; text is too short to be the round thumb.
      if (edge || maxY - minY + 1 < height * 2 / 3 || maxX - minX + 1 > height * 3 ||
          maxY < track.top || minY >= track.bottom || tail <= bestArea) continue;
      bestArea = tail;
      thumb = new Bounds(minX, minY, maxX + 1, maxY + 1);
    }
    if (thumb == null) return null;
    return new Bounds(Math.min(track.left, thumb.left), Math.min(track.top, thumb.top),
      track.right, Math.max(track.bottom, thumb.bottom));
  }

  private static List<Bounds> yellowBands(int[] pixels) {
    List<Bounds> values = new ArrayList<>();
    int start = -1;
    int left = SAMPLE_WIDTH;
    int right = -1;
    for (int y = 40; y < SAMPLE_HEIGHT - 28; y++) {
      int rowLeft = SAMPLE_WIDTH;
      int rowRight = -1;
      int yellow = 0;
      for (int x = 5; x < SAMPLE_WIDTH - 5; x++) {
        if (isRegistrationColor(pixels[y * SAMPLE_WIDTH + x])) {
          yellow += 1;
          rowLeft = Math.min(rowLeft, x);
          rowRight = Math.max(rowRight, x);
        }
      }
      if (yellow >= SAMPLE_WIDTH / 3) {
        if (start < 0) start = y;
        left = Math.min(left, rowLeft);
        right = Math.max(right, rowRight);
      } else if (start >= 0) {
        if (y - start >= 4) values.add(new Bounds(left, start, right + 1, y));
        start = -1;
        left = SAMPLE_WIDTH;
        right = -1;
      }
    }
    return values;
  }

  /**
   * Proves an empty Tickets shell and distinguishes the selected tab.
   *
   * <p>This is a bounded chrome/silhouette proof, not text recognition. It requires exactly one
   * selected tab underline, both tab-label silhouettes, the short wide empty-state silhouette, the
   * lower navigation shell, and the selected Tickets glyph. The ordinary path proves the full
   * lower separator. If source-to-probe sampling loses only that one-pixel divider, a narrow
   * fallback additionally requires the neutral Home, Profile, and Menu glyphs with no competing
   * selected glyph. Any card/action color below the tabs, a selected Home tab, an overlay, or
   * redesigned geometry fails closed. The returned target is centred on the proved unselected
   * opposite label: Time-tickets from the Single-use state, or Single-use from the distinct
   * {@code tickets_time_empty} state. The caller still gates each direction by typed state and
   * command target.</p>
   */
  private static EmptyTicketsDetection detectEmptyTicketsTabs(int[] pixels) {
    if (pixels == null || pixels.length != SAMPLE_WIDTH * SAMPLE_HEIGHT) return null;

    int[] backgroundLuminances = new int[SAMPLE_WIDTH * 24];
    int backgroundIndex = 0;
    for (int y = 218; y < 242; y++) {
      for (int x = 0; x < SAMPLE_WIDTH; x++) {
        backgroundLuminances[backgroundIndex++] = luminance(pixels[y * SAMPLE_WIDTH + x]);
      }
    }
    Arrays.sort(backgroundLuminances);
    int background = backgroundLuminances[backgroundLuminances.length / 2];

    int selectedSingleUseRows = 0;
    int selectedTimeRows = 0;
    for (int y = 33; y <= 40; y++) {
      int longestLeftRun = 0;
      int longestLeftRunStart = -1;
      int currentLeftRun = 0;
      int currentLeftRunStart = -1;
      for (int x = 5; x < 100; x++) {
        if (isRegistrationColor(pixels[y * SAMPLE_WIDTH + x])) {
          if (currentLeftRun == 0) currentLeftRunStart = x;
          currentLeftRun += 1;
          if (currentLeftRun > longestLeftRun) {
            longestLeftRun = currentLeftRun;
            longestLeftRunStart = currentLeftRunStart;
          }
        } else {
          currentLeftRun = 0;
          currentLeftRunStart = -1;
        }
      }
      int longestLeftRunEnd = longestLeftRunStart + longestLeftRun;
      if (longestLeftRun >= 60 && longestLeftRun <= 95 &&
        longestLeftRunStart >= 8 && longestLeftRunStart <= 18 &&
        longestLeftRunEnd >= 90 && longestLeftRunEnd <= 102
      ) selectedSingleUseRows += 1;
      int longestRightRun = 0;
      int longestRightRunStart = -1;
      int currentRightRun = 0;
      int currentRightRunStart = -1;
      for (int x = 90; x < SAMPLE_WIDTH - 5; x++) {
        if (isRegistrationColor(pixels[y * SAMPLE_WIDTH + x])) {
          if (currentRightRun == 0) currentRightRunStart = x;
          currentRightRun += 1;
          if (currentRightRun > longestRightRun) {
            longestRightRun = currentRightRun;
            longestRightRunStart = currentRightRunStart;
          }
        } else {
          currentRightRun = 0;
          currentRightRunStart = -1;
        }
      }
      int longestRightRunEnd = longestRightRunStart + longestRightRun;
      if (longestRightRun >= 60 && longestRightRun <= 95 &&
        longestRightRunStart >= 90 && longestRightRunStart <= 110 &&
        longestRightRunEnd >= 175 && longestRightRunEnd <= 187
      ) selectedTimeRows += 1;
    }
    boolean singleUseSelected = selectedSingleUseRows >= 1 && selectedSingleUseRows <= 4;
    boolean timeTicketsSelected = selectedTimeRows >= 1 && selectedTimeRows <= 4;
    if (singleUseSelected == timeTicketsSelected) {
      return null;
    }

    int leftLabelPixels = 0;
    int leftLabelLeft = 100;
    int leftLabelTop = 34;
    int leftLabelRight = -1;
    int leftLabelBottom = -1;
    int timeLabelPixels = 0;
    int timeLabelLeft = 184;
    int timeLabelTop = 34;
    int timeLabelRight = -1;
    int timeLabelBottom = -1;
    for (int y = 18; y < 34; y++) {
      for (int x = 10; x < 100; x++) {
        int pixel = pixels[y * SAMPLE_WIDTH + x];
        if (saturation(pixel) <= 64 && Math.abs(luminance(pixel) - background) >= 24) {
          leftLabelPixels += 1;
          leftLabelLeft = Math.min(leftLabelLeft, x);
          leftLabelTop = Math.min(leftLabelTop, y);
          leftLabelRight = Math.max(leftLabelRight, x + 1);
          leftLabelBottom = Math.max(leftLabelBottom, y + 1);
        }
      }
      for (int x = 100; x < 184; x++) {
        int pixel = pixels[y * SAMPLE_WIDTH + x];
        if (saturation(pixel) <= 64 && Math.abs(luminance(pixel) - background) >= 24) {
          timeLabelPixels += 1;
          timeLabelLeft = Math.min(timeLabelLeft, x);
          timeLabelTop = Math.min(timeLabelTop, y);
          timeLabelRight = Math.max(timeLabelRight, x + 1);
          timeLabelBottom = Math.max(timeLabelBottom, y + 1);
        }
      }
    }
    if (leftLabelRight <= leftLabelLeft || leftLabelBottom <= leftLabelTop ||
      timeLabelRight <= timeLabelLeft || timeLabelBottom <= timeLabelTop ||
      leftLabelPixels < 70 || leftLabelPixels > 420 ||
      leftLabelRight - leftLabelLeft < 50 || leftLabelRight - leftLabelLeft > 82 ||
      leftLabelBottom - leftLabelTop < 3 || leftLabelBottom - leftLabelTop > 9 ||
      timeLabelPixels < 40 || timeLabelPixels > 300 ||
      timeLabelRight - timeLabelLeft < 30 || timeLabelRight - timeLabelLeft > 72 ||
      timeLabelBottom - timeLabelTop < 3 || timeLabelBottom - timeLabelTop > 9
    ) return null;

    int emptyPixels = 0;
    int emptyLeft = 170;
    int emptyTop = 170;
    int emptyRight = -1;
    int emptyBottom = -1;
    boolean[] emptyRows = new boolean[45];
    boolean[] emptyColumns = new boolean[146];
    for (int y = 125; y < 170; y++) {
      for (int x = 24; x < 170; x++) {
        int pixel = pixels[y * SAMPLE_WIDTH + x];
        if (saturation(pixel) <= 64 && Math.abs(luminance(pixel) - background) >= 24) {
          emptyPixels += 1;
          emptyLeft = Math.min(emptyLeft, x);
          emptyTop = Math.min(emptyTop, y);
          emptyRight = Math.max(emptyRight, x + 1);
          emptyBottom = Math.max(emptyBottom, y + 1);
          emptyRows[y - 125] = true;
          emptyColumns[x - 24] = true;
        }
      }
    }
    if (emptyRight <= emptyLeft || emptyBottom <= emptyTop) return null;
    int emptyWidth = emptyRight - emptyLeft;
    int emptyHeight = emptyBottom - emptyTop;
    int emptyArea = emptyWidth * emptyHeight;
    int activeEmptyRows = 0;
    int activeEmptyColumns = 0;
    for (boolean active : emptyRows) if (active) activeEmptyRows += 1;
    for (boolean active : emptyColumns) if (active) activeEmptyColumns += 1;
    if (emptyPixels < 220 || emptyPixels > 900 ||
      emptyWidth < 100 || emptyWidth > 145 ||
      emptyHeight < 8 || emptyHeight > 20 ||
      activeEmptyRows < 8 || activeEmptyColumns < 80 ||
      emptyPixels * 100 < emptyArea * 12 ||
      emptyPixels * 100 > emptyArea * 65
    ) return null;

    int unexpectedActionPixels = 0;
    for (int y = 45; y < 255; y++) {
      for (int x = 5; x < SAMPLE_WIDTH - 5; x++) {
        if (isRegistrationColor(pixels[y * SAMPLE_WIDTH + x])) unexpectedActionPixels += 1;
      }
    }
    if (unexpectedActionPixels > 8) return null;

    int navigationSeparatorRows = 0;
    int separatorRow = -1;
    for (int y = 255; y <= 262; y++) {
      int contrastingNeutral = 0;
      for (int x = 2; x < SAMPLE_WIDTH - 2; x++) {
        int pixel = pixels[y * SAMPLE_WIDTH + x];
        if (saturation(pixel) <= 64 &&
          Math.abs(luminance(pixel) - background) >= 24
        ) contrastingNeutral += 1;
      }
      if (contrastingNeutral >= SAMPLE_WIDTH * 3 / 4) {
        navigationSeparatorRows += 1;
        separatorRow = y;
      }
    }
    if (navigationSeparatorRows > 4) {
      return null;
    }

    boolean navigationSeparatorProved = navigationSeparatorRows >= 1 && separatorRow >= 0;
    int navigationTop = navigationSeparatorProved ? Math.max(262, separatorRow + 2) : 262;
    int selectedTicketsPixels = 0;
    int selectedHomePixels = 0;
    int neutralHomePixels = 0;
    int otherSelectedPixels = 0;
    for (int y = navigationTop; y < 280; y++) {
      for (int x = 8; x < 40; x++) {
        int pixel = pixels[y * SAMPLE_WIDTH + x];
        if (isRegistrationColor(pixel)) selectedHomePixels += 1;
        if (saturation(pixel) <= 64 &&
          Math.abs(luminance(pixel) - background) >= 28
        ) neutralHomePixels += 1;
      }
      for (int x = 46; x < 84; x++) {
        if (isRegistrationColor(pixels[y * SAMPLE_WIDTH + x])) selectedTicketsPixels += 1;
      }
      for (int x = 108; x < 184; x++) {
        if (isRegistrationColor(pixels[y * SAMPLE_WIDTH + x])) otherSelectedPixels += 1;
      }
    }
    if (selectedTicketsPixels < 8 || selectedTicketsPixels > 120 ||
      selectedHomePixels > 4 || neutralHomePixels < 12 || otherSelectedPixels > 4
    ) {
      return null;
    }
    if (!navigationSeparatorProved &&
      !neutralProfileAndMenuRejection(pixels, background, navigationTop).isEmpty()
    ) {
      return null;
    }

    Bounds timeLabelGlyph = new Bounds(
      timeLabelLeft,
      timeLabelTop,
      timeLabelRight,
      timeLabelBottom
    );
    return new EmptyTicketsDetection(
      timeTicketsSelected ? new Bounds(
          Math.max(10, leftLabelLeft - 4),
          Math.max(18, leftLabelTop - 4),
          Math.min(100, leftLabelRight + 4),
          Math.min(34, leftLabelBottom + 4)
        ) : null,
      timeTicketsSelected ? null : new Bounds(
          Math.max(100, timeLabelLeft - 4),
          Math.max(18, timeLabelTop - 4),
          Math.min(184, timeLabelRight + 4),
          Math.min(34, timeLabelBottom + 4)
        ),
      timeLabelGlyph,
      timeTicketsSelected
    );
  }

  private static boolean isTimeTicketsLabelCloseAlias(
    int[] originalPixels,
    Bounds close,
    EmptyTicketsDetection emptyTickets
  ) {
    if (originalPixels == null || originalPixels.length != PROBE_WIDTH * PROBE_HEIGHT ||
      close == null || emptyTickets == null
    ) return false;
    Bounds timeLabel = emptyTickets.timeLabelGlyph;
    int centerX = (close.left + close.right - 1) / 2;
    int centerY = (close.top + close.bottom - 1) / 2;
    if (centerX < timeLabel.left || centerX >= timeLabel.right ||
      centerY < timeLabel.top || centerY >= timeLabel.bottom
    ) return false;
    // Header preservation chooses the strongest of each native 2x2 cell so subdued label strokes
    // survive action-probe reduction. Separate strokes from adjacent letters can combine into a
    // low-resolution X even though no X exists in the native frame. A real close glyph remains an
    // X in at least one of the four native sample phases, so only the absence of every phase proof
    // permits this narrowly located label alias.
    return !hasNativeProbeDetailClose(originalPixels);
  }

  private static boolean hasNativeProbeDetailClose(int[] probe) {
    if (probe == null || probe.length != PROBE_WIDTH * PROBE_HEIGHT) return false;
    int[] phase = new int[SAMPLE_WIDTH * SAMPLE_HEIGHT];
    for (int offsetY = 0; offsetY < 2; offsetY++) {
      for (int offsetX = 0; offsetX < 2; offsetX++) {
        for (int y = 0; y < SAMPLE_HEIGHT; y++) {
          int sourceRow = Math.min(PROBE_HEIGHT - 1, y * 2 + offsetY) * PROBE_WIDTH;
          int targetRow = y * SAMPLE_WIDTH;
          for (int x = 0; x < SAMPLE_WIDTH; x++) {
            phase[targetRow + x] = probe[
              sourceRow + Math.min(PROBE_WIDTH - 1, x * 2 + offsetX)
            ];
          }
        }
        if (detectDetailCloseBounds(phase) != null) return true;
      }
    }
    return false;
  }

  /**
   * Proves the current ViVi route-planning home and returns only the bottom Tickets-tab target.
   *
   * <p>The proof deliberately ignores all dynamic page content above the bottom navigation. It
   * requires the orange selected Home glyph plus separate neutral Tickets, Profile, and Menu
   * glyphs in their fixed bottom-navigation regions. A selected peer tab, missing or ambiguous
   * peer, navigation overlay, login, or blocker therefore cannot authorize this navigation tap.
   * The returned diagnostic is one bounded gate code with no pixels, text, coordinates, or ticket
   * data.</p>
   */
  private static ViviHomeDetection detectViviHome(int[] pixels) {
    if (pixels == null || pixels.length != SAMPLE_WIDTH * SAMPLE_HEIGHT) {
      return rejectViviHome("invalid_probe");
    }

    int[] bottomLuminances = new int[SAMPLE_WIDTH * 22];
    int bottomIndex = 0;
    for (int y = 262; y < 284; y++) {
      for (int x = 0; x < SAMPLE_WIDTH; x++) {
        bottomLuminances[bottomIndex++] = luminance(pixels[y * SAMPLE_WIDTH + x]);
      }
    }
    Arrays.sort(bottomLuminances);
    int navigationBackground = bottomLuminances[bottomLuminances.length / 2];

    int selectedHomePixels = 0;
    int selectedHomeLeft = 38;
    int selectedHomeTop = 283;
    int selectedHomeRight = -1;
    int selectedHomeBottom = -1;
    for (int y = 258; y < 283; y++) {
      for (int x = 8; x < 38; x++) {
        if (!isRegistrationColor(pixels[y * SAMPLE_WIDTH + x])) continue;
        selectedHomePixels += 1;
        selectedHomeLeft = Math.min(selectedHomeLeft, x);
        selectedHomeTop = Math.min(selectedHomeTop, y);
        selectedHomeRight = Math.max(selectedHomeRight, x + 1);
        selectedHomeBottom = Math.max(selectedHomeBottom, y + 1);
      }
    }
    // The current selected Home outline retains fourteen orange samples after the two bounded
    // production reductions. Six retain margin for antialiasing drift while still requiring all
    // three peer-navigation glyphs below to be independently present and neutral.
    if (selectedHomePixels < 6) {
      return rejectViviHome("selected_home_sparse");
    }
    int selectedHomeWidth = selectedHomeRight - selectedHomeLeft;
    int selectedHomeHeight = selectedHomeBottom - selectedHomeTop;
    int selectedHomeArea = selectedHomeWidth * selectedHomeHeight;
    if (selectedHomePixels > 96 ||
      selectedHomeWidth < 4 || selectedHomeWidth > 24 ||
      selectedHomeHeight < 3 || selectedHomeHeight > 20 ||
      selectedHomeArea < 20 ||
      selectedHomePixels * 100 > selectedHomeArea * 65
    ) {
      return rejectViviHome("selected_home_shape");
    }

    // Peer glyphs begin below the optional separator. Starting at 262 ignores that thin dynamic
    // edge without requiring it to exist or measuring any content above navigation.
    int ticketTop = 262;
    int selectedTicketPixels = 0;
    boolean[] neutralTicket = new boolean[SAMPLE_WIDTH * SAMPLE_HEIGHT];
    for (int y = ticketTop; y < 283; y++) {
      for (int x = 46; x < 82; x++) {
        int pixel = pixels[y * SAMPLE_WIDTH + x];
        if (isRegistrationColor(pixel)) selectedTicketPixels += 1;
        if (saturation(pixel) <= 64 &&
          Math.abs(luminance(pixel) - navigationBackground) >= 28
        ) {
          neutralTicket[y * SAMPLE_WIDTH + x] = true;
        }
      }
    }
    if (selectedTicketPixels >= 4) return rejectViviHome("selected_ticket_conflict");

    boolean[] visited = new boolean[neutralTicket.length];
    boolean[] ticketGlyph = new boolean[neutralTicket.length];
    int[] queue = new int[(82 - 46) * (283 - ticketTop)];
    int ticketGlyphComponents = 0;
    for (int y = ticketTop; y < 283; y++) {
      for (int x = 46; x < 82; x++) {
        int start = y * SAMPLE_WIDTH + x;
        if (!neutralTicket[start] || visited[start]) continue;
        int head = 0;
        int tail = 0;
        visited[start] = true;
        queue[tail++] = start;
        while (head < tail) {
          int current = queue[head++];
          int currentX = current % SAMPLE_WIDTH;
          int currentY = current / SAMPLE_WIDTH;
          int[] neighbors = {
            currentX > 46 ? current - 1 : -1,
            currentX + 1 < 82 ? current + 1 : -1,
            currentY > ticketTop ? current - SAMPLE_WIDTH : -1,
            currentY + 1 < 283 ? current + SAMPLE_WIDTH : -1
          };
          for (int neighbor : neighbors) {
            if (neighbor >= 0 && neutralTicket[neighbor] && !visited[neighbor]) {
              visited[neighbor] = true;
              queue[tail++] = neighbor;
            }
          }
        }
        // The current anti-aliased ticket outline can become several short components after the
        // bounded 384-to-192 action-probe reduction. Retain real strokes while discarding isolated
        // one-pixel noise, then prove the aggregate horizontal ticket silhouette below.
        if (tail >= 2) {
          ticketGlyphComponents += 1;
          for (int index = 0; index < tail; index++) ticketGlyph[queue[index]] = true;
        }
      }
    }
    if (ticketGlyphComponents < 1) return rejectViviHome("ticket_components_missing");
    if (ticketGlyphComponents > 6) return rejectViviHome("ticket_components_ambiguous");

    int glyphPixels = 0;
    int glyphLeft = 82;
    int glyphTop = 283;
    int glyphRight = -1;
    int glyphBottom = -1;
    int[] glyphRows = new int[283 - ticketTop];
    int[] glyphColumns = new int[82 - 46];
    for (int y = ticketTop; y < 283; y++) {
      for (int x = 46; x < 82; x++) {
        if (!ticketGlyph[y * SAMPLE_WIDTH + x]) continue;
        glyphPixels += 1;
        glyphLeft = Math.min(glyphLeft, x);
        glyphTop = Math.min(glyphTop, y);
        glyphRight = Math.max(glyphRight, x + 1);
        glyphBottom = Math.max(glyphBottom, y + 1);
        glyphRows[y - ticketTop] += 1;
        glyphColumns[x - 46] += 1;
      }
    }
    if (glyphRight <= glyphLeft || glyphBottom <= glyphTop) {
      return rejectViviHome("ticket_bounds_unproved");
    }
    int glyphWidth = glyphRight - glyphLeft;
    int glyphHeight = glyphBottom - glyphTop;
    int glyphArea = glyphWidth * glyphHeight;
    if (glyphPixels < 12) return rejectViviHome("ticket_pixels_sparse");
    if (glyphWidth < 14) return rejectViviHome("ticket_width_narrow");
    if (glyphWidth > 30) return rejectViviHome("ticket_width_wide");
    if (glyphHeight < 5) return rejectViviHome("ticket_height_short");
    if (glyphHeight > 14) return rejectViviHome("ticket_height_tall");
    if (glyphWidth * 100 < glyphHeight * 160) return rejectViviHome("ticket_aspect_tall");
    if (glyphWidth * 100 > glyphHeight * 500) return rejectViviHome("ticket_aspect_wide");
    if (glyphPixels * 100 < glyphArea * 8) return rejectViviHome("ticket_density_sparse");
    if (glyphPixels * 100 > glyphArea * 65) return rejectViviHome("ticket_density_dense");

    int activeRows = 0;
    int activeColumns = 0;
    int leftThirdInk = 0;
    int rightThirdInk = 0;
    int firstThirdEnd = glyphLeft + Math.max(1, glyphWidth / 3);
    int lastThirdStart = glyphRight - Math.max(1, glyphWidth / 3);
    for (int count : glyphRows) if (count > 0) activeRows += 1;
    for (int x = glyphLeft; x < glyphRight; x++) {
      int count = glyphColumns[x - 46];
      if (count > 0) activeColumns += 1;
      if (x < firstThirdEnd) leftThirdInk += count;
      if (x >= lastThirdStart) rightThirdInk += count;
    }
    if (activeRows < 5) return rejectViviHome("ticket_rows_sparse");
    if (activeColumns < 4) return rejectViviHome("ticket_columns_sparse");
    if (leftThirdInk < 3) return rejectViviHome("ticket_left_edge_sparse");
    if (rightThirdInk < 3) return rejectViviHome("ticket_right_edge_sparse");

    String peerRejection = neutralProfileAndMenuRejection(
      pixels,
      navigationBackground,
      ticketTop
    );
    if (!peerRejection.isEmpty()) return rejectViviHome(peerRejection);

    // Input uses the box centre. Returning the proved silhouette rather than the whole tab keeps
    // the mapped device tap centred on the live Tickets glyph (about x=405 on the current Pixel).
    return new ViviHomeDetection(
      new Bounds(glyphLeft, glyphTop, glyphRight, glyphBottom),
      "proved_bottom_navigation"
    );
  }

  private static ViviHomeDetection rejectViviHome(String diagnostic) {
    return new ViviHomeDetection(null, "reject_" + diagnostic);
  }

  /**
   * Proves that Profile is the one selected ViVi bottom tab.
   *
   * <p>Only the fixed lower navigation participates. The profile glyph must be orange while Home,
   * Tickets, and Menu remain separately present and neutral; the page body is deliberately ignored.
   * This is the logout workflow's authority to inspect account controls, not generic Ticket tap
   * authority.</p>
   */
  private static ViviProfileDetection detectViviProfile(int[] pixels) {
    if (pixels == null || pixels.length != SAMPLE_WIDTH * SAMPLE_HEIGHT) {
      return rejectViviProfile("invalid_probe");
    }
    int navigationTop = 262;
    int[] bottomLuminances = new int[SAMPLE_WIDTH * 22];
    int bottomIndex = 0;
    for (int y = navigationTop; y < 284; y++) {
      for (int x = 0; x < SAMPLE_WIDTH; x++) {
        bottomLuminances[bottomIndex++] = luminance(pixels[y * SAMPLE_WIDTH + x]);
      }
    }
    Arrays.sort(bottomLuminances);
    int navigationBackground = bottomLuminances[bottomLuminances.length / 2];

    NavigationGlyphStats selectedProfile = navigationGlyphStats(
      pixels,
      navigationBackground,
      navigationTop,
      106,
      140,
      true
    );
    if (!selectedProfile.present() || selectedProfile.pixels < 8 ||
      selectedProfile.pixels > 180 || selectedProfile.width() < 7 ||
      selectedProfile.width() > 24 || selectedProfile.height() < 7 ||
      selectedProfile.height() > 22 || selectedProfile.area() < 49 ||
      selectedProfile.pixels * 100 > selectedProfile.area() * 70
    ) return rejectViviProfile("selected_profile_shape");

    int selectedPeerPixels = selectedNavigationPixels(pixels, navigationTop, 8, 40) +
      selectedNavigationPixels(pixels, navigationTop, 46, 84) +
      selectedNavigationPixels(pixels, navigationTop, 152, 186);
    if (selectedPeerPixels >= 4) return rejectViviProfile("peer_selected_conflict");

    NavigationGlyphStats home = navigationGlyphStats(
      pixels,
      navigationBackground,
      navigationTop,
      8,
      40,
      false
    );
    if (!home.present() || home.pixels < 6 || home.pixels > 96 ||
      home.width() < 4 || home.width() > 24 || home.height() < 3 || home.height() > 20 ||
      home.area() < 20 || home.pixels * 100 > home.area() * 65
    ) return rejectViviProfile("peer_home_shape");

    NavigationGlyphStats tickets = navigationGlyphStats(
      pixels,
      navigationBackground,
      navigationTop,
      46,
      84,
      false
    );
    if (!tickets.present() || tickets.pixels < 12 || tickets.pixels > 180 ||
      tickets.width() < 14 || tickets.width() > 30 || tickets.height() < 5 ||
      tickets.height() > 14 || tickets.area() < 70 ||
      tickets.pixels * 100 < tickets.area() * 8 ||
      tickets.pixels * 100 > tickets.area() * 65
    ) return rejectViviProfile("peer_ticket_shape");

    int menuRows = 0;
    NavigationGlyphStats menu = new NavigationGlyphStats();
    for (int y = navigationTop; y < 283; y++) {
      int run = 0;
      int longestRun = 0;
      for (int x = 152; x < 186; x++) {
        int pixel = pixels[y * SAMPLE_WIDTH + x];
        if (isNeutralNavigationInk(pixel, navigationBackground)) {
          run += 1;
          longestRun = Math.max(longestRun, run);
          menu.add(x, y);
        } else {
          run = 0;
        }
      }
      if (longestRun >= 10) menuRows += 1;
    }
    if (!menu.present() || menuRows < 2 || menuRows > 8 || menu.pixels < 20 ||
      menu.width() < 10 || menu.width() > 30
    ) return rejectViviProfile("peer_menu_shape");
    return new ViviProfileDetection(true, "proved_bottom_navigation");
  }

  private static ViviProfileDetection rejectViviProfile(String diagnostic) {
    return new ViviProfileDetection(false, "reject_" + diagnostic);
  }

  /**
   * Proves either the Tickets or Menu lower tab as selected while the other three glyphs remain
   * neutral. The two routes deliberately share one state: re-authentication only needs proof that
   * it may select Profile, and no Ticket action is authorized from this generic state.
   */
  private static ViviOtherTabDetection detectViviOtherBottomTab(
    int[] pixels,
    boolean ticketsSelected
  ) {
    if (pixels == null || pixels.length != SAMPLE_WIDTH * SAMPLE_HEIGHT) {
      return new ViviOtherTabDetection(false);
    }
    int navigationTop = 262;
    int[] bottomLuminances = new int[SAMPLE_WIDTH * 22];
    int bottomIndex = 0;
    for (int y = navigationTop; y < 284; y++) {
      for (int x = 0; x < SAMPLE_WIDTH; x++) {
        bottomLuminances[bottomIndex++] = luminance(pixels[y * SAMPLE_WIDTH + x]);
      }
    }
    Arrays.sort(bottomLuminances);
    int navigationBackground = bottomLuminances[bottomLuminances.length / 2];

    NavigationGlyphStats home = navigationGlyphStats(
      pixels, navigationBackground, navigationTop, 8, 40, false
    );
    NavigationGlyphStats tickets = navigationGlyphStats(
      pixels, navigationBackground, navigationTop, 46, 84, ticketsSelected
    );
    NavigationGlyphStats profile = navigationGlyphStats(
      pixels, navigationBackground, navigationTop, 106, 140, false
    );
    NavigationGlyphStats menu = navigationGlyphStats(
      pixels, navigationBackground, navigationTop, 152, 186, !ticketsSelected
    );
    if (!validHomeNavigationGlyph(home) || !validTicketNavigationGlyph(tickets) ||
      !validProfileNavigationGlyph(profile) ||
      !validMenuNavigationGlyph(pixels, menu, navigationBackground, navigationTop, !ticketsSelected)
    ) return new ViviOtherTabDetection(false);

    int selectedPeers = selectedNavigationPixels(pixels, navigationTop, 8, 40) +
      selectedNavigationPixels(pixels, navigationTop, 106, 140) +
      (ticketsSelected
        ? selectedNavigationPixels(pixels, navigationTop, 152, 186)
        : selectedNavigationPixels(pixels, navigationTop, 46, 84));
    return new ViviOtherTabDetection(selectedPeers < 4);
  }

  private static boolean validHomeNavigationGlyph(NavigationGlyphStats value) {
    return value.present() && value.pixels >= 6 && value.pixels <= 96 &&
      value.width() >= 4 && value.width() <= 24 && value.height() >= 3 &&
      value.height() <= 20 && value.area() >= 20 &&
      value.pixels * 100 <= value.area() * 65;
  }

  private static boolean validTicketNavigationGlyph(NavigationGlyphStats value) {
    return value.present() && value.pixels >= 12 && value.pixels <= 180 &&
      value.width() >= 14 && value.width() <= 30 && value.height() >= 5 &&
      value.height() <= 14 && value.area() >= 70 &&
      value.pixels * 100 >= value.area() * 8 && value.pixels * 100 <= value.area() * 70;
  }

  private static boolean validProfileNavigationGlyph(NavigationGlyphStats value) {
    return value.present() && value.pixels >= 10 && value.pixels <= 180 &&
      value.width() >= 7 && value.width() <= 24 && value.height() >= 7 &&
      value.height() <= 22;
  }

  private static boolean validMenuNavigationGlyph(
    int[] pixels,
    NavigationGlyphStats value,
    int navigationBackground,
    int navigationTop,
    boolean selected
  ) {
    int rows = 0;
    for (int y = navigationTop; y < 283; y++) {
      int run = 0;
      int longestRun = 0;
      for (int x = 152; x < 186; x++) {
        int pixel = pixels[y * SAMPLE_WIDTH + x];
        boolean ink = selected
          ? isRegistrationColor(pixel)
          : isNeutralNavigationInk(pixel, navigationBackground);
        if (ink) {
          run += 1;
          longestRun = Math.max(longestRun, run);
        } else {
          run = 0;
        }
      }
      if (longestRun >= 10) rows += 1;
    }
    return value.present() && rows >= 2 && rows <= 8 && value.pixels >= 20 &&
      value.width() >= 10 && value.width() <= 30;
  }

  private static NavigationGlyphStats navigationGlyphStats(
    int[] pixels,
    int navigationBackground,
    int navigationTop,
    int left,
    int right,
    boolean selected
  ) {
    NavigationGlyphStats stats = new NavigationGlyphStats();
    for (int y = navigationTop; y < 283; y++) {
      for (int x = left; x < right; x++) {
        int pixel = pixels[y * SAMPLE_WIDTH + x];
        if (selected ? isRegistrationColor(pixel) :
          isNeutralNavigationInk(pixel, navigationBackground)
        ) stats.add(x, y);
      }
    }
    return stats;
  }

  private static int selectedNavigationPixels(
    int[] pixels,
    int navigationTop,
    int left,
    int right
  ) {
    int selected = 0;
    for (int y = navigationTop; y < 283; y++) {
      for (int x = left; x < right; x++) {
        if (isRegistrationColor(pixels[y * SAMPLE_WIDTH + x])) selected += 1;
      }
    }
    return selected;
  }

  private static boolean blocksViviHomeProof(
    String ordinary,
    String activated,
    String slider,
    int[] geometryPixels
  ) {
    return !slider.isEmpty() ||
      activated.equals(TicketControlCodeVisualClassifier.RAW_TICKET) ||
      ordinary.equals(TicketControlCodeVisualClassifier.CONTROL_POPUP) ||
      ordinary.equals(TicketControlCodeVisualClassifier.GENERATED) ||
      looksLikeLogin(geometryPixels);
  }

  private static String neutralProfileAndMenuRejection(
    int[] pixels,
    int navigationBackground,
    int navigationTop
  ) {
    int profilePixels = 0;
    int profileLeft = 140;
    int profileTop = 283;
    int profileRight = -1;
    int profileBottom = -1;
    int selectedOtherPixels = 0;
    for (int y = navigationTop; y < 283; y++) {
      for (int x = 106; x < 140; x++) {
        int pixel = pixels[y * SAMPLE_WIDTH + x];
        if (isRegistrationColor(pixel)) selectedOtherPixels += 1;
        if (isNeutralNavigationInk(pixel, navigationBackground)) {
          profilePixels += 1;
          profileLeft = Math.min(profileLeft, x);
          profileTop = Math.min(profileTop, y);
          profileRight = Math.max(profileRight, x + 1);
          profileBottom = Math.max(profileBottom, y + 1);
        }
      }
      for (int x = 152; x < 186; x++) {
        if (isRegistrationColor(pixels[y * SAMPLE_WIDTH + x])) selectedOtherPixels += 1;
      }
    }
    if (selectedOtherPixels >= 4 || profileRight <= profileLeft || profileBottom <= profileTop) {
      return selectedOtherPixels >= 4 ? "peer_selected_conflict" : "peer_profile_missing";
    }
    int profileWidth = profileRight - profileLeft;
    int profileHeight = profileBottom - profileTop;
    if (profilePixels < 10 || profilePixels > 180 ||
      profileWidth < 7 || profileWidth > 24 ||
      profileHeight < 7 || profileHeight > 22
    ) {
      return "peer_profile_shape";
    }

    int menuRows = 0;
    int menuPixels = 0;
    int menuLeft = 186;
    int menuRight = -1;
    for (int y = navigationTop; y < 283; y++) {
      int run = 0;
      int longestRun = 0;
      for (int x = 152; x < 186; x++) {
        if (isNeutralNavigationInk(pixels[y * SAMPLE_WIDTH + x], navigationBackground)) {
          run += 1;
          longestRun = Math.max(longestRun, run);
          menuPixels += 1;
          menuLeft = Math.min(menuLeft, x);
          menuRight = Math.max(menuRight, x + 1);
        } else {
          run = 0;
        }
      }
      if (longestRun >= 10) menuRows += 1;
    }
    if (menuRows < 2 || menuRows > 8) return "peer_menu_rows";
    if (menuPixels < 20) return "peer_menu_pixels";
    if (menuRight <= menuLeft) return "peer_menu_missing";
    if (menuRight - menuLeft < 10 || menuRight - menuLeft > 30) return "peer_menu_width";
    return "";
  }

  private static boolean isNeutralNavigationInk(int pixel, int navigationBackground) {
    return saturation(pixel) <= 64 &&
      Math.abs(luminance(pixel) - navigationBackground) >= 28;
  }

  /**
   * Proves current ViVi list chrome without OCR or card content.
   *
   * <p>The list has a short selected-tab underline followed, after a neutral gap, by a much wider
   * red/orange card-header band. Ticket detail may have one continuous colored route strip in the same
   * upper area, so neither color nor one band is sufficient. These ranges are sanitized action-
   * probe coordinates and deliberately exclude the card body and registration button.</p>
   */
  private static boolean hasStrongTicketListChrome(int[] pixels) {
    if (pixels == null || pixels.length != SAMPLE_WIDTH * SAMPLE_HEIGHT) return false;
    int underlineRow = -1;
    for (int y = 32; y <= 40; y++) {
      int run = longestRegistrationRun(pixels, y);
      if (run >= 10 && run <= 112) {
        underlineRow = y;
        break;
      }
    }
    if (underlineRow < 0) return false;

    int gapRows = 0;
    for (int y = Math.max(underlineRow + 1, 39); y <= 44; y++) {
      if (longestRegistrationRun(pixels, y) < 8) gapRows += 1;
    }
    if (gapRows < 2) return false;

    int wideHeaderRows = 0;
    int widestHeaderRun = 0;
    for (int y = 44; y <= 64; y++) {
      int run = longestListHeaderRun(pixels, y);
      widestHeaderRun = Math.max(widestHeaderRun, run);
      if (run >= 80) wideHeaderRows += 1;
    }
    return wideHeaderRows >= 6 && widestHeaderRun >= 120;
  }

  private static int longestRegistrationRun(int[] pixels, int y) {
    int longest = 0;
    int current = 0;
    for (int x = 5; x < SAMPLE_WIDTH - 5; x++) {
      if (isRegistrationColor(pixels[y * SAMPLE_WIDTH + x])) {
        current += 1;
        longest = Math.max(longest, current);
      } else {
        current = 0;
      }
    }
    return longest;
  }

  private static int longestListHeaderRun(int[] pixels, int y) {
    int longest = 0;
    int current = 0;
    for (int x = 5; x < SAMPLE_WIDTH - 5; x++) {
      if (isTicketListHeaderColor(pixels[y * SAMPLE_WIDTH + x])) {
        current += 1;
        longest = Math.max(longest, current);
      } else {
        current = 0;
      }
    }
    return longest;
  }

  private static Bounds detectDetailCloseBounds(int[] pixels) {
    final int searchLeft = SAMPLE_WIDTH * 3 / 4;
    final int searchTop = 2;
    final int searchRight = SAMPLE_WIDTH - 2;
    final int searchBottom = 34;
    int[] headerLuminance = new int[(searchRight - searchLeft) * (searchBottom - searchTop)];
    int headerIndex = 0;
    for (int y = searchTop; y < searchBottom; y++) {
      for (int x = searchLeft; x < searchRight; x++) {
        headerLuminance[headerIndex++] = luminance(pixels[y * SAMPLE_WIDTH + x]);
      }
    }
    Arrays.sort(headerLuminance);
    int backgroundLuminance = headerLuminance[headerLuminance.length / 2];

    List<Bounds> closeGlyphs = new ArrayList<>();
    findDetailCloseComponents(
      pixels,
      backgroundLuminance,
      true,
      searchLeft,
      searchTop,
      searchRight,
      searchBottom,
      closeGlyphs
    );
    findDetailCloseComponents(
      pixels,
      backgroundLuminance,
      false,
      searchLeft,
      searchTop,
      searchRight,
      searchBottom,
      closeGlyphs
    );
    if (closeGlyphs.size() > 1) return null;
    int centerX;
    int centerY;
    if (closeGlyphs.size() == 1) {
      Bounds glyph = closeGlyphs.get(0);
      centerX = (glyph.left + glyph.right - 1) / 2;
      centerY = (glyph.top + glyph.bottom - 1) / 2;
    } else {
      Bounds template = detectDetailCloseTemplate(
        pixels,
        backgroundLuminance,
        searchLeft,
        searchTop,
        searchRight,
        searchBottom
      );
      if (template == null) return null;
      centerX = (template.left + template.right - 1) / 2;
      centerY = (template.top + template.bottom - 1) / 2;
    }
    // The action path taps the center of this box. Keep that center on the visually proved X;
    // the previous fixed box was centred 8 sample pixels to its left and could miss the button.
    int horizontalRadius = Math.min(14, Math.min(centerX, SAMPLE_WIDTH - centerX));
    int verticalRadius = Math.min(14, Math.min(centerY, 36 - centerY));
    if (horizontalRadius < 8 || verticalRadius < 8) return null;
    return new Bounds(
      centerX - horizontalRadius,
      centerY - verticalRadius,
      centerX + horizontalRadius,
      centerY + verticalRadius
    );
  }

  private static Bounds detectDetailCloseTemplate(
    int[] pixels,
    int backgroundLuminance,
    int searchLeft,
    int searchTop,
    int searchRight,
    int searchBottom
  ) {
    int bestScore = 0;
    int bestX = -1;
    int bestY = -1;
    int competingScore = 0;
    for (int centerY = searchTop + 5; centerY < searchBottom - 5; centerY++) {
      for (int centerX = searchLeft + 5; centerX < searchRight - 5; centerX++) {
        for (int polarity : new int[] {1, -1}) {
          // The current ViVi header renders a compact 12-14 source-pixel X. After the
          // bounded two-stage action probe that is only a 5-7 pixel glyph, so include
          // the two smaller radii while retaining four-arm, centre, clear-axis and
          // unique-candidate proof.
          for (int radius = 2; radius <= 9; radius++) {
            int score = detailCloseTemplateScore(
              pixels,
              backgroundLuminance,
              centerX,
              centerY,
              radius,
              polarity
            );
            if (score <= 0) continue;
            if (score > bestScore) {
              if (bestX >= 0 && (Math.abs(centerX - bestX) > 5 || Math.abs(centerY - bestY) > 5)) {
                competingScore = Math.max(competingScore, bestScore);
              }
              bestScore = score;
              bestX = centerX;
              bestY = centerY;
            } else if (bestX >= 0 &&
              (Math.abs(centerX - bestX) > 5 || Math.abs(centerY - bestY) > 5)) {
              competingScore = Math.max(competingScore, score);
            }
          }
        }
      }
    }
    if (bestScore < 120 || competingScore * 100 >= bestScore * 88) return null;
    return new Bounds(bestX - 1, bestY - 1, bestX + 2, bestY + 2);
  }

  private static int detailCloseTemplateScore(
    int[] pixels,
    int backgroundLuminance,
    int centerX,
    int centerY,
    int radius,
    int polarity
  ) {
    // The template search intentionally considers centres close to the header edge, while the
    // larger candidate radii can extend outside the 192x288 sample. Reject that candidate before
    // any direct axis sample; clamping it would duplicate edge pixels and could manufacture an X.
    if (pixels == null || pixels.length != SAMPLE_WIDTH * SAMPLE_HEIGHT || radius < 1 ||
      centerX - radius < 0 || centerX + radius >= SAMPLE_WIDTH ||
      centerY - radius < 0 || centerY + radius >= SAMPLE_HEIGHT) {
      return 0;
    }
    int[] arms = new int[4];
    int contrastScore = 0;
    for (int step = 1; step <= radius; step++) {
      int[][] points = {
        {centerX - step, centerY - step},
        {centerX + step, centerY - step},
        {centerX - step, centerY + step},
        {centerX + step, centerY + step},
      };
      for (int arm = 0; arm < points.length; arm++) {
        int contrast = localDirectionalContrast(
          pixels,
          points[arm][0],
          points[arm][1],
          backgroundLuminance,
          polarity
        );
        if (contrast >= 12) {
          arms[arm] += 1;
          contrastScore += Math.min(contrast, 48);
        }
      }
    }
    int requiredArmSamples = Math.max(2, radius * 2 / 3);
    for (int arm : arms) if (arm < requiredArmSamples) return 0;
    if (localDirectionalContrast(
      pixels, centerX, centerY, backgroundLuminance, polarity
    ) < 10) return 0;

    int clearAxisSamples = 0;
    for (int distance : new int[] {Math.max(3, radius / 2), radius}) {
      int[][] axisPoints = {
        {centerX - distance, centerY},
        {centerX + distance, centerY},
        {centerX, centerY - distance},
        {centerX, centerY + distance},
      };
      for (int[] point : axisPoints) {
        int contrast = polarity * (
          luminance(pixels[point[1] * SAMPLE_WIDTH + point[0]]) - backgroundLuminance
        );
        if (contrast < 12) clearAxisSamples += 1;
      }
    }
    if (clearAxisSamples < 6) return 0;
    int balancePenalty = (Arrays.stream(arms).max().orElse(0) -
      Arrays.stream(arms).min().orElse(0)) * 12;
    return contrastScore - balancePenalty;
  }

  private static int localDirectionalContrast(
    int[] pixels,
    int x,
    int y,
    int backgroundLuminance,
    int polarity
  ) {
    int best = Integer.MIN_VALUE;
    for (int offsetY = -1; offsetY <= 1; offsetY++) {
      for (int offsetX = -1; offsetX <= 1; offsetX++) {
        int sampleX = Math.max(0, Math.min(SAMPLE_WIDTH - 1, x + offsetX));
        int sampleY = Math.max(0, Math.min(SAMPLE_HEIGHT - 1, y + offsetY));
        int contrast = polarity * (
          luminance(pixels[sampleY * SAMPLE_WIDTH + sampleX]) - backgroundLuminance
        );
        best = Math.max(best, contrast);
      }
    }
    return best;
  }

  private static void findDetailCloseComponents(
    int[] pixels,
    int backgroundLuminance,
    boolean brightForeground,
    int searchLeft,
    int searchTop,
    int searchRight,
    int searchBottom,
    List<Bounds> closeGlyphs
  ) {
    boolean[] foreground = new boolean[SAMPLE_WIDTH * SAMPLE_HEIGHT];
    boolean[] visited = new boolean[foreground.length];
    for (int y = searchTop; y < searchBottom; y++) {
      for (int x = searchLeft; x < searchRight; x++) {
        int index = y * SAMPLE_WIDTH + x;
        int pixel = pixels[index];
        int red = (pixel >> 16) & 0xff;
        int green = (pixel >> 8) & 0xff;
        int blue = pixel & 0xff;
        int maximum = Math.max(red, Math.max(green, blue));
        int minimum = Math.min(red, Math.min(green, blue));
        int delta = luminance(pixel) - backgroundLuminance;
        // ViVi's current anti-aliased close glyph is intentionally subdued in both themes.
        // Geometry remains the authority: the lower contrast threshold is still gated by a
        // nearly neutral color, a sparse square component, both diagonals, all four arms, a
        // covered centre, and exactly one candidate in the header-only search region.
        foreground[index] = maximum - minimum <= 112 &&
          (brightForeground ? delta >= 24 : delta <= -24);
      }
    }

    int[] component = new int[(searchRight - searchLeft) * (searchBottom - searchTop)];
    for (int y = searchTop; y < searchBottom; y++) {
      for (int x = searchLeft; x < searchRight; x++) {
        int start = y * SAMPLE_WIDTH + x;
        if (!foreground[start] || visited[start]) continue;
        int head = 0;
        int tail = 0;
        int left = x;
        int top = y;
        int right = x + 1;
        int bottom = y + 1;
        component[tail++] = start;
        visited[start] = true;
        while (head < tail) {
          int current = component[head++];
          int currentX = current % SAMPLE_WIDTH;
          int currentY = current / SAMPLE_WIDTH;
          left = Math.min(left, currentX);
          top = Math.min(top, currentY);
          right = Math.max(right, currentX + 1);
          bottom = Math.max(bottom, currentY + 1);
          for (int offsetY = -1; offsetY <= 1; offsetY++) {
            for (int offsetX = -1; offsetX <= 1; offsetX++) {
              if (offsetX == 0 && offsetY == 0) continue;
              int adjacentX = currentX + offsetX;
              int adjacentY = currentY + offsetY;
              if (adjacentX < searchLeft || adjacentX >= searchRight ||
                adjacentY < searchTop || adjacentY >= searchBottom) continue;
              int adjacent = adjacentY * SAMPLE_WIDTH + adjacentX;
              if (!foreground[adjacent] || visited[adjacent]) continue;
              visited[adjacent] = true;
              component[tail++] = adjacent;
            }
          }
        }
        Bounds bounds = new Bounds(left, top, right, bottom);
        if (looksLikeDetailCloseX(component, tail, bounds)) closeGlyphs.add(bounds);
      }
    }
  }

  private static boolean looksLikeDetailCloseX(int[] component, int count, Bounds bounds) {
    int width = bounds.right - bounds.left;
    int height = bounds.bottom - bounds.top;
    if (width < 5 || height < 5 || width > 19 || height > 19 ||
      Math.abs(width - height) > 4 || count < 7 || count * 100 > width * height * 58) {
      return false;
    }
    int centerX = (bounds.left + bounds.right - 1) / 2;
    int centerY = (bounds.top + bounds.bottom - 1) / 2;
    if (centerX < 158 || centerX > SAMPLE_WIDTH - 5 || centerY < 7 || centerY > 26) {
      return false;
    }

    int mainDiagonal = 0;
    int antiDiagonal = 0;
    boolean mainTopLeft = false;
    boolean mainBottomRight = false;
    boolean antiTopRight = false;
    boolean antiBottomLeft = false;
    boolean centerCovered = false;
    int diagonalTolerance = Math.max(width, height) * 2;
    int diagonalScale = (width - 1) * (height - 1);
    for (int index = 0; index < count; index++) {
      int x = component[index] % SAMPLE_WIDTH;
      int y = component[index] / SAMPLE_WIDTH;
      int relativeX = x - bounds.left;
      int relativeY = y - bounds.top;
      int mainDistance = Math.abs(relativeX * (height - 1) - relativeY * (width - 1));
      int antiDistance = Math.abs(
        relativeX * (height - 1) + relativeY * (width - 1) - diagonalScale
      );
      if (mainDistance <= diagonalTolerance) {
        mainDiagonal += 1;
        if (relativeX * 3 <= width && relativeY * 3 <= height) mainTopLeft = true;
        if (relativeX * 3 >= width * 2 && relativeY * 3 >= height * 2) mainBottomRight = true;
      }
      if (antiDistance <= diagonalTolerance) {
        antiDiagonal += 1;
        if (relativeX * 3 >= width * 2 && relativeY * 3 <= height) antiTopRight = true;
        if (relativeX * 3 <= width && relativeY * 3 >= height * 2) antiBottomLeft = true;
      }
      if (Math.abs(x - centerX) <= 1 && Math.abs(y - centerY) <= 1) centerCovered = true;
    }
    int minimumDiagonalEvidence = Math.max(4, Math.min(width, height) * 2 / 3);
    return mainDiagonal >= minimumDiagonalEvidence && antiDiagonal >= minimumDiagonalEvidence &&
      mainTopLeft && mainBottomRight && antiTopRight && antiBottomLeft && centerCovered;
  }

  private static boolean looksLikeLogin(int[] pixels) {
    int editableWhite = 0;
    int blueButton = 0;
    for (int y = SAMPLE_HEIGHT / 3; y < SAMPLE_HEIGHT * 4 / 5; y++) {
      for (int x = SAMPLE_WIDTH / 8; x < SAMPLE_WIDTH * 7 / 8; x++) {
        int pixel = pixels[y * SAMPLE_WIDTH + x];
        int red = (pixel >> 16) & 0xff;
        int green = (pixel >> 8) & 0xff;
        int blue = pixel & 0xff;
        if (red > 220 && green > 220 && blue > 220) editableWhite += 1;
        if (blue > 125 && blue - red > 30 && blue - green > 15) blueButton += 1;
      }
    }
    if (editableWhite > 2_000 && blueButton > 300) return true;
    return looksLikeCurrentDarkLogin(pixels);
  }

  /**
   * Proves ViVi 1.0.99's current dark login layout without reading rendered text.
   *
   * <p>The screen combines three independently bounded shapes which do not occur together on a
   * ticket: the compact orange ViVi mark, a large neutral two-field panel with almost no orange,
   * and the edge-to-edge orange guest bar above the system navigation area. Requiring all three
   * keeps a ticket card, registration slider, or route-home navigation from becoming login proof.</p>
   */
  private static boolean looksLikeCurrentDarkLogin(int[] pixels) {
    int logoOrange = 0;
    int logoOrangeRows = 0;
    for (int y = 55; y < 102; y++) {
      int rowOrange = 0;
      for (int x = 38; x < 158; x++) {
        if (isRegistrationColor(pixels[y * SAMPLE_WIDTH + x])) {
          logoOrange += 1;
          rowOrange += 1;
        }
      }
      if (rowOrange >= 10) logoOrangeRows += 1;
    }

    int neutralDarkForm = 0;
    int formOrange = 0;
    for (int y = 90; y < 175; y++) {
      for (int x = 10; x < 182; x++) {
        int pixel = pixels[y * SAMPLE_WIDTH + x];
        int lightness = luminance(pixel);
        if (saturation(pixel) <= 48 && lightness >= 20 && lightness <= 100) {
          neutralDarkForm += 1;
        }
        if (isRegistrationColor(pixel)) formOrange += 1;
      }
    }

    int bottomOrange = 0;
    int bottomWideRows = 0;
    for (int y = 240; y < 283; y++) {
      int rowOrange = 0;
      for (int x = 0; x < SAMPLE_WIDTH; x++) {
        if (isRegistrationColor(pixels[y * SAMPLE_WIDTH + x])) {
          bottomOrange += 1;
          rowOrange += 1;
        }
      }
      if (rowOrange >= 180) bottomWideRows += 1;
    }

    return logoOrange >= 400 && logoOrange <= 2_000 && logoOrangeRows >= 10 &&
      neutralDarkForm >= 11_000 && formOrange <= 200 &&
      bottomOrange >= 3_200 && bottomWideRows >= 16;
  }

  private static Bounds scaleBounds(String wire) {
    String[] values = wire.split(",");
    if (values.length != 4) return null;
    try {
      int left = Integer.parseInt(values[0]) * SAMPLE_WIDTH /
        TicketControlCodeVisualClassifier.SAMPLE_WIDTH;
      int top = Integer.parseInt(values[1]) * SAMPLE_HEIGHT /
        TicketControlCodeVisualClassifier.SAMPLE_HEIGHT;
      int right = Integer.parseInt(values[2]) * SAMPLE_WIDTH /
        TicketControlCodeVisualClassifier.SAMPLE_WIDTH;
      int bottom = Integer.parseInt(values[3]) * SAMPLE_HEIGHT /
        TicketControlCodeVisualClassifier.SAMPLE_HEIGHT;
      return new Bounds(left, top, right, bottom);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static int[] downsample(int[] source, int sourceWidth, int sourceHeight, int width, int height) {
    int[] output = new int[width * height];
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        int sourceX = Math.min(sourceWidth - 1, x * sourceWidth / width);
        int sourceY = Math.min(sourceHeight - 1, y * sourceHeight / height);
        output[y * width + x] = source[sourceY * sourceWidth + sourceX];
      }
    }
    return output;
  }

  /** Preserve thin neutral header controls from the native probe in both bounded corners. */
  private static int[] preserveProbeHeaderContrast(int[] probe, int[] geometryPixels) {
    int[] output = geometryPixels.clone();
    preserveProbeHeaderRegion(probe, output, 4, 2, SAMPLE_WIDTH / 2, 34);
    preserveProbeHeaderRegion(
      probe,
      output,
      SAMPLE_WIDTH * 3 / 4,
      2,
      SAMPLE_WIDTH - 2,
      34
    );
    return output;
  }

  private static void preserveProbeHeaderRegion(
    int[] probe,
    int[] output,
    int left,
    int top,
    int right,
    int bottom
  ) {
    int[] luminances = new int[(right - left) * 2 * (bottom - top) * 2];
    int index = 0;
    for (int y = top * 2; y < bottom * 2; y++) {
      for (int x = left * 2; x < right * 2; x++) {
        luminances[index++] = luminance(probe[y * PROBE_WIDTH + x]);
      }
    }
    Arrays.sort(luminances);
    int background = luminances[luminances.length / 2];
    for (int y = top; y < bottom; y++) {
      for (int x = left; x < right; x++) {
        int strongest = output[y * SAMPLE_WIDTH + x];
        int strongestContrast = -1;
        for (int offsetY = 0; offsetY < 2; offsetY++) {
          for (int offsetX = 0; offsetX < 2; offsetX++) {
            int pixel = probe[(y * 2 + offsetY) * PROBE_WIDTH + x * 2 + offsetX];
            int red = (pixel >> 16) & 0xff;
            int green = (pixel >> 8) & 0xff;
            int blue = pixel & 0xff;
            int saturation = Math.max(red, Math.max(green, blue)) -
              Math.min(red, Math.min(green, blue));
            if (saturation > 112) continue;
            int contrast = Math.abs(luminance(pixel) - background);
            if (contrast > strongestContrast) {
              strongestContrast = contrast;
              strongest = pixel;
            }
          }
        }
        output[y * SAMPLE_WIDTH + x] = strongest;
      }
    }
  }

  private static boolean isRegistrationColor(int pixel) {
    int red = (pixel >> 16) & 0xff;
    int green = (pixel >> 8) & 0xff;
    int blue = pixel & 0xff;
    return red >= 175 && green >= 95 && green <= 238 && blue <= 110 && red - green >= 22;
  }

  private static int saturation(int pixel) {
    int red = (pixel >> 16) & 0xff;
    int green = (pixel >> 8) & 0xff;
    int blue = pixel & 0xff;
    return Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue));
  }

  private static boolean isTicketListHeaderColor(int pixel) {
    int red = (pixel >> 16) & 0xff;
    int green = (pixel >> 8) & 0xff;
    int blue = pixel & 0xff;
    return isRegistrationColor(pixel) ||
      (red >= 165 && green >= 40 && green <= 150 && blue <= 120 &&
        red - green >= 50 && red - blue >= 50);
  }

  private static int luminance(int pixel) {
    int red = (pixel >> 16) & 0xff;
    int green = (pixel >> 8) & 0xff;
    int blue = pixel & 0xff;
    return (red * 54 + green * 183 + blue * 19) >> 8;
  }

  private static String visualHash(int[] pixels, Bounds bounds) {
    long hash = 0xcbf29ce484222325L;
    for (int y = bounds.top; y < bounds.bottom; y += 4) {
      for (int x = bounds.left; x < bounds.right; x += 4) {
        hash ^= (luminance(pixels[y * SAMPLE_WIDTH + x]) / 32);
        hash *= 0x100000001b3L;
      }
    }
    return Long.toUnsignedString(hash, 36);
  }
}
