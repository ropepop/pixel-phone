package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal enum class TicketProofStreamCleanupDecision {
  STOP,
  RETRY,
  DROP
}

/**
 * Authorizes cleanup only for the exact no-viewer stream session started by a proof command.
 * A newer session, any viewer, or an already-closed stream permanently invalidates the lease.
 * Temporary phone-control ownership defers the same fenced cleanup for another grace period.
 */
internal fun ticketProofStreamCleanupDecision(
  expectedSessionGeneration: Long?,
  currentSessionGeneration: Long,
  streamActive: Boolean,
  clientCount: Int,
  startOwnershipActive: Boolean,
  controlOwnershipActive: Boolean,
  actionOwnershipActive: Boolean,
  reauthOwnershipActive: Boolean
): TicketProofStreamCleanupDecision {
  return TicketProofStreamCleanupDecision.valueOf(NativeTicketCapture.call("cleanup", buildJsonObject {
    put("expectedGeneration", expectedSessionGeneration)
    put("currentGeneration", currentSessionGeneration)
    put("streamActive", streamActive)
    put("clientCount", clientCount)
    put("start", startOwnershipActive)
    put("control", controlOwnershipActive)
    put("action", actionOwnershipActive)
    put("reauth", reauthOwnershipActive)
  }).jsonPrimitive.content)
}
