package lv.jolkins.pixelorchestrator.app.ticket;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.zip.ZipFile;

/** Test APK only: checks loading from the installed product APK in app_process. */
public final class TicketRootNativeLoadMain {
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("expected installed APK");
    String entry = "lib/arm64-v8a/libpixel_health.so";
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (ZipFile apk = new ZipFile(args[0]); InputStream input = apk.getInputStream(apk.getEntry(entry))) {
      byte[] block = new byte[8192];
      int count;
      while ((count = input.read(block)) >= 0) digest.update(block, 0, count);
    }
    System.load(args[0] + "!/" + entry);
    System.setProperty("pixel.media.native.library", args[0] + "!/" + entry);
    TicketEncoderStartupPrimer primer = new TicketEncoderStartupPrimer(3);
    if (!primer.canPostInput(1000L)) throw new AssertionError("initial prime input");
    primer.noteInputPosted(1000L);
    if (primer.canPostInput(1099L) || !primer.canPostInput(1100L)) throw new AssertionError("native prime cadence");
    if (primer.classifyCompleteAccessUnit(true, true, 1100L) != TicketEncoderStartupPrimer.OutputDisposition.FORWARD) throw new AssertionError("native first key");
    primer.beginBoundaryDrain();
    TicketH264FrameRecord frame = new TicketH264FrameRecord(true,1L,1L,1L,1L,1L,1L,1L,new byte[]{0,0,1,0x65});
    primer.bufferBoundaryAccessUnit(frame);
    if (primer.completeBoundaryDrain() != frame) throw new AssertionError("native boundary frame reference");
    primer.noteBoundaryAccessUnitForwarded();
    if (!primer.boundaryAccessUnitForwarded()) throw new AssertionError("native boundary forward");
    if (NativeTicketMedia.validateCodecInput(new long[]{1,1,1,2,3},new long[0],0) != 0) throw new AssertionError("native input admission");
    if (NativeTicketMedia.nextEncoderGeneration(Long.MAX_VALUE,1L) != 1L) throw new AssertionError("native generation rollover");
    if (NativeTicketMedia.captureLooksVisible(new int[]{0xff000000,0xff000000})) throw new AssertionError("native blackout");
    // The existing visibility contract requires eight bright samples and a dark sample.
    if (!NativeTicketMedia.captureLooksVisible(new int[]{0xff000000,0xffffffff,0xffffffff,0xffffffff,0xffffffff,0xffffffff,0xffffffff,0xffffffff,0xffffffff})) throw new AssertionError("native visible capture");
    StringBuilder hash = new StringBuilder();
    for (byte value : digest.digest()) hash.append(String.format("%02x", value & 255));
    System.out.println("NATIVE_APK_LOAD_OK sha256=" + hash);
  }
}
