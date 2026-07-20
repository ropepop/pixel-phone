package lv.jolkins.pixelorchestrator.app

import lv.jolkins.pixelorchestrator.coreconfig.HealthSnapshot
import lv.jolkins.pixelorchestrator.coreconfig.ModuleHealthState
import lv.jolkins.pixelorchestrator.coreconfig.ModuleRuntimeState
import lv.jolkins.pixelorchestrator.coreconfig.OperationEvent
import lv.jolkins.pixelorchestrator.coreconfig.ServiceRuntimeState
import lv.jolkins.pixelorchestrator.coreconfig.StackStateV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportBundleExporterTest {
  @Test
  fun redactedStateDropsNetworkIdentityEvidenceAndFreeFormDetails() {
    val state = StackStateV1(
      lastNetworkFingerprint = "private-network",
      lastObservedPublicIpv4 = "192.0.2.10",
      services = mapOf("dns" to ServiceRuntimeState(lastFailureReason = "token=secret")),
      moduleState = mapOf("dns" to ModuleRuntimeState(details = mapOf("path" to "/private"))),
      lastHealthSnapshot = HealthSnapshot(
        rootGranted = true,
        moduleHealth = mapOf("dns" to ModuleHealthState(healthy = true, details = mapOf("ip" to "192.0.2.10"))),
        evidence = mapOf("command" to "private")
      ),
      operationLog = listOf(OperationEvent(1, "dns", "start", true, "private output"))
    )

    val redacted = SupportBundleExporter.redactState(state)

    assertEquals("", redacted.lastNetworkFingerprint)
    assertEquals("", redacted.lastObservedPublicIpv4)
    assertEquals("", redacted.services.getValue("dns").lastFailureReason)
    assertTrue(redacted.moduleState.getValue("dns").details.isEmpty())
    assertTrue(redacted.lastHealthSnapshot.moduleHealth.getValue("dns").details.isEmpty())
    assertTrue(redacted.lastHealthSnapshot.evidence.isEmpty())
    assertEquals("", redacted.operationLog.single().details)
    assertTrue(redacted.lastHealthSnapshot.rootGranted)
  }
}
