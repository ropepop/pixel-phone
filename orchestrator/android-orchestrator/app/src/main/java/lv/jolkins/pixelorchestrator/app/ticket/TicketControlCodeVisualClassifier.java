package lv.jolkins.pixelorchestrator.app.ticket;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/** Native bounded-probe classification; Java retains the existing process-private signature salt. */
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
  public static final String TICKET_LIST_WITH_REGISTRATION_BUTTON = "ticket_list_with_registration_button";
  public static final String UNKNOWN = "unknown";
  private static final byte[] VISUAL_SIGNATURE_SALT = new byte[32];
  private static final String VISUAL_SIGNATURE_EPOCH;

  static {
    new SecureRandom().nextBytes(VISUAL_SIGNATURE_SALT);
    VISUAL_SIGNATURE_EPOCH = hexPrefix(sha256(VISUAL_SIGNATURE_SALT), 6);
  }

  private TicketControlCodeVisualClassifier() {}

  public static String classify(int[] pixels) {
    return NativeTicketMedia.controlClassify(pixels).intern();
  }

  public static String classifyForActivatedTicket(int[] pixels) {
    return NativeTicketMedia.controlClassifyForActivatedTicket(pixels).intern();
  }

  public static String classifyForCleanup(int[] pixels) {
    return NativeTicketMedia.controlClassifyForCleanup(pixels).intern();
  }

  public static String classifyForCleanupHighResolution(int[] pixels) {
    return NativeTicketMedia.controlClassifyForCleanupHighResolution(pixels).intern();
  }

  public static String classifySubmitLayout(int[] pixels) {
    return NativeTicketMedia.controlClassifySubmitLayout(pixels).intern();
  }

  public static String generatedResultCloseBounds(int[] pixels) {
    return NativeTicketMedia.controlGeneratedResultCloseBounds(pixels);
  }

  public static String generatedResultCloseBoundsHighResolution(int[] pixels) {
    return NativeTicketMedia.controlGeneratedResultCloseBoundsHighResolution(pixels);
  }

  public static String registrationSliderBounds(int[] pixels) {
    return NativeTicketMedia.controlRegistrationSliderBounds(pixels);
  }

  public static String submitInputBounds(int[] pixels) {
    return NativeTicketMedia.controlSubmitInputBounds(pixels);
  }

  public static String submitButtonBounds(int[] pixels) {
    return NativeTicketMedia.controlSubmitButtonBounds(pixels);
  }

  /** Opaque, process-salted identity; native bytes never leave this in-memory digest boundary. */
  public static String ticketCodeVisualSignature(int[] pixels) {
    return signature(NativeTicketMedia.controlCodeSignaturePixels(pixels));
  }

  public static String ticketCodeVisualSignatureHighResolution(int[] pixels) {
    return signature(NativeTicketMedia.controlHighResCodeSignaturePixels(pixels));
  }

  public static String ticketDetailStaticVisualSignature(int[] pixels, int width, int height) {
    return signature(NativeTicketMedia.controlStaticSignaturePixels(pixels, width, height));
  }

  /** Identifies the in-memory salt generation so a helper restart always fails closed. */
  public static String ticketCodeVisualSignatureEpoch() {
    return VISUAL_SIGNATURE_EPOCH;
  }

  private static String signature(byte[] pixels) {
    if (pixels == null) return "";
    MessageDigest digest = sha256Digest();
    if (digest == null) return "";
    digest.update(VISUAL_SIGNATURE_SALT);
    digest.update(pixels);
    return hexPrefix(digest.digest(), 12);
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      return null;
    }
  }

  private static byte[] sha256(byte[] value) {
    MessageDigest digest = sha256Digest();
    return digest == null ? new byte[0] : digest.digest(value);
  }

  private static String hexPrefix(byte[] value, int bytes) {
    if (value.length < bytes) return "";
    StringBuilder output = new StringBuilder(bytes * 2);
    for (int index = 0; index < bytes; index++) {
      output.append(String.format("%02x", value[index] & 0xff));
    }
    return output.toString();
  }
}
