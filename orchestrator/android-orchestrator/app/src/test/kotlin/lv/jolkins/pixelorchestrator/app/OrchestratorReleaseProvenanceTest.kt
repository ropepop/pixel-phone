package lv.jolkins.pixelorchestrator.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrchestratorReleaseProvenanceTest {
  @Test
  fun actionResultSerializesTheReleaseThatProducedIt() {
    val result = OrchestratorActionResult(
      pixelRunId = "run-123",
      action = "health",
      component = "ticket_screen",
      success = true,
      message = "healthy",
      recordedAt = "2026-07-10T10:11:12Z",
      releaseProvenance = OrchestratorReleaseProvenance(
        releaseId = "pixel-20260710-abc123",
        sourceCommit = "abc123def456",
        sourceDirty = false,
        builtAt = "2026-07-10T10:00:00Z"
      )
    )

    val encoded = Json.encodeToString(OrchestratorActionResult.serializer(), result)
    val provenance = Json.parseToJsonElement(encoded).jsonObject
      .getValue("releaseProvenance")
      .jsonObject

    assertEquals("pixel-20260710-abc123", provenance.getValue("releaseId").jsonPrimitive.content)
    assertEquals("abc123def456", provenance.getValue("sourceCommit").jsonPrimitive.content)
    assertFalse(provenance.getValue("sourceDirty").jsonPrimitive.content.toBoolean())
    assertEquals("2026-07-10T10:00:00Z", provenance.getValue("builtAt").jsonPrimitive.content)
  }

  @Test
  fun generatedBuildIdentityIsAlwaysInspectable() {
    val provenance = OrchestratorReleaseProvenance.current()

    assertTrue(provenance.releaseId.isNotBlank())
    assertTrue(provenance.releaseId != "local-unset")
    assertTrue(provenance.sourceCommit.isNotBlank())
    assertTrue(provenance.builtAt.isNotBlank())
    assertTrue(provenance.builtAt != "unknown")
  }
}
