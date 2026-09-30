package lv.jolkins.pixelorchestrator.app.ticket;

/** Emits only opaque identities of helper-owned synthetic pixels for restart comparison. */
public class VisualIdentityProbe {
  public static void main(String[] args) {
    int[] pixels=VisualParityTest.detailPixels(false,true);
    String first=TicketVisualActionClassifier.classify(pixels).currentAnchor;
    String second=TicketVisualActionClassifier.classify(pixels.clone()).currentAnchor;
    if(!first.matches("d_[0-9a-f]{28}")||!first.equals(second))throw new AssertionError("unstable process identity");
    System.out.println("visual_process_identity="+first);
  }
}
