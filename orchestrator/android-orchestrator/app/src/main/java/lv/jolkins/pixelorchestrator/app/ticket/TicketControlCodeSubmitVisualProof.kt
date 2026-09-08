package lv.jolkins.pixelorchestrator.app.ticket

/** One pair proves both the popup state and, when required, its input targets. */
internal class TicketControlCodeSubmitVisualProof {
  private var previous: TicketControlCodeVisualProbe? = null
  private var lastProbeId = 0L

  fun observe(current: TicketControlCodeVisualProbe?, requireGeometry: Boolean): Boolean {
    val before = previous
    previous = null
    if (current == null || current.probeId <= lastProbeId) return false
    lastProbeId = current.probeId
    if (current.result !in setOf(
        TicketControlCodeVisualClassifier.CONTROL_POPUP_STATIC_READY,
        TicketControlCodeVisualClassifier.CONTROL_POPUP_VALUE_READY,
        TicketControlCodeVisualClassifier.CONTROL_POPUP_KEYBOARD_READY
      )) return false
    previous = current
    return before != null && before.result == current.result && (!requireGeometry ||
      current.inputBounds != null && current.submitBounds != null &&
      current.inputBounds == before.inputBounds && current.submitBounds == before.submitBounds)
  }
}
