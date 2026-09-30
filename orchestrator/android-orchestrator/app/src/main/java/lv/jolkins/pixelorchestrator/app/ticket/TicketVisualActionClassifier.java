package lv.jolkins.pixelorchestrator.app.ticket;

import java.time.LocalDate;
import java.util.List;

/** Fixed-size, phone-local visual state and action-anchor classifier for Ticket action v3. */
public final class TicketVisualActionClassifier {
  public static final int PROBE_WIDTH = 384;
  public static final int PROBE_HEIGHT = 576;
  public static final int DETAIL_VALIDITY_WIDTH = PROBE_WIDTH * 2 * 3 / 4;
  public static final int DETAIL_VALIDITY_HEIGHT = PROBE_HEIGHT * 2 * 7 / 72;
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
    public String detailCardAnchor = "";
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
      if (!detailCardAnchor.isEmpty()) value.append(" detail_card=").append(detailCardAnchor);
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

  private TicketVisualActionClassifier() {}

  public static Result classify(int[] pixels) {
    return NativeTicketMedia.classifyAction(pixels, true, LocalDate.now().toEpochDay());
  }

  /** The native detail-only path deliberately skips list/date recognition. */
  public static Result classifyCurrent(int[] pixels) {
    return NativeTicketMedia.classifyAction(pixels, false, LocalDate.now().toEpochDay());
  }

  public static String selectedBottomNavigationTab(int[] pixels) {
    return NativeTicketMedia.selectedNavigation(pixels).intern();
  }

  /** Android uses this crop on the same captured picture; no capture owner is added. */
  static Bounds detailValidityBounds(int width, int height) {
    return new Bounds(0, height * 35 / 72, width * 3 / 4, height * 42 / 72);
  }

  static String detailCardAnchor(int[] validityBand) {
    List<TicketVisualDateGlyphRecognizer.DateRange> ranges =
      TicketVisualDateGlyphRecognizer.recognize(validityBand, DETAIL_VALIDITY_WIDTH, DETAIL_VALIDITY_HEIGHT);
    return ranges.size() == 1 ? ranges.get(0).anchor : "";
  }

  /** JNI calls the existing process-salt adapter only for a proved detail. */
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

}
