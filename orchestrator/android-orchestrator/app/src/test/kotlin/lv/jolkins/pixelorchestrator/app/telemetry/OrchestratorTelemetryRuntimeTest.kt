package lv.jolkins.pixelorchestrator.app.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OrchestratorTelemetryRuntimeTest {
  @Test
  fun parseEnvKeepsOnlyTheThreeTypedConfigurationKeys() {
    val parsed = OrchestratorTelemetryRuntime.parseEnv(
      """
        PIXEL_ORCHESTRATOR_OBSERVABILITY_HOST="https://maincloud.spacetimedb.com"
        PIXEL_ORCHESTRATOR_OBSERVABILITY_DATABASE=pixel-orchestrator-observability-prod
        PIXEL_ORCHESTRATOR_OBSERVABILITY_SERVICE_TOKEN_FILE='/data/local/pixel-stack/conf/apps/pixel-orchestrator-observability-token'
        UNRELATED_SECRET=must-not-be-retained
      """.trimIndent()
    )

    assertEquals(
      "https://maincloud.spacetimedb.com",
      parsed[OrchestratorTelemetryRuntime.KEY_HOST]
    )
    assertEquals(
      "pixel-orchestrator-observability-prod",
      parsed[OrchestratorTelemetryRuntime.KEY_DATABASE]
    )
    assertEquals(
      OrchestratorTelemetryRuntime.TOKEN_PATH,
      parsed[OrchestratorTelemetryRuntime.KEY_TOKEN_FILE]
    )
    assertFalse(parsed.containsKey("UNRELATED_SECRET"))
  }
}
